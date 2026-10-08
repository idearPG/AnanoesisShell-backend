package com.ananoesis.shell.approval;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.ananoesis.shell.security.CredentialProtectionException;
import com.ananoesis.shell.service.HostNotFoundException;
import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ssh.ExecLimits;
import com.ananoesis.shell.ssh.ExecOutcome;
import com.ananoesis.shell.ssh.PtyCommandGateway;
import com.ananoesis.shell.ssh.PtyCommandScheduler;
import com.ananoesis.shell.ssh.SshConnectException;
import com.ananoesis.shell.ssh.SshExecService;
import com.ananoesis.shell.support.TurnCancelledException;

/**
 * 执行<b>已获批准</b>的命令（tasks 8.2 / 8.4 + 5.5 PTY 路由改造）。
 *
 * <h2>WHY 需要这么一个类，而不是让智能体循环直接调 {@code SshExecService}</h2>
 * <p>三件事必须一起发生、且只有一处实现：</p>
 * <ol>
 *   <li>用 {@code settings} 里的<b>当前</b>时限与输出上限构造 {@link ExecLimits}
 *       （spec「命令执行超时中断」/「输出长度上限」，TRACEABILITY Q5）；</li>
 *   <li>把 {@link ExecOutcome} 翻译成契约的 {@link ToolResultStatus} 与审计列；</li>
 *   <li>把执行结果写回 {@code approvals} 审计行（spec「审批审计日志」的执行结果要素）。</li>
 * </ol>
 * <p>散在调用方，第 3 步最容易漏——症状是"审计里全是 pending/approved，
 * 却查不到命令到底跑成什么样"，而这正是审计存在的理由。</p>
 *
 * <h2>5.5 改造：PTY 路由</h2>
 * <p>design.md D3：「所有 Agent 远端工具通过所属 runtime 的同一持久 Shell 执行」。
 * 新增 {@link #run(UUID, UUID, String, String)} 重载：当提供 {@code sessionId} 时，
 * 命令通过持久 PTY 执行，cd/export 效果保留。旧 {@link #run(UUID, UUID, String)}
 * 保持 exec 通道，兼容既有测试与过渡期。</p>
 *
 * <h2>安全</h2>
 * <p>凭据解密完全发生在 {@code SshTargetResolver} 的回调窗口内，本类接触不到明文。
 * {@link Result#feedback()} 是要回喂给<b>模型</b>的文本，其中只有远端命令的输出，
 * 没有任何本地凭据；异常路径也只透出 {@code userMessage()} 这类已脱敏的文案。</p>
 */
@Component
public class ApprovedCommandRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovedCommandRunner.class);

    /** 未预期异常的对外固定文案；与 REST 侧 {@code ApiExceptionHandler} 保持一致。 */
    private static final String INTERNAL_ERROR_MESSAGE = "命令执行失败：服务器内部错误，请查看后端日志获取详情";

    private final SshExecService exec;
    private final SettingsService settings;
    private final ApprovalAuditService audit;
    /** task 5.5：PTY 路由网关。旧测试不注入时为 null。 */
    @Nullable
    private final PtyCommandGateway ptyGateway;

    /**
     * 生产构造器：注入 PTY 网关。
     *
     * <p>WHY {@link Autowired}：多构造器时 Spring 需要明确标注哪个是注入入口。</p>
     */
    @Autowired
    public ApprovedCommandRunner(SshExecService exec, SettingsService settings,
                                 ApprovalAuditService audit,
                                 @Nullable PtyCommandGateway ptyGateway) {
        this.exec = Objects.requireNonNull(exec, "exec 不得为 null");
        this.settings = Objects.requireNonNull(settings, "settings 不得为 null");
        this.audit = Objects.requireNonNull(audit, "audit 不得为 null");
        this.ptyGateway = ptyGateway;
    }

    /**
     * 兼容构造器：无 PTY 网关（旧测试使用）。
     */
    public ApprovedCommandRunner(SshExecService exec, SettingsService settings,
                                 ApprovalAuditService audit) {
        this(exec, settings, audit, null);
    }

    /**
     * 一次执行的结果。
     *
     * <p>WHY 用"要么有 execution、要么有 infrastructureError"的二选一形状，
     * 而不是给 {@link CommandExecution} 加一个 {@code failed} 标志：
     * 连接失败时<b>没有</b>退出码、没有 stdout/stderr、也没有"截断"这回事。
     * 硬造一个 {@code CommandExecution(exitCode=null, ...)} 会让 {@code status()}
     * 返回 {@code SUCCESS}（因为既不超时也不截断），那是对模型说谎。</p>
     *
     * @param execution            远端 shell 给出的事实；基础设施失败时为 {@code null}
     * @param infrastructureError  面向用户的失败说明；命令真的跑过时为 {@code null}
     */
    public record Result(@Nullable CommandExecution execution, @Nullable String infrastructureError) {

        /** @return 命令是否真的在远端跑过 */
        public boolean executed() {
            return execution != null;
        }

        /** @return 契约 {@code ToolResultStatus}，用于 {@code ai_stream(tool_result)} */
        public ToolResultStatus status() {
            return executed() ? execution.status() : ToolResultStatus.ERROR;
        }

        /** @return 回喂给模型的文本 */
        public String feedback() {
            return executed() ? execution.feedback() : infrastructureError;
        }
    }

    /**
     * @return 当前生效的执行约束（每次都重新读设置：用户可能刚刚在界面上改了时限）
     */
    public ExecLimits limits() {
        return new ExecLimits(Duration.ofSeconds(settings.runCommandTimeoutSeconds()),
                settings.runCommandMaxOutputBytes());
    }

    /**
     * 执行一条已获批准的命令（旧 exec 通道，兼容既有调用）。
     *
     * @param approvalId 审计行 id
     * @param hostId     目标服务器
     * @param command    命令原文（就是用户在弹框里批准的那一条，MUST NOT 被改写）
     */
    public Result run(UUID approvalId, UUID hostId, String command) {
        return run(approvalId, hostId, command, null);
    }

    /**
     * 执行一条已获批准的命令（task 5.5：双路径路由）。
     *
     * <p>当 {@code sessionId} 非 null 且 PTY 网关可用时，命令通过持久 PTY 执行
     * （cd/export 效果保留）。否则回落旧的 exec 通道。</p>
     *
     * @param approvalId 审计行 id
     * @param hostId     目标服务器（PTY 路径下用于日志，实际执行走 PTY）
     * @param command    命令原文
     * @param sessionId  目标会话 id（可为 null——null 时走 exec 通道）
     */
    public Result run(UUID approvalId, UUID hostId, String command, @Nullable String sessionId) {
        Objects.requireNonNull(approvalId, "approvalId 不得为 null");
        Objects.requireNonNull(hostId, "hostId 不得为 null");

        ExecLimits limits = limits();
        LOG.info("开始执行已批准的命令: approvalId={} hostId={} session={} timeoutSeconds={} maxOutputBytes={}",
                approvalId, hostId, sessionId, limits.timeout().toSeconds(), limits.maxOutputBytes());

        // task 5.5：尝试 PTY 路径
        if (sessionId != null && ptyGateway != null) {
            Result ptyResult = tryPtyPath(approvalId, sessionId, command, limits);
            if (ptyResult != null) {
                return ptyResult;
            }
            // PTY 路径不可用，回落 exec
            LOG.debug("PTY 路径不可用，回落 exec 通道: approvalId={} session={}", approvalId, sessionId);
        }

        // 旧 exec 路径
        return runViaExec(approvalId, hostId, command, limits);
    }

    /**
     * 尝试通过持久 PTY 执行已批准的命令。
     *
     * <p>WHY 等待上限用 {@link PtyCommandScheduler#ptyWaitCeilingSeconds} 而不是
     * settings 的超时：调度器已改为「空闲超时 + 绝对上限」语义，长时活跃命令（如
     * 安装 JDK）会跑满绝对上限；等待方若仍按旧 60s 等待，会在调度器中断之前
     * 拿不到结果并误回落 exec 通道重跑（BUG-A）。</p>
     *
     * @return 成功时返回 Result；PTY 不可用时返回 null（调用方回落 exec）
     */
    @Nullable
    private Result tryPtyPath(UUID approvalId, String sessionId, String command, ExecLimits limits) {
        try {
            long startNanos = System.nanoTime();
            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    ptyGateway.submit(sessionId, command);
            PtyCommandScheduler.CommandResult cmdResult =
                    future.get(PtyCommandScheduler.ptyWaitCeilingSeconds(limits.timeout().toSeconds()),
                            TimeUnit.SECONDS);

            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            // PTY 合并 stdout/stderr，全部放在 stdout
            ExecOutcome outcome = new ExecOutcome(
                    cmdResult.exitCode(),
                    cmdResult.stdout(),
                    "", // PTY 合并 stdout/stderr
                    cmdResult.truncated(),
                    cmdResult.timedOut(),
                    elapsedMs);

            CommandExecution execution = CommandExecution.of(outcome);
            audit.recordExecution(approvalId, execution);
            LOG.info("命令经 PTY 执行完成: approvalId={} exit={} truncated={} timedOut={} elapsedMs={}",
                    approvalId, execution.exitCode(), execution.truncated(), execution.timedOut(), elapsedMs);
            return new Result(execution, null);
        } catch (IllegalArgumentException e) {
            // sessionId 无效或调度器不存在——回落
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // BUG-B：等待被用户停止打断不是「PTY 不可用」，MUST NOT 回落 exec
            // 把已批准的命令重跑一遍；透传给回合顶层按受控停止收尾
            throw new TurnCancelledException("等待 PTY 结果时被用户停止", e);
        } catch (ExecutionException e) {
            LOG.warn("PTY 路径执行失败: approvalId={} cause={}",
                    approvalId, String.valueOf(e.getCause().getMessage()));
            String errorMsg = e.getCause().getMessage();
            audit.recordExecutionFailure(approvalId, errorMsg);
            return new Result(null, errorMsg);
        } catch (TimeoutException e) {
            LOG.warn("PTY 路径执行超时: approvalId={}", approvalId);
            audit.recordExecutionFailure(approvalId, "命令执行超时");
            return new Result(null, "命令执行超时");
        }
    }

    /**
     * 旧的 exec 通道执行路径。
     */
    private Result runViaExec(UUID approvalId, UUID hostId, String command, ExecLimits limits) {
        try {
            ExecOutcome outcome = exec.executeForHost(hostId, command, limits);
            CommandExecution execution = CommandExecution.of(outcome);
            audit.recordExecution(approvalId, execution);
            LOG.info("命令执行结束: approvalId={} exit={} truncated={} timedOut={} elapsedMs={}",
                    approvalId, execution.exitCode(), execution.truncated(), execution.timedOut(),
                    outcome.durationMillis());
            return new Result(execution, null);
        } catch (HostNotFoundException e) {
            return failure(approvalId, "目标服务器配置不存在或已被删除", e);
        } catch (SshConnectException e) {
            // WHY 只把 userMessage 透出去：e.getMessage() 里含主机地址、用户名等细节，
            // 而这段文本会被回喂给模型（可能进而进入模型服务商的日志）
            return failure(approvalId, e.userMessage(), e);
        } catch (CredentialProtectionException e) {
            return failure(approvalId,
                    CredentialProtectionException.UNAVAILABLE_MESSAGE + "，请在设置中配置主密码后重试", e);
        } catch (RuntimeException e) {
            LOG.error("执行已批准的命令时出现未预期异常: approvalId={}", approvalId, e);
            return failure(approvalId, INTERNAL_ERROR_MESSAGE, e);
        }
    }

    private Result failure(UUID approvalId, String userMessage, Exception cause) {
        LOG.warn("命令未能执行: approvalId={} cause={}", approvalId, String.valueOf(cause.getMessage()));
        try {
            audit.recordExecutionFailure(approvalId, userMessage);
        } catch (RuntimeException e) {
            // 审计写不进也不能改变已经发生的事实：命令确实没跑，必须如实告诉模型
            LOG.error("写入命令执行失败的审计行失败: approvalId={}", approvalId, e);
        }
        return new Result(null, userMessage);
    }
}

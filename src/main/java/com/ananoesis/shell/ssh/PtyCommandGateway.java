package com.ananoesis.shell.ssh;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.ssh.PtyCommandScheduler.CommandResult;

/**
 * PTY 命令路由网关（task 5.5）。
 *
 * <p>把 {@code sessionId → SessionRuntime → PtyCommandScheduler} 的查找链
 * 封装成一个可注入的服务，供 {@code AgentTools} 和 {@code ApprovedCommandRunner}
 * 把远端工具路由到绑定的持久 PTY。</p>
 *
 * <h2>WHY 需要这层间接</h2>
 * <p>design.md D3：「所有 Agent 远端工具通过所属 runtime 的同一持久 Shell 执行」。
 * 旧路径（{@link SshExecService}）每次调用自建连接，命令效果（{@code cd}/{@code export}）
 * 无法保留。新路径通过持久 PTY 执行，Shell 状态自然延续。
 * 但旧路径不能删除——既有测试和过渡期仍需要它。因此本类提供<b>新路径入口</b>，
 * 调用方按「有 sessionId 走 PTY、否则走 exec」的策略选择。</p>
 *
 * <p>WHY 不直接注入 {@code SshTerminalService}：本类只暴露「提交命令」这一件事，
 * 不暴露 runtime 查找、终端操作等无关能力。接口越窄，误用面越小。</p>
 */
@Service
public class PtyCommandGateway {

    private static final Logger LOG = LoggerFactory.getLogger(PtyCommandGateway.class);

    private final SshTerminalService terminalService;

    public PtyCommandGateway(SshTerminalService terminalService) {
        this.terminalService = Objects.requireNonNull(terminalService, "terminalService 不得为 null");
    }

    /**
     * 向指定会话的持久 PTY 提交一条命令。
     *
     * @param sessionId 目标会话 id
     * @param command   要执行的 shell 命令
     * @return 命令结果的 future
     * @throws IllegalArgumentException 会话不存在或未安装 Shell 集成
     */
    public CompletableFuture<CommandResult> submit(String sessionId, String command) {
        Objects.requireNonNull(sessionId, "sessionId 不得为 null");
        Objects.requireNonNull(command, "command 不得为 null");

        SessionRuntime runtime = terminalService.findRuntime(sessionId);
        if (runtime == null) {
            throw new IllegalArgumentException("会话不存在或已关闭: " + sessionId);
        }

        PtyCommandScheduler scheduler = runtime.scheduler();
        if (scheduler == null) {
            throw new IllegalArgumentException("会话未安装 Shell 集成: " + sessionId);
        }

        LOG.debug("PTY 网关提交命令: session={} command={}", sessionId, command);
        return scheduler.submitCommand(command);
    }

    /**
     * 查找指定会话的调度器（供需要更精细控制的场景使用）。
     *
     * @return 调度器；会话不存在或未集成时返回 null
     */
    public PtyCommandScheduler findScheduler(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        SessionRuntime runtime = terminalService.findRuntime(sessionId);
        if (runtime == null) {
            return null;
        }
        return runtime.scheduler();
    }

    /**
     * 查询指定会话是否处于嵌套 Shell 环境（如 Docker 容器内）。
     *
     * <p>WHY 挂在本类而非让 AiAgentService 直连调度器：与 {@link #findScheduler} 同理，
     * 本类已持有 {@link SshTerminalService} 的查找链，复用现成路由改动面最小。
     * 会话不存在/未集成时安全返回 false，系统提示词因此不渲染嵌套段。</p>
     *
     * @param sessionId 终端会话 id，可为 null
     * @return true 表示处于嵌套 Shell 或已降级为 exec 通道模式
     */
    public boolean isNestedShell(String sessionId) {
        PtyCommandScheduler scheduler = findScheduler(sessionId);
        return scheduler != null && scheduler.isNestedShell();
    }
}

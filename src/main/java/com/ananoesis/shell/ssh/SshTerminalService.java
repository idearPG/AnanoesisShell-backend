package com.ananoesis.shell.ssh;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.config.SshProperties;
import com.ananoesis.shell.entity.CommandExecution;
import com.ananoesis.shell.service.CommandExecutionService;
import com.ananoesis.shell.service.Conversation;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.ssh.PtyCommandScheduler.ManualCommandInfo;

import jakarta.annotation.PreDestroy;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

/**
 * 交互式终端会话的编排（tasks 6.3 / 6.6 + Wave 2 多连接改造）。
 *
 * <p>本类负责把"连接 → 分配 PTY → 开 shell → 起读泵 → 注册 → 记账"这条链
 * 以<b>要么全成、要么全不留痕</b>的方式串起来。链上任一步失败都会把已经拿到的
 * 资源全部释放，并把失败原因记进 {@code sessions}——审计里不该出现
 * "有一条 connecting 记录却永远没有结束"的孤儿行。</p>
 *
 * <p>WHY PTY 参数取自 {@link SshProperties} 而不是写死：前端 xterm.js 的初始尺寸
 * 与后端分配的 PTY 尺寸必须一致，否则首屏就会出现换行错位。做成配置项让二者由同一处对齐。</p>
 *
 * <h2>Wave 2 改造</h2>
 * <p>从 host_id 管理改为 session_id 管理。每次连接生成新的 {@link SessionRuntime}，
 * 同主机两次连接得到不同的 runtime 实例，关闭一条不影响另一条（design.md D1）。</p>
 */
@Service
public class SshTerminalService {

    private static final Logger LOG = LoggerFactory.getLogger(SshTerminalService.class);

    private final SshConnectionService connection;
    private final SshProperties properties;
    private final TerminalSessionRegistry registry;
    private final SshTargetResolver resolver;
    private final SessionRecorder recorder;
    private final CommandExecutionService commandExecutions;
    private final ConversationService conversations;

    /**
     * session_id → SessionRuntime 映射。
     * WHY 独立于 {@link TerminalSessionRegistry}：SessionRuntime 在断线宽限期内
     * 仍持有引用（供重订阅），而 TerminalSessionRegistry 的 SshTerminalSession 可能已关闭。
     */
    private final Map<String, SessionRuntime> sessionRuntimes = new ConcurrentHashMap<>();

    /**
     * 所有会话调度器共享的超时线程池（命令超时 Ctrl-C、中断观察窗口都是短任务）。
     * WHY 单线程共享：每条命令的定时任务只有排定/取消两种毫秒级操作，
     * 为每个 runtime 单独建池会随连接数线性膨胀，得不偿失。
     */
    private final ScheduledExecutorService shellTimeouts =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pty-cmd-timeout");
                thread.setDaemon(true);
                return thread;
            });

    public SshTerminalService(SshConnectionService connection,
                              SshProperties properties,
                              TerminalSessionRegistry registry,
                              SshTargetResolver resolver,
                              SessionRecorder recorder,
                              CommandExecutionService commandExecutions,
                              ConversationService conversations) {
        this.connection = Objects.requireNonNull(connection, "connection 不得为 null");
        this.properties = Objects.requireNonNull(properties, "properties 不得为 null");
        this.registry = Objects.requireNonNull(registry, "registry 不得为 null");
        this.resolver = Objects.requireNonNull(resolver, "resolver 不得为 null");
        this.recorder = Objects.requireNonNull(recorder, "recorder 不得为 null");
        this.commandExecutions = Objects.requireNonNull(commandExecutions, "commandExecutions 不得为 null");
        this.conversations = Objects.requireNonNull(conversations, "conversations 不得为 null");
    }

    /**
     * 按主机配置打开一条交互式终端（WebSocket 层使用的入口，Q1 的 {@code action=open}）。
     *
     * <p>每次调用创建新的 {@link SessionRuntime}（新的 session_id），即使 hostId 相同。
     * WHY 不按 host_id 去重：design.md D1 明确要求"同主机多开，每个连接独立"。</p>
     *
     * @param listenerFactory 以本次会话 id 为参数构造输出监听器，MUST NOT 返回 null
     * @return 新创建的 SessionRuntime
     * @throws com.ananoesis.shell.service.HostNotFoundException 主机配置不存在
     * @throws SshConnectException                               连接/认证失败或凭据缺失
     */
    public SessionRuntime open(UUID hostId, Function<String, TerminalOutputListener> listenerFactory) {
        Objects.requireNonNull(hostId, "hostId 不得为 null");
        Objects.requireNonNull(listenerFactory, "listenerFactory 不得为 null");

        return resolver.withTarget(hostId, target -> {
            String sessionId = recorder.recordStart(hostId, SessionKind.INTERACTIVE_PTY);
            SessionRuntime runtime = null;
            try {
                TerminalOutputListener listener = Objects.requireNonNull(
                        listenerFactory.apply(sessionId), "listenerFactory 不得返回 null");

                // WHY 中转器：createTerminal 要求先交出监听器（读泵启动即用），
                // 而集成包装必须在 terminal 建好后（安装代码经它 send）才能构造；
                // bash 会话初始挂预安装静音：登录 banner/首个 prompt 可能在 installer
                // 接入闸门之前就到达读泵，不静音会漏给前端（#19 竞态防御；双 prompt
                // 真根因是旧三段式分次发送，已由 ShellIntegration 单行安装修复）；
                // 非 bash 不装静音也不装闸门，人工终端从第一个字节起照常透传
                boolean preInstallMute = "bash".equalsIgnoreCase(properties.getShellType());
                RelayOutputListener relay = new RelayOutputListener(
                        preInstallMute ? new PreInstallMuteListener(listener) : listener);
                SshTerminalSession terminal = createTerminal(sessionId, target, relay,
                        reason -> finishAudited(sessionId, reason));

                runtime = new SessionRuntime(hostId, terminal, relay,
                        sid -> {
                            registry.unregister(sid);
                            sessionRuntimes.remove(sid);
                        });
                registry.register(terminal);
                sessionRuntimes.put(runtime.sessionId().toString(), runtime);
                recorder.recordOpen(sessionId);
                installShellIntegration(terminal, listener, relay, runtime);

                LOG.info("交互式终端已建立: session={} target={} pty={}x{} term={}",
                        runtime.sessionId(), target,
                        properties.getPtyColumns(), properties.getPtyRows(), properties.getPtyTerm());
                return runtime;
            } catch (SshConnectException e) {
                recorder.recordEnd(sessionId, e.closeReason(), e.getMessage());
                if (runtime != null) {
                    sessionRuntimes.remove(runtime.sessionId().toString());
                }
                throw e;
            } catch (RuntimeException e) {
                recorder.recordEnd(sessionId, SshCloseReason.ERROR, String.valueOf(e.getMessage()));
                if (runtime != null) {
                    sessionRuntimes.remove(runtime.sessionId().toString());
                }
                throw e;
            }
        });
    }

    /**
     * 便捷重载：调用方持有的监听器与会话 id 无关时使用。
     */
    public SessionRuntime open(UUID hostId, TerminalOutputListener listener) {
        Objects.requireNonNull(listener, "listener 不得为 null");
        return open(hostId, id -> listener);
    }

    /**
     * 在给定目标上打开一条交互式终端，<b>不写审计</b>。
     *
     * <p>WHY 是包级可见而不是 public：测试与包内代码使用，避免暴露"绕开审计的公开入口"。</p>
     * <p>WHY 返回 {@link SshTerminalSession} 而非 {@link SessionRuntime}：
     * 现有测试直接操作终端会话（send/resize/close），不需要 runtime 层。</p>
     */
    SshTerminalSession open(SshTarget target, TerminalOutputListener listener) {
        Objects.requireNonNull(target, "target 不得为 null");
        Objects.requireNonNull(listener, "listener 不得为 null");
        String sessionId = UUID.randomUUID().toString();
        SshTerminalSession terminal = createTerminal(sessionId, target, listener, reason -> { });
        registry.register(terminal);
        return terminal;
    }

    /**
     * 查找指定 session_id 的运行时。
     */
    public SessionRuntime findRuntime(String sessionId) {
        return sessionId != null ? sessionRuntimes.get(sessionId) : null;
    }

    /**
     * 查找指定 session_id 的运行时，要求必须存在。
     */
    public SessionRuntime requireRuntime(String sessionId) {
        SessionRuntime runtime = findRuntime(sessionId);
        if (runtime == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return runtime;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 建立 PTY 会话。失败时保证不留半成品：已取得的每一层资源都会被释放。
     */
    private SshTerminalSession createTerminal(String sessionId,
                                              SshTarget target,
                                              TerminalOutputListener listener,
                                              Consumer<SshCloseReason> onClosed) {
        SSHClient client = connection.connect(target);
        Session channel = null;
        Session.Shell shell = null;
        SshTerminalSession terminal = null;
        try {
            channel = client.startSession();
            channel.allocatePTY(properties.getPtyTerm(),
                    properties.getPtyColumns(), properties.getPtyRows(), 0, 0, Map.of());
            shell = channel.startShell();

            Consumer<SshCloseReason> hook = reason -> {
                registry.unregister(sessionId);
                onClosed.accept(reason);
            };
            terminal = new SshTerminalSession(sessionId, client, channel, shell, listener, hook);
            terminal.startReaders();
            return terminal;
        } catch (IOException | RuntimeException e) {
            if (terminal != null) {
                terminal.close(SshCloseReason.ERROR);
            } else {
                SshConnectionService.closeQuietly(shell);
                SshConnectionService.closeQuietly(channel);
                SshConnectionService.disconnectQuietly(client);
            }
            registry.unregister(sessionId);
            if (e instanceof SshConnectException classified) {
                throw classified;
            }
            String detail = "打开交互式终端失败: " + target + " session=" + sessionId;
            LOG.warn("{}: {}", detail, String.valueOf(e));
            LOG.debug("打开交互式终端失败完整栈", e);
            throw new SshConnectException(SshFailureKind.CHANNEL_FAILURE, detail, e);
        }
    }

    private void finishAudited(String sessionId, SshCloseReason reason) {
        recorder.recordEnd(sessionId, reason, null);
    }

    /**
     * 安装会话级 Shell 集成并接线到 runtime（task 5.2-5.5 的组装环节）。
     *
     * <p>WHY 失败不阻断开终端：集成的价值是让 Agent 命令继承 cwd/env 并回流输出；
     * 它的缺席只降级为"exec 通道执行"，人工终端与审计链路必须照常可用，
     * 故安装异常一律吞成"未安装"并留日志（design D3 降级承诺）。</p>
     */
    private void installShellIntegration(SshTerminalSession terminal,
                                         TerminalOutputListener listener,
                                         RelayOutputListener relay,
                                         SessionRuntime runtime) {
        try {
            // WHY 传 relay::switchTo：bash 安装代码会被 readline 自回显（stty -echo 压不住，
            // known-issues #14），installer 必须先经此回调把输出链切到闸门，再写安装代码，
            // 才能吞掉回显噪声；后续的 switchTo 对 bash 是幂等重绑，对非 bash 是挂回原监听器
            // WHY 使用带 nestedDetectTimeoutMs 的重载：用户可通过配置文件调整嵌套检测阈值
            ShellIntegrationInstaller.Outcome outcome = ShellIntegrationInstaller.install(
                    terminal, properties.getShellType(), shellTimeouts, listener, relay::switchTo,
                    ShellIntegrationInstaller.DEFAULT_GATE_TIMEOUT_MS,
                    properties.getNestedDetectTimeout() * 1000L);
            relay.switchTo(outcome.listener());
            if (outcome.scheduler() != null) {
                runtime.setScheduler(outcome.scheduler());
                // WHY 注入嵌套 Shell 回调：调度器检测到嵌套 Shell 后触发重安装，
                // 重安装用新 nonce 和新调度器替换当前的
                outcome.scheduler().setNestedShellCallback(() ->
                        reinstallShellIntegration(terminal, listener, relay, runtime));
                // WHY 注入人工命令回调：用户手动执行的命令完成后持久化到账本和对话历史，
                // 让 Agent 模式能引用人工命令的结果（Shell → Agent 记忆同步）
                outcome.scheduler().setManualCommandListener(
                        info -> onManualCommandComplete(runtime.sessionId(), info));
                LOG.info("Shell 集成已接线: session={} nonce={}",
                        runtime.sessionId(), outcome.nonce());
            }
        } catch (RuntimeException e) {
            // 兆底恢复透传：异常可能发生在 installer 接入闸门之前（relay 还停在
            // 预安装静音 #19），不切回会把用户终端永久静音；降级为人工终端的
            // 语义就是"所有输出原样可见"，重复 switchTo 幂等无害
            relay.switchTo(listener);
            LOG.warn("Shell 集成安装失败，本会话降级为人工模式: session={} cause={}",
                    runtime.sessionId(), String.valueOf(e.getMessage()));
        }
    }
    
    /**
     * 嵌套 Shell 检测后重新安装集成代码（用新 nonce 和新调度器替换）。
     *
     * <p>WHY 失败不阻断：重装与首次安装同语义——失败仅降级为 exec 通道，
     * 调度器内部 nestedFallback 标志会阻止后续 PTY 命令提交。</p>
     */
    private void reinstallShellIntegration(SshTerminalSession terminal,
                                           TerminalOutputListener listener,
                                           RelayOutputListener relay,
                                           SessionRuntime runtime) {
        try {
            ShellIntegrationInstaller.Outcome outcome = ShellIntegrationInstaller.reinstall(
                    terminal, properties.getShellType(), shellTimeouts, listener, relay::switchTo);
            relay.switchTo(outcome.listener());
            if (outcome.scheduler() != null) {
                runtime.setScheduler(outcome.scheduler());
                LOG.info("嵌套 Shell 集成重安装完成: session={} nonce={}",
                        runtime.sessionId(), outcome.nonce());
            }
        } catch (RuntimeException e) {
            relay.switchTo(listener);
            LOG.warn("嵌套 Shell 集成重安装失败，降级为 exec 通道: session={} cause={}",
                    runtime.sessionId(), String.valueOf(e.getMessage()));
        }
    }
    
    @PreDestroy
    void shutdownTimeouts() {
        shellTimeouts.shutdownNow();
    }

    // ==================================================================
    // 人工命令持久化回调（Shell → Agent 记忆同步）
    // ==================================================================

    /**
     * 人工命令完成回调：将命令持久化到账本和对话历史。
     *
     * <p>WHY 在回调中做两件事：①写 {@code command_executions} 保证审计完整，
     * ②写 {@code ai_messages} 让 Agent 上下文能引用人工命令结果。
     * conversationId 为 null（用户从未创建对话）时只写账本，跳过对话历史。</p>
     */
    private void onManualCommandComplete(UUID sessionId, ManualCommandInfo info) {
        try {
            // 1. 写入 command_executions 账本
            UUID conversationId = lookupConversationId(sessionId);
            CommandExecution execution = commandExecutions.claimExecution(
                    "manual", null, null, sessionId, conversationId, info.command());
            // 提取输出摘要（去掉命令回显的第一行）
            String outputSummary = extractOutputSummary(info.output(), info.command());
            commandExecutions.markCompleted(
                    execution.getId(), info.exitCode(), outputSummary, "",
                    info.truncated());

            // 2. 写入对话历史（conversationId 存在时）
            if (conversationId != null) {
                String eventContent = formatShellEventContent(info, outputSummary);
                UUID executionUuid = UUID.fromString(execution.getId());
                conversations.saveShellEventMessage(conversationId, eventContent,
                        executionUuid, null);
            }
            LOG.debug("人工命令已持久化: session={} command={} exitCode={}",
                    sessionId, info.command(), info.exitCode());
        } catch (RuntimeException e) {
            // WHY 吞异常不抛出：回调在调度器同步块内触发，异常会破坏调度器状态机
            LOG.warn("人工命令持久化失败，不影响终端功能: session={} command={} cause={}",
                    sessionId, info.command(), e.getMessage());
        }
    }

    /**
     * 从 {@code ai_conversations} 表查找绑定到指定 sessionId 的对话 ID。
     *
     * @return 对话 ID；未找到时返回 null（降级：只写账本，跳过对话历史）
     */
    @Nullable
    private UUID lookupConversationId(UUID sessionId) {
        List<Conversation> found = conversations.findBySession(sessionId);
        return found.isEmpty() ? null : found.get(0).id();
    }

    /**
     * 从 PTY 输出中提取命令结果摘要（去掉第一行命令回显）。
     */
    private static String extractOutputSummary(String fullOutput, String command) {
        if (fullOutput == null || fullOutput.isEmpty()) {
            return "";
        }
        // 跳过第一行命令回显
        int nlIdx = fullOutput.indexOf('\n');
        String afterEcho = nlIdx >= 0 ? fullOutput.substring(nlIdx + 1) : "";
        // 去掉 \r
        afterEcho = afterEcho.replace("\r", "");
        // 截断到 500 字符
        if (afterEcho.length() > 500) {
            return afterEcho.substring(0, 500) + "\n...（已截断）";
        }
        return afterEcho;
    }

    /**
     * 格式化 shell_event 消息内容。
     */
    private static String formatShellEventContent(ManualCommandInfo info, String outputSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append(info.command()).append('\n');
        sb.append("exit=").append(info.exitCode());
        if (!outputSummary.isEmpty()) {
            sb.append('\n').append(outputSummary);
        }
        return sb.toString();
    }

    /**
     * 可切换目标的输出中转器。
     *
     * <p>WHY volatile：读泵线程在构造时刻就开始回调，而接线完成后才切换目标；
     * 两条线程无锁协调，一次性替换引用用 volatile 即足够。</p>
     */
    private static final class RelayOutputListener implements TerminalOutputListener {

        private volatile TerminalOutputListener target;

        RelayOutputListener(TerminalOutputListener target) {
            this.target = target;
        }

        void switchTo(TerminalOutputListener next) {
            this.target = next;
        }

        @Override
        public void onStdout(String data) {
            target.onStdout(data);
        }

        @Override
        public void onStderr(String data) {
            target.onStderr(data);
        }

        @Override
        public void onClosed(SshCloseReason reason) {
            target.onClosed(reason);
        }
    }
}

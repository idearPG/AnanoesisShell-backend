package com.ananoesis.shell.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

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
import com.ananoesis.shell.ws.ToolName;

/**
 * 智能体可调用的工具集（tasks 9.2 / 9.3 / 9.4 / 9.5 / 5.5）。
 */
@Component
public class AgentTools {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTools.class);

    public static final String CTX_HOST_ID = "hostId";
    public static final String CTX_SESSION_ID = "sessionId";
    public static final String CTX_CONVERSATION_ID = "conversationId";

    private static final int READ_ONLY_TIMEOUT_SECONDS = 30;

    private static final String READ_FILE_TRUNCATED_HINT =
            "\n[... 已按 read_file.max_lines 上限截断，后续行未返回；请用 run_command 配合 grep/sed 精确定位 ...]";

    private static final String SYSTEM_INFO_COMMAND = String.join("; ",
            "echo '== 内核 =='",
            "uname -a",
            "echo '== 发行版 =='",
            "head -n 6 /etc/os-release 2>/dev/null || echo '(无 /etc/os-release)'",
            "echo '== 运行时长与负载 =='",
            "uptime",
            "echo '== 内存 =='",
            "free -m 2>/dev/null || echo '(无 free 命令)'",
            "echo '== 磁盘 =='",
            "df -hP 2>/dev/null | head -n 20 || echo '(无 df 命令)'",
            "echo '== CPU 核数 =='",
            "nproc 2>/dev/null || echo '(无 nproc 命令)'");

    private static final String INTERNAL_ERROR_MESSAGE = "服务器内部错误，请查看后端日志获取详情";

    private final SshExecService exec;
    private final SettingsService settings;
    @Nullable
    private final PtyCommandGateway ptyGateway;

    private static final ThreadLocal<String> LAST_ERROR = new ThreadLocal<>();

    @Autowired
    public AgentTools(SshExecService exec, SettingsService settings,
                      @Nullable PtyCommandGateway ptyGateway) {
        this.exec = Objects.requireNonNull(exec, "exec 不得为 null");
        this.settings = Objects.requireNonNull(settings, "settings 不得为 null");
        this.ptyGateway = ptyGateway;
    }

    public AgentTools(SshExecService exec, SettingsService settings) {
        this(exec, settings, null);
    }

    // ==================================================================
    // 只读工具
    // ==================================================================

    // WHY resultConverter = PlainTextToolResultConverter.class：
    // Spring AI 默认转换器对任何返回值都调 JsonParser.toJson()，
    // 会把 String 再 JSON 化一遍（加引号、\n 变字面量），
    // 导致回喂给模型的文本不可读。PlainTextToolResultConverter 原样透出。
    @Tool(name = "list_dir",
            description = "列出指定目录的内容（类似 ls -la）。路径必须是绝对路径。",
            resultConverter = PlainTextToolResultConverter.class)
    public String listDir(@ToolParam(description = "目录绝对路径") String path,
                          ToolContext context) {
        String trimmed = requireText(path, "path");
        UUID hostId = requireHostId(context);
        LOG.info("list_dir: hostId={} path={}", hostId, trimmed);
        String command = "ls -Al --time-style=long-iso " + quote(trimmed);
        return executeAndFormat(ToolName.LIST_DIR, command, context);
    }

    @Tool(name = "read_file",
            description = "读取指定文件的内容。路径必须是绝对路径。可指定起始行。",
            resultConverter = PlainTextToolResultConverter.class)
    public String readFile(@ToolParam(description = "文件绝对路径") String path,
                           @ToolParam(description = "起始行号（1-based），不传从头开始", required = false) Integer startLine,
                           ToolContext context) {
        String trimmed = requireText(path, "path");
        int maxLines = settings.readFileMaxLines();
        int start = (startLine == null || startLine <= 0) ? 1 : startLine;
        int end = start + maxLines - 1;
        UUID hostId = requireHostId(context);
        LOG.info("read_file: hostId={} path={} range={}-{}", hostId, trimmed, start, end);
        String command = "sed -n '" + start + "," + end + "p' " + quote(trimmed);
        ExecOutcome outcome = executeOrReturnError(ToolName.READ_FILE, command, context);
        if (outcome == null) {
            // 基础设施失败，错误消息已在 executeOrReturnError 中以 "错误：" 格式返回
            return lastErrorString();
        }
        String text = format(outcome);
        // WHY 按 stdout 行数判断：format 会加 "exit=N\n" 前缀，不应算入内容行数
        if (countLines(outcome.stdout()) >= maxLines) {
            text += READ_FILE_TRUNCATED_HINT;
        }
        return text;
    }

    @Tool(name = "system_info",
            description = "采集目标服务器的系统概况（内核、内存、磁盘、CPU 等）。无需参数。",
            resultConverter = PlainTextToolResultConverter.class)
    public String systemInfo(ToolContext context) {
        UUID hostId = requireHostId(context);
        LOG.info("system_info: hostId={}", hostId);
        return executeAndFormat(ToolName.SYSTEM_INFO, SYSTEM_INFO_COMMAND, context);
    }

    // ==================================================================
    // 副作用工具
    // ==================================================================

    @Tool(name = "run_command",
            description = "在目标服务器上执行一条 shell 命令。需要用户批准。",
            resultConverter = PlainTextToolResultConverter.class)
    public String runCommand(@ToolParam(description = "要执行的命令") String command,
                             @ToolParam(description = "执行理由说明", required = false) String description,
                             ToolContext context) {
        throw new UnsupportedOperationException(
                "run_command 必须经过审批闸门（ApprovalGate），不得直接执行。"
                        + "这是代码缺陷，请检查 AiAgentService 的工具路由逻辑。");
    }

    // ==================================================================
    // 内部：执行通道
    // ==================================================================

    /**
     * 执行只读命令并格式化结果。
     *
     * <p>WHY 区分两种失败路径：
     * <ul>
     *   <li><b>基础设施失败</b>（异常：主机不存在、连接失败等）→ "错误：xxx"，
     *       因为根本没有远端执行结果</li>
     *   <li><b>命令执行结果</b>（含非零退出码）→ format(outcome)，
     *       因为 exit code + stdout + stderr 是模型需要的事实</li>
     * </ul></p>
     */
    private String executeAndFormat(ToolName toolName, String command, ToolContext context) {
        ExecOutcome outcome = executeOrReturnError(toolName, command, context);
        if (outcome == null) {
            return lastErrorString();
        }
        return format(outcome);
    }

    /**
     * 执行只读命令。基础设施失败时返回 null 并设置 LAST_ERROR。
     *
     * @return ExecOutcome（命令确实执行了）或 null（基础设施失败）
     */
    @Nullable
    private ExecOutcome executeOrReturnError(ToolName toolName, String command, ToolContext context) {
        String sessionId = sessionIdOf(context);
        if (ptyGateway != null && sessionId != null && !sessionId.isBlank()) {
            try {
                return tryPtyPath(sessionId, command);
            } catch (TurnCancelledException e) {
                // BUG-B：用户停止不是「PTY 路径不可用」，MUST 透传禁止回落 exec 重跑
                throw e;
            } catch (RuntimeException e) {
                LOG.debug("PTY 路径不可用，回落 exec 通道: tool={} session={} cause={}",
                        toolName.getValue(), sessionId, e.getMessage());
            }
        }
        return executeViaExecOrError(toolName, command, context);
    }

    @Nullable
    private ExecOutcome tryPtyPath(String sessionId, String command) {
        long start = System.currentTimeMillis();
        CompletableFuture<PtyCommandScheduler.CommandResult> future = ptyGateway.submit(sessionId, command);
        // WHY 等待上限对齐调度器绝对上限：调度器改为空闲超时语义后，长时活跃命令
        // 会跑满绝对上限才中断；此处若按旧 30s 等待会提前超时并误回落 exec 重跑（BUG-A）。
        long waitSeconds = PtyCommandScheduler.ptyWaitCeilingSeconds(READ_ONLY_TIMEOUT_SECONDS);
        try {
            PtyCommandScheduler.CommandResult result = future.get(waitSeconds, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            return new ExecOutcome(result.exitCode(), result.stdout(), "",
                    result.truncated(), result.timedOut(), elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // BUG-B：旧实现抛普通 RuntimeException 被 executeOrReturnError 的
            // catch(RuntimeException) 当「PTY 不可用」吞掉回落 exec，停止永远停不干净
            throw new TurnCancelledException("等待 PTY 结果时被用户停止", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("PTY 执行失败", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("超时", e);
        }
    }

    /**
     * 查询会话终端的当前工作目录（供 {@link AiAgentService} 注入系统提示词）。
     *
     * <p>WHY 挂在本类而非 AiAgentService 直连 gateway：AiAgentService 构造器虽收
     * agentTools 但未存字段（只反射生成回调），而本类已持有可选的
     * {@link PtyCommandGateway}，复用现成查找链改动面最小。无网关/未命中都安全
     * 返回 null，提示词因此不渲染工作目录段而不是构建失败。</p>
     *
     * @param sessionId 终端会话 id，可为 null
     * @return 会话最新 cwd；会话不存在、未集成或无网关时为 null
     */
    public @Nullable String sessionCwdOf(@Nullable String sessionId) {
        if (ptyGateway == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        PtyCommandScheduler scheduler = ptyGateway.findScheduler(sessionId);
        return scheduler == null ? null : scheduler.sessionCwd();
    }

    /**
     * 打断指定会话在飞的远端命令（用户停止回合时由 {@link AiAgentService#stop} 调用）。
     *
     * <p>WHY 经本类而不是让 AiAgentService 直连网关：与 {@link #sessionCwdOf} 同理，
     * 本类持有可选的 {@link PtyCommandGateway}，复用现成查找链改动面最小。
     * 停止只中断本地等待不够——远端 PTY 上的命令（如安装中的 JDK）还在跑，
     * 必须由调度器发 Ctrl-C 真正打断（BUG-B）。</p>
     *
     * @param sessionId 终端会话 id，可为 null（无网关/未命中静默跳过，不打断停止主流程）
     */
    public void interruptInFlightCommand(@Nullable String sessionId) {
        if (ptyGateway == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        PtyCommandScheduler scheduler = ptyGateway.findScheduler(sessionId);
        if (scheduler != null) {
            scheduler.interruptCurrent();
        }
    }

    /**
     * 通过 exec 路径执行。异常时设置 LAST_ERROR 并返回 null。
     */
    @Nullable
    private ExecOutcome executeViaExecOrError(ToolName toolName, String command, ToolContext context) {
        UUID hostId = hostIdOf(context);
        ExecLimits limits = readOnlyLimits();
        try {
            return exec.executeForHost(hostId, command, limits);
        } catch (HostNotFoundException e) {
            LAST_ERROR.set("目标服务器配置不存在或已被删除");
            return null;
        } catch (SshConnectException e) {
            LAST_ERROR.set(e.userMessage());
            return null;
        } catch (CredentialProtectionException e) {
            LAST_ERROR.set("凭据保护不可用，请在设置中配置主密码后重试");
            return null;
        } catch (RuntimeException e) {
            LOG.error("只读工具未预期异常: tool={}", toolName.getValue(), e);
            LAST_ERROR.set("工具执行失败：" + INTERNAL_ERROR_MESSAGE);
            return null;
        }
    }

    /** 将 LAST_ERROR 转为面向模型的 "错误：" 字符串。 */
    private String lastErrorString() {
        String error = LAST_ERROR.get();
        LAST_ERROR.remove();
        return "错误：" + (error == null ? INTERNAL_ERROR_MESSAGE : error);
    }

    ExecLimits readOnlyLimits() {
        return new ExecLimits(Duration.ofSeconds(READ_ONLY_TIMEOUT_SECONDS),
                settings.runCommandMaxOutputBytes());
    }

    // ==================================================================
    // 内部：格式化
    // ==================================================================

    static String format(ExecOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("exit=").append(outcome.exitCode());
        if (outcome.timedOut()) {
            sb.append(" [执行超时，已被中断，输出可能不完整]");
        }
        if (outcome.truncated()) {
            sb.append(" [输出超过上限，已被截断]");
        }
        sb.append("\n");
        if (!outcome.stdout().isEmpty()) {
            sb.append(outcome.stdout());
        }
        if (!outcome.stderr().isEmpty()) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append("\n");
            }
            sb.append("\n--- stderr ---\n");
            sb.append(outcome.stderr());
            if (!outcome.stderr().endsWith("\n")) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 统计文本行数。末尾换行不计为额外一行。
     * WHY 与 wc -l 一致："l1\nl2\n" 是 2 行，不是 3 行。
     */
    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean lastWasNewline = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (!lastWasNewline) {
                    count++;
                }
                lastWasNewline = true;
            } else {
                lastWasNewline = false;
            }
        }
        if (!lastWasNewline) {
            count++;
        }
        return count;
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不得为空");
        }
        return value.trim();
    }

    private static UUID requireHostId(@Nullable ToolContext context) {
        if (context == null) {
            throw new IllegalArgumentException("本会话未绑定目标服务器");
        }
        Object value = context.getContext().get(CTX_HOST_ID);
        if (value == null) {
            throw new IllegalArgumentException("本会话未绑定目标服务器");
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不是合法 UUID: " + s);
            }
        }
        throw new IllegalArgumentException("本会话未绑定目标服务器");
    }

    @Nullable
    private static UUID hostIdOf(@Nullable ToolContext context) {
        if (context == null) {
            return null;
        }
        Object value = context.getContext().get(CTX_HOST_ID);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static String sessionIdOf(@Nullable ToolContext context) {
        if (context == null) {
            return null;
        }
        Object value = context.getContext().get(CTX_SESSION_ID);
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        return null;
    }

    static void clearLastError() {
        LAST_ERROR.remove();
    }
}

package com.ananoesis.shell.ws;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.security.CredentialProtectionException;
import com.ananoesis.shell.service.NotFoundException;
import com.ananoesis.shell.ssh.PtyCommandScheduler;
import com.ananoesis.shell.ssh.SessionRuntime;
import com.ananoesis.shell.ssh.SshCloseReason;
import com.ananoesis.shell.ssh.SshConnectException;
import com.ananoesis.shell.ssh.SshTerminalService;
import com.ananoesis.shell.ssh.TerminalOutputListener;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code /terminal} 通道的协议处理器（task 6.3 的 WebSocket 侧 + Wave 2 resize/bind）。
 *
 * <p>职责严格限定在<b>协议翻译</b>：把 asyncapi.yaml 的 {@code terminal_input} 翻成
 * 对 {@link SshTerminalService} 的调用，把 {@link TerminalOutputListener} 的回调翻成
 * {@code terminal_output} 帧。它不碰凭据、不碰 SSH 库、不碰数据库——那些都在服务层。</p>
 *
 * <h2>会话归属</h2>
 * <p>每条 WebSocket 连接持有<b>自己</b>的一批终端会话，因此一条连接上可以并存多个终端
 * （前端多标签页），而 {@code session_id} 只能操作本连接建立的那些会话。
 * 跨连接的 id 一律按 {@code not_found} 处理，详见 {@code Connection#requireOwn}。</p>
 *
 * <h2>线程模型</h2>
 * <p>入站帧由容器线程串行投递；出站帧则可能来自 stdout 读泵、stderr 读泵、
 * 空闲回收线程与容器的连接关闭回调——<b>并发</b>。
 * {@code WebSocketSession.sendMessage} 并非线程安全（并发写会破坏帧边界），
 * 因此所有出站帧统一经 {@link ConcurrentWebSocketSessionDecorator} 串行化。</p>
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;
    private static final int MAX_TERMINALS_PER_CONNECTION = 8;
    private static final String INTERNAL_ERROR_MESSAGE = "服务器内部错误，请查看后端日志获取详情";

    /**
     * resize 校验：列/行上限。
     * WHY 1000：远超正常使用场景（4K 分辨率下终端也不会超过 300 列），
     * 但足以阻止恶意前端发送 Integer.MAX_VALUE 导致远端 PTY 分配失败。
     */
    private static final int RESIZE_MAX_COLS = 1000;
    private static final int RESIZE_MAX_ROWS = 1000;

    private final SshTerminalService terminalService;
    private final ObjectMapper objectMapper;

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(SshTerminalService terminalService, ObjectMapper objectMapper) {
        this.terminalService = Objects.requireNonNull(terminalService, "terminalService 不得为 null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不得为 null");
    }

    // ==================================================================
    // 连接生命周期
    // ==================================================================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_SIZE_LIMIT);
        connections.put(session.getId(), new Connection(guarded));
        LOG.debug("终端 WebSocket 已连接: ws={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) {
            LOG.warn("收到来自未登记连接的文本帧，已忽略: ws={}", session.getId());
            return;
        }
        connection.handle(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.remove(session.getId());
        int released = connection == null ? 0 : connection.closeAll(SshCloseReason.USER_DISCONNECT);
        LOG.info("终端 WebSocket 已断开: ws={} status={} released={}", session.getId(), status, released);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOG.warn("终端 WebSocket 传输错误，正在释放会话: ws={} cause={}",
                session.getId(), String.valueOf(exception.getMessage()));
        Connection connection = connections.remove(session.getId());
        if (connection != null) {
            connection.closeAll(SshCloseReason.ERROR);
        }
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    int connectionCount() {
        return connections.size();
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("关闭 WebSocket 连接时出错（已忽略）: ws={}", session.getId(), e);
        }
    }

    // ==================================================================
    // 单条连接的状态机
    // ==================================================================

    /**
     * 一条 WebSocket 连接及其名下的全部终端会话。
     */
    private final class Connection {

        private final WebSocketSession session;
        private final Map<String, SessionRuntime> terminals = new ConcurrentHashMap<>();

        Connection(WebSocketSession session) {
            this.session = session;
        }

        // ---------------- 入站 ----------------

        void handle(String payload) {
            TerminalInput input;
            try {
                input = objectMapper.readValue(payload, TerminalInput.class);
            } catch (IOException | RuntimeException e) {
                LOG.warn("terminal_input 无法解析，已按 validation_error 回复: ws={} cause={}",
                        session.getId(), String.valueOf(e.getMessage()));
                send(TerminalOutput.error(ErrorCode.VALIDATION_ERROR, "终端输入消息格式不正确"));
                return;
            }
            TerminalInput.Action action = input.action();
            if (action == null) {
                send(TerminalOutput.error(ErrorCode.VALIDATION_ERROR, "缺少必填字段 action"));
                return;
            }
            switch (action) {
                case OPEN -> open(input);
                case INPUT -> forward(input);
                case CLOSE -> close(input);
                case RESIZE -> resize(input);
                // WHY 需要 default：它覆盖的是"Java 枚举新增了常量却没人处理"这种编译期
                // 察觉不到的遗漏。注意线上出现的**未知取值**到不了这里——Jackson 默认拒绝
                // 未知枚举常量，会在上面 readValue 处就失败并按"帧无法解析"回复。
                default -> send(TerminalOutput.error(ErrorCode.VALIDATION_ERROR,
                        "不支持的 action 取值: " + action));
            }
        }

        private void open(TerminalInput input) {
            UUID hostId = input.hostId();
            if (hostId == null) {
                send(TerminalOutput.error(ErrorCode.VALIDATION_ERROR, "action=open 必须携带 host_id"));
                return;
            }
            if (terminals.size() >= MAX_TERMINALS_PER_CONNECTION) {
                LOG.warn("已达单连接终端数上限: ws={} max={}", session.getId(), MAX_TERMINALS_PER_CONNECTION);
                send(TerminalOutput.error(ErrorCode.CONFLICT,
                        "单个连接可打开的终端数量已达上限（" + MAX_TERMINALS_PER_CONNECTION + "）"));
                return;
            }
            try {
                SessionRuntime runtime = terminalService.open(hostId,
                        sessionId -> new Bridge(UUID.fromString(sessionId)));
                attach(runtime);
                send(TerminalOutput.opened(runtime.sessionId()));
            } catch (NotFoundException e) {
                LOG.warn("终端目标主机不存在: ws={} cause={}", session.getId(), e.getMessage());
                send(TerminalOutput.error(ErrorCode.NOT_FOUND, e.getMessage()));
            } catch (SshConnectException e) {
                LOG.info("终端建立失败: ws={} kind={} detail={}", session.getId(), e.kind(), e.getMessage());
                send(TerminalOutput.error(e.errorCode(), e.userMessage()));
            } catch (CredentialProtectionException e) {
                LOG.error("主密钥不可用，拒绝建立终端: ws={}", session.getId(), e);
                send(TerminalOutput.error(ErrorCode.CREDENTIAL_PROTECTION_UNAVAILABLE,
                        CredentialProtectionException.UNAVAILABLE_MESSAGE + "，请在设置中配置主密码后重试"));
            } catch (RuntimeException e) {
                LOG.error("建立终端时出现未预期异常: ws={}", session.getId(), e);
                send(TerminalOutput.error(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
            }
        }

        private void forward(TerminalInput input) {
            SessionRuntime runtime = requireOwn(input.sessionId());
            if (runtime == null) {
                return;
            }
            // WHY 在此标 busy：WS input 帧就是用户真实键入的证据——Agent 只有在
            // 空提示符（manual_idle）才允许注入命令，避免把字节打进用户的半行输入/
            // 全屏程序；人工命令跑完后 bash 钩子的 PROMPT 帧自动恢复 idle
            PtyCommandScheduler scheduler = runtime.scheduler();
            if (scheduler != null && input.data() != null && !input.data().isEmpty()) {
                scheduler.onManualBusy();
            }
            runtime.terminalSession().send(input.data());
        }

        private void close(TerminalInput input) {
            SessionRuntime runtime = requireOwn(input.sessionId());
            if (runtime == null) {
                return;
            }
            runtime.close(SshCloseReason.USER_DISCONNECT);
        }

        /**
         * task 4.5：resize 校验与转发。
         *
         * <p>WHY 必须校验：远端 PTY 分配对尺寸有上限（内核通常限制在 10000x10000 以内），
         * 零/负尺寸会导致远端程序行为异常（如 bash 按 0 列换行产生无限循环）。
         * 校验在本 handler 内完成，不污染服务层。</p>
         *
         * <p>WHY 只影响所属 session 的 PTY：每个 resize 帧携带 session_id，
         * 只转发到该 session 对应的远端 shell，不影响同连接上的其他终端。</p>
         */
        private void resize(TerminalInput input) {
            SessionRuntime runtime = requireOwn(input.sessionId());
            if (runtime == null) {
                return;
            }
            Integer cols = input.cols();
            Integer rows = input.rows();
            if (cols == null || rows == null) {
                send(TerminalOutput.error(input.sessionId(), ErrorCode.VALIDATION_ERROR,
                        "resize 必须携带 cols 和 rows"));
                return;
            }
            if (cols <= 0 || rows <= 0) {
                send(TerminalOutput.error(input.sessionId(), ErrorCode.VALIDATION_ERROR,
                        "cols 和 rows 必须为正整数"));
                return;
            }
            if (cols > RESIZE_MAX_COLS || rows > RESIZE_MAX_ROWS) {
                send(TerminalOutput.error(input.sessionId(), ErrorCode.VALIDATION_ERROR,
                        "cols 或 rows 超出允许范围（1.." + RESIZE_MAX_COLS + " / 1.." + RESIZE_MAX_ROWS + "）"));
                return;
            }
            // 只影响所属 session 的 PTY
            runtime.terminalSession().resize(cols, rows);
        }

        /**
         * 取出<b>属于本连接</b>的运行时；不存在则回一帧错误并返回 null。
         */
        private SessionRuntime requireOwn(UUID sessionId) {
            if (sessionId == null) {
                send(TerminalOutput.error(ErrorCode.VALIDATION_ERROR, "该 action 必须携带 session_id"));
                return null;
            }
            SessionRuntime runtime = terminals.get(sessionId.toString());
            if (runtime == null) {
                send(TerminalOutput.error(sessionId, ErrorCode.NOT_FOUND, "终端会话不存在或已结束"));
                return null;
            }
            return runtime;
        }

        // ---------------- 会话表维护 ----------------

        private void attach(SessionRuntime runtime) {
            synchronized (terminals) {
                if (!runtime.isClosed()) {
                    terminals.put(runtime.sessionId().toString(), runtime);
                } else {
                    LOG.debug("会话在登记前已结束，跳过: ws={} session={}",
                            session.getId(), runtime.sessionId());
                }
            }
        }

        private void detach(String sessionId) {
            synchronized (terminals) {
                terminals.remove(sessionId);
            }
        }

        private int closeAll(SshCloseReason reason) {
            List<SessionRuntime> snapshot;
            synchronized (terminals) {
                snapshot = List.copyOf(terminals.values());
                terminals.clear();
            }
            for (SessionRuntime runtime : snapshot) {
                runtime.close(reason);
            }
            return snapshot.size();
        }

        // ---------------- 出站 ----------------

        private void send(TerminalOutput frame) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
            } catch (SessionLimitExceededException e) {
                LOG.warn("终端输出积压超过上限，按不可靠连接关闭: ws={} status={}",
                        session.getId(), e.getStatus());
                closeQuietly(session, e.getStatus());
            } catch (IOException | RuntimeException e) {
                LOG.debug("发送 terminal_output 失败: ws={} type={}", session.getId(), frame.type(), e);
            }
        }

        // ---------------- SSH 输出 → terminal_output ----------------

        /**
         * 把一条 SSH 会话的输出桥接到它所属的 WebSocket 连接。
         *
         * <p>WHY 嵌在 {@link Connection} 里面：它必须同时握住"会话 id"与"目标连接"，
         * 而后者只对创建它的那条连接有意义。嵌进来后，"Bridge 一定发往自己的连接"由编译器保证。</p>
         */
        private final class Bridge implements TerminalOutputListener {

            private final UUID sessionId;

            Bridge(UUID sessionId) {
                this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不得为 null");
            }

            @Override
            public void onStdout(String data) {
                send(TerminalOutput.stdout(sessionId, data));
            }

            @Override
            public void onStderr(String data) {
                send(TerminalOutput.stderr(sessionId, data));
            }

            @Override
            public void onClosed(SshCloseReason reason) {
                detach(sessionId.toString());
                send(TerminalOutput.closed(sessionId, reason.toContractEndReason()));
            }
        }
    }
}

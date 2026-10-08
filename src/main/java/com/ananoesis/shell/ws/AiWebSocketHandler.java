package com.ananoesis.shell.ws;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

import com.ananoesis.shell.ai.AiAgentService;
import com.ananoesis.shell.ai.AiStreamEmitter;
import com.ananoesis.shell.ai.TurnRequest;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code /ai} 通道的协议处理器（tasks 7.4 / 9.4）。
 *
 * <p>契约按 TRACEABILITY <b>Q2</b> 的裁定，上下行<b>复用同一个</b> {@code ai_stream} schema：
 * 客户端上行 {@code type=user_message} 触发一轮对话、{@code type=stop_turn} 打断在飞回合
 * （V2，用户 Ctrl+C 诉求），服务器下行其余类型
 * （{@code thinking_delta}/{@code answer_delta}/{@code tool_call}/{@code tool_result}/
 * {@code final}/{@code error}）。因此本类同时是<b>入站解析器</b>与
 * {@link AiStreamEmitter} 的<b>出站实现</b>。</p>
 *
 * <h2>职责边界</h2>
 * <p>本类只做<b>协议翻译</b>与<b>会话簿记</b>：把上行帧翻成 {@link TurnRequest} 交给
 * {@link AiAgentService}，把智能体产出的帧序列化后广播出去。它不碰模型、不碰工具、
 * 不碰数据库——回合的全部逻辑在 {@code AiAgentService} 里，本类因此薄到可以被完整单测。</p>
 *
 * <h2>WHY 用 {@code ObjectProvider} 之外的一侧断环</h2>
 * <p>{@code AiAgentService} 通过 {@code ObjectProvider<AiStreamEmitter>} 拿到本类，
 * 本类通过构造器拿到 {@code AiAgentService}。看似循环，实则不成立：
 * {@code ObjectProvider} 是<b>惰性</b>的，Spring 构造智能体时并不解析 emitter，
 * 直到第一次 {@code getIfAvailable()} 才去容器里找。若两侧都用构造器直连，
 * 应用启动期就会因循环依赖失败。</p>
 *
 * <h2>WHY 出站广播给<b>所有</b>连接，而校验错误只回<b>发起</b>连接</h2>
 * <p>增量帧必须出现在用户<b>正在看</b>的那个窗口里，而"提问的连接"与"当前标签页"
 * 并不总是同一个（用户可能在 A 提问后切到 B 等结果）；帧自带 {@code conversation_id}，
 * 前端据此过滤即可，所以广播是安全的。而"提问内容为空"这类校验错误是对一次具体请求的
 * 直接答复，广播出去只会在别的标签页上凭空弹出一条与那边无关的报错。</p>
 *
 * <h2>WHY 缺 {@code conversation_id} 时只记日志、不回错误帧</h2>
 * <p>契约把 {@code conversation_id} 列为 {@code required}，而 {@link AiStreamFrame#error}
 * 也拒绝 null 的会话 id。要回一帧"缺 conversation_id"的错误，就得发出一个
 * <b>违反契约</b>的帧——用一个违约去报告另一个违约。前端也无处渲染它
 * （不知道该挂到哪个会话下）。于是选择只记日志；这是开发期缺陷，日志足以定位。</p>
 *
 * <h2>安全</h2>
 * <ul>
 *   <li>MUST NOT 记录 {@code content}：用户完全可能把一段含口令的配置贴进提问里，
 *       而提问原文已经落库（那是功能需要），日志再抄一份只是扩大暴露面。
 *       本类只记长度。</li>
 *   <li>MUST NOT 记录帧的 JSON 原文，理由同上。</li>
 * </ul>
 */
@Component
public class AiWebSocketHandler extends TextWebSocketHandler implements AiStreamEmitter {

    private static final Logger LOG = LoggerFactory.getLogger(AiWebSocketHandler.class);

    /** 单帧发送的时间上限（毫秒）。同 {@code ApprovalWebSocketHandler}。 */
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    /**
     * 出站缓冲上限（字节）。
     *
     * <p>WHY 取 1MiB 而不是审批通道的 512KiB：一次流式回答会产出<b>成百上千</b>个增量帧，
     * 前端若在渲染上卡住（例如切到后台标签页被浏览器降频），积压量远高于低频的审批帧。
     * 上限的真正作用是"别让一个死掉的连接无限吃内存"，1MiB 约等于一部长文的流式输出量，
     * 足够容忍瞬时卡顿，又不至于在多个会话并发时失控。</p>
     */
    private static final int SEND_BUFFER_SIZE_LIMIT = 1024 * 1024;

    /** 提问内容为空时回给前端的说明。 */
    static final String EMPTY_CONTENT_MESSAGE = "提问内容不得为空";

    private final AiAgentService agent;
    private final ObjectMapper objectMapper;

    /** 键 = {@link WebSocketSession#getId()}；值 = 串行化后的会话。 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public AiWebSocketHandler(AiAgentService agent, ObjectMapper objectMapper) {
        this.agent = Objects.requireNonNull(agent, "agent 不得为 null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不得为 null");
    }

    // ==================================================================
    // 连接生命周期
    // ==================================================================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_SIZE_LIMIT));
        LOG.debug("AI WebSocket 已连接: ws={}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        LOG.info("AI WebSocket 已断开: ws={} status={} 剩余连接={}",
                session.getId(), status, sessions.size());
        // WHY 不在这里取消在飞回合：回合可能正阻塞在审批等待上，而用户很可能只是刷新了页面。
        // 取消会让那条已批准的命令失去回喂对象、审计行停在半截。
        // 让它跑完并落库，用户重连后仍能在历史消息里看到结果——
        // 期间的出站帧由 emit() 在"无连接"时静默丢弃（见下）。
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOG.warn("AI WebSocket 传输错误: ws={} cause={}",
                session.getId(), String.valueOf(exception.getMessage()));
        sessions.remove(session.getId());
        closeQuietly(session);
    }

    /** 当前活跃的 AI 连接数；供测试断言"没有连接泄漏"。 */
    int connectionCount() {
        return sessions.size();
    }

    // ==================================================================
    // 上行：user_message / stop_turn
    // ==================================================================

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        AiStreamFrame frame;
        try {
            frame = objectMapper.readValue(message.getPayload(), AiStreamFrame.class);
        } catch (IOException | RuntimeException e) {
            // 解析失败时连 conversation_id 都拿不到，无从回错误帧（见类注释）
            LOG.warn("ai_stream 无法解析，已忽略: ws={} cause={}",
                    session.getId(), String.valueOf(e.getMessage()));
            return;
        }

        if (frame.type() == AiStreamFrame.Type.STOP_TURN) {
            handleStopTurn(session, frame);
            return;
        }

        if (frame.type() != AiStreamFrame.Type.USER_MESSAGE) {
            // 下行类型被客户端回发。忽略而不是报错：Q2 复用同一 schema 的必然代价是
            // "类型合法但方向不对"，那属于前端缺陷，后端不替它兜
            LOG.warn("忽略非法上行帧: ws={} type={}", session.getId(), frame.type());
            return;
        }

        UUID conversationId = frame.conversationId();
        if (conversationId == null) {
            LOG.warn("user_message 缺少 conversation_id，已忽略: ws={}", session.getId());
            return;
        }

        String content = frame.content();
        if (content == null || content.isBlank()) {
            LOG.info("user_message 内容为空，已回校验错误: ws={} conversationId={}",
                    session.getId(), conversationId);
            send(session, AiStreamFrame.error(conversationId, ErrorCode.VALIDATION_ERROR,
                    EMPTY_CONTENT_MESSAGE));
            return;
        }

        // WHY 上行 host_id 优先于会话已绑定的服务器：契约允许 user_message 携带可选的
        // host_id，而 Conversation.host_id 本身可为空（TRACEABILITY Q7）——
        // 前端完全可能先建会话、提问时才选目标机。两者都有值且不同时以上行为准，
        // 因为那代表用户在界面上的<b>当前</b>选择；AiAgentService 会在 host 为空时
        // 回退到会话绑定，此处无需重复判断。session_id 同理透传：它是智能体把
        // 获准命令路由回共享 PTY（继承 cwd/env）的唯一依据，缺失即回落 exec
        TurnRequest request = new TurnRequest(conversationId, frame.hostId(), content, frame.sessionId());
        boolean accepted = agent.submit(request);
        if (accepted) {
            LOG.info("已受理提问: ws={} conversationId={} hostId={} 长度={}",
                    session.getId(), conversationId, frame.hostId(), content.length());
        } else {
            // submit() 已经发出 conflict 错误帧，此处不重复通知
            LOG.info("提问被拒（本会话已有回合在飞）: ws={} conversationId={}",
                    session.getId(), conversationId);
        }
    }

    /**
     * 处理 {@code stop_turn} 上行（用户 Ctrl+C 打断在飞回合）。
     *
     * <p>只翻成 {@link AiAgentService#stop}：回合侧的中断清算（停模型流、
     * 未执行工具失效、以 final+STOPPED_NOTE 收尾）全在智能体内已有实现。
     * 未命中在飞回合（已自然结束）时 stop 返回 false，这里不报错也不回帧——
     * 停止是幂等通知，多发一次无副作用；回错误帧反而会在前端凭空弹报错。</p>
     */
    private void handleStopTurn(WebSocketSession session, AiStreamFrame frame) {
        UUID conversationId = frame.conversationId();
        if (conversationId == null) {
            LOG.warn("stop_turn 缺少 conversation_id，已忽略: ws={}", session.getId());
            return;
        }
        boolean stopped = agent.stop(conversationId);
        LOG.info("已处理 stop_turn: ws={} conversationId={} 命中在飞回合={}",
                session.getId(), conversationId, stopped);
    }

    // ==================================================================
    // 下行：AiStreamEmitter
    // ==================================================================

    /**
     * 广播一帧 {@code ai_stream}（{@link AiStreamEmitter} 的实现）。
     *
     * <p>本方法 MUST NOT 抛异常：它被智能体在增量循环里高频调用，
     * 一次发送失败不该中断整个回合——产物还要落库，用户刷新后仍应看到完整回答。</p>
     */
    @Override
    public void emit(AiStreamFrame frame) {
        Objects.requireNonNull(frame, "frame 不得为 null");
        if (sessions.isEmpty()) {
            // 没有前端连着 AI 通道（用户关了页面/正在刷新）。回合继续跑完并落库，
            // 只记 debug：这在"提问后切走"的正常用法下会发生，不是故障
            LOG.debug("无 AI 连接，帧被丢弃: conversationId={} type={}",
                    frame.conversationId(), frame.type());
            return;
        }
        for (WebSocketSession session : Set.copyOf(sessions.values())) {
            send(session, frame);
        }
    }

    // ==================================================================
    // 出站
    // ==================================================================

    private void send(WebSocketSession session, AiStreamFrame frame) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
        } catch (SessionLimitExceededException e) {
            // 前端消费不过来（多半是被浏览器降频的后台标签页）：这条连接已不可信，
            // 主动关掉，否则它会永远留在 sessions 表里、每帧广播都白跑一趟
            LOG.warn("AI 帧积压超过上限，按不可靠连接关闭: ws={} status={}",
                    session.getId(), e.getStatus());
            closeQuietly(session);
            sessions.values().remove(session);
        } catch (IOException | RuntimeException e) {
            // 序列化失败或对端已走。MUST NOT 把异常原文写进 INFO/WARN：
            // Jackson 的报错会带上被序列化对象的内容片段，而增量帧里就是用户的对话正文
            LOG.debug("发送 ai_stream 失败: ws={} type={} cause={}",
                    session.getId(), frame.type(), String.valueOf(e.getMessage()));
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("关闭 AI WebSocket 时出错（已忽略）: ws={}", session.getId(), e);
        }
    }
}

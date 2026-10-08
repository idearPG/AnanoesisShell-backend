package com.ananoesis.shell.ws;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

import com.ananoesis.shell.approval.ApprovalGate;
import com.ananoesis.shell.approval.ApprovalNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code /approval} 通道的协议处理器（tasks 8.1 / 8.2 / 6.4）。
 *
 * <p>方向与终端<b>相反</b>：审批请求由<b>后端</b>发起（契约里是 {@code publish} +
 * {@code operationId=sendApprovalRequest}），用户的决定由<b>客户端</b>回送
 * （{@code subscribe} + {@code receiveApprovalResponse}）。</p>
 *
 * <h2>版本化支持（6.4）</h2>
 * <p>入站帧的 {@code decision} 支持三种取值：{@code approve}、{@code cancel}、{@code modify}。
 * 带 {@code expected_version} 的裁决走 {@link ApprovalGate#respondWithVersion}；
 * 修改操作走 {@link ApprovalGate#modify}。不带版本号的旧客户端仍走向后兼容的
 * {@link ApprovalGate#respond}。</p>
 *
 * <h2>职责边界</h2>
 * <p>本类只做<b>协议翻译</b>与<b>会话簿记</b>：把 {@link ApprovalGate.Ticket} 翻成
 * {@link ApprovalRequestFrame} 广播出去，把入站的 {@link ApprovalResponseFrame} 翻成
 * 闸门调用。它不判断"该不该批准"、不执行命令、不写审计——
 * 那些分别在 {@code ApprovalGate} 与 {@code ApprovedCommandRunner} 里。</p>
 *
 * <h2>WHY 广播给<b>所有</b>已连接会话，而不是只发给发起对话的那一个</h2>
 * <p>这是单机单用户桌面应用：审批弹框必须出现在用户<b>正在看</b>的那个窗口里。
 * 而"发起对话的连接"与"用户当前所在的标签页"并不总是同一个——
 * 用户可能在 A 标签页提问，然后切到 B 标签页等结果。只发给 A，弹框就永远不会被看到，
 * 命令一路挂到超时。契约的 {@code ApprovalRequest} 里也没有"目标连接"这一字段，
 * 说明它本来就设计成广播。</p>
 *
 * <h2>WHY 坏帧只记日志、不回错误帧</h2>
 * <p>契约在 {@code /approval} 通道上只定义了 {@code approval_request} 与
 * {@code approval_response} 两种消息，<b>没有</b>错误帧（不像 {@code /terminal}
 * 有 {@code terminal_output(type=error)}）。自己发明一种帧就是破坏冻结契约。
 * 代价是前端发错字段时得不到即时反馈——但那属于开发期缺陷，
 * 后端日志足以定位；相比之下，改契约的影响面大得多。</p>
 */
@Component
public class ApprovalWebSocketHandler extends TextWebSocketHandler implements ApprovalNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalWebSocketHandler.class);

    /** 单帧发送的时间上限（毫秒）。同 {@code TerminalWebSocketHandler}。 */
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    /**
     * 出站缓冲上限（字节）。
     * WHY 比终端小得多：审批帧是低频的小 JSON（一条命令 + 一段分析），
     * 512KiB 已经能积压上千条。真到了这个量，说明前端早就不消费了，
     * 继续堆只会白占内存。
     */
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    private final ApprovalGate gate;
    private final ObjectMapper objectMapper;

    /** 键 = {@link WebSocketSession#getId()}；值 = 串行化后的会话。 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public ApprovalWebSocketHandler(ApprovalGate gate, ObjectMapper objectMapper) {
        this.gate = Objects.requireNonNull(gate, "gate 不得为 null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不得为 null");
    }

    // ==================================================================
    // 连接生命周期
    // ==================================================================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_SIZE_LIMIT));
        LOG.debug("审批 WebSocket 已连接: ws={}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        LOG.info("审批 WebSocket 已断开: ws={} status={} 剩余连接={}",
                session.getId(), status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOG.warn("审批 WebSocket 传输错误: ws={} cause={}",
                session.getId(), String.valueOf(exception.getMessage()));
        sessions.remove(session.getId());
        closeQuietly(session);
    }

    /** 当前活跃的审批连接数；供测试断言"没有连接泄漏"。 */
    int connectionCount() {
        return sessions.size();
    }

    // ==================================================================
    // 下行：approval_request
    // ==================================================================

    /**
     * 广播一个待决提案（{@link ApprovalNotifier} 的实现）。
     *
     * <p>WHY 逐个会话发送、单个失败不影响其余：某个标签页可能刚被关掉，
     * 对它的发送会抛异常。若因此中断循环，剩下那些<b>活着</b>的连接就收不到弹框——
     * 一个已死连接把整个审批流程带下水，是最坏的结果。</p>
     */
    @Override
    public void notifyRequest(ApprovalGate.Ticket ticket) {
        ApprovalRequestFrame frame = toFrame(ticket);
        if (sessions.isEmpty()) {
            // 没有前端连着审批通道。提案仍然有效（已落库、已在计时），
            // 用户重连后虽然收不到这条历史请求，但超时兜底会把它结掉。
            LOG.warn("无审批连接，无法推送审批请求: approvalId={}", ticket.approvalId());
            return;
        }
        for (WebSocketSession session : Set.copyOf(sessions.values())) {
            send(session, frame);
        }
        LOG.info("已推送审批请求: approvalId={} hostId={} tool={} timeoutSeconds={} 连接数={}",
                ticket.approvalId(), ticket.proposal().hostId(),
                ticket.proposal().toolName().getValue(), ticket.timeoutSeconds(), sessions.size());
    }

    /**
     * Ticket → 契约帧。
     *
     * <p>{@code timeout_seconds} 下发<b>实值</b>（TRACEABILITY Q5）：审批时限存在
     * {@code settings} 里、不经 REST 暴露，前端拿不到。不下发的话前端只能自己硬编码倒计时，
     * 一旦用户改了时限，弹框显示的秒数与后端真实的超时时刻就会分叉。</p>
     */
    private static ApprovalRequestFrame toFrame(ApprovalGate.Ticket ticket) {
        com.ananoesis.shell.approval.ApprovalProposal proposal = ticket.proposal();
        return ApprovalRequestFrame.of(
                ticket.approvalId(),
                proposal.conversationId(),
                proposal.hostId(),
                proposal.hostLabel(),
                proposal.toolName(),
                proposal.toolParams(),
                proposal.command(),
                proposal.aiAnalysis(),
                1, // V2：审批版本快照，初始为 1
                ticket.timeoutSeconds(),
                ticket.requestedAt());
    }

    // ==================================================================
    // 上行：approval_response（含版本化 6.4）
    // ==================================================================

    /**
     * 处理入站审批响应。
     *
     * <p>WHY 三种 decision 分别处理：
     * <ul>
     *   <li>{@code approve/cancel}：带 {@code expected_version} 走版本校验裁决，
     *       不带则走向后兼容的无版本裁决；</li>
     *   <li>{@code modify}：必须带 {@code expected_version} 和 {@code modified_command}，
     *       走 {@link ApprovalGate#modify} 产生新版本。</li>
     * </ul>
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ApprovalResponseFrame frame;
        try {
            frame = objectMapper.readValue(message.getPayload(), ApprovalResponseFrame.class);
        } catch (IOException | RuntimeException e) {
            LOG.warn("approval_response 无法解析，已忽略: ws={} cause={}",
                    session.getId(), String.valueOf(e.getMessage()));
            return;
        }
        if (frame.approvalId() == null || frame.decision() == null) {
            // 契约 required=[approval_id, decision]；缺任一项都无从裁决
            LOG.warn("approval_response 缺少必填字段，已忽略: ws={} approvalId={} decision={}",
                    session.getId(), frame.approvalId(), frame.decision());
            return;
        }

        boolean handled;
        switch (frame.decision()) {
            case MODIFY -> handled = handleModify(session, frame);
            case APPROVE, CANCEL -> handled = handleDecision(session, frame);
            default -> {
                LOG.warn("approval_response 含未建模的 decision，已忽略: ws={} decision={}",
                        session.getId(), frame.decision());
                return;
            }
        }

        if (!handled) {
            // 常见于用户对着一个已经超时的弹框点了按钮（前端倒计时与后端时限有细微偏差时）
            LOG.info("审批响应未命中任何挂起提案（可能已超时或版本不匹配）: ws={} approvalId={} decision={}",
                    session.getId(), frame.approvalId(), frame.decision());
        }
    }

    /**
     * 处理批准/取消决定（含版本校验）。
     *
     * <p>WHY 有 {@code expected_version} 时走版本校验路径：
     * 前端弹框显示的是某个版本的命令快照，如果期间命令被修改（版本递增），
     * 用户批准的其实是旧版本——这不应该被执行。</p>
     */
    private boolean handleDecision(WebSocketSession session, ApprovalResponseFrame frame) {
        if (frame.expectedVersion() != null) {
            // WHY 带版本校验的裁决路径（6.4）
            return gate.respondWithVersion(frame.approvalId(), frame.decision(), frame.expectedVersion());
        }
        // WHY 向后兼容：不带版本号的旧客户端仍按原逻辑裁决
        return gate.respond(frame.approvalId(), frame.decision());
    }

    /**
     * 处理修改决定（design D5 / 6.4）。
     *
     * <p>WHY 必须有 {@code expected_version} 和 {@code modified_command}：
     * 修改操作必须携带前端持有的版本号（乐观并发控制）和修改后的命令。
     * 缺少任一项则无从执行修改。</p>
     */
    private boolean handleModify(WebSocketSession session, ApprovalResponseFrame frame) {
        if (frame.expectedVersion() == null) {
            LOG.warn("modify 决定缺少 expected_version，已忽略: ws={} approvalId={}",
                    session.getId(), frame.approvalId());
            return false;
        }
        if (frame.modifiedCommand() == null || frame.modifiedCommand().isBlank()) {
            LOG.warn("modify 决定缺少 modified_command，已忽略: ws={} approvalId={}",
                    session.getId(), frame.approvalId());
            return false;
        }
        return gate.modify(frame.approvalId(), frame.modifiedCommand(), frame.expectedVersion());
    }

    // ==================================================================
    // 出站
    // ==================================================================

    private void send(WebSocketSession session, ApprovalRequestFrame frame) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
        } catch (SessionLimitExceededException e) {
            // 前端消费不过来：这条连接已不可信，主动关掉，
            // 否则它会永远留在 sessions 表里、每次广播都白跑一趟
            LOG.warn("审批帧积压超过上限，按不可靠连接关闭: ws={} status={}", session.getId(), e.getStatus());
            closeQuietly(session);
            sessions.values().remove(session);
        } catch (IOException | RuntimeException e) {
            // 对端已经走了；只记 debug，不影响其它连接
            LOG.debug("发送 approval_request 失败: ws={} cause={}",
                    session.getId(), String.valueOf(e.getMessage()));
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("关闭审批 WebSocket 时出错（已忽略）: ws={}", session.getId(), e);
        }
    }
}

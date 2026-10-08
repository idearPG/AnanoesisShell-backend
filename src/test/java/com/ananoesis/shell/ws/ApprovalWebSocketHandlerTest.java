package com.ananoesis.shell.ws;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mockito;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.approval.ApprovalGate;
import com.ananoesis.shell.approval.ApprovalProposal;
import com.ananoesis.shell.support.FakeWebSocketSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * {@code /approval} 通道的协议翻译测试（JaCoCo 0.75 门禁补覆盖）。
 *
 * <p>覆盖三块此前无测试的路径：连接簿记（established/closed/transportError）、
 * 下行广播的容错分支（无连接、死连接跳过、发送异常吞掉、缓冲超限关闭），
 * 以及上行帧到闸门调用的翻译矩阵（版本化裁决 / 向后兼容裁决 / modify 必填校验）。
 * 闸门自身的裁决语义在 {@code ApprovalGateIntegrationTest} 已有覆盖，本层只管接线。</p>
 *
 * <p>WHY 手写 JSON 上行帧：同 {@link AiWebSocketHandlerTest}——裸字符串才能证明
 * 契约线上键名（snake_case）真的可被解析，自造帧会抵消注解写错的风险。</p>
 */
class ApprovalWebSocketHandlerTest {

    private ApprovalGate gate;
    private ApprovalWebSocketHandler handler;
    // WHY 带 JSR310：帧里 createdAt 是 OffsetDateTime，裸 mapper 无 JavaTimeModule 会
    // 在装饰器内序列化失败并被 handler 吞成 debug，现象就是“收不到帧”
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        gate = mock(ApprovalGate.class);
        handler = new ApprovalWebSocketHandler(gate, mapper);
    }

    /** 构造一个 RUN_COMMAND 提案的 Ticket（RUN_COMMAND 非自动执行，可直接成帧）。 */
    private ApprovalGate.Ticket ticket() {
        ApprovalProposal proposal = new ApprovalProposal(
                UUID.randomUUID(), UUID.randomUUID(), "label",
                ToolName.RUN_COMMAND, Map.of("command", "rm -rf /"),
                "rm -rf /", "会删除文件", null);
        return new ApprovalGate.Ticket(UUID.randomUUID(), proposal, 60,
                OffsetDateTime.now(), OffsetDateTime.now().plusSeconds(60));
    }

    // ==================================================================
    // 连接簿记
    // ==================================================================

    @Nested
    @DisplayName("连接簿记")
    class Lifecycle {

        @Test
        @DisplayName("连接建立后计入活跃数")
        void establishedCountsConnection() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            assertThat(handler.connectionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常断开后移出活跃表")
        void closedRemovesConnection() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.afterConnectionClosed(ws, org.springframework.web.socket.CloseStatus.NORMAL);
            assertThat(handler.connectionCount()).isZero();
        }

        @Test
        @DisplayName("传输错误：移出活跃表并关闭会话")
        void transportErrorClosesSession() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTransportError(ws, new IOException("模拟断链"));
            assertThat(handler.connectionCount()).isZero();
            assertThat(ws.isOpen()).isFalse();
            assertThat(ws.closeStatus()).isEqualTo(org.springframework.web.socket.CloseStatus.SERVER_ERROR);
        }

        @Test
        @DisplayName("传输错误时关闭已断会话抛异常也不上抛（closeQuietly 吞掉）")
        void transportErrorWithFailingCloseIsSwallowed() throws IOException {
            FakeWebSocketSession ws = Mockito.spy(new FakeWebSocketSession());
            Mockito.doThrow(new IOException("关闭失败")).when(ws).close(any());
            Mockito.doThrow(new IOException("关闭失败")).when(ws).close(any(org.springframework.web.socket.CloseStatus.class));
            handler.afterConnectionEstablished(ws);
            handler.handleTransportError(ws, new IOException("boom"));
            assertThat(handler.connectionCount()).isZero();
        }
    }

    // ==================================================================
    // 下行广播
    // ==================================================================

    @Nested
    @DisplayName("下行 approval_request 广播")
    class Broadcast {

        @Test
        @DisplayName("无连接时静默返回，不抛异常")
        void noSessionsIsNoop() {
            handler.notifyRequest(ticket());
            assertThat(handler.connectionCount()).isZero();
        }

        @Test
        @DisplayName("单连接收到契约帧（snake_case 键名与实值时限）")
        void singleSessionReceivesFrame() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);

            ApprovalGate.Ticket t = ticket();
            handler.notifyRequest(t);

            assertThat(ws.sentPayloads()).hasSize(1);
            String payload = ws.sentPayloads().get(0);
            assertThat(payload)
                    .contains("\"approval_id\":\"" + t.approvalId() + "\"")
                    .contains("\"tool_name\":\"run_command\"")
                    .contains("\"timeout_seconds\":60")
                    .contains("\"version\":1");
        }

        @Test
        @DisplayName("多连接各自收到一份（广播语义）")
        void broadcastsToAllSessions() {
            FakeWebSocketSession a = new FakeWebSocketSession("a");
            FakeWebSocketSession b = new FakeWebSocketSession("b");
            handler.afterConnectionEstablished(a);
            handler.afterConnectionEstablished(b);

            handler.notifyRequest(ticket());

            assertThat(a.sentPayloads()).hasSize(1);
            assertThat(b.sentPayloads()).hasSize(1);
        }

        @Test
        @DisplayName("对端已消失的发送失败被吞掉，不影响其它连接")
        void sendFailureDoesNotBreakBroadcast() {
            FakeWebSocketSession dead = new FakeWebSocketSession("dead");
            FakeWebSocketSession alive = new FakeWebSocketSession("alive");
            handler.afterConnectionEstablished(dead);
            handler.afterConnectionEstablished(alive);
            // 让 dead 的后续写入失败：先关闭底层会话，装饰器写已关闭会话会抛 IOException
            try {
                dead.close();
            } catch (IOException ignored) {
            }

            handler.notifyRequest(ticket());

            // alive 仍收到；dead 已关闭，装饰器可能抛错但广播未中断
            assertThat(alive.sentPayloads()).hasSize(1);
        }

        @Test
        @DisplayName("出站缓冲超限：主动关闭该连接并移出活跃表")
        void bufferOverflowClosesSession() throws Exception {
            FakeWebSocketSession small = new FakeWebSocketSession("small");
            // 慢消费：每次真实发送占住装饰器发送锁 20ms，另一线程的帧才会进缓冲
            small.setSendDelayMillis(20);
            handler.afterConnectionEstablished(small);
            // 装饰器只在“发送锁被别的线程持有”时才累积缓冲，因此用后台长发送制造并发排队；
            // 每帧 128KiB 的 aiAnalysis 让积压迅速超过 512KiB 上限
            ApprovalProposal proposal = new ApprovalProposal(
                    UUID.randomUUID(), UUID.randomUUID(), null,
                    ToolName.RUN_COMMAND, Map.of(), "c", "x".repeat(128 * 1024), null);
            ApprovalGate.Ticket t = new ApprovalGate.Ticket(
                    UUID.randomUUID(), proposal, 60, OffsetDateTime.now(), OffsetDateTime.now().plusSeconds(60));
            Thread hog = new Thread(() -> {
                for (int i = 0; i < 60; i++) {
                    handler.notifyRequest(t);
                }
            }, "hog-sender");
            hog.start();
            try {
                for (int i = 0; i < 200 && handler.connectionCount() > 0; i++) {
                    handler.notifyRequest(t);
                    Thread.sleep(2);
                }
            } finally {
                hog.join(5000);
            }
            assertThat(handler.connectionCount()).as("超限后应被移出活跃表").isZero();
            assertThat(small.closeStatus()).isNotNull();
        }
    }

    // ==================================================================
    // 上行翻译
    // ==================================================================

    @Nested
    @DisplayName("上行 approval_response 翻译")
    class Inbound {

        private String frame(Object approvalId, String decision, Object expectedVersion, String modified) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (approvalId != null) {
                parts.add("\"approval_id\":\"" + approvalId + "\"");
            }
            if (decision != null) {
                parts.add("\"decision\":\"" + decision + "\"");
            }
            if (expectedVersion != null) {
                parts.add("\"expected_version\":" + expectedVersion);
            }
            if (modified != null) {
                parts.add("\"modified_command\":\"" + modified + "\"");
            }
            return "{" + String.join(",", parts) + "}";
        }

        @Test
        @DisplayName("坏帧（非 JSON）只记日志不回发")
        void malformedFrameIgnored() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage("not-json{{"));
            verify(gate, never()).respond(any(), any());
            assertThat(ws.sentPayloads()).isEmpty();
        }

        @Test
        @DisplayName("缺 approval_id 的帧被忽略")
        void missingApprovalIdIgnored() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(null, "approve", null, null)));
            verify(gate, never()).respond(any(), any());
        }

        @Test
        @DisplayName("缺 decision 的帧被忽略")
        void missingDecisionIgnored() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(UUID.randomUUID(), null, null, null)));
            verify(gate, never()).respond(any(), any());
        }

        @Test
        @DisplayName("带 expected_version 的 approve 走版本校验裁决")
        void approveWithVersionGoesVersionedPath() {
            UUID id = UUID.randomUUID();
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(id, "approve", 2, null)));
            verify(gate).respondWithVersion(eq(id), eq(ApprovalResponseFrame.Decision.APPROVE), eq(2));
            verify(gate, never()).respond(any(), any());
        }

        @Test
        @DisplayName("不带版本的 cancel 走向后兼容裁决")
        void cancelWithoutVersionGoesLegacyPath() {
            UUID id = UUID.randomUUID();
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(id, "cancel", null, null)));
            verify(gate).respond(eq(id), eq(ApprovalResponseFrame.Decision.CANCEL));
        }

        @Test
        @DisplayName("modify 齐备时翻成 gate.modify")
        void modifyWithAllFieldsRoutesToGate() {
            UUID id = UUID.randomUUID();
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(id, "modify", 1, "ls -l")));
            verify(gate).modify(eq(id), eq("ls -l"), eq(1));
        }

        @Test
        @DisplayName("modify 缺 expected_version 被忽略")
        void modifyWithoutVersionIgnored() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(UUID.randomUUID(), "modify", null, "ls -l")));
            verify(gate, never()).modify(any(), any(), anyInt());
        }

        @Test
        @DisplayName("modify 缺/空白 modified_command 被忽略")
        void modifyWithoutCommandIgnored() {
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(UUID.randomUUID(), "modify", 1, null)));
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(UUID.randomUUID(), "modify", 1, "  ")));
            verify(gate, never()).modify(any(), any(), anyInt());
        }

        @Test
        @DisplayName("闸门未命中（已超时）不抛异常、正常走完")
        void unmatchedApprovalIsLoggedNotThrown() {
            UUID id = UUID.randomUUID();
            when(gate.respond(any(), any())).thenReturn(false);
            FakeWebSocketSession ws = new FakeWebSocketSession();
            handler.afterConnectionEstablished(ws);
            handler.handleTextMessage(ws, new org.springframework.web.socket.TextMessage(
                    frame(id, "approve", null, null)));
            verify(gate).respond(eq(id), eq(ApprovalResponseFrame.Decision.APPROVE));
        }
    }
}

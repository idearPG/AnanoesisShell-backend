package com.ananoesis.shell.ws;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mockito;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.web.socket.TextMessage;

import com.ananoesis.shell.ai.AiAgentService;
import com.ananoesis.shell.ai.TurnRequest;
import com.ananoesis.shell.support.FakeWebSocketSession;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code /ai} 通道上行协议测试（用户反馈「Agent 对话要能像 Shell 一样 Ctrl+C 打断」）。
 *
 * <p>覆盖两类上行帧：{@code user_message}（既有语义回归基线）与
 * {@code stop_turn}（V2 新增）。WHY 断言只落在处理器与 {@link AiAgentService}
 * 的交互上：回合停止的全部语义（中断工作线程、落 STOPPED_NOTE、发 final 帧）
 * 在 {@code AiAgentServiceTest} 已有覆盖，本层只验证「协议翻译」这一段接线——
 * 此前 {@code AiAgentService.stop()} 因没有任何上行入口而成为死代码。</p>
 *
 * <p>WHY 手写 JSON 字符串而不是序列化 AiStreamFrame：反序列化与序列化共用同一份
 * 注解，自造帧会把「线上键名写错」这类契约漂移自动抵消；裸字符串才能证明
 * 真实前端发出的 {@code stop_turn} 取值可被解析。</p>
 */
class AiWebSocketHandlerTest {

    private AiAgentService agent;
    private AiWebSocketHandler handler;
    private FakeWebSocketSession ws;

    @BeforeEach
    void setUp() {
        agent = Mockito.mock(AiAgentService.class);
        handler = new AiWebSocketHandler(agent, new ObjectMapper());
        ws = new FakeWebSocketSession();
        handler.afterConnectionEstablished(ws);
    }

    @Test
    @DisplayName("stop_turn 上行帧翻成 AiAgentService.stop(conversation_id)")
    void stopTurnFrameTriggersAgentStop() {
        UUID conversationId = UUID.randomUUID();

        handler.handleTextMessage(ws, new TextMessage(
                "{\"type\":\"stop_turn\",\"conversation_id\":\"" + conversationId + "\"}"));

        verify(agent).stop(conversationId);
        // 停止是幂等通知，不应额外回错误帧（回合侧会以 final+STOPPED_NOTE 收尾）
        assertThat(ws.sentPayloads()).isEmpty();
    }

    @Test
    @DisplayName("stop_turn 缺 conversation_id 时忽略，不停止任何回合")
    void stopTurnWithoutConversationIdIsIgnored() {
        handler.handleTextMessage(ws, new TextMessage("{\"type\":\"stop_turn\"}"));

        verify(agent, never()).stop(any());
    }

    @Test
    @DisplayName("回归基线：user_message 仍正常翻成 submit")
    void userMessageStillRoutedToSubmit() {
        UUID conversationId = UUID.randomUUID();
        when(agent.submit(any(TurnRequest.class))).thenReturn(true);

        handler.handleTextMessage(ws, new TextMessage(
                "{\"type\":\"user_message\",\"conversation_id\":\"" + conversationId
                        + "\",\"content\":\"看看负载\"}"));

        verify(agent).submit(any(TurnRequest.class));
    }

    @Test
    @DisplayName("下行类型被回发时仍忽略（Q2 复用 schema 的方向纪律）")
    void downstreamTypeUpstreamIsIgnored() {
        handler.handleTextMessage(ws, new TextMessage(
                "{\"type\":\"final\",\"conversation_id\":\"" + UUID.randomUUID() + "\"}"));

        verify(agent, never()).stop(any());
        verify(agent, never()).submit(any());
    }
}

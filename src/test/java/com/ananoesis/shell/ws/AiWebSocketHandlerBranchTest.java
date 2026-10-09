package com.ananoesis.shell.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.ananoesis.shell.ai.AiAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link AiWebSocketHandler} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖消息处理，但构造器 null 校验、emit 无连接、
 * connectionCount 等分支未覆盖。</p>
 */
@DisplayName("AiWebSocketHandler 分支覆盖")
class AiWebSocketHandlerBranchTest {

    private AiAgentService agent;
    private ObjectMapper objectMapper;
    private AiWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        agent = mock(AiAgentService.class);
        objectMapper = new ObjectMapper();
        handler = new AiWebSocketHandler(agent, objectMapper);
    }

    @Test
    @DisplayName("构造器: agent 为 null 时抛异常")
    void nullAgentThrows() {
        assertThatThrownBy(() -> new AiWebSocketHandler(null, objectMapper))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("agent");
    }

    @Test
    @DisplayName("构造器: objectMapper 为 null 时抛异常")
    void nullObjectMapperThrows() {
        assertThatThrownBy(() -> new AiWebSocketHandler(agent, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("objectMapper");
    }

    @Test
    @DisplayName("connectionCount: 初始为 0")
    void connectionCountInitiallyZero() {
        assertThat(handler.connectionCount()).isZero();
    }

    @Test
    @DisplayName("emit: 无连接时不抛异常")
    void emitNoSessionsDoesNotThrow() {
        AiStreamFrame frame = AiStreamFrame.error(
                java.util.UUID.randomUUID(),
                com.ananoesis.shell.contract.model.ErrorCode.INTERNAL_ERROR,
                "test");
        handler.emit(frame);
        // 无连接时静默丢弃，不抛异常
    }

    @Test
    @DisplayName("emit: frame 为 null 时抛异常")
    void emitNullFrameThrows() {
        assertThatThrownBy(() -> handler.emit(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("EMPTY_CONTENT_MESSAGE 常量值")
    void emptyContentMessage() {
        assertThat(AiWebSocketHandler.EMPTY_CONTENT_MESSAGE).isEqualTo("提问内容不得为空");
    }
}

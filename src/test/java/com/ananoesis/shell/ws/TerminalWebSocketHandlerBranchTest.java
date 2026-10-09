package com.ananoesis.shell.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.ananoesis.shell.ssh.SshTerminalService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link TerminalWebSocketHandler} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖消息处理，但构造器 null 校验、
 * connectionCount 等分支未覆盖。</p>
 */
@DisplayName("TerminalWebSocketHandler 分支覆盖")
class TerminalWebSocketHandlerBranchTest {

    @Test
    @DisplayName("构造器: terminalService 为 null 时抛异常")
    void nullTerminalServiceThrows() {
        assertThatThrownBy(() -> new TerminalWebSocketHandler(null, new ObjectMapper()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("terminalService");
    }

    @Test
    @DisplayName("构造器: objectMapper 为 null 时抛异常")
    void nullObjectMapperThrows() {
        assertThatThrownBy(() -> new TerminalWebSocketHandler(mock(SshTerminalService.class), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("objectMapper");
    }

    @Test
    @DisplayName("connectionCount: 初始为 0")
    void connectionCountInitiallyZero() {
        var handler = new TerminalWebSocketHandler(mock(SshTerminalService.class), new ObjectMapper());
        assertThat(handler.connectionCount()).isZero();
    }
}

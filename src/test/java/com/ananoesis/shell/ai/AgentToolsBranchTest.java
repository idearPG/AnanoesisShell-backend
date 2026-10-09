package com.ananoesis.shell.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ssh.SshExecService;

/**
 * {@link AgentTools} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖工具调用闭环，但构造器 null 校验等分支未覆盖。</p>
 */
@DisplayName("AgentTools 分支覆盖")
class AgentToolsBranchTest {

    @Test
    @DisplayName("构造器: exec 为 null 时抛异常")
    void nullExecThrows() {
        assertThatThrownBy(() -> new AgentTools(null, mock(SettingsService.class)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("exec");
    }

    @Test
    @DisplayName("构造器: settings 为 null 时抛异常")
    void nullSettingsThrows() {
        assertThatThrownBy(() -> new AgentTools(mock(SshExecService.class), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settings");
    }

    @Test
    @DisplayName("常量值正确")
    void constantsCorrect() {
        assertThat(AgentTools.CTX_HOST_ID).isEqualTo("hostId");
        assertThat(AgentTools.CTX_SESSION_ID).isEqualTo("sessionId");
        assertThat(AgentTools.CTX_CONVERSATION_ID).isEqualTo("conversationId");
    }
}

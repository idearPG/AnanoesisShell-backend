package com.ananoesis.shell.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.ananoesis.shell.mapper.AiConversationMapper;
import com.ananoesis.shell.mapper.AiMessageMapper;
import com.ananoesis.shell.mapper.HostMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ConversationService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖 CRUD 闭环，但构造器 null 校验等分支未覆盖。</p>
 */
@DisplayName("ConversationService 分支覆盖")
class ConversationServiceBranchTest {

    @Test
    @DisplayName("构造器: conversations 为 null 时抛异常")
    void nullConversationsThrows() {
        assertThatThrownBy(() -> new ConversationService(
                null, mock(AiMessageMapper.class), mock(HostMapper.class), new ObjectMapper()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("构造器: messages 为 null 时抛异常")
    void nullMessagesThrows() {
        assertThatThrownBy(() -> new ConversationService(
                mock(AiConversationMapper.class), null, mock(HostMapper.class), new ObjectMapper()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("构造器: hosts 为 null 时抛异常")
    void nullHostsThrows() {
        assertThatThrownBy(() -> new ConversationService(
                mock(AiConversationMapper.class), mock(AiMessageMapper.class), null, new ObjectMapper()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("构造器: objectMapper 为 null 时抛异常")
    void nullObjectMapperThrows() {
        assertThatThrownBy(() -> new ConversationService(
                mock(AiConversationMapper.class), mock(AiMessageMapper.class), mock(HostMapper.class), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("常量值正确")
    void constantsCorrect() {
        assertThat(ConversationService.STATUS_ACTIVE).isEqualTo("active");
        assertThat(ConversationService.ROLE_USER).isEqualTo("user");
        assertThat(ConversationService.ROLE_ASSISTANT).isEqualTo("assistant");
        assertThat(ConversationService.ROLE_TOOL).isEqualTo("tool");
        assertThat(ConversationService.SOURCE_AI).isEqualTo("ai");
        assertThat(ConversationService.SOURCE_SHELL_EVENT).isEqualTo("shell_event");
        assertThat(ConversationService.LIST_DEFAULT_LIMIT).isEqualTo(30);
        assertThat(ConversationService.LIST_MAX_LIMIT).isEqualTo(100);
        assertThat(ConversationService.MSG_DEFAULT_LIMIT).isEqualTo(50);
        assertThat(ConversationService.MSG_MAX_LIMIT).isEqualTo(200);
        assertThat(ConversationService.CONTEXT_WINDOW_LIMIT).isEqualTo(60);
    }
}

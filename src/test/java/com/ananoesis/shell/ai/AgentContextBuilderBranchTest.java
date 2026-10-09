package com.ananoesis.shell.ai;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.entity.AiMessage;
import com.ananoesis.shell.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link AgentContextBuilder} 分支覆盖补测。
 *
 * <p>WHY 独立测试：原测试覆盖 buildContext 基本闭环，但构造器 null 校验、
 * 空消息列表、validateToolGroups 的各种边界（孤立 tool、不完整组、重复 call_id）、
 * 截断方法等分支未被覆盖。</p>
 */
@DisplayName("AgentContextBuilder 分支覆盖")
class AgentContextBuilderBranchTest {

    private ConversationService conversations;
    private ObjectMapper objectMapper;
    private ContextTokenEstimator estimator;
    private AgentContextBuilder builder;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        objectMapper = new ObjectMapper();
        estimator = mock(ContextTokenEstimator.class);
        builder = new AgentContextBuilder(conversations, objectMapper, estimator);
    }

    @Nested
    @DisplayName("构造器 null 校验")
    class ConstructorNulls {
        @Test
        @DisplayName("conversations 为 null 时抛异常")
        void nullConversations() {
            assertThatThrownBy(() -> new AgentContextBuilder(null, objectMapper, estimator))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("objectMapper 为 null 时抛异常")
        void nullObjectMapper() {
            assertThatThrownBy(() -> new AgentContextBuilder(conversations, null, estimator))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("estimator 为 null 时抛异常")
        void nullEstimator() {
            assertThatThrownBy(() -> new AgentContextBuilder(conversations, objectMapper, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("buildContext")
    class BuildContext {
        @Test
        @DisplayName("空消息列表返回空结果，estimatedSize=256")
        void emptyMessagesReturns256() {
            UUID convId = UUID.randomUUID();
            when(conversations.loadRecentMessages(eq(convId), eq(AgentContextBuilder.WINDOW_SIZE)))
                    .thenReturn(List.of());

            AgentContextBuilder.BuildResult result = builder.buildContext(convId, 8000);

            assertThat(result.messages()).isEmpty();
            assertThat(result.notices()).isEmpty();
            assertThat(result.estimatedSize()).isEqualTo(256);
        }

        @Test
        @DisplayName("仅含用户消息时正常构建")
        void userMessageOnly() {
            UUID convId = UUID.randomUUID();
            AiMessage userMsg = createMessage("user", "hello", null, null, null);
            when(conversations.loadRecentMessages(eq(convId), eq(AgentContextBuilder.WINDOW_SIZE)))
                    .thenReturn(List.of(userMsg));
            when(estimator.estimate(any(), any())).thenReturn(100);

            AgentContextBuilder.BuildResult result = builder.buildContext(convId, 8000);
            assertThat(result.messages()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("validateToolGroups")
    class ValidateToolGroups {
        @Test
        @DisplayName("孤立 tool 消息被排除")
        void orphanToolExcluded() {
            AiMessage toolMsg = createMessage("tool", "result", null, "call_1", "run_cmd");
            List<AiMessage> validated = builder.validateToolGroups(List.of(toolMsg));
            assertThat(validated).isEmpty();
        }

        @Test
        @DisplayName("不完整 assistant 工具组被排除")
        void incompleteAssistantGroupExcluded() {
            AiMessage assistantMsg = createMessage("assistant", "",
                    "[{\"id\":\"call_1\",\"name\":\"run_command\",\"arguments\":\"{}\"}]",
                    null, null);
            List<AiMessage> validated = builder.validateToolGroups(List.of(assistantMsg));
            assertThat(validated).isEmpty();
        }

        @Test
        @DisplayName("完整的 assistant+tool 组被保留")
        void completeGroupRetained() {
            AiMessage assistantMsg = createMessage("assistant", "",
                    "[{\"id\":\"call_1\",\"name\":\"run_command\",\"arguments\":\"{}\"}]",
                    null, null);
            AiMessage toolMsg = createMessage("tool", "result", null, "call_1", "run_command");
            List<AiMessage> validated = builder.validateToolGroups(List.of(assistantMsg, toolMsg));
            assertThat(validated).hasSize(2);
        }

        @Test
        @DisplayName("重复 call_id 的 tool 结果整组排除")
        void duplicateCallIdExcluded() {
            AiMessage assistantMsg = createMessage("assistant", "",
                    "[{\"id\":\"call_1\",\"name\":\"run_command\",\"arguments\":\"{}\"}]",
                    null, null);
            AiMessage tool1 = createMessage("tool", "result1", null, "call_1", "run_command");
            AiMessage tool2 = createMessage("tool", "result2", null, "call_1", "run_command");
            List<AiMessage> validated = builder.validateToolGroups(List.of(assistantMsg, tool1, tool2));
            // 重复 call_id → toolResults 标记为 null → 不完整
            assertThat(validated).isEmpty();
        }

        @Test
        @DisplayName("纯文本 assistant 消息始终保留")
        void plainAssistantRetained() {
            AiMessage msg = createMessage("assistant", "Hello!", null, null, null);
            List<AiMessage> validated = builder.validateToolGroups(List.of(msg));
            assertThat(validated).hasSize(1);
        }

        @Test
        @DisplayName("用户消息始终保留")
        void userMessageRetained() {
            AiMessage msg = createMessage("user", "hello", null, null, null);
            List<AiMessage> validated = builder.validateToolGroups(List.of(msg));
            assertThat(validated).hasSize(1);
        }

        @Test
        @DisplayName("tool_calls 为空的 assistant 保留")
        void emptyToolCallsRetained() {
            AiMessage msg = createMessage("assistant", "text", "", null, null);
            List<AiMessage> validated = builder.validateToolGroups(List.of(msg));
            assertThat(validated).hasSize(1);
        }

        @Test
        @DisplayName("null toolCallId 的 tool 消息被排除")
        void nullToolCallIdExcluded() {
            AiMessage msg = createMessage("tool", "result", null, null, "cmd");
            List<AiMessage> validated = builder.validateToolGroups(List.of(msg));
            assertThat(validated).isEmpty();
        }
    }

    @Nested
    @DisplayName("截断方法")
    class Truncation {
        @Test
        @DisplayName("truncateToBytes: 短字符串不截断")
        void shortStringNotTruncated() throws Exception {
            Method m = AgentContextBuilder.class.getDeclaredMethod("truncateToBytes", String.class, int.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "hello", 100)).isEqualTo("hello");
        }

        @Test
        @DisplayName("truncateToBytes: 长字符串被截断")
        void longStringTruncated() throws Exception {
            Method m = AgentContextBuilder.class.getDeclaredMethod("truncateToBytes", String.class, int.class);
            m.setAccessible(true);
            String result = (String) m.invoke(null, "a".repeat(200), 50);
            assertThat(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("truncateFromEnd: 短字符串不截断")
        void shortStringNotTruncatedFromEnd() throws Exception {
            Method m = AgentContextBuilder.class.getDeclaredMethod("truncateFromEnd", String.class, int.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "hello", 100)).isEqualTo("hello");
        }

        @Test
        @DisplayName("truncateFromEnd: 长字符串从尾部截断")
        void longStringTruncatedFromEnd() throws Exception {
            Method m = AgentContextBuilder.class.getDeclaredMethod("truncateFromEnd", String.class, int.class);
            m.setAccessible(true);
            String result = (String) m.invoke(null, "a".repeat(200), 50);
            assertThat(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(50);
        }
    }

    @Nested
    @DisplayName("常量")
    class Constants {
        @Test
        @DisplayName("WINDOW_SIZE = 60")
        void windowSize() {
            assertThat(AgentContextBuilder.WINDOW_SIZE).isEqualTo(60);
        }
    }

    private AiMessage createMessage(String role, String content, String toolCalls,
                                     String toolCallId, String toolName) {
        AiMessage msg = new AiMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolCalls(toolCalls);
        msg.setToolCallId(toolCallId);
        msg.setToolName(toolName);
        msg.setSeq(1);
        return msg;
    }
}

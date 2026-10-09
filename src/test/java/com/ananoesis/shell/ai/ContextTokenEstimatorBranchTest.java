package com.ananoesis.shell.ai;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * {@link ContextTokenEstimator} 分支覆盖补测。
 *
 * <p>WHY 独立测试：原测试覆盖 estimate 基本闭环，但 extractContent 的各消息类型
 * 分支（UserMessage/SystemMessage/AssistantMessage/ToolResponseMessage/未知类型）
 * 和 utf8Length 的 null/空/非空分支未被覆盖。</p>
 */
@DisplayName("ContextTokenEstimator 分支覆盖")
class ContextTokenEstimatorBranchTest {

    private final ContextTokenEstimator estimator = new ContextTokenEstimator();

    @Nested
    @DisplayName("estimate")
    class Estimate {
        @Test
        @DisplayName("空消息列表只含 REQUEST_OVERHEAD")
        void emptyMessagesOnlyOverhead() {
            assertThat(estimator.estimate(List.of(), List.of()))
                    .isEqualTo(ContextTokenEstimator.REQUEST_OVERHEAD);
        }

        @Test
        @DisplayName("UserMessage 计入内容字节 + PER_MESSAGE_OVERHEAD")
        void userMessageCounted() {
            List<Message> messages = List.of(new UserMessage("hello"));
            int result = estimator.estimate(messages, List.of());
            // REQUEST_OVERHEAD + PER_MESSAGE_OVERHEAD + utf8("hello")=5
            assertThat(result).isEqualTo(
                    ContextTokenEstimator.REQUEST_OVERHEAD
                    + ContextTokenEstimator.PER_MESSAGE_OVERHEAD
                    + 5);
        }

        @Test
        @DisplayName("SystemMessage 计入内容字节")
        void systemMessageCounted() {
            List<Message> messages = List.of(new SystemMessage("sys"));
            int result = estimator.estimate(messages, List.of());
            assertThat(result).isEqualTo(
                    ContextTokenEstimator.REQUEST_OVERHEAD
                    + ContextTokenEstimator.PER_MESSAGE_OVERHEAD
                    + 3);
        }

        @Test
        @DisplayName("AssistantMessage 计入内容字节")
        void assistantMessageCounted() {
            AssistantMessage msg = AssistantMessage.builder().content("reply").build();
            List<Message> messages = List.of(msg);
            int result = estimator.estimate(messages, List.of());
            assertThat(result).isEqualTo(
                    ContextTokenEstimator.REQUEST_OVERHEAD
                    + ContextTokenEstimator.PER_MESSAGE_OVERHEAD
                    + 5);
        }

        @Test
        @DisplayName("AssistantMessage content 为 null 时计 0 字节")
        void assistantNullContentZeroBytes() {
            AssistantMessage msg = AssistantMessage.builder().content(null).build();
            List<Message> messages = List.of(msg);
            int result = estimator.estimate(messages, List.of());
            assertThat(result).isEqualTo(
                    ContextTokenEstimator.REQUEST_OVERHEAD
                    + ContextTokenEstimator.PER_MESSAGE_OVERHEAD);
        }

        @Test
        @DisplayName("ToolResponseMessage 计入所有 responseData")
        void toolResponseCounted() {
            ToolResponseMessage msg = ToolResponseMessage.builder()
                    .responses(List.of(
                            new ToolResponseMessage.ToolResponse("id1", "name1", "data1"),
                            new ToolResponseMessage.ToolResponse("id2", "name2", "data2")))
                    .build();
            List<Message> messages = List.of(msg);
            int result = estimator.estimate(messages, List.of());
            // data1=5 + data2=5 = 10
            assertThat(result).isEqualTo(
                    ContextTokenEstimator.REQUEST_OVERHEAD
                    + ContextTokenEstimator.PER_MESSAGE_OVERHEAD
                    + 10);
        }

        @Test
        @DisplayName("ToolResponse 的 responseData 为 null 时跳过")
        void toolResponseNullDataSkipped() {
            ToolResponseMessage msg = ToolResponseMessage.builder()
                    .responses(List.of(
                            new ToolResponseMessage.ToolResponse("id1", "name1", null)))
                    .build();
            List<Message> messages = List.of(msg);
            int result = estimator.estimate(messages, List.of());
            assertThat(result).isEqualTo(
                    ContextTokenEstimator.REQUEST_OVERHEAD
                    + ContextTokenEstimator.PER_MESSAGE_OVERHEAD);
        }

        @Test
        @DisplayName("UserMessage text 为 null 时计 0 字节")
        void userMessageNullTextZeroBytes() {
            // UserMessage 构造器不接受 null，用空串代替
            UserMessage msg = new UserMessage("");
            List<Message> messages = List.of(msg);
            int result = estimator.estimate(messages, List.of());
            assertThat(result).isEqualTo(
                    ContextTokenEstimator.REQUEST_OVERHEAD
                    + ContextTokenEstimator.PER_MESSAGE_OVERHEAD);
        }
    }

    @Nested
    @DisplayName("常量")
    class Constants {
        @Test
        @DisplayName("PER_MESSAGE_OVERHEAD = 32")
        void perMessageOverhead() {
            assertThat(ContextTokenEstimator.PER_MESSAGE_OVERHEAD).isEqualTo(32);
        }

        @Test
        @DisplayName("REQUEST_OVERHEAD = 256")
        void requestOverhead() {
            assertThat(ContextTokenEstimator.REQUEST_OVERHEAD).isEqualTo(256);
        }
    }
}

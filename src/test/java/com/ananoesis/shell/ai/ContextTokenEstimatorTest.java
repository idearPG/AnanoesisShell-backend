package com.ananoesis.shell.ai;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 上下文 token 估算器（design D7 步骤 5，tasks 8.3）。
 *
 * <p>WHY 不用字符数除以 4：OpenAI 的 tiktoken 对中文、代码、JSON 的编码比各不相同，
 * "字符/4" 只在英文自然文本上近似成立。本估算器直接计算最终请求序列化后的 UTF-8 字节数，
 * 加上每条消息 32 字节和整请求 256 字节的结构余量，作为保守估算。
 * 中文/代码/JSON 按同一实际编码计数，不猜品牌对应 tokenizer。</p>
 */
class ContextTokenEstimatorTest {

    private final ContextTokenEstimator estimator = new ContextTokenEstimator();

    // ======================================================================
    // 基础估算
    // ======================================================================

    @Test
    @DisplayName("空请求仍有 256 字节的结构余量")
    void emptyRequestHasStructuralOverhead() {
        int estimate = estimator.estimate(List.of(), List.of());

        // WHY 256：整请求的结构余量（roles、分隔符、协议字段等）
        assertThat(estimate).isEqualTo(256);
    }

    @Test
    @DisplayName("每条消息额外加 32 字节的消息余量")
    void eachMessageAddsPerMessageOverhead() {
        List<Message> messages = List.of(
                new UserMessage("Hi"),
                new AssistantMessage("Hello")
        );

        int estimate = estimator.estimate(messages, List.of());

        // 256（结构） + 2×32（消息余量） + UTF-8("Hi") + UTF-8("Hello")
        int expected = 256 + 2 * 32
                + "Hi".getBytes(StandardCharsets.UTF_8).length
                + "Hello".getBytes(StandardCharsets.UTF_8).length;
        assertThat(estimate).isEqualTo(expected);
    }

    @Test
    @DisplayName("中文按 UTF-8 实际编码计数（每汉字 3 字节），不做字符除四")
    void chineseCharactersAreCountedByUtf8Encoding() {
        List<Message> messages = List.of(new UserMessage("你好世界"));

        int estimate = estimator.estimate(messages, List.of());

        // "你好世界" 4 个汉字 × 3 字节 = 12 字节
        int chineseBytes = "你好世界".getBytes(StandardCharsets.UTF_8).length;
        assertThat(chineseBytes).isEqualTo(12); // 验证假设
        int expected = 256 + 32 + chineseBytes;
        assertThat(estimate).isEqualTo(expected);
    }

    @Test
    @DisplayName("JSON 与代码按 UTF-8 实际编码计数")
    void jsonAndCodeAreCountedByUtf8Encoding() {
        String jsonContent = "{\"command\":\"ls -la /var/log\"}";
        List<Message> messages = List.of(new UserMessage(jsonContent));

        int estimate = estimator.estimate(messages, List.of());

        int contentBytes = jsonContent.getBytes(StandardCharsets.UTF_8).length;
        int expected = 256 + 32 + contentBytes;
        assertThat(estimate).isEqualTo(expected);
    }

    // ======================================================================
    // 工具定义计入估算
    // ======================================================================

    @Test
    @DisplayName("工具定义的序列化字节也计入估算")
    void toolDefinitionsAreIncludedInEstimate() {
        List<Message> messages = List.of(new UserMessage("Hi"));
        List<ToolCallback> tools = List.of(stubCallback("list_dir", "列出目录内容"));

        int withTools = estimator.estimate(messages, tools);
        int withoutTools = estimator.estimate(messages, List.of());

        assertThat(withTools).isGreaterThan(withoutTools);
    }

    // ======================================================================
    // 系统消息
    // ======================================================================

    @Test
    @DisplayName("系统消息同样按内容字节 + 32 余量计入")
    void systemMessageIsCountedLikeOtherMessages() {
        String systemContent = "You are a helpful assistant.";
        List<Message> messages = List.of(new SystemMessage(systemContent));

        int estimate = estimator.estimate(messages, List.of());

        int expected = 256 + 32
                + systemContent.getBytes(StandardCharsets.UTF_8).length;
        assertThat(estimate).isEqualTo(expected);
    }

    // ======================================================================
    // 工具响应消息
    // ======================================================================

    @Test
    @DisplayName("工具响应消息的内容同样按 UTF-8 字节计数")
    void toolResponseContentIsCountedByUtf8() {
        String toolResult = "exit=0\n total 128\n-rw-r--r-- 1 root root 1234 Jan 1 00:00 test.log";
        List<Message> messages = List.of(
                new AssistantMessage("让我查看文件"),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call_1", "list_dir", toolResult)))
                        .build()
        );

        int estimate = estimator.estimate(messages, List.of());

        // 2 条消息 × 32 余量 + 两条消息内容的 UTF-8 字节
        int expected = 256 + 2 * 32
                + "让我查看文件".getBytes(StandardCharsets.UTF_8).length
                + toolResult.getBytes(StandardCharsets.UTF_8).length;
        assertThat(estimate).isEqualTo(expected);
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private static ToolCallback stubCallback(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(description).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("测试替身不应被执行");
            }
        };
    }
}

package com.ananoesis.shell.ai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.ai.ContextLimitClassifier.Verdict;

/**
 * {@link ContextLimitClassifier} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原类无单元测试，15 个分支（null 校验、cause 链遍历、错误码/消息匹配）
 * 均未覆盖。</p>
 */
@DisplayName("ContextLimitClassifier")
class ContextLimitClassifierTest {

    private final ContextLimitClassifier classifier = new ContextLimitClassifier();

    // ==================================================================
    // classify(Throwable)
    // ==================================================================

    @Test
    @DisplayName("classify(Throwable): null 返回 OTHER_ERROR")
    void classifyNullThrowableReturnsOtherError() {
        assertThat(classifier.classify((Throwable) null)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(Throwable): 消息含 context_length_exceeded 返回 CONTEXT_LIMIT")
    void classifyThrowableWithContextLengthExceededReturnsContextLimit() {
        Throwable error = new RuntimeException("Error: context_length_exceeded");
        assertThat(classifier.classify(error)).isEqualTo(Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("classify(Throwable): 消息含 context_window_exceeded 返回 CONTEXT_LIMIT")
    void classifyThrowableWithContextWindowExceededReturnsContextLimit() {
        Throwable error = new RuntimeException("Error: context_window_exceeded");
        assertThat(classifier.classify(error)).isEqualTo(Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("classify(Throwable): cause 链中含上下文超限返回 CONTEXT_LIMIT")
    void classifyThrowableWithCauseChainReturnsContextLimit() {
        Throwable cause = new RuntimeException("context_length_exceeded");
        Throwable error = new RuntimeException("Wrapper", cause);
        assertThat(classifier.classify(error)).isEqualTo(Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("classify(Throwable): 普通异常返回 OTHER_ERROR")
    void classifyThrowableWithNormalErrorReturnsOtherError() {
        Throwable error = new RuntimeException("Normal error");
        assertThat(classifier.classify(error)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(Throwable): 消息含 token + context 返回 CONTEXT_LIMIT")
    void classifyThrowableWithTokenContextReturnsContextLimit() {
        Throwable error = new RuntimeException("Maximum token context limit reached");
        assertThat(classifier.classify(error)).isEqualTo(Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("classify(Throwable): 消息仅含 token 返回 OTHER_ERROR")
    void classifyThrowableWithTokenOnlyReturnsOtherError() {
        Throwable error = new RuntimeException("Token usage: 50%");
        assertThat(classifier.classify(error)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(Throwable): 空消息返回 OTHER_ERROR")
    void classifyThrowableWithEmptyMessageReturnsOtherError() {
        Throwable error = new RuntimeException("");
        assertThat(classifier.classify(error)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(Throwable): 自引用 cause 不无限循环")
    void classifyThrowableWithSelfReferentialCauseDoesNotLoop() {
        Throwable error = new RuntimeException("Normal error");
        // 模拟自引用 cause
        try {
            java.lang.reflect.Field causeField = Throwable.class.getDeclaredField("cause");
            causeField.setAccessible(true);
            causeField.set(error, error);
        } catch (Exception ignored) {
            // 反射失败时跳过
        }
        assertThat(classifier.classify(error)).isEqualTo(Verdict.OTHER_ERROR);
    }

    // ==================================================================
    // classify(ChatResponse)
    // ==================================================================

    @Test
    @DisplayName("classify(ChatResponse): null 返回 OTHER_ERROR")
    void classifyNullResponseReturnsOtherError() {
        assertThat(classifier.classify((org.springframework.ai.chat.model.ChatResponse) null))
                .isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(ChatResponse): results 为 null 返回 OTHER_ERROR")
    void classifyResponseNullResultsReturnsOtherError() {
        var response = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.mockito.Mockito.when(response.getResults()).thenReturn(null);
        assertThat(classifier.classify(response)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(ChatResponse): results 为空返回 OTHER_ERROR")
    void classifyResponseEmptyResultsReturnsOtherError() {
        var response = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.mockito.Mockito.when(response.getResults()).thenReturn(java.util.List.of());
        assertThat(classifier.classify(response)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(ChatResponse): metadata 含 context_length_exceeded 错误码返回 CONTEXT_LIMIT")
    void classifyResponseWithContextLimitErrorCodeReturnsContextLimit() {
        var output = org.mockito.Mockito.mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        var metadata = java.util.Map.<String, Object>of("error_code", "context_length_exceeded");
        org.mockito.Mockito.when(output.getMetadata()).thenReturn(metadata);
        var generation = org.mockito.Mockito.mock(org.springframework.ai.chat.model.Generation.class);
        org.mockito.Mockito.when(generation.getOutput()).thenReturn(output);
        var response = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.mockito.Mockito.when(response.getResults()).thenReturn(java.util.List.of(generation));
        assertThat(classifier.classify(response)).isEqualTo(Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("classify(ChatResponse): metadata 含超限错误消息返回 CONTEXT_LIMIT")
    void classifyResponseWithContextLimitErrorMessageReturnsContextLimit() {
        var output = org.mockito.Mockito.mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        var metadata = java.util.Map.<String, Object>of("error_message", "The context_length_exceeded error");
        org.mockito.Mockito.when(output.getMetadata()).thenReturn(metadata);
        var generation = org.mockito.Mockito.mock(org.springframework.ai.chat.model.Generation.class);
        org.mockito.Mockito.when(generation.getOutput()).thenReturn(output);
        var response = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.mockito.Mockito.when(response.getResults()).thenReturn(java.util.List.of(generation));
        assertThat(classifier.classify(response)).isEqualTo(Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("classify(ChatResponse): output 为 null 返回 OTHER_ERROR")
    void classifyResponseNullOutputReturnsOtherError() {
        var generation = org.mockito.Mockito.mock(org.springframework.ai.chat.model.Generation.class);
        org.mockito.Mockito.when(generation.getOutput()).thenReturn(null);
        var response = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.mockito.Mockito.when(response.getResults()).thenReturn(java.util.List.of(generation));
        assertThat(classifier.classify(response)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(ChatResponse): metadata 为 null 返回 OTHER_ERROR")
    void classifyResponseNullMetadataReturnsOtherError() {
        var output = org.mockito.Mockito.mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        org.mockito.Mockito.when(output.getMetadata()).thenReturn(null);
        var generation = org.mockito.Mockito.mock(org.springframework.ai.chat.model.Generation.class);
        org.mockito.Mockito.when(generation.getOutput()).thenReturn(output);
        var response = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.mockito.Mockito.when(response.getResults()).thenReturn(java.util.List.of(generation));
        assertThat(classifier.classify(response)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(ChatResponse): metadata 无错误字段返回 OTHER_ERROR")
    void classifyResponseNoErrorFieldsReturnsOtherError() {
        var output = org.mockito.Mockito.mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        var metadata = java.util.Map.<String, Object>of("other_field", "some_value");
        org.mockito.Mockito.when(output.getMetadata()).thenReturn(metadata);
        var generation = org.mockito.Mockito.mock(org.springframework.ai.chat.model.Generation.class);
        org.mockito.Mockito.when(generation.getOutput()).thenReturn(output);
        var response = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.mockito.Mockito.when(response.getResults()).thenReturn(java.util.List.of(generation));
        assertThat(classifier.classify(response)).isEqualTo(Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("classify(Throwable): 消息含 token + maximum 返回 CONTEXT_LIMIT")
    void classifyThrowableWithTokenMaximumReturnsContextLimit() {
        Throwable error = new RuntimeException("token count exceeded maximum allowed");
        assertThat(classifier.classify(error)).isEqualTo(Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("classify(Throwable): null 消息返回 OTHER_ERROR")
    void classifyThrowableWithNullMessageReturnsOtherError() {
        Throwable error = new RuntimeException((String) null);
        assertThat(classifier.classify(error)).isEqualTo(Verdict.OTHER_ERROR);
    }
}

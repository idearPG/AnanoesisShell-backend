package com.ananoesis.shell.ai;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.web.client.ResourceAccessException;

/**
 * 上下文超限分类器测试（design D7 "单次恢复"段，tasks 9.1）。
 *
 * <p>WHY 纯单元测试：分类逻辑是纯字符串匹配，不涉及网络或数据库。
 * 每条测试验证一种错误形态是否被正确分类为 CONTEXT_LIMIT 或 OTHER_ERROR。</p>
 *
 * <h2>分类纪律</h2>
 * <p>只有明确的结构化错误码或 token 上限语义才触发恢复。
 * 普通 400/413、401、网络异常、未知参数错误、finish_reason=length 均不触发。
 * WHY：误触发会浪费唯一的恢复额度，导致真正的上下文超限时无法自救。</p>
 */
class ContextLimitClassifierTest {

    private final ContextLimitClassifier classifier = new ContextLimitClassifier();

    // ======================================================================
    // 应识别为上下文超限
    // ======================================================================

    @Test
    @DisplayName("识别结构化错误码 context_length_exceeded（OpenAI 标准错误码）")
    void recognizesContextLengthExceededErrorCode() {
        RuntimeException error = new RuntimeException(
                "400 Bad Request: {\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"too many tokens\"}}");

        assertThat(classifier.classify(error)).isEqualTo(ContextLimitClassifier.Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("识别结构化错误码 context_window_exceeded（部分端点使用的变体）")
    void recognizesContextWindowExceededErrorCode() {
        RuntimeException error = new RuntimeException(
                "400 Bad Request: {\"error\":{\"code\":\"context_window_exceeded\"}}");

        assertThat(classifier.classify(error)).isEqualTo(ContextLimitClassifier.Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("识别明确的 token 上限语义描述")
    void recognizesExplicitTokenLimitSemantics() {
        RuntimeException error = new RuntimeException(
                "This model's maximum context length is 128000 tokens. "
                        + "You requested 150000 tokens. Please reduce your input.");

        assertThat(classifier.classify(error)).isEqualTo(ContextLimitClassifier.Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("识别嵌套在 cause 链中的上下文超限异常")
    void recognizesContextLimitInCauseChain() {
        RuntimeException inner = new RuntimeException(
                "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"exceeded\"}}");
        RuntimeException outer = new RuntimeException("Model call failed", inner);

        assertThat(classifier.classify(outer)).isEqualTo(ContextLimitClassifier.Verdict.CONTEXT_LIMIT);
    }

    @Test
    @DisplayName("识别 ChatResponse 元数据中的上下文超限错误码")
    void recognizesContextLimitInChatResponseMetadata() {
        ChatResponse response = buildErrorResponse("context_length_exceeded",
                "This model's maximum context length is 128000 tokens");

        assertThat(classifier.classify(response)).isEqualTo(ContextLimitClassifier.Verdict.CONTEXT_LIMIT);
    }

    // ======================================================================
    // 不应识别为上下文超限
    // ======================================================================

    @Test
    @DisplayName("普通 400 错误不触发恢复（无明确上下文超限语义）")
    void plainBadRequestDoesNotTriggerRecovery() {
        RuntimeException error = new RuntimeException("400 Bad Request: invalid parameters");

        assertThat(classifier.classify(error)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("413 Payload Too Large 不触发恢复（可能是请求体过大而非上下文超限）")
    void http413DoesNotTriggerRecovery() {
        RuntimeException error = new RuntimeException("413 Payload Too Large");

        assertThat(classifier.classify(error)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("401 认证失败不触发恢复")
    void authenticationFailureDoesNotTriggerRecovery() {
        RuntimeException error = new RuntimeException("401 Unauthorized: invalid api key");

        assertThat(classifier.classify(error)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("网络异常（ResourceAccessException）不触发恢复")
    void networkExceptionDoesNotTriggerRecovery() {
        RuntimeException error = new ResourceAccessException("Connection refused");

        assertThat(classifier.classify(error)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("未知参数错误不触发恢复")
    void unknownParameterErrorDoesNotTriggerRecovery() {
        RuntimeException error = new RuntimeException("Unknown parameter: temperature_x");

        assertThat(classifier.classify(error)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("finish_reason=length 不触发恢复（只是输出被截断，不是输入超限）")
    void finishReasonLengthDoesNotTriggerRecovery() {
        // WHY：finish_reason=length 表示模型输出达到了最大长度限制，
        // 而非输入上下文超限。混淆两者会浪费唯一的恢复额度
        ChatResponse response = buildFinishReasonResponse("length");

        assertThat(classifier.classify(response)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("finish_reason=stop 不触发恢复（正常结束）")
    void finishReasonStopDoesNotTriggerRecovery() {
        ChatResponse response = buildFinishReasonResponse("stop");

        assertThat(classifier.classify(response)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("null 异常返回 OTHER_ERROR")
    void nullErrorReturnsOtherError() {
        assertThat(classifier.classify((Throwable) null)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("null ChatResponse 返回 OTHER_ERROR")
    void nullChatResponseReturnsOtherError() {
        assertThat(classifier.classify((ChatResponse) null)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    @Test
    @DisplayName("自引用 cause 不会导致死循环")
    void selfReferencingCauseDoesNotCauseInfiniteLoop() {
        // WHY：与 AiAgentService.classify 的自引用 cause 测试同理——
        // 防御性编程，避免 getCause() == this 的异常把分类器拖入死循环
        RuntimeException selfReferencing = new RuntimeException("自引用") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(classifier.classify(selfReferencing)).isEqualTo(ContextLimitClassifier.Verdict.OTHER_ERROR);
    }

    // ======================================================================
    // 辅助方法
    // ======================================================================

    /** 构建一个包含错误码和错误信息的 ChatResponse（模拟端点返回的错误响应）。 */
    private static ChatResponse buildErrorResponse(String errorCode, String message) {
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason("error")
                .build();
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .properties(Map.of("error_code", errorCode, "error_message", message))
                .build();
        return new ChatResponse(List.of(new Generation(assistantMessage, metadata)));
    }

    /** 构建一个只带 finish_reason 的 ChatResponse。 */
    private static ChatResponse buildFinishReasonResponse(String finishReason) {
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(""), metadata)));
    }
}

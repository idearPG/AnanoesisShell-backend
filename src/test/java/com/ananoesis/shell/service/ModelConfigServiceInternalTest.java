package com.ananoesis.shell.service;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ModelConfig;
import com.ananoesis.shell.contract.model.ThinkingRequestFormat;
import com.ananoesis.shell.service.InvalidRequestException.FieldViolation;

/**
 * {@link ModelConfigService} 静态/包私有方法的分支覆盖。
 *
 * <p>WHY 独立测试：原测试覆盖 CRUD 闭环，但 computeInputBudget/
 * violationsOf/parseInstant/hasText/deriveThinkingRequestFormat 等方法的分支未被覆盖。</p>
 */
@DisplayName("ModelConfigService 内部方法分支覆盖")
class ModelConfigServiceInternalTest {

    @Nested
    @DisplayName("computeInputBudget")
    class ComputeInputBudget {
        @Test
        @DisplayName("正常计算：安全余量取 max(512, ceil(10%))")
        void normalComputation() {
            // 8192 * 0.10 = 819.2 → ceil = 820, max(512, 820) = 820
            // 8192 - 1024 - 820 = 6348
            assertThat(ModelConfigService.computeInputBudget(8192, 1024)).isEqualTo(6348);
        }

        @Test
        @DisplayName("小窗口：安全余量取 512（10% 不足 512）")
        void smallWindowSafetyMargin() {
            // 1024 * 0.10 = 102.4 → ceil = 103, max(512, 103) = 512
            // 1024 - 512 - 512 = 0
            assertThat(ModelConfigService.computeInputBudget(1024, 512)).isEqualTo(0);
        }

        @Test
        @DisplayName("中等窗口：安全余量取 10%")
        void mediumWindowSafetyMargin() {
            // 4096 * 0.10 = 409.6 → ceil = 410, max(512, 410) = 512
            // 4096 - 1024 - 512 = 2560
            assertThat(ModelConfigService.computeInputBudget(4096, 1024)).isEqualTo(2560);
        }

        @Test
        @DisplayName("大窗口（8192）：安全余量取 10%")
        void largeWindowSafetyMargin() {
            // 8192 * 0.10 = 819.2 → ceil = 820, max(512, 820) = 820
            // 8192 - 2048 - 820 = 5324
            assertThat(ModelConfigService.computeInputBudget(8192, 2048)).isEqualTo(5324);
        }
    }

    @Nested
    @DisplayName("violationsOf（反射调用私有静态方法）")
    class ViolationsOf {

        @SuppressWarnings("unchecked")
        private List<FieldViolation> invokeViolationsOf(ModelConfig request) throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("violationsOf", ModelConfig.class);
            m.setAccessible(true);
            return (List<FieldViolation>) m.invoke(null, request);
        }

        private ModelConfig baseRequest() {
            ModelConfig mc = new ModelConfig("openai", URI.create("https://api.example.com/v1"), "gpt-4");
            mc.setContextWindowTokens(8192);
            mc.setMaxOutputTokens(1024);
            return mc;
        }

        @Test
        @DisplayName("合法请求无违规")
        void validRequestNoViolations() throws Exception {
            assertThat(invokeViolationsOf(baseRequest())).isEmpty();
        }

        @Test
        @DisplayName("provider 为空时返回违规")
        void emptyProviderViolations() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setProvider("");
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("model 为空时返回违规")
        void emptyModelViolations() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setModel("");
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("base_url 为 null 时返回违规")
        void nullBaseUrlViolations() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setBaseUrl(null);
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("base_url 为相对地址时返回违规")
        void relativeBaseUrlViolations() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setBaseUrl(URI.create("/v1"));
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("base_url 协议非 http/https 时返回违规")
        void ftpBaseUrlViolations() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setBaseUrl(URI.create("ftp://example.com/v1"));
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("base_url 无 host 时返回违规")
        void noHostBaseUrlViolations() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setBaseUrl(URI.create("https:///path"));
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("context_window_tokens 低于下限时返回违规")
        void contextWindowTooSmall() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setContextWindowTokens(100);
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("context_window_tokens 超过上限时返回违规")
        void contextWindowTooLarge() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setContextWindowTokens(3000000);
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("max_output_tokens 为负数时返回违规")
        void negativeMaxOutputTokens() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setMaxOutputTokens(-1);
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("输入预算不足 512 时返回违规")
        void inputBudgetTooSmall() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setContextWindowTokens(1024);
            mc.setMaxOutputTokens(512);
            // 1024 - 512 - max(512, ceil(102.4)) = 1024 - 512 - 512 = 0 < 512
            assertThat(invokeViolationsOf(mc)).isNotEmpty();
        }

        @Test
        @DisplayName("context_window_tokens 为 null 时跳过校验")
        void nullContextWindowSkipsValidation() throws Exception {
            ModelConfig mc = baseRequest();
            mc.setContextWindowTokens(null);
            // 只有 context_window_tokens 相关的校验被跳过
            List<FieldViolation> violations = invokeViolationsOf(mc);
            assertThat(violations).isEmpty();
        }
    }

    @Nested
    @DisplayName("deriveThinkingRequestFormat（反射）")
    class DeriveThinkingRequestFormat {
        @SuppressWarnings("unchecked")
        private ThinkingRequestFormat invoke(String provider) throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("deriveThinkingRequestFormat", String.class);
            m.setAccessible(true);
            return (ThinkingRequestFormat) m.invoke(null, provider);
        }

        @Test
        @DisplayName("openai → NONE")
        void openaiReturnsNone() throws Exception {
            assertThat(invoke("openai")).isEqualTo(ThinkingRequestFormat.NONE);
        }

        @Test
        @DisplayName("OpenAI（大写）→ NONE")
        void openaiCaseInsensitive() throws Exception {
            assertThat(invoke("OpenAI")).isEqualTo(ThinkingRequestFormat.NONE);
        }

        @Test
        @DisplayName("其他 provider → QWEN_COMPATIBLE")
        void otherReturnsQwenCompatible() throws Exception {
            assertThat(invoke("mindie")).isEqualTo(ThinkingRequestFormat.QWEN_COMPATIBLE);
        }

        @Test
        @DisplayName("null provider → QWEN_COMPATIBLE")
        void nullReturnsQwenCompatible() throws Exception {
            assertThat(invoke(null)).isEqualTo(ThinkingRequestFormat.QWEN_COMPATIBLE);
        }

        @Test
        @DisplayName("空白 provider → QWEN_COMPATIBLE")
        void blankReturnsQwenCompatible() throws Exception {
            assertThat(invoke("  ")).isEqualTo(ThinkingRequestFormat.QWEN_COMPATIBLE);
        }
    }

    @Nested
    @DisplayName("hasText / rejectIfAny（反射）")
    class HasTextAndReject {
        @Test
        @DisplayName("hasText: null → false")
        void nullIsFalse() throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("hasText", String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, (String) null)).isEqualTo(false);
        }

        @Test
        @DisplayName("hasText: 空串 → false")
        void emptyIsFalse() throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("hasText", String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "")).isEqualTo(false);
        }

        @Test
        @DisplayName("hasText: 空白串 → false")
        void blankIsFalse() throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("hasText", String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "   ")).isEqualTo(false);
        }

        @Test
        @DisplayName("hasText: 有内容 → true")
        void textIsTrue() throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("hasText", String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "hello")).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("parseInstant（反射）")
    class ParseInstant {
        @Test
        @DisplayName("有效 ISO-8601 时间戳可解析")
        void validTimestamp() throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("parseInstant", String.class);
            m.setAccessible(true);
            String now = OffsetDateTime.now().toString();
            assertThat(m.invoke(null, now)).isNotNull();
        }

        @Test
        @DisplayName("null 返回 null")
        void nullReturnsNull() throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("parseInstant", String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, (String) null)).isNull();
        }

        @Test
        @DisplayName("空串返回 null")
        void emptyReturnsNull() throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("parseInstant", String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "")).isNull();
        }

        @Test
        @DisplayName("无效格式返回 null")
        void invalidReturnsNull() throws Exception {
            Method m = ModelConfigService.class.getDeclaredMethod("parseInstant", String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "not-a-timestamp")).isNull();
        }
    }
}

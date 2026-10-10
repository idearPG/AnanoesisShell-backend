package com.ananoesis.shell.controller;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.service.TransferConflictException;
import com.ananoesis.shell.service.TransferQuotaExceededException;

/**
 * {@link ApiExceptionHandler} 内部辅助方法与更多 handler 的分支覆盖。
 *
 * <p>WHY 独立测试：camelToSnake/wireNameOf/lastNodeOf/reasonOf 等辅助方法
 * 以及 handleTransferConflict/handleQuotaExceeded/handleUnexpected/handleNoResource
 * 的分支未被既有测试覆盖。</p>
 */
@DisplayName("ApiExceptionHandler 内部方法分支覆盖")
class ApiExceptionHandlerInternalTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    // ==================================================================
    // camelToSnake
    // ==================================================================

    @Nested
    @DisplayName("camelToSnake")
    class CamelToSnake {
        @Test
        @DisplayName("null → null")
        void nullReturnsNull() {
            assertThat(ApiExceptionHandler.camelToSnake(null)).isNull();
        }

        @Test
        @DisplayName("空串 → 空串")
        void emptyReturnsEmpty() {
            assertThat(ApiExceptionHandler.camelToSnake("")).isEmpty();
        }

        @Test
        @DisplayName("全小写不变")
        void allLowerCase() {
            assertThat(ApiExceptionHandler.camelToSnake("password")).isEqualTo("password");
        }

        @Test
        @DisplayName("authType → auth_type")
        void simpleCamelCase() {
            assertThat(ApiExceptionHandler.camelToSnake("authType")).isEqualTo("auth_type");
        }

        @Test
        @DisplayName("baseUrl → base_url")
        void shortCamelCase() {
            assertThat(ApiExceptionHandler.camelToSnake("baseUrl")).isEqualTo("base_url");
        }

        @Test
        @DisplayName("defaultThinkingMode → default_thinking_mode")
        void multiCamelCase() {
            assertThat(ApiExceptionHandler.camelToSnake("defaultThinkingMode"))
                    .isEqualTo("default_thinking_mode");
        }

        @Test
        @DisplayName("首字母大写 → _前缀")
        void startsWithUpperCase() {
            // 'A' at i=0 → no underscore prepended
            assertThat(ApiExceptionHandler.camelToSnake("AuthType")).isEqualTo("auth_type");
        }
    }

    // ==================================================================
    // wireNameOf
    // ==================================================================

    @Nested
    @DisplayName("wireNameOf（反射）")
    class WireNameOf {
        private String invoke(String name) throws Exception {
            Method m = ApiExceptionHandler.class.getDeclaredMethod("wireNameOf", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, name);
        }

        @Test
        @DisplayName("null → UNKNOWN_PARAMETER")
        void nullReturnsUnknown() throws Exception {
            assertThat(invoke(null)).isEqualTo("request");
        }

        @Test
        @DisplayName("空白 → UNKNOWN_PARAMETER")
        void blankReturnsUnknown() throws Exception {
            assertThat(invoke("  ")).isEqualTo("request");
        }

        @Test
        @DisplayName("正常名原样返回")
        void normalReturned() throws Exception {
            assertThat(invoke("page")).isEqualTo("page");
        }
    }

    // ==================================================================
    // lastNodeOf
    // ==================================================================

    @Nested
    @DisplayName("lastNodeOf（反射）")
    class LastNodeOf {
        private String invoke(jakarta.validation.Path path) throws Exception {
            Method m = ApiExceptionHandler.class.getDeclaredMethod("lastNodeOf", jakarta.validation.Path.class);
            m.setAccessible(true);
            return (String) m.invoke(null, path);
        }

        @Test
        @DisplayName("null path → UNKNOWN_PARAMETER")
        void nullReturnsUnknown() throws Exception {
            assertThat(invoke(null)).isEqualTo("request");
        }

        @Test
        @DisplayName("单节点 path → 返回节点名")
        void singleNodePath() throws Exception {
            // 通过 ConstraintViolation 获取真实 Path
            jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
            jakarta.validation.Validator validator = factory.getValidator();
            // 用一个简单的 bean 校验来获取 Path
            java.util.Set<jakarta.validation.ConstraintViolation<LastNodeOfTestBean>> violations =
                    validator.validate(new LastNodeOfTestBean());
            if (!violations.isEmpty()) {
                jakarta.validation.Path path = violations.iterator().next().getPropertyPath();
                String result = invoke(path);
                assertThat(result).isEqualTo("value");
            }
        }

        /** 用于 lastNodeOf 测试的简单 bean。 */
        private static class LastNodeOfTestBean {
            @jakarta.validation.constraints.Min(value = 10, message = "too small")
            private int value = 1;
            public int getValue() { return value; }
        }
    }

    // ==================================================================
    // 更多 handler
    // ==================================================================

    @Test
    @DisplayName("handleTransferConflict: 返回 409 + TRANSFER_CONFLICT")
    void handleTransferConflictReturns409() {
        var ex = new TransferConflictException("目标已存在", "{}");
        var response = handler.handleTransferConflict(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.TRANSFER_CONFLICT);
    }

    @Test
    @DisplayName("handleQuotaExceeded: 返回 429 + TRANSFER_QUOTA_EXCEEDED")
    void handleQuotaExceededReturns429() {
        var ex = new TransferQuotaExceededException("配额已满");
        var response = handler.handleQuotaExceeded(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.TRANSFER_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("handleUnexpected: 返回 500 + INTERNAL_ERROR")
    void handleUnexpectedReturns500() {
        var ex = new RuntimeException("unexpected");
        var response = handler.handleUnexpected(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("服务器内部错误，请查看后端日志获取详情");
    }

    @Test
    @DisplayName("handleConflict: 返回 409 + CONFLICT")
    void handleConflictReturns409() {
        var ex = new com.ananoesis.shell.service.ConflictException("冲突");
        var response = handler.handleConflict(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.CONFLICT);
    }
}

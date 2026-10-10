package com.ananoesis.shell.controller;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.security.CredentialProtectionException;
import com.ananoesis.shell.service.InvalidRequestException;
import com.ananoesis.shell.service.NotFoundException;

/**
 * {@link ApiExceptionHandler} handler 方法分支补测。
 *
 * <p>WHY 独立测试：覆盖 handleUnreadableBody/handleTypeMismatch/handleMissingBinding/
 * handleNoResource/handleConstraintViolation 等 handler 的分支。</p>
 */
@DisplayName("ApiExceptionHandler handler 补测")
class ApiExceptionHandlerHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("handleUnreadableBody: 返回 400")
    void handleUnreadableBodyReturns400() {
        var ex = new org.springframework.http.converter.HttpMessageNotReadableException("bad json");
        var response = handler.handleUnreadableBody(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("handleTypeMismatch: 返回 400")
    void handleTypeMismatchReturns400() {
        var ex = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "bad", String.class, "param", null, null);
        var response = handler.handleTypeMismatch(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("handleMissingBinding: 返回 400")
    void handleMissingBindingReturns400() {
        var ex = new org.springframework.web.bind.ServletRequestBindingException("missing") {};
        var response = handler.handleMissingBinding(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("handleConstraintViolation: 返回 400 + 空明细")
    void handleConstraintViolationReturns400() {
        // 构造 ConstraintViolationException（空 violations）
        var ex = new jakarta.validation.ConstraintViolationException(java.util.Set.of());
        var response = handler.handleConstraintViolation(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("body: null details 时 details 为空或 null")
    void nullDetailsNotSet() {
        var response = handler.handleNotFound(new NotFoundException("x") {});
        // 生成的 DTO 可能初始化 details 为空列表
        assertThat(response.getBody().getDetails()).isNullOrEmpty();
    }

    @Test
    @DisplayName("body: 空 details 列表不设 details 字段")
    void emptyDetailsNotSet() {
        var violations = List.<InvalidRequestException.FieldViolation>of();
        var response = handler.handleInvalidRequest(
                new InvalidRequestException("x", violations));
        assertThat(response.getBody().getDetails()).isEmpty();
    }

    @Test
    @DisplayName("handleCredentialProtectionUnavailable: 消息包含固定文案")
    void credentialProtectionMessageContainsFixed() {
        var response = handler.handleCredentialProtectionUnavailable(
                new CredentialProtectionException("test"));
        assertThat(response.getBody().getMessage())
                .contains(CredentialProtectionException.UNAVAILABLE_MESSAGE);
    }
}

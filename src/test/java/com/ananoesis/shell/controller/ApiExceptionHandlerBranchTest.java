package com.ananoesis.shell.controller;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiExceptionHandler} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：辅助方法（camelToSnake/wireNameOf/lastNodeOf/reasonOf）
 * 含多个分支（null/空/正常路径），原测试未覆盖。</p>
 */
@DisplayName("ApiExceptionHandler 分支覆盖")
class ApiExceptionHandlerBranchTest {

    // ---- camelToSnake ----

    @Test
    @DisplayName("camelToSnake: null 输入返回 null")
    void camelToSnakeNullReturnsNull() {
        assertThat(ApiExceptionHandler.camelToSnake(null)).isNull();
    }

    @Test
    @DisplayName("camelToSnake: 空串返回空串")
    void camelToSnakeEmptyReturnsEmpty() {
        assertThat(ApiExceptionHandler.camelToSnake("")).isEmpty();
    }

    @Test
    @DisplayName("camelToSnake: 纯小写不变")
    void camelToSnakeLowerNoChange() {
        assertThat(ApiExceptionHandler.camelToSnake("password")).isEqualTo("password");
    }

    @Test
    @DisplayName("camelToSnake: authType → auth_type")
    void camelToSnakeAuthType() {
        assertThat(ApiExceptionHandler.camelToSnake("authType")).isEqualTo("auth_type");
    }

    @Test
    @DisplayName("camelToSnake: baseUrl → base_url")
    void camelToSnakeBaseUrl() {
        assertThat(ApiExceptionHandler.camelToSnake("baseUrl")).isEqualTo("base_url");
    }

    @Test
    @DisplayName("camelToSnake: defaultThinkingMode → default_thinking_mode")
    void camelToSnakeDefaultThinkingMode() {
        assertThat(ApiExceptionHandler.camelToSnake("defaultThinkingMode"))
                .isEqualTo("default_thinking_mode");
    }

    @Test
    @DisplayName("camelToSnake: 首字母大写 → _前缀")
    void camelToSnakeLeadingUpper() {
        assertThat(ApiExceptionHandler.camelToSnake("AuthType")).isEqualTo("auth_type");
    }

    // ---- handleNotFound 等 handler 方法直接调用 ----

    @Test
    @DisplayName("handleNotFound: 返回 404 + NOT_FOUND")
    void handleNotFoundReturns404() {
        var handler = new ApiExceptionHandler();
        var response = handler.handleNotFound(
                new com.ananoesis.shell.service.NotFoundException("host not found") {});
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(
                com.ananoesis.shell.contract.model.ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("handleConflict: 返回 409 + CONFLICT")
    void handleConflictReturns409() {
        var handler = new ApiExceptionHandler();
        var response = handler.handleConflict(
                new com.ananoesis.shell.service.ConflictException("conflict"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo(
                com.ananoesis.shell.contract.model.ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("handleTransferConflict: 返回 409 + TRANSFER_CONFLICT")
    void handleTransferConflictReturns409() {
        var handler = new ApiExceptionHandler();
        var response = handler.handleTransferConflict(
                new com.ananoesis.shell.service.TransferConflictException("file exists", "snapshot"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getCode()).isEqualTo(
                com.ananoesis.shell.contract.model.ErrorCode.TRANSFER_CONFLICT);
    }

    @Test
    @DisplayName("handleQuotaExceeded: 返回 429 + TRANSFER_QUOTA_EXCEEDED")
    void handleQuotaExceededReturns429() {
        var handler = new ApiExceptionHandler();
        var response = handler.handleQuotaExceeded(
                new com.ananoesis.shell.service.TransferQuotaExceededException("full"));
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody().getCode()).isEqualTo(
                com.ananoesis.shell.contract.model.ErrorCode.TRANSFER_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("handleUnexpected: 返回 500 + INTERNAL_ERROR")
    void handleUnexpectedReturns500() {
        var handler = new ApiExceptionHandler();
        var response = handler.handleUnexpected(new RuntimeException("boom"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getCode()).isEqualTo(
                com.ananoesis.shell.contract.model.ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("handleInvalidRequest: 返回 400 + 字段明细")
    void handleInvalidRequestReturns400WithDetails() {
        var handler = new ApiExceptionHandler();
        var violations = java.util.List.of(
                new com.ananoesis.shell.service.InvalidRequestException.FieldViolation("host", "不能为空"));
        var response = handler.handleInvalidRequest(
                new com.ananoesis.shell.service.InvalidRequestException("校验失败", violations));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getDetails()).hasSize(1);
        assertThat(response.getBody().getDetails().get(0).getField()).isEqualTo("host");
    }

    @Test
    @DisplayName("handleInvalidRequest: 空明细时 details 为空列表")
    void handleInvalidRequestEmptyViolationsNoDetails() {
        var handler = new ApiExceptionHandler();
        var violations = java.util.List.<com.ananoesis.shell.service.InvalidRequestException.FieldViolation>of();
        var response = handler.handleInvalidRequest(
                new com.ananoesis.shell.service.InvalidRequestException("校验失败", violations));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getDetails()).isEmpty();
    }

    @Test
    @DisplayName("handleCredentialProtectionUnavailable: 返回 503")
    void handleCredentialProtectionReturns503() {
        var handler = new ApiExceptionHandler();
        var response = handler.handleCredentialProtectionUnavailable(
                new com.ananoesis.shell.security.CredentialProtectionException("no keyring"));
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getCode()).isEqualTo(
                com.ananoesis.shell.contract.model.ErrorCode.CREDENTIAL_PROTECTION_UNAVAILABLE);
    }
}

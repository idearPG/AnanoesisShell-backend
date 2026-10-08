package com.ananoesis.shell.controller;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.ananoesis.shell.contract.api.HostsApi;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.Host;
import com.ananoesis.shell.security.CredentialProtectionException;
import com.ananoesis.shell.service.HostNotFoundException;
import com.ananoesis.shell.service.InvalidRequestException;

/**
 * 领域异常 → 契约错误响应的映射表（tasks 5.1 的错误路径部分）。
 *
 * <p>WHY 用纯单元测试而不是走一遍 HTTP：这里要锁定的是"哪类异常映射到哪个
 * {@code ErrorCode} 与 HTTP 状态"，与 Servlet 容器、Jackson、路由都无关。
 * 走 HTTP 会让断言被上下文启动时间稀释，也难以覆盖"密钥库不可用"这种
 * 在共享测试上下文里无法安全构造的分支。</p>
 *
 * <p>WHY 单独断言"不回显被拒绝的取值"：{@code FieldError} 由 Spring 自动携带
 * {@code rejectedValue}——对 {@code password} 字段而言那就是用户刚输入的明文 SSH 密码。
 * 一旦把它拼进响应，credential-store spec「明文凭据 MUST NOT 写入错误信息」即被违反，
 * 而且不会有任何其它测试失败——只有这条断言能挡住它。</p>
 */
class ApiExceptionHandlerTest {

    private static final String SECRET = "S3cr3t-P@ssw0rd-XYZ-9f3a";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("资源不存在 → 404 not_found")
    void notFoundMapping() {
        ResponseEntity<Error> response =
                handler.handleNotFound(new HostNotFoundException("主机不存在: id=xxx"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(response.getBody().getMessage()).isNotBlank();
        assertThat(response.getBody().getTimestamp()).as("契约含 timestamp 字段").isNotNull();
    }

    @Test
    @DisplayName("业务校验失败 → 400 validation_error，并带字段级明细")
    void validationMappingCarriesFieldDetails() {
        ResponseEntity<Error> response = handler.handleInvalidRequest(
                new InvalidRequestException("请求校验失败",
                        List.of(new InvalidRequestException.FieldViolation("password", "认证方式为密码时必填"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error body = response.getBody();
        assertThat(body.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(body.getDetails()).hasSize(1);
        assertThat(body.getDetails().get(0).getField()).isEqualTo("password");
        assertThat(body.getDetails().get(0).getReason()).contains("必填");
    }

    @Test
    @DisplayName("密钥库不可用 → 503 credential_protection_unavailable（禁止静默明文）")
    void credentialProtectionMapping() {
        ResponseEntity<Error> response = handler.handleCredentialProtectionUnavailable(
                CredentialProtectionException.unavailable("没有可用的主密钥来源", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Error body = response.getBody();
        assertThat(body.getCode()).isEqualTo(ErrorCode.CREDENTIAL_PROTECTION_UNAVAILABLE);
        assertThat(body.getMessage()).contains(CredentialProtectionException.UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("Bean Validation 失败 → 400 validation_error，字段名用**契约 JSON 名**，且不回显被拒绝的取值")
    void beanValidationMappingNeverEchoesRejectedValue() throws Exception {
        Host rejected = new Host("10.0.0.1", 22, "ops", null);
        rejected.setPassword(SECRET);
        BindingResult binding = new BeanPropertyBindingResult(rejected, "host");
        binding.rejectValue("authType", "NotNull", "不得为空");
        // 真实的约束消息模板只描述规则、不含取值；而 rejectedValue 由 Spring 自动从
        // 目标对象读出，此处即为明文密码——这才是生产环境里真正的泄露路径
        binding.rejectValue("password", "Size", "长度必须在 1 到 128 之间");
        assertThat(binding.getFieldError("password").getRejectedValue())
                .as("前置：夹具确实把明文密码放进了 rejectedValue")
                .isEqualTo(SECRET);
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(parameterOfHost(), binding);

        ResponseEntity<Error> response = handler.handleBeanValidationFailure(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error body = response.getBody();
        assertThat(body.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        // WHY 断言 JSON 名而非 Java 属性名：契约里该字段叫 auth_type，前端只认识契约名；
        // 回传 authType 会让前端的"把错误挂到表单项上"逻辑静默失效
        assertThat(body.getDetails()).extracting("field").contains("auth_type", "password");
        assertThat(body.getDetails()).extracting("reason").doesNotContain(SECRET);
        assertThat(body.getMessage()).doesNotContain(SECRET);
        assertThat(String.valueOf(body)).as("整个错误响应体").doesNotContain(SECRET);
    }

    @Test
    @DisplayName("未预期异常 → 500 internal_error，且不把内部细节透给客户端")
    void unexpectedFailureMappingHidesInternals() {
        RuntimeException boom = new RuntimeException(
                "SQLITE_BUSY: database is locked at /home/ops/.ananoesis/data.db");

        ResponseEntity<Error> response = handler.handleUnexpected(boom);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Error body = response.getBody();
        assertThat(body.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        // WHY 必须是固定文案：ssh-connection spec 要求错误"不暴露服务器内部细节"，
        // 而 500 的原始异常消息里最容易混进路径、SQL、堆栈片段
        assertThat(body.getMessage()).doesNotContain("SQLITE_BUSY").doesNotContain(".ananoesis");
    }

    /** 取 {@code HostsApi#createHost(Host)} 的第 0 个参数，用于构造真实的校验异常。 */
    private static MethodParameter parameterOfHost() throws NoSuchMethodException {
        Method method = HostsApi.class.getMethod("createHost", Host.class);
        return new MethodParameter(method, 0);
    }
}

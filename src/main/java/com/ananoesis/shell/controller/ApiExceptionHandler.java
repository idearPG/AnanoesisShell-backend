package com.ananoesis.shell.controller;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.ErrorDetailsInner;
import com.ananoesis.shell.security.CredentialProtectionException;
import com.ananoesis.shell.service.ConflictException;
import com.ananoesis.shell.service.InvalidRequestException;
import com.ananoesis.shell.service.NotFoundException;
import com.ananoesis.shell.service.TransferConflictException;
import com.ananoesis.shell.service.TransferQuotaExceededException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常 → 契约 {@code Error} 响应的唯一翻译层（tasks 2.4 / 5.1 / 7.1 / 8.5）。
 *
 * <p>WHY 必须集中在这里，而不是让各 Controller 自己 try/catch：
 * {@code openapi.yaml} 为每个端点都声明了同一套 {@code 4xx/5xx → Error} 响应，
 * 契约里的 {@code ErrorCode} 是**封闭枚举**。分散处理必然出现两种漂移：
 * 有人漏写返回 Spring 默认的错误 JSON（结构与契约不符），
 * 有人自造错误码（前端 switch 落空）。集中在一个 advice 里，
 * "HTTP 世界只可能产出契约形状的错误"就成了结构保证。</p>
 *
 * <p>安全底线（credential-store / ssh-connection spec）：响应体 MUST NOT 含
 * 明文凭据，也 MUST NOT 泄露服务器内部细节（SQL 片段、文件路径、堆栈）。
 * 因此 5xx 一律使用固定文案，原始异常只进服务端日志。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 5xx 对外固定文案：不携带任何来自底层异常的内容。 */
    private static final String INTERNAL_ERROR_MESSAGE = "服务器内部错误，请查看后端日志获取详情";
    /** 请求体无法解析时的对外文案。WHY 不复述 Jackson 的原文：见 {@link #handleUnreadableBody}。 */
    private static final String UNREADABLE_BODY_MESSAGE = "请求体格式不正确或含有非法值";
    /** 路径/查询参数类型不匹配时的对外文案。 */
    private static final String TYPE_MISMATCH_MESSAGE = "请求参数格式不正确";
    /** 查询/路径参数越界时的对外文案（如 {@code page=0}、{@code size=500}）。 */
    private static final String PARAMETER_OUT_OF_RANGE_MESSAGE = "请求参数取值超出允许范围";
    /** 缺少必需参数/请求头时的对外文案。 */
    private static final String MISSING_PARAMETER_MESSAGE = "缺少必需的请求参数或请求头";
    /** 拿不到参数名时 {@code details[].field} 的兜底值。 */
    private static final String UNKNOWN_PARAMETER = "request";

    // ==================================================================
    // 业务异常
    // ==================================================================

    /** 资源不存在 → 404 {@code not_found}。 */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Error> handleNotFound(NotFoundException exception) {
        LOG.warn("资源不存在: {}", exception.getMessage());
        return body(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, exception.getMessage(), null);
    }

    /** 业务规则校验失败 → 400 {@code validation_error}，带字段级明细。 */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Error> handleInvalidRequest(InvalidRequestException exception) {
        List<ErrorDetailsInner> details = exception.violations().stream()
                .map(violation -> new ErrorDetailsInner()
                        .field(violation.field())
                        .reason(violation.reason()))
                .toList();
        LOG.warn("请求未通过业务校验: {} details={}", exception.getMessage(), details.size());
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, exception.getMessage(), details);
    }

    /**
     * 与资源当前状态冲突 → 409 {@code conflict}。
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Error> handleConflict(ConflictException exception) {
        LOG.warn("请求与资源当前状态冲突: {}", exception.getMessage());
        return body(HttpStatus.CONFLICT, ErrorCode.CONFLICT, exception.getMessage(), null);
    }

    /**
     * 传输目标文件已存在 → 409 {@code transfer_conflict}（design.md D9 覆盖语义）。
     *
     * <p>WHY 放在 {@link #handleConflict} 之后：Spring 按声明顺序匹配最具体的异常类型，
     * {@link TransferConflictException} 是 {@link RuntimeException} 的直接子类，
     * 与 {@link ConflictException} 无继承关系，顺序不影响正确性，但放在后面使阅读更自然。</p>
     */
    @ExceptionHandler(TransferConflictException.class)
    public ResponseEntity<Error> handleTransferConflict(TransferConflictException exception) {
        LOG.warn("传输目标已存在: {}", exception.getMessage());
        return body(HttpStatus.CONFLICT, ErrorCode.TRANSFER_CONFLICT, exception.getMessage(), null);
    }

    /**
     * 传输配额已满 → 429 {@code transfer_quota_exceeded}。
     */
    @ExceptionHandler(TransferQuotaExceededException.class)
    public ResponseEntity<Error> handleQuotaExceeded(TransferQuotaExceededException exception) {
        LOG.warn("传输配额已满: {}", exception.getMessage());
        return body(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TRANSFER_QUOTA_EXCEEDED, exception.getMessage(), null);
    }

    /**
     * 主密钥不可用 → 503 {@code credential_protection_unavailable}。
     */
    @ExceptionHandler(CredentialProtectionException.class)
    public ResponseEntity<Error> handleCredentialProtectionUnavailable(CredentialProtectionException exception) {
        LOG.error("凭据保护不可用，已拒绝写入明文: {}", exception.getMessage(), exception);
        return body(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.CREDENTIAL_PROTECTION_UNAVAILABLE,
                CredentialProtectionException.UNAVAILABLE_MESSAGE + "，请在设置中配置主密码后重试", null);
    }

    // ==================================================================
    // 框架层异常（在到达业务代码之前就被 Spring 抛出的那一批）
    // ==================================================================

    /**
     * {@code @Valid} 校验失败 → 400 {@code validation_error}。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Error> handleBeanValidationFailure(MethodArgumentNotValidException exception) {
        Class<?> beanType = exception.getParameter().getParameterType();
        List<ErrorDetailsInner> details = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorDetailsInner()
                        .field(jsonFieldName(beanType, fieldError.getField()))
                        .reason(reasonOf(fieldError)))
                .toList();
        LOG.warn("请求体未通过 Bean Validation: 违规字段数={}", details.size());
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "请求校验失败", details);
    }

    /**
     * 请求体无法解析 → 400 {@code validation_error}。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Error> handleUnreadableBody(HttpMessageNotReadableException exception) {
        LOG.warn("请求体无法解析: {}", exception.getMessage());
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, UNREADABLE_BODY_MESSAGE, null);
    }

    /**
     * 路径/查询参数类型不匹配 → 400 {@code validation_error}。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Error> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        LOG.warn("请求参数类型不匹配: name={}", exception.getName());
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, TYPE_MISMATCH_MESSAGE, null);
    }

    /**
     * <b>方法级</b>参数约束不满足 → 400 {@code validation_error}。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Error> handleParameterConstraintFailure(HandlerMethodValidationException exception) {
        List<ErrorDetailsInner> details = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String field = wireNameOf(result.getMethodParameter().getParameterName());
            for (MessageSourceResolvable resolvable : result.getResolvableErrors()) {
                details.add(new ErrorDetailsInner().field(field).reason(reasonOf(resolvable)));
            }
        }
        LOG.warn("请求参数未通过约束校验: 违规项数={}", details.size());
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, PARAMETER_OUT_OF_RANGE_MESSAGE, details);
    }

    /**
     * 与 {@link #handleParameterConstraintFailure} 同一语义的另一条抛出路径。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Error> handleConstraintViolation(ConstraintViolationException exception) {
        List<ErrorDetailsInner> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            details.add(new ErrorDetailsInner()
                    .field(wireNameOf(lastNodeOf(violation.getPropertyPath())))
                    .reason(violation.getMessage() == null || violation.getMessage().isBlank()
                            ? "取值不合法" : violation.getMessage()));
        }
        LOG.warn("请求参数未通过约束校验: 违规项数={}", details.size());
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, PARAMETER_OUT_OF_RANGE_MESSAGE, details);
    }

    /**
     * 缺少必需的请求参数或请求头 → 400 {@code validation_error}。
     *
     * <p>WHY 需要它：Spring 对 {@code @RequestHeader(required=true)} 和
     * {@code @RequestParam(required=true)} 缺失时抛 {@link ServletRequestBindingException}
     * （含其子类 {@code MissingRequestHeaderException} / {@code MissingServletRequestParameterException}）。
     * 不显式接管就会落进 {@link #handleUnexpected} 变成 500，
     * 把纯粹的客户端漏参问题报告成服务器故障。</p>
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<Error> handleMissingBinding(ServletRequestBindingException exception) {
        LOG.warn("缺少必需的请求参数或请求头: {}", exception.getMessage());
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, MISSING_PARAMETER_MESSAGE, null);
    }

    /**
     * 未匹配到任何处理器 → 404 {@code not_found}。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Error> handleNoResource(NoResourceFoundException exception) {
        LOG.warn("请求的资源路径不存在: {}", exception.getResourcePath());
        return body(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "请求的资源不存在", null);
    }

    /**
     * 兜底：任何未预期异常 → 500 {@code internal_error}。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> handleUnexpected(Exception exception) {
        LOG.error("未预期异常，已按 internal_error 返回", exception);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE, null);
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    private static ResponseEntity<Error> body(HttpStatus status, ErrorCode code,
                                              String message, List<ErrorDetailsInner> details) {
        Error error = new Error(code, message).timestamp(OffsetDateTime.now());
        if (details != null && !details.isEmpty()) {
            error.setDetails(details);
        }
        return ResponseEntity.status(status).body(error);
    }

    /**
     * 约束消息优先，缺失时退回错误码。
     * WHY 不用 {@code FieldError#getRejectedValue()}：见 {@link #handleBeanValidationFailure}。
     */
    private static String reasonOf(FieldError fieldError) {
        String msg = fieldError.getDefaultMessage();
        return (msg == null || msg.isBlank()) ? "校验未通过" : msg;
    }

    private static String reasonOf(MessageSourceResolvable resolvable) {
        String msg = resolvable.getDefaultMessage();
        return (msg == null || msg.isBlank()) ? "校验未通过" : msg;
    }

    /**
     * 从 Java 属性名翻译成契约 JSON 字段名。
     *
     * <p>WHY camelCase → snake_case：生成的 DTO 用 {@code @JsonProperty("auth_type")} 标注了
     * 每个改名属性，但 Spring Validation 的 {@code FieldError} 给出的是 Java 属性名（如 {@code authType}）。
     * 前端只认契约里的 snake_case 名，因此必须翻译。</p>
     */
    private static String jsonFieldName(Class<?> beanType, String javaFieldName) {
        // WHY 直接做 camelCase → snake_case 而不是反射读 @JsonProperty：
        // 所有契约 DTO 的 JSON 命名规则统一为 snake_case，转换结果与 @JsonProperty 值一致。
        // 直接转换更简单、更快、且无需依赖 Jackson 的内省行为。
        return camelToSnake(javaFieldName);
    }

    /**
     * 将 camelCase 转为 snake_case。
     * <p>例：{@code authType → auth_type}、{@code baseUrl → base_url}、
     * {@code defaultThinkingMode → default_thinking_mode}、{@code password → password}。</p>
     */
    static String camelToSnake(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        StringBuilder sb = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String wireNameOf(@org.springframework.lang.Nullable String javaName) {
        if (javaName == null || javaName.isBlank()) {
            return UNKNOWN_PARAMETER;
        }
        return javaName;
    }

    private static String lastNodeOf(@org.springframework.lang.Nullable jakarta.validation.Path path) {
        if (path == null) {
            return UNKNOWN_PARAMETER;
        }
        String last = null;
        for (jakarta.validation.Path.Node node : path) {
            last = node.getName();
        }
        return last == null ? UNKNOWN_PARAMETER : last;
    }
}

package com.ananoesis.shell.ws;

import java.util.UUID;

import org.springframework.lang.Nullable;

import com.ananoesis.shell.contract.model.EndReason;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * {@code terminal_output} 消息载荷（asyncapi.yaml {@code components/schemas/TerminalOutput}）。
 *
 * <p>WHY 只提供静态工厂而不暴露规范构造器给调用方：契约规定这三个字段组是
 * <b>互斥</b>的——{@code type=data} 带 {@code stream}+{@code data}；
 * {@code type=error} 带 {@code error_code}+{@code message}；
 * {@code type=closed} 带 {@code end_reason}。
 * 若让调用方自由拼装，"error 事件里忘了填 error_code"这种违约只会在前端表现为
 * 一个空白错误框。工厂方法把合法组合固化下来，非法组合根本写不出来。</p>
 *
 * <p>WHY {@code @JsonInclude(NON_NULL)}：契约把 {@code stream}/{@code data}/
 * {@code error_code}/{@code message}/{@code end_reason} 都声明为可选。
 * 默认行为会把它们序列化成 {@code null}，前端就得处处判 null 而不是判字段是否存在。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TerminalOutput(
        @Nullable UUID sessionId,
        @Nullable Long eventSeq,
        Type type,
        @Nullable Stream stream,
        @Nullable String data,
        @Nullable ErrorCode errorCode,
        @Nullable String message,
        @Nullable EndReason endReason,
        @Nullable Object snapshot) {

    /** 输出事件类型。取值见 asyncapi.yaml，线上为小写。 */
    public enum Type {
        @JsonProperty("data") DATA,
        @JsonProperty("error") ERROR,
        @JsonProperty("closed") CLOSED,
        /** V2：绑定成功确认。 */
        @JsonProperty("bound") BOUND,
        /** V2：重连快照，含事件尾部。 */
        @JsonProperty("subscription_snapshot") SUBSCRIPTION_SNAPSHOT
    }

    /** 数据流类别；仅在 {@code type=data} 时出现。 */
    public enum Stream {
        @JsonProperty("stdout") STDOUT,
        @JsonProperty("stderr") STDERR
    }

    /**
     * open 成功的回执帧：只带 {@code session_id}，载荷为空。
     *
     * <p>WHY 用 {@code type=data, stream=stdout, data=""} 而不是新增类型：
     * 契约 0.1.0 已冻结（design.md D7），后端无权扩枚举；而向 xterm.js 写一个
     * 空字符串是无副作用的，前端也可以纯粹把它当作"握手完成"的信号。</p>
     */
    public static TerminalOutput opened(UUID sessionId) {
        return new TerminalOutput(sessionId, null, Type.DATA, Stream.STDOUT, "", null, null, null, null);
    }

    public static TerminalOutput stdout(UUID sessionId, String data) {
        return new TerminalOutput(sessionId, null, Type.DATA, Stream.STDOUT, data, null, null, null, null);
    }

    public static TerminalOutput stderr(UUID sessionId, String data) {
        return new TerminalOutput(sessionId, null, Type.DATA, Stream.STDERR, data, null, null, null, null);
    }

    /**
     * 连接/协议级错误。
     *
     * <p>WHY 允许 {@code sessionId} 为 null：{@code action=open} 失败时会话 id
     * 可能还没下发（例如主机不可达），此时前端只能靠 {@code error_code} 与
     * {@code message} 呈现错误。契约把 {@code session_id} 声明为可选正是为此。</p>
     *
     * @param message 面向用户的说明；MUST NOT 含凭据或服务器内部细节
     *                （ssh-connection spec「建立 SSH 连接」）
     */
    public static TerminalOutput error(@Nullable UUID sessionId, ErrorCode errorCode, String message) {
        return new TerminalOutput(sessionId, null, Type.ERROR, null, null, errorCode, message, null, null);
    }

    /** 不带 session_id 的错误帧，用于 {@code open} 尚未分配到会话的场合。 */
    public static TerminalOutput error(ErrorCode errorCode, String message) {
        return error(null, errorCode, message);
    }

    public static TerminalOutput closed(UUID sessionId, @Nullable EndReason endReason) {
        return new TerminalOutput(sessionId, null, Type.CLOSED, null, null, null, null, endReason, null);
    }
}

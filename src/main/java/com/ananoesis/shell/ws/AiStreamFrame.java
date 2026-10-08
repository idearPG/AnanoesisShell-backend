package com.ananoesis.shell.ws;

import java.util.UUID;

import org.springframework.lang.Nullable;

import com.ananoesis.shell.contract.model.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * {@code ai_stream} 消息载荷（asyncapi.yaml {@code components/schemas/AiStream}）。
 *
 * <h2>WHY 一个 record 同时服务两个方向，而终端那边却拆成了 Input/Output</h2>
 * <p>终端的 {@code terminal_input} 与 {@code terminal_output} 是<b>两个不同的 schema</b>，
 * 所以自然拆成两个 record，出站那个能把契约的 {@code required} 表达成"组件非空"。
 * 而 AI 通道按 TRACEABILITY <b>Q2</b> 的裁定<b>复用同一个 schema</b>：
 * 客户端上行 {@code type=user_message}，服务器下行其余六种 type。
 * 一个 schema 两个方向，就无法同时满足"入站全组件可空"与"出站 required 组件非空"。</p>
 *
 * <p>取舍：<b>入站容忍优先</b>，所有组件标 {@link Nullable}。理由与 {@link TerminalInput} 相同——
 * 契约的 {@code required} 约束的是发送方，本类是接收方的反序列化目标；若声明非空，
 * 缺字段的帧会在 Jackson 阶段炸掉，处理器就没机会按契约回一个
 * {@code type=error, error_code=validation_error} 的帧，前端只会看到连接莫名断开。</p>
 *
 * <p>出站侧的 required 义务改由<b>静态工厂</b>承担：工厂不接受 null 的
 * {@code conversationId}，且总是填 {@code type}。这条义务由
 * {@code AiStreamFrameTest#everyOutboundFactoryPopulatesContractRequiredFields}
 * 用"序列化后必须出现这两个键"来验证——比结构性断言更贴近真实违约形态。</p>
 *
 * <p>WHY 显式写 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 而不依赖 Spring Boot 的
 * 全局配置：Spring Boot 自动装配的 ObjectMapper 确实关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 但那是<b>容器</b>的行为。单元测试里的 {@code new ObjectMapper()}、
 * 以及将来任何手工构造的 mapper 都不带这个默认值——契约在 0.1.0 之后的小版本里新增一个字段，
 * 后端就会在解析入站帧时抛异常，症状是"前端升级后 AI 通道一发消息就断线"。
 * 把容忍度写在类型上，它与 mapper 配置解耦。</p>
 *
 * <h2>安全</h2>
 * <p>{@code content}/{@code message} 是面向用户的文本，MUST NOT 含明文凭据。
 * {@code error_code} 复用生成物 {@link ErrorCode}，因此契约改枚举时这里编译期出错。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiStreamFrame(
        @Nullable Type type,
        @Nullable UUID conversationId,
        @Nullable UUID messageId,
        @Nullable UUID hostId,
        @Nullable Boolean thinkingMode,
        @Nullable String content,
        @Nullable Segment segment,
        @Nullable ToolCallEventFrame toolCall,
        @Nullable ErrorCode errorCode,
        @Nullable String message,
        @Nullable String finishReason,
        @Nullable UUID sessionId,
        @Nullable Long eventSeq,
        @Nullable Object contextNotice,
        @Nullable UUID attemptId,
        @Nullable Integer attemptNumber) {

    /**
     * ai_stream 事件类型（契约 {@code AiStreamType}）。
     *
     * <p>上行有两种：{@code USER_MESSAGE}（Q2：复用 ai_stream 承载用户提问）与
     * {@code STOP_TURN}（V2：用户 Ctrl+C 打断在飞回合，翻成 AiAgentService.stop）。
     * 其余均为下行。</p>
     */
    public enum Type {
        /** 客户端提交用户提问。 */
        @JsonProperty("user_message") USER_MESSAGE,
        /** 思考过程增量（仅思考模式）。 */
        @JsonProperty("thinking_delta") THINKING_DELTA,
        /** 最终回答增量。 */
        @JsonProperty("answer_delta") ANSWER_DELTA,
        /** 工具调用发起。 */
        @JsonProperty("tool_call") TOOL_CALL,
        /** 工具调用结果（含"用户已拒绝"）。 */
        @JsonProperty("tool_result") TOOL_RESULT,
        /** 本回合结束。 */
        @JsonProperty("final") FINAL,
        /** 错误。 */
        @JsonProperty("error") ERROR,
        /** V2：上下文裁剪通知（agent-context「请求总 token 预算」）。 */
        @JsonProperty("context_notice") CONTEXT_NOTICE,
        /** V2：超限恢复重置（agent-context「上下文超限的单次恢复」）。 */
        @JsonProperty("attempt_reset") ATTEMPT_RESET,
        /** V2：客户端请求停止本会话在飞回合（上行，仅需 conversation_id；放末尾避免移动既有 ordinal）。 */
        @JsonProperty("stop_turn") STOP_TURN
    }

    /** 增量文本归属（契约 {@code AiStream.segment} 的内联 enum）。 */
    public enum Segment {
        /** 思考过程——界面上通常折叠/灰显。 */
        @JsonProperty("thinking") THINKING,
        /** 最终回答——界面上正文渲染。 */
        @JsonProperty("answer") ANSWER
    }

    // ======================================================================
    // 下行工厂
    // ======================================================================

    /**
     * 思考过程增量（tasks 7.3）。
     *
     * <p>WHY 由调用方决定"要不要发"而不是在这里判断：非思考模式下模型不产出
     * {@code reasoning_content}，此时压根不会调用本工厂。若在这里再判一次，
     * 就出现两处真相，早晚不一致。</p>
     */
    public static AiStreamFrame thinkingDelta(UUID conversationId, String content) {
        return delta(conversationId, Type.THINKING_DELTA, Segment.THINKING, content);
    }

    /** 最终回答增量（tasks 7.4）。 */
    public static AiStreamFrame answerDelta(UUID conversationId, String content) {
        return delta(conversationId, Type.ANSWER_DELTA, Segment.ANSWER, content);
    }

    private static AiStreamFrame delta(UUID conversationId, Type type, Segment segment, String content) {
        return new AiStreamFrame(type, requireConversationId(conversationId), null, null, null,
                content, segment, null, null, null, null, null, null, null, null, null);
    }

    /** 工具调用发起（ai-agent「操作透明性」）。 */
    public static AiStreamFrame toolCall(UUID conversationId, ToolCallEventFrame toolCall) {
        return toolEvent(conversationId, Type.TOOL_CALL, toolCall);
    }

    /** 工具调用结果；被拒绝时 {@code result_status=rejected}、{@code result="用户已拒绝"}。 */
    public static AiStreamFrame toolResult(UUID conversationId, ToolCallEventFrame toolCall) {
        return toolEvent(conversationId, Type.TOOL_RESULT, toolCall);
    }

    private static AiStreamFrame toolEvent(UUID conversationId, Type type, ToolCallEventFrame toolCall) {
        if (toolCall == null) {
            throw new IllegalArgumentException("工具事件不得为 null");
        }
        return new AiStreamFrame(type, requireConversationId(conversationId), null, null, null,
                null, null, toolCall, null, null, null, null, null, null, null, null);
    }

    /**
     * 回合结束。
     *
     * @param messageId    落库后的 assistant 消息 id，供前端与历史消息对齐
     * @param finishReason 模型给出的结束原因（{@code stop}/{@code tool_calls}/{@code length}…），可为 null
     */
    public static AiStreamFrame finished(UUID conversationId, @Nullable UUID messageId,
                                         @Nullable String finishReason) {
        return new AiStreamFrame(Type.FINAL, requireConversationId(conversationId), messageId, null, null,
                null, null, null, null, null, finishReason, null, null, null, null, null);
    }

    /**
     * 错误（tasks 7.2/7.4）。
     *
     * <p>WHY {@code errorCode} 必填：契约把 error 帧的用途定义为"让前端能分派"，
     * 没有码的 error 帧前端只能显示一句纯文本，无法据此跳转到设置页
     * （{@code api_key_missing}）或提示检查网络（{@code model_endpoint_unreachable}）。</p>
     *
     * @param message 面向用户的说明，MUST NOT 含 api key、堆栈或内部路径
     */
    public static AiStreamFrame error(UUID conversationId, ErrorCode errorCode, String message) {
        if (errorCode == null) {
            throw new IllegalArgumentException("错误帧必须携带 error_code");
        }
        return new AiStreamFrame(Type.ERROR, requireConversationId(conversationId), null, null, null,
                null, null, null, errorCode, message, null, null, null, null, null, null);
    }

    /**
     * 上下文超限恢复重置（design D7 "单次恢复"，tasks 9.2）。
     *
     * <p>前端收到此帧后应撤回失败尝试的未完成回答，已执行命令段不撤回。
     * 新 attempt 的结果不与失败片段拼接。</p>
     *
     * @param attemptNumber 第几次尝试（从 1 起；恢复后为 2）
     */
    public static AiStreamFrame attemptReset(UUID conversationId, UUID attemptId, int attemptNumber) {
        if (attemptId == null) {
            throw new IllegalArgumentException("attempt_reset 帧必须携带 attempt_id");
        }
        return new AiStreamFrame(Type.ATTEMPT_RESET, requireConversationId(conversationId), null, null, null,
                null, null, null, null, null, null, null, null, null, attemptId, attemptNumber);
    }

    // ======================================================================
    // 复制式补充（record 不可变，故返回新实例）
    // ======================================================================

    /** 附上本回合实际使用的思考模式，供界面标注"思考中/直接回答"。 */
    public AiStreamFrame withThinkingMode(boolean enabled) {
        return new AiStreamFrame(type, conversationId, messageId, hostId, enabled,
                content, segment, toolCall, errorCode, message, finishReason,
                sessionId, eventSeq, contextNotice, attemptId, attemptNumber);
    }

    /** 附上目标服务器 id（工具调用与错误帧常需要它来定位是哪台机器）。 */
    public AiStreamFrame withHostId(UUID hostId) {
        return new AiStreamFrame(type, conversationId, messageId, hostId, thinkingMode,
                content, segment, toolCall, errorCode, message, finishReason,
                sessionId, eventSeq, contextNotice, attemptId, attemptNumber);
    }

    /** 附上关联消息 id（增量帧落库后回填，便于前端把增量与历史消息对上）。 */
    public AiStreamFrame withMessageId(UUID messageId) {
        return new AiStreamFrame(type, conversationId, messageId, hostId, thinkingMode,
                content, segment, toolCall, errorCode, message, finishReason,
                sessionId, eventSeq, contextNotice, attemptId, attemptNumber);
    }

    private static UUID requireConversationId(UUID conversationId) {
        if (conversationId == null) {
            // conversation_id 是契约 required，也是前端路由增量的唯一依据；
            // 缺了它，增量会被渲染到错误的会话里，或在多会话界面上直接消失
            throw new IllegalArgumentException("conversationId 不得为 null");
        }
        return conversationId;
    }
}

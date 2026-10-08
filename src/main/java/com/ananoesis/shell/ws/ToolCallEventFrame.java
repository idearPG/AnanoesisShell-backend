package com.ananoesis.shell.ws;

import java.util.Map;
import java.util.UUID;

import org.springframework.lang.Nullable;

import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * {@code tool_call} 事件明细（asyncapi.yaml {@code components/schemas/ToolCallEvent}）。
 *
 * <p>承载 ai-agent spec「操作透明性」要求的三件事：调了哪个工具、用了什么参数、
 * 结果如何（或"用户已拒绝"）。同一形状既用于 {@code ai_stream(type=tool_call)}
 * 也用于 {@code ai_stream(type=tool_result)}——契约就是这么设计的：
 * 前者填 {@code auto}/{@code approval_id}，后者填 {@code result_status}/{@code result}。</p>
 *
 * <p>WHY 契约对本 schema <b>没有</b> required，因此这里所有组件都标 {@link Nullable}：
 * 出站时"该填的字段都填了"由静态工厂保证（工厂不接受 null 语义参数），
 * 而不是靠 record 组件的非空声明。若在 Java 侧声明非空，
 * {@code WsContractAlignmentTest} 的"出站 required 恰为无 @Nullable 的组件"断言就会与契约不符。</p>
 *
 * <p>WHY 也要 {@code @JsonIgnoreProperties(ignoreUnknown = true)}：本类会被嵌套在入站帧
 * {@link AiStreamFrame} 里反序列化。父类上的 ignoreUnknown <b>不会</b>下沉到嵌套类型——
 * 契约日后给 {@code ToolCallEvent} 加一个字段，父帧能解析、子对象照样抛异常。
 * 容忍度必须逐层声明。</p>
 *
 * <p>WHY {@code result_status} 复用生成物 {@link ToolResultStatus} 而不是自己写一个枚举：
 * asyncapi 与 openapi 各写了一份同名枚举，但取值一致（{@code success/rejected/
 * execution_timeout/output_truncated/error}）。复用生成物意味着 openapi 那份改了取值，
 * 这里编译期就出错；asyncapi 那份是否跟着改，由
 * {@code WsContractAlignmentTest#generatedToolResultStatusMatchesAsyncapi} 守着。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallEventFrame(
        @Nullable ToolName toolName,
        @Nullable Map<String, Object> toolParams,
        @Nullable Boolean auto,
        @Nullable UUID approvalId,
        @Nullable ToolResultStatus resultStatus,
        @Nullable String result,
        @Nullable UUID callId,
        @Nullable UUID commandId,
        @Nullable UUID runId) {

    /**
     * 只读工具的自动执行事件（{@code auto=true}，无 {@code approval_id}）。
     *
     * @param toolParams 工具参数；null 视为空表（契约声明为 object，前端按对象渲染）
     */
    public static ToolCallEventFrame autoExecuted(ToolName toolName, Map<String, Object> toolParams) {
        return new ToolCallEventFrame(requireAuto(toolName), params(toolParams), Boolean.TRUE, null, null, null,
                null, null, null);
    }

    /**
     * 副作用工具转入审批的事件（{@code auto=false} + {@code approval_id}）。
     *
     * <p>WHY {@code approval_id} 必填：前端要靠它把弹框与后续 {@code approval_response}
     * 对上号。缺了它，用户点"批准"时前端无从填 {@code approval_id}，
     * 症状是"弹框点了没反应"。</p>
     */
    public static ToolCallEventFrame pendingApproval(ToolName toolName, Map<String, Object> toolParams,
                                                     UUID approvalId) {
        if (approvalId == null) {
            throw new IllegalArgumentException("转入审批的工具事件必须携带 approvalId");
        }
        return new ToolCallEventFrame(requireGated(toolName), params(toolParams), Boolean.FALSE,
                approvalId, null, null, null, null, null);
    }

    /**
     * 工具执行结果事件。
     *
     * @param auto       本次调用当初是否自动执行（保持与 tool_call 阶段一致，便于前端配对渲染）
     * @param result     结果文本；被拒绝时为「用户已拒绝」
     */
    public static ToolCallEventFrame result(ToolName toolName, Map<String, Object> toolParams, boolean auto,
                                            @Nullable UUID approvalId, ToolResultStatus status,
                                            @Nullable String result) {
        if (status == null) {
            throw new IllegalArgumentException("工具结果必须携带 result_status");
        }
        return new ToolCallEventFrame(requireNonNull(toolName), params(toolParams), auto, approvalId, status, result,
                null, null, null);
    }

    private static ToolName requireAuto(ToolName toolName) {
        requireNonNull(toolName);
        if (!toolName.autoExecuted()) {
            // 副作用工具走这条路径就是绕过审批闸门，属于必须立刻暴露的编码错误
            throw new IllegalArgumentException("工具 " + toolName.getValue() + " 有副作用，不得标记为自动执行");
        }
        return toolName;
    }

    private static ToolName requireGated(ToolName toolName) {
        requireNonNull(toolName);
        if (toolName.autoExecuted()) {
            throw new IllegalArgumentException("只读工具 " + toolName.getValue() + " 不经审批，不应携带 approval_id");
        }
        return toolName;
    }

    private static ToolName requireNonNull(ToolName toolName) {
        if (toolName == null) {
            throw new IllegalArgumentException("toolName 不得为 null");
        }
        return toolName;
    }

    /** WHY 归一成非 null 空表：契约把 tool_params 声明为 object，发 null 会让前端渲染出 "null"。 */
    private static Map<String, Object> params(Map<String, Object> toolParams) {
        return toolParams == null ? Map.of() : Map.copyOf(toolParams);
    }
}

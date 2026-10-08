package com.ananoesis.shell.ws;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.lang.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * {@code approval_request} 消息载荷（asyncapi.yaml {@code components/schemas/ApprovalRequest}）。
 *
 * <p>对应 command-approval spec「执行前人工审批」：智能体提议执行有副作用的命令时，
 * 后端生成 {@code approval_id} 并推送本帧，前端据此弹框呈现<b>待执行命令</b>与
 * <b>智能体分析</b>，由用户决定批准或取消。</p>
 *
 * <h2>WHY 出站帧把契约 required 表达成"组件非空"</h2>
 * <p>本帧只下行，后端是生产者，契约的 {@code required} 就是它自己的义务。
 * 在 record 组件上省略 {@link Nullable}，意味着"能构造出 {@code approval_id=null} 的审批帧"
 * 这条代码路径在编译期就不存在——比运行时校验更强。
 * 该对应关系由 {@code WsContractAlignmentTest#approvalRequestRequiredMatchesContract} 钉住。</p>
 *
 * <h2>WHY {@code timeout_seconds} 下发的是<b>实值</b>而不是让前端读设置</h2>
 * <p>TRACEABILITY <b>Q5</b> 的裁定：审批时限存在 {@code settings} 键值里、不经 REST 暴露，
 * 前端拿不到。若不下发，前端只能自己硬编码一个倒计时，一旦用户在设置里改了时限，
 * 弹框显示的秒数与后端真实的超时时刻就会分叉——用户眼看着倒计时还剩 30 秒，
 * 命令却已经被后端按"取消"处理了。</p>
 *
 * <h2>安全</h2>
 * <p>{@code ai_analysis} 与 {@code tool_params} MUST NOT 含明文凭据（契约原文要求）。
 * 命令本身可能含用户写进去的秘密（如 {@code mysql -p'xxx'}），
 * 那是用户自己的输入、且必须在弹框里如实呈现才能让他判断——
 * 但后端 MUST NOT 在此基础上追加任何解密出来的凭据。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalRequestFrame(
        UUID approvalId,
        UUID conversationId,
        @Nullable UUID sessionId,
        @Nullable Long eventSeq,
        UUID hostId,
        @Nullable String hostLabel,
        ToolName toolName,
        Map<String, Object> toolParams,
        @Nullable String command,
        String aiAnalysis,
        Integer version,
        @Nullable Integer timeoutSeconds,
        @Nullable OffsetDateTime createdAt) {

    /**
     * 构造审批请求帧。
     *
     * @param toolParams     工具参数；null 归一为空表（契约声明为 object）
     * @param command        便捷展示字段，等价于 {@code tool_params.command}；可为 null
     * @param timeoutSeconds 本次审批实际使用的等待时限（秒），取自 settings
     */
    public static ApprovalRequestFrame of(UUID approvalId, UUID conversationId, UUID hostId,
                                          @Nullable String hostLabel, ToolName toolName,
                                          @Nullable Map<String, Object> toolParams,
                                          @Nullable String command, String aiAnalysis,
                                          Integer version,
                                          @Nullable Integer timeoutSeconds,
                                          @Nullable OffsetDateTime createdAt) {
        if (approvalId == null || conversationId == null || hostId == null) {
            throw new IllegalArgumentException("approvalId/conversationId/hostId 均不得为 null");
        }
        if (toolName == null) {
            throw new IllegalArgumentException("toolName 不得为 null");
        }
        if (toolName.autoExecuted()) {
            // 只读工具不经审批（TRACEABILITY Q8：approvals 表仅审副作用命令）；
            // 给它发审批帧会让用户对着一个"其实不需要批准"的弹框做无意义的决定
            throw new IllegalArgumentException("只读工具 " + toolName.getValue() + " 不应产生审批请求");
        }
        if (aiAnalysis == null) {
            throw new IllegalArgumentException("aiAnalysis 不得为 null；'模型没给分析'请用空串表达");
        }
        if (version == null) {
            throw new IllegalArgumentException("version 不得为 null；V2 契约要求审批版本必填");
        }
        return new ApprovalRequestFrame(approvalId, conversationId, null, null, hostId, hostLabel, toolName,
                toolParams == null ? Map.of() : Map.copyOf(toolParams), command, aiAnalysis,
                version, timeoutSeconds, createdAt);
    }
}

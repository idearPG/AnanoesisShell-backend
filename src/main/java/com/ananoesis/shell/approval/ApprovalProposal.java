package com.ananoesis.shell.approval;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.lang.Nullable;

import com.ananoesis.shell.ws.ToolName;

/**
 * 一次<b>待审批</b>的工具调用提案（command-approval spec「执行前人工审批」的输入）。
 *
 * <p>WHY 提案里<b>没有</b> {@code approvalId} 与 {@code timeoutSeconds}：
 * 这两个都由 {@link ApprovalGate} 在受理时产生——id 必须是全局唯一且由闸门登记，
 * 时限必须取自 {@code settings} 的<b>当前</b>值（TRACEABILITY Q5）。
 * 若允许调用方自带，就会出现"智能体给自己签了一个 10 分钟的审批时限"
 * 或"两个并发回合复用同一个 approval_id"这类只有压测才暴露的缺陷。</p>
 *
 * <p>WHY 在构造时就校验而不等到发帧：本记录会被写进 {@code approvals} 审计行，
 * 也会在 {@code /ws/approval} 上广播。缺 {@code hostId} 的提案一旦落库，
 * 审计里就出现一条"不知道在哪台机器上执行"的记录——那正是 spec 要求必须有的要素。
 * 在入口拦住，比在三个下游各自判空更可靠。</p>
 *
 * <h2>安全</h2>
 * <p>{@code aiAnalysis} 与 {@code toolParams} MUST NOT 含明文凭据（契约与 spec 双重要求）。
 * 本类的 {@link #toString()} 刻意<b>不</b>打印这两个字段与 {@code command}：
 * 命令原文可能带用户内联的口令（{@code mysql -p'xxx'}），而 toString 是最容易
 * 被日志框架顺手调用的方法。</p>
 *
 * @param conversationId 发起该工具调用的会话
 * @param hostId         目标服务器（审计要素之一，不得为空）
 * @param hostLabel      目标服务器的展示名，可为 null（主机已被删除时）
 * @param toolName       工具名；只能是<b>副作用</b>工具
 * @param toolParams     工具入参（原样呈现给用户判断）
 * @param command        便捷展示字段，等价于 {@code toolParams.get("command")}
 * @param aiAnalysis     智能体给出的风险分析与操作理由
 * @param messageId      承载该工具调用的 assistant 消息 id，可为 null
 */
public record ApprovalProposal(
        UUID conversationId,
        UUID hostId,
        @Nullable String hostLabel,
        ToolName toolName,
        Map<String, Object> toolParams,
        @Nullable String command,
        String aiAnalysis,
        @Nullable UUID messageId) {

    public ApprovalProposal {
        Objects.requireNonNull(conversationId, "conversationId 不得为 null");
        Objects.requireNonNull(hostId, "hostId 不得为 null（审计要素「目标服务器」）");
        Objects.requireNonNull(toolName, "toolName 不得为 null");
        if (toolName.autoExecuted()) {
            // TRACEABILITY Q8：approvals 表仅审副作用命令。
            // 给只读工具走审批，用户会对着一个"其实不需要批准"的弹框做无意义的决定，
            // 更糟的是审计表会被 list_dir 之类的调用淹没，真正的危险命令反而查不出来
            throw new IllegalArgumentException("只读工具 " + toolName.getValue() + " 不经审批闸门");
        }
        Objects.requireNonNull(aiAnalysis, "aiAnalysis 不得为 null；'模型没给分析'请用空串表达");
        toolParams = toolParams == null ? Map.of() : Map.copyOf(toolParams);
    }

    /**
     * WHY 覆写：record 默认的 {@code toString()} 会把全部组件打出来，
     * 包括命令原文与模型分析。这两个字段可能含用户在命令行里内联的口令。
     */
    @Override
    public String toString() {
        return "ApprovalProposal{conversationId=" + conversationId + ", hostId=" + hostId
                + ", toolName=" + toolName.getValue() + ", params=" + toolParams.size() + "}";
    }
}

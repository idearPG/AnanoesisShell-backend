package com.ananoesis.shell.approval;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ApprovalDecision;
import com.ananoesis.shell.ws.ToolName;

/**
 * 审批子系统的<b>取值域换算</b>与<b>入口守卫</b>（tasks 8.1 / 8.2 / 8.3 / 8.5）。
 *
 * <h2>WHY 把这两个类放在同一个测试里</h2>
 * <p>{@link ApprovalOutcome} 与 {@link ApprovalProposal} 是同一条链路上的两端：
 * 提案进来（带守卫），结局出去（带三套取值域的换算）。它们共同回答一个问题——
 * <b>"契约 ↔ 数据库 ↔ WebSocket"这三套字面值之间只有一个真相来源"</b>。
 * 分开测会让"cancelled 在库里叫 rejected"这条知识散落在两个文件里，
 * 而它恰恰是最容易被后来者改错的一条。</p>
 *
 * <p>三套取值域：</p>
 * <ul>
 *   <li>WebSocket（{@code ApprovalResponseFrame.Decision}）：{@code approve} / {@code cancel}——用户的两个动作；</li>
 *   <li>数据库（{@code approvals.decision}）：{@code pending} / {@code approved} / {@code rejected} / {@code timeout}；</li>
 *   <li>REST 契约（{@code ApprovalDecision}）：{@code approved} / {@code cancelled} / {@code timed_out}。</li>
 * </ul>
 * <p>注意"用户取消"在库里叫 {@code rejected}、在契约里叫 {@code cancelled}——
 * 名字对不上是历史遗留，换算错了不会有任何编译期或运行期报错，只会让审计说谎。</p>
 */
class ApprovalMappingTest {

    // ======================================================================
    // ApprovalOutcome：三套取值域的换算
    // ======================================================================

    @Test
    @DisplayName("只有 APPROVED 放行命令；取消与超时都不放行")
    void onlyApprovedReleasesTheCommand() {
        assertThat(ApprovalOutcome.APPROVED.approved()).isTrue();
        assertThat(ApprovalOutcome.CANCELLED.approved()).isFalse();
        assertThat(ApprovalOutcome.TIMED_OUT.approved()).isFalse();
    }

    @Test
    @DisplayName("库内 decision 字面值：approved / rejected / timeout（注意 cancelled 在库里叫 rejected）")
    void dbDecisionUsesTheSchemaVocabulary() {
        assertThat(ApprovalOutcome.APPROVED.dbDecision()).isEqualTo("approved");
        assertThat(ApprovalOutcome.CANCELLED.dbDecision()).isEqualTo("rejected");
        assertThat(ApprovalOutcome.TIMED_OUT.dbDecision()).isEqualTo("timeout");
    }

    @Test
    @DisplayName("decided_by 区分「人不想要」与「人没来得及看」：超时记 system，其余记 user")
    void decidedByAttributesTimeoutToTheSystem() {
        // WHY 在意这个区分：spec 要求审计能分辨明确否决与流程缺陷。
        // 两者在契约侧都是"没执行"，但责任归属完全不同——
        // 前者是用户的意思，后者说明时限设得太短或用户离开了
        assertThat(ApprovalOutcome.APPROVED.dbDecidedBy()).isEqualTo("user");
        assertThat(ApprovalOutcome.CANCELLED.dbDecidedBy()).isEqualTo("user");
        assertThat(ApprovalOutcome.TIMED_OUT.dbDecidedBy()).isEqualTo("system");
    }

    @Test
    @DisplayName("契约侧 decision：approved / cancelled / timed_out（三个取值一一对应，无遗漏）")
    void contractDecisionCoversAllThreeOutcomes() {
        assertThat(ApprovalOutcome.APPROVED.toContract()).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(ApprovalOutcome.CANCELLED.toContract()).isEqualTo(ApprovalDecision.CANCELLED);
        assertThat(ApprovalOutcome.TIMED_OUT.toContract()).isEqualTo(ApprovalDecision.TIMED_OUT);

        // WHY 断言"契约枚举被穷尽"：ApprovalAuditService#dbDecisionOf 靠反向遍历
        // 把契约取值换回库内字面值，若契约新增第四个取值而这里没跟上，
        // 那次遍历会走到 throw IllegalStateException —— 变成 500
        assertThat(ApprovalDecision.values()).hasSize(ApprovalOutcome.values().length);
    }

    @Test
    @DisplayName("从库内字面值反查：三种结局都能还原，pending 与未知值一律为 null")
    void fromDbDecisionRoundTrips() {
        for (ApprovalOutcome outcome : ApprovalOutcome.values()) {
            assertThat(ApprovalOutcome.fromDbDecision(outcome.dbDecision()))
                    .as("%s 必须能由自己的库内字面值还原", outcome)
                    .isEqualTo(outcome);
        }

        // WHY pending 必须是 null 而不是某个结局：契约的 ApprovalDecision 没有 pending，
        // 硬塞一个取值就是让审计说谎。null 让 ApprovalAuditService 能把还在等待的行
        // 排除在 GET /api/approvals 之外（"当前有哪些待审批"是实时状态，走 WebSocket）
        assertThat(ApprovalOutcome.fromDbDecision("pending")).isNull();
        assertThat(ApprovalOutcome.fromDbDecision("cancelled")).isNull();
        assertThat(ApprovalOutcome.fromDbDecision("")).isNull();
        assertThat(ApprovalOutcome.fromDbDecision(null)).isNull();
    }

    // ======================================================================
    // ApprovalProposal：入口守卫（TRACEABILITY Q8）
    // ======================================================================

    @Test
    @DisplayName("Q8：只读工具不得进入审批闸门——三个只读工具全部被构造器拒绝")
    void readOnlyToolsCannotBeProposed() {
        for (ToolName readOnly : new ToolName[] {ToolName.LIST_DIR, ToolName.READ_FILE, ToolName.SYSTEM_INFO}) {
            assertThat(readOnly.autoExecuted()).as("%s 应为只读工具", readOnly).isTrue();
            assertThatThrownBy(() -> proposal(readOnly, Map.of("path", "/var/log")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(readOnly.getValue())
                    .hasMessageContaining("不经审批闸门");
        }
    }

    @Test
    @DisplayName("副作用工具 run_command 是唯一可被提案的工具")
    void runCommandIsTheOnlyProposableTool() {
        assertThat(ToolName.RUN_COMMAND.autoExecuted()).isFalse();
        assertThat(proposal(ToolName.RUN_COMMAND, Map.of("command", "systemctl restart nginx")).toolName())
                .isEqualTo(ToolName.RUN_COMMAND);
    }

    @Test
    @DisplayName("审计要素缺失即在入口拦住：conversationId / hostId / toolName / aiAnalysis 不得为 null")
    void auditEssentialsAreRequiredAtConstruction() {
        UUID conversationId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        assertThatThrownBy(() -> new ApprovalProposal(null, hostId, "标签",
                ToolName.RUN_COMMAND, Map.of(), "uptime", "分析", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId");

        // WHY hostId 尤其不能为空：它一旦落库，审计里就出现一条
        // "不知道在哪台机器上执行"的记录，而目标服务器正是 spec 列明的审计要素
        assertThatThrownBy(() -> new ApprovalProposal(conversationId, null, "标签",
                ToolName.RUN_COMMAND, Map.of(), "uptime", "分析", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hostId")
                .hasMessageContaining("目标服务器");

        assertThatThrownBy(() -> new ApprovalProposal(conversationId, hostId, "标签",
                null, Map.of(), "uptime", "分析", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("toolName");

        assertThatThrownBy(() -> new ApprovalProposal(conversationId, hostId, "标签",
                ToolName.RUN_COMMAND, Map.of(), "uptime", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("aiAnalysis");
    }

    @Test
    @DisplayName("aiAnalysis 允许空串：「模型没给分析」与「分析为 null」是两件事")
    void emptyAnalysisIsAllowedButNullIsNot() {
        ApprovalProposal empty = new ApprovalProposal(UUID.randomUUID(), UUID.randomUUID(), null,
                ToolName.RUN_COMMAND, null, "reboot", "", null);
        assertThat(empty.aiAnalysis()).isEmpty();

        ApprovalProposal proposal = proposal(ToolName.RUN_COMMAND, Map.of("command", "reboot"));
        assertThat(proposal.aiAnalysis()).isEqualTo("为了排查");
    }

    @Test
    @DisplayName("toolParams 为 null 时归一为空表，且外部改动原表不会影响提案")
    void toolParamsAreDefensivelyCopied() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("command", "df -hP");
        ApprovalProposal proposal = new ApprovalProposal(UUID.randomUUID(), UUID.randomUUID(), "标签",
                ToolName.RUN_COMMAND, mutable, "df -hP", "分析", null);

        mutable.put("command", "rm -rf /");

        // WHY 必须拷贝：提案会被写进审计行、也会在 /ws/approval 上广播给前端弹框。
        // 若与调用方共享同一个 Map，用户在弹框里看到的命令与实际执行的命令可能不一致——
        // 那等于让他批准了一件他没同意的事
        assertThat(proposal.toolParams()).containsEntry("command", "df -hP").hasSize(1);
        assertThatThrownBy(() -> proposal.toolParams().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null 的 toolParams 归一为空表而不是留下 null")
    void nullToolParamsBecomeEmptyMap() {
        ApprovalProposal proposal = new ApprovalProposal(UUID.randomUUID(), UUID.randomUUID(), null,
                ToolName.RUN_COMMAND, null, "uptime", "", null);

        assertThat(proposal.toolParams()).isEmpty();
        assertThat(proposal.hostLabel()).isNull();
        assertThat(proposal.messageId()).isNull();
    }

    // ======================================================================
    // 安全：toString 脱敏
    // ======================================================================

    @Test
    @DisplayName("toString 不含命令原文、AI 分析与参数明细（这三处最可能带用户内联的口令）")
    void toStringDoesNotLeakCommandOrAnalysis() {
        ApprovalProposal proposal = new ApprovalProposal(
                UUID.randomUUID(), UUID.randomUUID(), "生产数据库",
                ToolName.RUN_COMMAND,
                Map.of("command", "mysql -uroot -pSup3r-S3cret-P@ssw0rd -e 'drop table users'"),
                "mysql -uroot -pSup3r-S3cret-P@ssw0rd -e 'drop table users'",
                "需要清库，口令是 Sup3r-S3cret-P@ssw0rd",
                UUID.randomUUID());

        String printed = proposal.toString();

        // WHY 必须覆写：record 默认的 toString 会把全部组件打出来，
        // 而 toString 是最容易被日志框架顺手调用的方法
        assertThat(printed).doesNotContain("Sup3r-S3cret").doesNotContain("drop table");
        assertThat(printed)
                .contains("toolName=run_command")
                .contains("hostId=" + proposal.hostId())
                .contains("params=1");
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private static ApprovalProposal proposal(ToolName toolName, Map<String, Object> params) {
        return new ApprovalProposal(UUID.randomUUID(), UUID.randomUUID(), "测试机",
                toolName, params, "systemctl restart nginx", "为了排查", null);
    }
}

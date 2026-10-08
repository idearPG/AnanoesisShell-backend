package com.ananoesis.shell.approval;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.ananoesis.shell.ssh.ExecOutcome;

/**
 * {@link CommandExecution} 的三向映射与脱敏（tasks 8.2 / 8.4 / 8.5）。
 *
 * <p>WHY 单独测这个 record：它是 SSH 层原始事实到<b>三个下游</b>的唯一翻译点——
 * 契约 {@code ToolResultStatus}、库里 {@code execution_status} 列、回喂模型的文本。
 * 三套取值域彼此不同（库里有 {@code failed}，契约里没有；契约里有 {@code rejected}，
 * 但被拒绝的命令压根没有执行状态），映射错一处就会出现
 * "审计说超时、模型看到成功"这类自相矛盾的记录，而且没有任何编译期提示。</p>
 *
 * <p>纯单元测试，不启 Spring 上下文：本类不碰数据库也不碰网络。</p>
 */
class CommandExecutionTest {

    // ======================================================================
    // 构造与归一化
    // ======================================================================

    @Test
    @DisplayName("由 ExecOutcome 构造时如实搬运五个字段（丢掉耗时，那不是命令的事实）")
    void ofCopiesFactsFromExecOutcome() {
        ExecOutcome outcome = new ExecOutcome(3, "输出", "错误", true, false, 1234L);

        CommandExecution execution = CommandExecution.of(outcome);

        assertThat(execution.exitCode()).isEqualTo(3);
        assertThat(execution.stdout()).isEqualTo("输出");
        assertThat(execution.stderr()).isEqualTo("错误");
        assertThat(execution.truncated()).isTrue();
        assertThat(execution.timedOut()).isFalse();
    }

    @Test
    @DisplayName("null 的 stdout/stderr 归一为空串：下游拼接与长度计算都不必再判空")
    void nullStreamsBecomeEmpty() {
        CommandExecution execution = new CommandExecution(0, null, null, false, false);

        assertThat(execution.stdout()).isEmpty();
        assertThat(execution.stderr()).isEmpty();
    }

    // ======================================================================
    // status()：契约 ToolResultStatus
    // ======================================================================

    @Test
    @DisplayName("正常结束 → success；非 0 退出码**仍然**是 success")
    void nonZeroExitIsStillSuccess() {
        assertThat(execution(0, false, false).status()).isEqualTo(ToolResultStatus.SUCCESS);

        // WHY 这条最要紧：契约的 ToolResultStatus 没有 "failed" 一档。
        // grep 无匹配（exit=1）、ls 一个不存在的目录（exit=2）都是工具**正常工作**
        // 之后得到的事实。报成 error 会让模型以为是基础设施故障而放弃这条排查线索。
        assertThat(execution(1, false, false).status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(execution(127, false, false).status()).isEqualTo(ToolResultStatus.SUCCESS);
    }

    @Test
    @DisplayName("超时 → execution_timeout（tasks 8.4：命令执行超时中断）")
    void timedOutMapsToExecutionTimeout() {
        assertThat(execution(ExecOutcome.EXIT_CODE_UNKNOWN, false, true).status())
                .isEqualTo(ToolResultStatus.EXECUTION_TIMEOUT);
    }

    @Test
    @DisplayName("截断 → output_truncated（tasks 8.4：输出长度上限）")
    void truncatedMapsToOutputTruncated() {
        assertThat(execution(0, true, false).status()).isEqualTo(ToolResultStatus.OUTPUT_TRUNCATED);
    }

    @Test
    @DisplayName("同时超时与截断时，超时压过截断：被中断的命令其输出必然也不完整")
    void timeoutWinsOverTruncation() {
        assertThat(execution(143, true, true).status()).isEqualTo(ToolResultStatus.EXECUTION_TIMEOUT);
    }

    // ======================================================================
    // dbExecutionStatus()：approvals.execution_status 列
    // ======================================================================

    @Test
    @DisplayName("库内取值域与契约不同：success/failed/timeout/truncated（受 DDL CHECK 约束）")
    void dbStatusUsesTheSchemaVocabulary() {
        assertThat(execution(0, false, false).dbExecutionStatus()).isEqualTo("success");
        assertThat(execution(1, false, false).dbExecutionStatus()).isEqualTo("failed");
        assertThat(execution(0, true, false).dbExecutionStatus()).isEqualTo("truncated");
        assertThat(execution(-1, false, true).dbExecutionStatus()).isEqualTo("timeout");
    }

    @Test
    @DisplayName("退出码缺失时按 failed 记：宁可保守，也不要让审计里出现一条来历不明的 success")
    void missingExitCodeIsRecordedAsFailed() {
        assertThat(new CommandExecution(null, "", "", false, false).dbExecutionStatus()).isEqualTo("failed");
        assertThat(new CommandExecution(null, "", "", false, false).status()).isEqualTo(ToolResultStatus.SUCCESS);
    }

    @Test
    @DisplayName("同一事实在两套取值域里的换算不冲突（契约 success ↔ 库 failed 是允许的）")
    void contractAndDbVocabulariesAreIndependent() {
        CommandExecution nonZeroExit = execution(2, false, false);

        // 这正是两个方法必须分开的原因：契约回 success（工具没坏），库里记 failed（命令没成功）
        assertThat(nonZeroExit.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(nonZeroExit.dbExecutionStatus()).isEqualTo("failed");
    }

    // ======================================================================
    // feedback()：回喂给模型的文本
    // ======================================================================

    @Test
    @DisplayName("回喂文本以 exit= 打头，并按 stdout/stderr 分节")
    void feedbackCarriesExitCodeAndBothStreams() {
        String feedback = new CommandExecution(0, "hello\n", "warn\n", false, false).feedback();

        assertThat(feedback).isEqualTo("""
                exit=0
                --- stdout ---
                hello
                --- stderr ---
                warn
                """);
    }

    @Test
    @DisplayName("退出码缺失时写成 exit=? 而不是 exit=null：模型会把 null 当成一个真实的码")
    void feedbackRendersMissingExitCodeAsQuestionMark() {
        assertThat(new CommandExecution(null, "", "", false, false).feedback())
                .startsWith("exit=?\n");
    }

    @Test
    @DisplayName("空流写成「(空)」：让模型能区分「没有输出」与「输出被吞了」")
    void emptyStreamIsRenderedExplicitly() {
        String feedback = new CommandExecution(1, "", "", false, false).feedback();

        assertThat(feedback).isEqualTo("""
                exit=1
                --- stdout ---
                (空)
                --- stderr ---
                (空)
                """);
    }

    @Test
    @DisplayName("超时与截断在回喂文本里显式标注，且标注出现在输出之前")
    void incompleteOutputIsLabeledBeforeTheBody() {
        String feedback = new CommandExecution(-1, "半截日志", "", true, true).feedback();

        assertThat(feedback).isEqualTo("""
                exit=-1
                [执行超时，命令已被中断，以下输出可能不完整]
                [输出超过上限已被截断，以下内容不是完整结果]
                --- stdout ---
                半截日志
                --- stderr ---
                (空)
                """);
        // WHY 断言顺序：模型是顺序阅读的，标注若在正文之后，
        // 它可能已经基于不完整的输出下了结论
        assertThat(feedback.indexOf("[执行超时"))
                .isLessThan(feedback.indexOf("--- stdout ---"));
    }

    @Test
    @DisplayName("stdout 未以换行结尾时补一个：避免下一节的分隔标记与输出挤在同一行")
    void trailingNewlineIsAppendedWhenMissing() {
        assertThat(new CommandExecution(0, "no-newline", "", false, false).feedback())
                .contains("no-newline\n--- stderr ---");
    }

    @Test
    @DisplayName("超长输出按字符再收一次口，并说明省略了多少（tasks 8.4）")
    void oversizedStreamIsTrimmedWithAnExplicitOmissionNote() {
        String huge = "x".repeat(10_000);

        String feedback = new CommandExecution(0, huge, "", false, false).feedback();

        // WHY 要在 64KiB 的执行上限之外再裁一次：模型上下文通常只有几万 token，
        // 把整段输出灌回去会挤掉对话历史，让智能体"忘了"用户最初的问题
        assertThat(feedback).contains("[... 已省略 2000 字符 ...]");
        assertThat(feedback).contains("x".repeat(8_000));
        assertThat(feedback).doesNotContain("x".repeat(8_001));
        assertThat(feedback.length()).isLessThan(9_000);
    }

    @Test
    @DisplayName("恰好等于上限时不裁剪：裁剪提示只在真的丢了内容时才出现")
    void exactlyAtLimitIsNotTrimmed() {
        String exact = "y".repeat(8_000);

        String feedback = new CommandExecution(0, exact, "", false, false).feedback();

        assertThat(feedback).contains(exact).doesNotContain("已省略");
    }

    // ======================================================================
    // 安全：toString 脱敏
    // ======================================================================

    @Test
    @DisplayName("toString 不含 stdout/stderr 正文，只含字节数（命令输出可能带口令或 token）")
    void toStringDoesNotLeakCommandOutput() {
        String secretStdout = "MYSQL_PWD=Sup3r-S3cret-P@ssw0rd";
        String secretStderr = "token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9-leaked";
        CommandExecution execution = new CommandExecution(0, secretStdout, secretStderr, true, false);

        String printed = execution.toString();

        // WHY 必须覆写：record 的默认 toString 会把两个流全文打出来，
        // 而这条记录在 ApprovedCommandRunner 与 ApprovalAuditService 里都会被日志顺手打印
        assertThat(printed).doesNotContain("Sup3r-S3cret").doesNotContain("eyJhbGciOi");
        assertThat(printed)
                .contains("exitCode=0")
                .contains("stdoutBytes=" + secretStdout.length())
                .contains("stderrBytes=" + secretStderr.length())
                .contains("truncated=true")
                .contains("timedOut=false");
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private static CommandExecution execution(int exitCode, boolean truncated, boolean timedOut) {
        return new CommandExecution(exitCode, "输出", "", truncated, timedOut);
    }
}

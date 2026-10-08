package com.ananoesis.shell.approval;

import org.springframework.lang.Nullable;

import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.ananoesis.shell.ssh.ExecOutcome;

/**
 * 一条<b>已获批准并执行完毕</b>的命令的结果（tasks 8.2 / 8.4）。
 *
 * <p>它是 {@link ExecOutcome}（SSH 层的原始事实）到两个下游视角的<b>唯一</b>翻译点：</p>
 * <ul>
 *   <li>{@link #status()} —— 契约 {@code ToolResultStatus}，用于 {@code ai_stream(tool_result)}
 *       与 {@code approvals.execution.status}；</li>
 *   <li>{@link #dbExecutionStatus()} —— {@code approvals.execution_status} 列的字面值
 *       （受 DDL CHECK 约束，取值域与契约<b>不同</b>）；</li>
 *   <li>{@link #feedback()} —— 回喂给模型的纯文本。</li>
 * </ul>
 *
 * <h2>WHY 非 0 退出码仍然算 {@code SUCCESS}</h2>
 * <p>契约的 {@code ToolResultStatus} 只有 {@code success / rejected / execution_timeout /
 * output_truncated / error} 五个取值，没有"命令失败"。这不是遗漏而是语义划分：
 * {@code error} 表示<b>工具本身</b>坏了（连不上、通道断了），而"命令跑完了但返回 1"
 * 是工具<b>正常工作</b>后得到的一个事实。把它报成 {@code error} 会让模型以为
 * 是基础设施故障而放弃这条排查线索；报成 {@code success} 并在 {@link #feedback()} 里
 * 如实带上 {@code exit=1} 与 stderr，模型才能继续推理。
 * {@code ExecOutcome} 的类注释预留了"或按上层策略 error"的口子，本类就是那个上层策略。</p>
 *
 * <h2>WHY 超时与截断优先级高于退出码</h2>
 * <p>命令被中断时退出码是<b>不可信</b>的（可能是 -1，也可能是信号导致的 143）；
 * 截断时输出是<b>不完整</b>的。这两种情况下若还报 {@code success}，
 * 模型会拿着一段被砍掉一半的日志继续分析，得出错误结论且毫无察觉。
 * spec 明确要求这两种情形"明确标注"，因此它们必须压过退出码。</p>
 *
 * @param exitCode  远端退出码；命令未正常结束时为 {@link ExecOutcome#EXIT_CODE_UNKNOWN}
 * @param stdout    标准输出（已按上限截断）
 * @param stderr    标准错误（已按上限截断）
 * @param truncated 输出是否因超过 {@code run_command.max_output_bytes} 而被截断
 * @param timedOut  是否因超过 {@code run_command.timeout.seconds} 而被中断
 */
public record CommandExecution(
        @Nullable Integer exitCode,
        String stdout,
        String stderr,
        boolean truncated,
        boolean timedOut) {

    /** 回喂文本里 stdout/stderr 各自的最大字符数。WHY 见 {@link #feedback()}。 */
    private static final int FEEDBACK_STREAM_LIMIT = 8_000;

    public CommandExecution {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    /** 由 SSH 层的原始结果构造。 */
    public static CommandExecution of(ExecOutcome outcome) {
        return new CommandExecution(outcome.exitCode(), outcome.stdout(), outcome.stderr(),
                outcome.truncated(), outcome.timedOut());
    }

    /**
     * @return 契约 {@code ToolResultStatus}
     */
    public ToolResultStatus status() {
        if (timedOut) {
            return ToolResultStatus.EXECUTION_TIMEOUT;
        }
        if (truncated) {
            return ToolResultStatus.OUTPUT_TRUNCATED;
        }
        return ToolResultStatus.SUCCESS;
    }

    /**
     * @return {@code approvals.execution_status} 列的字面值（DDL CHECK 限定六个取值）
     *
     * <p>WHY 与 {@link #status()} 分开：库里存的是<b>执行过程</b>的事实
     * （成功/失败/超时/截断），契约回的是<b>面向模型与界面</b>的分类。
     * 两者取值域不同（库里有 {@code failed}，契约里没有；契约里有 {@code rejected}，
     * 但被拒绝的命令根本不会有执行状态），硬套一个枚举会丢失信息。</p>
     */
    public String dbExecutionStatus() {
        if (timedOut) {
            return "timeout";
        }
        if (truncated) {
            return "truncated";
        }
        return exitCode != null && exitCode == 0 ? "success" : "failed";
    }

    /**
     * @return 回喂给模型的文本
     *
     * <p>WHY 带 {@code exit=} 前缀与分段标记：模型需要能区分"这段是 stdout 还是 stderr"
     * 以及"命令究竟成功没有"。只把 stdout 拼给它，一条 {@code grep} 无匹配（退出码 1、
     * stdout 空）会被读成"命令成功且没有输出"，与"命令失败"是完全不同的结论。</p>
     *
     * <p>WHY 再做一次字符数裁剪：{@code run_command.max_output_bytes} 默认 64KiB，
     * 而模型上下文通常只有几万 token。把整段输出灌回去会挤掉对话历史，
     * 让智能体"忘了"用户最初的问题。这里按<b>字符</b>再收一次口，
     * 并在裁剪处显式标注，模型因此知道还有内容没看到。</p>
     */
    public String feedback() {
        StringBuilder text = new StringBuilder();
        text.append("exit=").append(exitCode == null ? "?" : exitCode).append('\n');
        if (timedOut) {
            text.append("[执行超时，命令已被中断，以下输出可能不完整]\n");
        }
        if (truncated) {
            text.append("[输出超过上限已被截断，以下内容不是完整结果]\n");
        }
        appendSection(text, "stdout", stdout);
        appendSection(text, "stderr", stderr);
        return text.toString();
    }

    private static void appendSection(StringBuilder text, String name, String value) {
        text.append("--- ").append(name).append(" ---\n");
        if (value.isEmpty()) {
            text.append("(空)\n");
            return;
        }
        if (value.length() > FEEDBACK_STREAM_LIMIT) {
            text.append(value, 0, FEEDBACK_STREAM_LIMIT)
                    .append("\n[... 已省略 ")
                    .append(value.length() - FEEDBACK_STREAM_LIMIT)
                    .append(" 字符 ...]\n");
            return;
        }
        text.append(value);
        if (!value.endsWith("\n")) {
            text.append('\n');
        }
    }

    /**
     * WHY 覆写：record 默认 {@code toString()} 会把 stdout/stderr 全文打出来。
     * 命令输出可能含用户环境的敏感片段（配置里的口令、token），
     * 而这条记录很可能被日志顺手打印。
     */
    @Override
    public String toString() {
        return "CommandExecution{exitCode=" + exitCode + ", stdoutBytes=" + stdout.length()
                + ", stderrBytes=" + stderr.length() + ", truncated=" + truncated
                + ", timedOut=" + timedOut + "}";
    }
}

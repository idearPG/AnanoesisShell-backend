package com.ananoesis.shell.ai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.ws.ToolName;

/**
 * {@link AgentSystemPrompt} 的行为约束断言（tasks 9.2 / 9.3 / 9.5）。
 *
 * <h2>WHY 要给一段提示词写测试</h2>
 * <p>系统提示不是装饰文字，它是<b>产品行为的载体</b>：工具分级纪律、
 * 「run_command 必须给 ai_analysis」、「绝不编造工具输出」、「命令必须非交互」
 * 这些约束全靠这段文字传达给模型。改动它等于改产品行为，
 * 却不会触发任何编译错误——因此用测试把关键句子钉住。</p>
 *
 * <h2>WHY 断言「包含关键短语」而不是「等于全文」</h2>
 * <p>全文断言会让任何一次措辞润色都变成测试失败，维护者于是学会无脑复制粘贴新期望值，
 * 测试就退化成一个改动检测器。这里只断言那些<b>删掉就会引发真实故障</b>的短语：
 * 例如「run_command 有副作用」没了，模型就会以为它能自动执行；
 * 「非交互」没了，模型可能提议 {@code vim}，命令会一直挂到超时。</p>
 */
class AgentSystemPromptTest {

    private static final String HOST_LABEL = "生产网关-01";

    // ======================================================================
    // 工具分级纪律（9.2 / 9.3 的行为侧）
    // ======================================================================

    @Test
    @DisplayName("提示明确区分只读工具与副作用工具，并说明后者要经人工审批")
    void promptStatesTheToolTieringDiscipline() {
        String prompt = AgentSystemPrompt.build(HOST_LABEL, true, false);

        assertThat(prompt)
                .as("三个只读工具必须被点名，模型才知道哪些可以放心用于取证")
                .contains("list_dir").contains("read_file").contains("system_info")
                .contains("只读工具")
                .contains("会自动执行");

        assertThat(prompt)
                .as("run_command 的副作用属性与审批流程是整条安全链的第一环")
                .contains("run_command 有副作用")
                .contains("绝不自动执行")
                .contains("人工审批");
    }

    @Test
    @DisplayName("提示要求 run_command 必须给出 ai_analysis：那是审批弹框上用户唯一能读的依据")
    void promptRequiresAiAnalysisForRunCommand() {
        assertThat(AgentSystemPrompt.build(HOST_LABEL, true, false))
                .contains("ai_analysis")
                .contains("理由与风险");
    }

    @Test
    @DisplayName("提示要求被拒绝后不要重复提交同一条命令")
    void promptForbidsResubmittingARejectedCommand() {
        // WHY 这一条值得钉住：模型默认的行为是「没达到目标就再试一次」。
        // 不写这句，用户点了拒绝之后会立刻再收到一个一模一样的弹框，
        // 审批闸门就从安全边界退化成烦人的确认框
        assertThat(AgentSystemPrompt.build(HOST_LABEL, true, false))
                .contains("用户已拒绝")
                .contains("不要重复提交同一条命令");
    }

    @Test
    @DisplayName("提示强制非交互命令，并说明输出受超时与长度上限约束（tasks 8.4）")
    void promptForcesNonInteractiveAndBoundedCommands() {
        String prompt = AgentSystemPrompt.build(HOST_LABEL, true, false);

        assertThat(prompt)
                .as("交互式命令会一直挂到执行超时，且没有任何输出可回喂")
                .contains("非交互")
                .contains("vim")
                .contains("执行超时")
                .contains("输出长度上限")
                .contains("截断");
    }

    @Test
    @DisplayName("提示禁止在命令里内联口令与密钥：凭据保护的最后一道行为约束")
    void promptForbidsInliningSecrets() {
        // WHY 这条属于安全测试：后端能保证 SSH 凭据不落日志、不入审计，
        // 但拦不住模型自己把用户贴在对话里的口令写进命令原文——
        // 而命令原文会进 approvals.tool_arguments 与审批弹框
        assertThat(AgentSystemPrompt.build(HOST_LABEL, true, false))
                .contains("绝对不要在命令里内联任何口令");
    }

    @Test
    @DisplayName("提示禁止编造工具输出：操作透明性的前提")
    void promptForbidsFabricatingToolOutput() {
        assertThat(AgentSystemPrompt.build(HOST_LABEL, true, false))
                .contains("绝不编造工具输出");
    }

    // ======================================================================
    // 目标服务器上下文（TRACEABILITY Q7）
    // ======================================================================

    @Test
    @DisplayName("有工具时提示带上目标服务器展示名，并提醒结论要针对这台机器")
    void promptCarriesHostLabelWhenToolsAreAvailable() {
        String prompt = AgentSystemPrompt.build(HOST_LABEL, true, false);

        assertThat(prompt)
                .contains("当前目标服务器")
                .contains(HOST_LABEL)
                .contains("用户可能同时管理多台机器");
    }

    @Test
    @DisplayName("会话 cwd 已知时注入当前工作目录：模型猜不到用户 cd 到了哪，否则「当前目录」问题全答成 /")
    void promptInjectsSessionCwdWhenKnown() {
        // 浏览器验收发现：用户 cd /tmp/acceptance 后问「列出当前目录文件」，
        // 提示词里没有 cwd，模型只能拿 / 去调 list_dir，答案完全错误
        String prompt = AgentSystemPrompt.build(HOST_LABEL, true, false, "/tmp/acceptance");

        assertThat(prompt)
                .contains("当前工作目录")
                .contains("/tmp/acceptance");
    }

    @Test
    @DisplayName("cwd 未知（null/空白）时不渲染工作目录小节：不能给模型一个空路径让它误信")
    void promptOmitsCwdSectionWhenUnknown() {
        assertThat(AgentSystemPrompt.build(HOST_LABEL, true, false, null))
                .doesNotContain("当前工作目录");
        assertThat(AgentSystemPrompt.build(HOST_LABEL, true, false, "   "))
                .doesNotContain("当前工作目录");
    }

    @Test
    @DisplayName("展示名缺失时不打印字面量 null，改用「已绑定一台目标服务器」的中性说法")
    void missingHostLabelIsNotRenderedAsNull() {
        String prompt = AgentSystemPrompt.build(null, true, false);

        // WHY 在意：hostLabel 为 null 发生在「主机配置已被删除但会话仍指向它」的时候。
        // 提示里出现字面量 null 会被模型当成主机名读出来
        assertThat(prompt)
                .doesNotContain("null")
                .doesNotContain(HOST_LABEL)
                .contains("已绑定一台目标服务器");
    }

    @Test
    @DisplayName("空白展示名与缺失同样处理：数据库里可能存了空串")
    void blankHostLabelIsTreatedAsMissing() {
        assertThat(AgentSystemPrompt.build("   ", true, false))
                .contains("已绑定一台目标服务器")
                .doesNotContain("「   」");
    }

    @Test
    @DisplayName("无工具时明确告知没有可用工具，且不出现目标服务器小节")
    void promptDeclaresAbsenceOfToolsWhenSessionHasNoHost() {
        String prompt = AgentSystemPrompt.build(null, false, false);

        // WHY 必须明说：否则模型会照常发起工具调用，而调用会被拒——
        // 用户看到的是一轮无意义的失败往返（Q7：会话可以不绑定服务器）
        assertThat(prompt)
                .contains("没有绑定目标服务器")
                .contains("没有任何可用的运维工具")
                .contains("请先在界面上为本会话选择一台目标服务器")
                .doesNotContain("## 当前目标服务器");
    }

    @Test
    @DisplayName("无工具时即便传入了展示名也不会渲染出来：没有工具就谈不上在它上面执行")
    void hostLabelIsIgnoredWhenNoToolsAreAvailable() {
        assertThat(AgentSystemPrompt.build(HOST_LABEL, false, false))
                .doesNotContain(HOST_LABEL);
    }

    // ======================================================================
    // 思考模式（tasks 7.3 的提示侧）
    // ======================================================================

    @Test
    @DisplayName("思考模式额外说明输出结构：结论必须写进最终回答而不是只留在思考段")
    void thinkingModeAddsOutputStructureSection() {
        String prompt = AgentSystemPrompt.build(HOST_LABEL, true, true);

        // WHY 这一句是必需的：前端把思考过程折叠、正文展开。
        // 模型若把结论只写在 reasoning_content 里，用户在正文区什么都看不到，
        // 症状是「AI 明明想了很久却什么也没说」
        assertThat(prompt)
                .contains("## 输出结构")
                .contains("思考模式")
                .contains("最终回答");
    }

    @Test
    @DisplayName("非思考模式不出现输出结构小节")
    void nonThinkingModeOmitsOutputStructureSection() {
        assertThat(AgentSystemPrompt.build(HOST_LABEL, true, false))
                .doesNotContain("## 输出结构")
                .doesNotContain("思考模式");
    }

    @Test
    @DisplayName("思考模式不改变工具纪律：分级约束在两种模式下同样存在")
    void toolDisciplineIsIndependentOfThinkingMode() {
        String thinking = AgentSystemPrompt.build(HOST_LABEL, true, true);
        String nonThinking = AgentSystemPrompt.build(HOST_LABEL, true, false);

        // WHY 单独断言：思考模式那段是额外追加的，
        // 若哪天有人把它写成了替换整段提示，工具纪律就会静默消失
        assertThat(thinking)
                .contains("run_command 有副作用")
                .contains("绝不编造工具输出")
                .contains("绝对不要在命令里内联任何口令");
        assertThat(nonThinking).doesNotContain("## 输出结构");
    }

    // ======================================================================
    // 基本不变量
    // ======================================================================

    @Test
    @DisplayName("任何参数组合都产出非空提示，且以中文运维助手的角色设定开头")
    void promptIsNeverEmpty() {
        for (boolean tools : new boolean[] {true, false}) {
            for (boolean thinking : new boolean[] {true, false}) {
                for (String label : new String[] {HOST_LABEL, null, ""}) {
                    String prompt = AgentSystemPrompt.build(label, tools, thinking);
                    assertThat(prompt)
                            .as("tools=%s thinking=%s label=%s", tools, thinking, label)
                            .isNotBlank()
                            .startsWith("你是一名 Linux 运维排障助手")
                            .contains("用中文回答");
                }
            }
        }
    }

    @Test
    @DisplayName("提示里点名的工具都在契约 ToolName 枚举内（防止提示与工具集漂移）")
    void promptMentionsOnlyContractToolNames() {
        String prompt = AgentSystemPrompt.build(HOST_LABEL, true, true);

        // WHY 值得一条：提示里写错工具名（例如写成 listdir 或 shell_exec），
        // 模型就会照着错的名字发起调用，而 ToolName.fromValue 返回 null，
        // 结果是一轮「不存在名为 X 的工具」的失败往返——
        // 症状看起来像模型犯傻，根因却在提示里
        assertThat(prompt).contains("list_dir / read_file / system_info");
        assertThat(ToolName.fromValue("list_dir")).isEqualTo(ToolName.LIST_DIR);
        assertThat(ToolName.fromValue("read_file")).isEqualTo(ToolName.READ_FILE);
        assertThat(ToolName.fromValue("system_info")).isEqualTo(ToolName.SYSTEM_INFO);
        assertThat(ToolName.fromValue("run_command")).isEqualTo(ToolName.RUN_COMMAND);
    }

    @Test
    @DisplayName("提示引导模型用 wc -l + read_file 分块 + grep -n 策略处理大文件（D4/D5）")
    void promptGuidesLargeFileReadingStrategy() {
        String prompt = AgentSystemPrompt.build(HOST_LABEL, true, false);
        assertThat(prompt).contains("wc -l");
        assertThat(prompt).contains("start_line").contains("end_line");
        assertThat(prompt).contains("grep -n");
    }

}

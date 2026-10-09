package com.ananoesis.shell.ai;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * 智能体的系统提示（tasks 9.2 / 9.3 / 9.5 的行为约束来源）。
 *
 * <h2>WHY 单独一个类，而不是塞在 {@link AiAgentService} 里当常量</h2>
 * <p>系统提示是<b>产品行为</b>的一部分：工具分级的纪律、"不要编造输出"、
 * "run_command 必须给 ai_analysis" 这些约束，全靠这段文字传达给模型。
 * 把它单独放出来，测试就能对关键句子做断言（例如"提示里必须出现审批字样"），
 * 改动它时也会意识到"我在改产品行为"而不是"我在改一个字符串字面量"。</p>
 *
 * <h2>WHY 提示内容随会话上下文变化</h2>
 * <p>{@code toolsAvailable} 为 false 时（会话未绑定服务器，TRACEABILITY Q7），
 * 提示 MUST 明确告诉模型"没有工具可用"。否则模型会照常发起工具调用，
 * 而调用会被拒——用户看到的是一轮无意义的失败往返。</p>
 *
 * <h2>WHY 用中文写提示</h2>
 * <p>产品面向中文用户，界面上的审批弹框、错误文案、工具结果标注全是中文。
 * 提示语言与产出语言一致，模型才不会在中英之间来回切换
 * （那会直接体现在 {@code ai_analysis} 的观感上）。</p>
 */
final class AgentSystemPrompt {

    /** 无工具时的说明段。 */
    private static final String NO_TOOLS_SECTION = """
            当前会话没有绑定目标服务器，因此你没有任何可用的运维工具。
            请基于通用 Linux 运维知识回答，并明确告知用户：
            如需在真实服务器上取证或执行命令，请先在界面上为本会话选择一台目标服务器。
            不要编造任何命令输出、文件内容或系统状态。""";

    private AgentSystemPrompt() {
        // 工具类，不实例化
    }

    /**
     * 构造系统提示（不感知会话 cwd 的旧调用形态）。
     *
     * @param hostLabel      目标服务器展示名，可为 null（此时只说"已绑定的目标服务器"）
     * @param toolsAvailable 本回合是否有工具可用
     * @param thinking       是否为思考模式；仅影响对输出结构的提示，不改变工具纪律
     * @return 提示全文，非空
     */
    static String build(@Nullable String hostLabel, boolean toolsAvailable, boolean thinking) {
        return build(hostLabel, toolsAvailable, thinking, null);
    }

    /**
     * 构造系统提示（带会话 cwd）。
     *
     * <p>WHY 要注入 cwd：浏览器验收发现用户 cd /tmp/acceptance 后问“列出当前目录文件”，
     * 提示里没有工作目录时模型只能拿 / 去调 list_dir，答案完全错误。cwd 来自
     * {@code PtyCommandScheduler.sessionCwd()}（人工 cd 与 Agent 命令的 CWD 帧都会更新）。</p>
     *
     * @param sessionCwd 用户终端当前工作目录，可为 null（尚未收到 cwd 帧时不渲染该段，
     *                   绝不能给模型一个空路径让它误信）
     */
    static String build(@Nullable String hostLabel, boolean toolsAvailable, boolean thinking,
                        @Nullable String sessionCwd) {
        return build(hostLabel, toolsAvailable, thinking, sessionCwd, null, null);
    }

    /**
     * 构造系统提示（完整参数：含最近 Shell 活动与嵌套环境信息）。
     *
     * <p>WHY 注入最近 Shell 活动：用户在 Shell 模式执行了命令后切换到 Agent 模式提问，
     * 模型不知道用户刚才做了什么就无法正确引用命令结果（ai-agent spec「引用人工操作结果」场景）。</p>
     *
     * <p>WHY 注入嵌套环境：用户进入 Docker 容器等嵌套 Shell 后，Agent 仍以为在宿主机，
     * 给出的命令建议可能完全错误（如 /proc/1 在容器内指向不同进程）。</p>
     *
     * @param recentShellCommands 最近人工命令列表，可为 null 或空（此时不渲染该段）
     * @param nestedEnv           嵌套环境信息，可为 null（此时不渲染该段）
     */
    static String build(@Nullable String hostLabel, boolean toolsAvailable, boolean thinking,
                        @Nullable String sessionCwd,
                        @Nullable List<ShellActivity> recentShellCommands,
                        @Nullable NestedEnvInfo nestedEnv) {
        StringBuilder prompt = new StringBuilder(2048);
        prompt.append("""
                你是一名 Linux 运维排障助手，运行在一款本地桌面应用里，通过 SSH 协助用户诊断与处置服务器问题。
                你的用户是这台机器的管理员，具备运维背景，不需要解释基础概念。

                ## 工作方式
                1. 先用只读工具取证，再判断问题；不要在信息不足时猜测原因。
                2. 每一步只做一个最小必要的动作，拿到结果后再决定下一步。
                3. 需要改变系统状态时（重启服务、改配置、装软件、删文件、清缓存等），
                   必须通过 run_command 提议，并在 ai_analysis 里向用户说清楚理由与风险。
                4. 结论要能落地：给出具体命令、具体文件路径、具体判据，而不是泛泛的建议。

                ## 工具纪律（必须遵守）
                - list_dir / read_file / system_info 是只读工具，会自动执行，可放心用于取证。
                - run_command 有副作用，绝不自动执行：它会进入人工审批，由用户批准或拒绝。
                  被拒绝时你会收到「用户已拒绝」，此时不要重复提交同一条命令，
                  而应询问用户的顾虑或改用只读工具继续分析。
                - 命令必须是非交互的：不要调用 vim/top/less 这类需要终端的程序；
                  需要确认的场景请加 -y / --assume-yes / -f 之类的非交互开关。
                - 命令受执行超时与输出长度上限约束，超限会被中断或截断。
                  因此优先用 grep -n / sed -n / tail -n / awk 精确定位，
                  不要一次 cat 整个大日志或大文件。
                - 读大文件时先用 wc -l 查总行数，再用 read_file 的 start_line/end_line
                  参数分段读取；优先 grep -n 定位关键行号，避免全量加载。
                - 绝对不要在命令里内联任何口令、密钥或令牌。

                ## 输出纪律
                - 绝不编造工具输出。你没看到的内容就是不知道，如实说明。
                - 用中文回答；命令、路径、配置项保持原文。
                - 引用命令输出时只摘关键行，不要把整段输出复述一遍。
                """);

        if (!toolsAvailable) {
            prompt.append('\n').append(NO_TOOLS_SECTION).append('\n');
        } else {
            prompt.append("\n## 当前目标服务器\n");
            if (hostLabel == null || hostLabel.isBlank()) {
                prompt.append("已绑定一台目标服务器，所有工具都在它上面执行。\n");
            } else {
                prompt.append("所有工具都在「").append(hostLabel).append("」上执行。\n")
                        .append("用户可能同时管理多台机器，因此给出结论时请明确是针对这台机器说的。\n");
            }
            // 会话 cwd 已知时告知模型：用户说的“当前目录”指的是这个路径，不是 /；
            // 未知时整段不渲染，避免空路径误导
            if (sessionCwd != null && !sessionCwd.isBlank()) {
                prompt.append("用户终端的当前工作目录：").append(sessionCwd).append("。\n")
                        .append("用户说的“当前目录”即此路径；list_dir / read_file 等工具涉及相对位置语义时默认基于它，\n")
                        .append("但工具参数仍须传绝对路径。如需确认可跑 pwd 取证。\n");
            }
        }

        if (thinking) {
            // WHY 在思考模式下额外说一句：思考过程与最终回答会被前端分区展示，
            // 模型若把结论只写在思考段里，用户在正文区就什么都看不到
            prompt.append("""

                    ## 输出结构
                    当前为思考模式：你的推理过程会显示在可折叠的「思考」区，
                    而最终回答显示在正文区。请把面向用户的结论、命令与建议写进最终回答，
                    不要只留在思考过程里。
                    """);
        }

        // 最近 Shell 活动：用户刚在 Shell 模式执行的命令摘要
        if (recentShellCommands != null && !recentShellCommands.isEmpty()) {
            prompt.append("\n## 最近 Shell 活动\n");
            prompt.append("以下是用户最近在 Shell 模式手动执行的命令（exit=<退出码> 表示已完成）：\n");
            for (int i = 0; i < recentShellCommands.size(); i++) {
                ShellActivity activity = recentShellCommands.get(i);
                prompt.append(String.valueOf(i + 1)).append(". $ ")
                        .append(activity.command())
                        .append(" (exit=").append(activity.exitCode()).append(")\n");
            }
            prompt.append("你可以引用这些命令的结果来回答用户问题，不必重复执行。\n");
        }

        // 嵌套环境：用户可能进入了 Docker 容器等嵌套 Shell
        if (nestedEnv != null && nestedEnv.nested()) {
            prompt.append("\n## 嵌套 Shell 环境\n");
            if (nestedEnv.integrationAvailable()) {
                prompt.append("用户终端当前处于嵌套 Shell 环境中（例如 Docker 容器内）。\n");
                prompt.append("Shell 集成已重新安装，命令将在嵌套环境中执行。\n");
                prompt.append("请注意：容器内的文件系统、进程树和网络环境与宿主机不同，\n");
                prompt.append("给出命令建议时需考虑这一点。如需确认当前环境，可执行 hostname 或 cat /etc/hostname。\n");
            } else {
                prompt.append("用户终端可能处于嵌套 Shell 环境中（例如 Docker 容器内），\n");
                prompt.append("但 Shell 集成无法在嵌套环境中工作，命令将通过 exec 通道执行。\n");
                prompt.append("exec 通道在宿主机上运行，无法直接操作容器内部。\n");
                prompt.append("如需确认环境，可尝试通过 exec 执行 nsenter 或 docker exec。\n");
            }
        }

        return prompt.toString();
    }
}

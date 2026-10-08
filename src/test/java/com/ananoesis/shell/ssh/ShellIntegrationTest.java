package com.ananoesis.shell.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ShellIntegration 测试（task 5.2）。
 *
 * <p>验证会话级 Bash 集成安装：代码生成、nonce 唯一性、
 * 不支持的 Shell 禁用、已有钩子保留、PTY 写入。</p>
 */
@DisplayName("ShellIntegration")
class ShellIntegrationTest {

    private CapturingTerminalSession terminal;

    @BeforeEach
    void setUp() {
        terminal = new CapturingTerminalSession();
    }

    // ==================================================================
    // 安装行为
    // ==================================================================

    @Nested
    @DisplayName("安装行为")
    class InstallationBehavior {

        @Test
        @DisplayName("bash 安装成功，向 PTY 写入集成代码")
        void bashInstallSendsIntegrationCodeToPty() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");

            ShellIntegration.Result result = integration.install();

            assertThat(result.installed()).isTrue();
            assertThat(result.shellType()).isEqualTo("bash");
            assertThat(result.nonce()).isNotEmpty();
            assertThat(terminal.sentData).isNotEmpty();
        }

        @Test
        @DisplayName("安装结果为 unsupported 时不向 PTY 写入任何数据")
        void unsupportedShellDoesNotSendToPty() {
            ShellIntegration integration = new ShellIntegration(terminal, "zsh");

            ShellIntegration.Result result = integration.install();

            assertThat(result.installed()).isFalse();
            assertThat(result.shellType()).isEqualTo("zsh");
            assertThat(result.nonce()).isEmpty();
            assertThat(terminal.sentData).isEmpty();
        }

        @Test
        @DisplayName("空 Shell 类型抛出异常")
        void emptyShellTypeThrowsException() {
            assertThatThrownBy(() -> new ShellIntegration(terminal, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 终端会话抛出异常")
        void nullTerminalThrowsException() {
            assertThatThrownBy(() -> new ShellIntegration(null, "bash"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // Nonce 唯一性
    // ==================================================================

    @Nested
    @DisplayName("Nonce 唯一性")
    class NonceUniqueness {

        @Test
        @DisplayName("每次安装生成不同的 nonce")
        void eachInstallGeneratesDifferentNonce() {
            ShellIntegration i1 = new ShellIntegration(terminal, "bash");
            ShellIntegration i2 = new ShellIntegration(terminal, "bash");

            ShellIntegration.Result r1 = i1.install();
            ShellIntegration.Result r2 = i2.install();

            assertThat(r1.nonce()).isNotEqualTo(r2.nonce());
        }

        @Test
        @DisplayName("nonce 为非空字符串")
        void nonceIsNonEmptyString() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            ShellIntegration.Result result = integration.install();

            assertThat(result.nonce()).isNotEmpty();
            // nonce 应可安全嵌入 OSC 帧（不含分号、ESC 等特殊字符）
            assertThat(result.nonce()).doesNotContain(";", "\u001B", "\u0007");
        }
    }

    // ==================================================================
    // 集成代码内容
    // ==================================================================

    @Nested
    @DisplayName("集成代码内容")
    class IntegrationCodeContent {

        @Test
        @DisplayName("集成代码包含 nonce")
        void codeContainsNonce() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            ShellIntegration.Result result = integration.install();

            String sentCode = joinSentData();
            assertThat(sentCode).contains(result.nonce());
        }

        @Test
        @DisplayName("集成代码包含 PROMPT_COMMAND 设置")
        void codeContainsPromptCommand() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            assertThat(sentCode).contains("PROMPT_COMMAND");
        }

        @Test
        @DisplayName("集成代码包含 DEBUG trap 安装")
        void codeContainsDebugTrap() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            assertThat(sentCode).contains("trap");
            assertThat(sentCode).contains("DEBUG");
        }

        @Test
        @DisplayName("集成代码保留已有 PROMPT_COMMAND（先读取旧值再调用）")
        void codePreservesExistingPromptCommand() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            // 应保存旧的 PROMPT_COMMAND 并在新钩子中调用
            assertThat(sentCode).contains("_ANANOESIS_OLD_PC");
            // 旧钩子应被调用
            assertThat(sentCode).contains("$_ANANOESIS_OLD_PC");
        }

        @Test
        @DisplayName("集成代码保留已有 DEBUG trap（先保存再调用）")
        void codePreservesExistingDebugTrap() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            // 应保存旧的 DEBUG trap 并在新钩子中调用
            assertThat(sentCode).contains("_ANANOESIS_OLD_DEBUG");
        }

        @Test
        @DisplayName("集成代码包含 cmd_start 帧发送")
        void codeContainsCmdStartFrame() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            assertThat(sentCode).contains("cmd_start");
        }

        @Test
        @DisplayName("集成代码包含 cmd_end 帧发送")
        void codeContainsCmdEndFrame() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            assertThat(sentCode).contains("cmd_end");
        }

        @Test
        @DisplayName("钩子帧的 printf 转义必须是单反斜杠 \\033：双反斜杠会让 bash printf 输出字面文本 '\\033]' 而非真实 ESC 字节，解码器永远看不到帧——真实 CentOS 首连噪声与闸门白屏的真根因（known-issues #14）")
        void framePrintfUsesSingleBackslashEscape() {
            String code = ShellIntegration.generateBashIntegrationCode("nonce-esc");

            // bash 代码中应为 printf '\033]1337;...'（单引号内单反斜杠，printf 解释为 ESC）
            assertThat(code).contains("printf '\\033]1337;");
            // 禁止双反斜杠形态：printf 会把 \\ 解释为一个字面反斜杠，后续 033 变普通文本
            assertThat(code).doesNotContain("'\\\\033]");
            assertThat(code).doesNotContain("'\\\\007");
        }

        @Test
        @DisplayName("集成代码包含 prompt 帧发送")
        void codeContainsPromptFrame() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            assertThat(sentCode).contains("prompt");
        }

        @Test
        @DisplayName("集成代码包含 cwd 帧发送")
        void codeContainsCwdFrame() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            assertThat(sentCode).contains("cwd");
        }

        @Test
        @DisplayName("结束钩子先保存 $? 避免后续处理改变退出码")
        void endHookSavesExitCodeFirst() {
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            String sentCode = joinSentData();
            // 钩子函数应先保存 $? 到局部变量
            assertThat(sentCode).contains("_ANANOESIS_S=$?");
        }

        @Test
        @DisplayName("安装代码用花括号组命令而非子 shell：子 shell 里的 PROMPT_COMMAND/trap 随退出丢失，父交互 shell 从未挂上钩子")
        void integrationCodeAppliesToCurrentShellNotSubshell() {
            // 真实接线踩坑（known-issues #13）：旧实现用 `( ... )` 包裹安装语句，
            // bash 为子 shell 新建进程执行，PROMPT_COMMAND/trap DEBUG 不会回写父交互 shell，
            // 导致钩子从未触发→PROMPT/CWD 帧缺席→调度器永久 MANUAL_BUSY→获批命令全被拒回落 exec。
            // 组命令 `{ ...; }` 在当前 shell 内执行，设置真实生效
            String code = ShellIntegration.generateBashIntegrationCode("nonce-routed-check");

            assertThat(code.strip()).as("应用组命令 `{ ...; }` 而非子 shell `( ... )`")
                    .startsWith("{").endsWith("}");
            // 防御：顶层首 token 不得是 `(`
            assertThat(code.strip().split("\\s")[0]).isNotEqualTo("(");
        }

        @Test
        @DisplayName("安装代码压缩为单行：多行输入会触发 readline 逐行输出 `>` 续行提示，污染用户终端")
        void integrationCodeIsSingleLine() {
            String code = ShellIntegration.generateBashIntegrationCode("nonce-single-line");

            // 除末尾换行符外不得再含任何 '\n'（单行命令只占一个提示符位，无 `>` 噪声）
            assertThat(code.stripTrailing()).doesNotContain("\n");
            assertThat(code).endsWith("\n");
        }

        @Test
        @DisplayName("整个安装必须是单条命令行（stty -echo; 钩子; stty echo）：分次发送会让每条命令各弹一个提示符，闸门开闸后用户看到同一行双 prompt（known-issues #19 真根因）")
        void installSendsHooksAsSingleCommandLine() {
            // 浏览器实测：首连同一行两个 `[root@localhost ~]#` 且无 Last login banner——
            // 证明两个 prompt 都来自闸门开闸之后：旧三段式里 code 执行完弹首个
            // 提示帧（开闸）+ prompt ①，随后的 stty echo 命令又产生 prompt ②。
            // 合并为单条命令后整行只触发一次 PROMPT_COMMAND → 恰好一个 prompt；
            // 回显抑制语义保留在同一行内（行首 stty -echo，行尾 stty echo）
            ShellIntegration integration = new ShellIntegration(terminal, "bash");
            integration.install();

            assertThat(terminal.sentData).as("安装序列必须一次性单条发送").hasSize(1);
            String sent = terminal.sentData.get(0);
            assertThat(sent.substring(0, sent.length() - 1))
                    .as("单行：除末尾换行外不得再有 '\n'（多命令行只占一个提示符位）")
                    .doesNotContain("\n");
            assertThat(sent).startsWith("stty -echo; ").contains("{_").endsWith("stty echo\n");
        }
    }

    // ==================================================================
    // 不支持的 Shell
    // ==================================================================

    @Nested
    @DisplayName("不支持的 Shell")
    class UnsupportedShells {

        @Test
        @DisplayName("zsh 返回未安装")
        void zshReturnsNotInstalled() {
            ShellIntegration.Result result = new ShellIntegration(terminal, "zsh").install();
            assertThat(result.installed()).isFalse();
        }

        @Test
        @DisplayName("fish 返回未安装")
        void fishReturnsNotInstalled() {
            ShellIntegration.Result result = new ShellIntegration(terminal, "fish").install();
            assertThat(result.installed()).isFalse();
        }

        @Test
        @DisplayName("sh 返回未安装")
        void shReturnsNotInstalled() {
            ShellIntegration.Result result = new ShellIntegration(terminal, "sh").install();
            assertThat(result.installed()).isFalse();
        }

        @Test
        @DisplayName("未知 Shell 返回未安装")
        void unknownShellReturnsNotInstalled() {
            ShellIntegration.Result result = new ShellIntegration(terminal, "csh").install();
            assertThat(result.installed()).isFalse();
        }
    }

    // ==================================================================
    // Stubs
    // ==================================================================

    /**
     * 捕获所有 send() 调用的终端会话桩。
     *
     * <p>WHY 继承 SshTerminalSession：复用 SessionRuntimeTest 的桩模式，
     * 允许 null SSH 资源，同时捕获写入 PTY 的数据用于断言。</p>
     */
    static class CapturingTerminalSession extends SshTerminalSession {
        final List<String> sentData = new ArrayList<>();

        CapturingTerminalSession() {
            super(UUID.randomUUID().toString(), null, null, null,
                    new SessionRuntimeTest.StubOutputListener(), reason -> { });
        }

        @Override
        public void send(String data) {
            if (data != null && !data.isEmpty()) {
                sentData.add(data);
            }
        }

        @Override
        public void close(SshCloseReason reason) { }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void resize(int columns, int rows) { }
    }

    /** 拼接所有发送到 PTY 的数据。 */
    private String joinSentData() {
        return String.join("", terminal.sentData);
    }
}

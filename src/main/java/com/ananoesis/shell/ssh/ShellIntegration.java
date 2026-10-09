package com.ananoesis.shell.ssh;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话级 Bash Shell 集成安装（task 5.2）。
 *
 * <p>在已建立的交互式 PTY 中安装 preexec/prompt 钩子，使 Shell 在执行
 * 每条命令前后发送 OSC 1337 控制帧。这些帧由 {@link ShellFrameDecoder} 解析，
 * 供 {@link PtyCommandScheduler} 识别命令边界。</p>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li>不改永久 .bashrc——所有钩子仅存在于当前会话的内存中</li>
 *   <li>保留已有 PROMPT_COMMAND 和 DEBUG trap，在新钩子中调用旧钩子</li>
 *   <li>结束钩子先保存 {@code $?}，避免后续处理改变真实退出码</li>
 *   <li>不支持的 Shell（zsh/fish/sh 等）明确禁用自动工具，但保留人工终端和 SFTP</li>
 *   <li>花括号组命令在当前 shell 内执行（子 shell 会丢失设置，见 known-issues #13），
 *       且连同 stty 包裹合并为<b>单条命令行</b>一次性发送——分次发送会让每条命令各弹
 *       一个提示符，闸门开闸后用户看到双 prompt（#19）；回显治理靠输出闸门（#14）</li>
 * </ul>
 *
 * <p>WHY 只支持 Bash 4+：design.md D3 明确"第一阶段支持 Linux Bash 4+"，
 * 其他 Shell 的语法差异过大（如 zsh 的 precmd/preexec 机制），
 * 强行兼容会引入大量条件分支和测试负担。扩展其他 Shell 是后续兼容工作。</p>
 */
public class ShellIntegration {

    private static final Logger LOG = LoggerFactory.getLogger(ShellIntegration.class);

    /** 支持的 Shell 类型常量。 */
    public static final String SHELL_BASH = "bash";

    private final SshTerminalSession terminalSession;
    private final String shellType;

    /**
     * @param terminalSession 已建立的 PTY 会话
     * @param shellType       远端 Shell 类型（如 "bash"、"zsh"）
     */
    public ShellIntegration(SshTerminalSession terminalSession, String shellType) {
        if (terminalSession == null) {
            throw new IllegalArgumentException("terminalSession 不得为 null");
        }
        if (shellType == null || shellType.isBlank()) {
            throw new IllegalArgumentException("shellType 不得为空");
        }
        this.terminalSession = terminalSession;
        this.shellType = shellType;
    }

    /**
     * 安装结果。
     *
     * @param installed 是否成功安装
     * @param shellType 检测到的 Shell 类型
     * @param nonce     安装使用的随机 nonce（未安装时为空串）
     */
    public record Result(boolean installed, String shellType, String nonce) {
        public Result {
            Objects.requireNonNull(shellType, "shellType 不得为 null");
            nonce = nonce == null ? "" : nonce;
        }
    }

    /**
     * 生成会话随机 nonce（嵌入控制帧供解码器过滤）。
     *
     * <p>WHY 暴露给包内：{@link ShellIntegrationInstaller} 需要<b>先</b>用 nonce 构造
     * 解码器与闸门监听器、接入输出链，<b>后</b>写安装代码——顺序颠倒会让安装期间的
     * readline 回显泄漏给前端（真实 CentOS 首连噪声，见 known-issues #14）。</p>
     */
    static String newNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 安装会话级 Shell 集成。
     *
     * <p>如果 Shell 类型不受支持，返回 {@code installed=false} 且不向 PTY 写入任何数据。
     * 如果支持，生成包含随机 nonce 的集成代码并通过 PTY 写入远端 Shell。</p>
     *
     * @return 安装结果，包含 nonce（供 {@link ShellFrameDecoder} 过滤帧使用）
     */
    public Result install() {
        return install(newNonce());
    }

    /**
     * 重新安装集成代码（嵌套 Shell 检测后使用新 nonce）。
     *
     * <p>复用 {@link #generateBashIntegrationCode} 生成新 nonce 的代码，
     * 经现有单行发送路径写入 PTY。嵌套 bash 中旧函数不存在，新安装代码
     * 会保存旧 PROMPT_COMMAND（可能引用不存在的函数）并覆盖为新函数。</p>
     *
     * @param nonce 新的会话随机 nonce
     * @return 安装结果
     */
    public Result reinstall(String nonce) {
        if (!SHELL_BASH.equals(shellType)) {
            LOG.info("不支持的 Shell 类型 '{}'，跳过重安装", shellType);
            return new Result(false, shellType, "");
        }
        String code = generateBashIntegrationCode(nonce);
        String singleLine = "stty -echo; " + code.substring(0, code.length() - 1) + "; stty echo\n";
        terminalSession.send(singleLine);
        LOG.info("已重安装 Bash 会话级集成: nonce={}", nonce);
        return new Result(true, SHELL_BASH, nonce);
    }

    /**
     * 用调用方预生成的 nonce 安装（见 {@link #newNonce()} 的 WHY）。
     *
     * @param nonce 会话随机 nonce，嵌入控制帧供解码器过滤
     * @return 安装结果
     */
    public Result install(String nonce) {
        if (!SHELL_BASH.equals(shellType)) {
            LOG.info("不支持的 Shell 类型 '{}'，禁用自动工具集成，保留人工终端/SFTP", shellType);
            return new Result(false, shellType, "");
        }

        String code = generateBashIntegrationCode(nonce);

        // WHY 单条命令一次性发送（stty -echo; 钩子; stty echo）：旧三段式分次发送时，
        // 钩子代码执行完就弹出**首个提示帧**（开启服务端闸门）+ prompt ①，随后的
        // stty echo 又是一条命令、再弹 prompt ②——用户在闸门开闸后看到同一行双提示符
        // （known-issues #19 真根因，首连实测两连 `[root@localhost ~]#` 且无 Last login）。
        // 合并为单行后整行只触发一次 PROMPT_COMMAND → 恰好一个 prompt；回显抑制语义
        // 保留在同一行内（行首 stty -echo、行尾 stty echo）；readline 自回显不受
        // tty ECHO 控制，那部分噪声仍靠 ShellIntegrationInstaller 的输出闸门吞掉（#14）
        String singleLine = "stty -echo; " + code.substring(0, code.length() - 1) + "; stty echo\n";
        terminalSession.send(singleLine);

        LOG.info("已安装 Bash 会话级集成: nonce={}", nonce);
        return new Result(true, SHELL_BASH, nonce);
    }

    /**
     * 生成 Bash 集成代码（单行）。
     *
     * <p>代码结构（按列表顺序用分隔符拼接为单行命令）：</p>
     * <ol>
     *   <li>花括号组命令包裹——必须在当前交互 shell 内执行；WHY 不用子 shell
     *       `( ... )`：bash 为子 shell 新建进程，内部的 PROMPT_COMMAND/trap DEBUG
     *       设置随子 shell 退出丢失，父 shell 从未挂上钩子（真实接线踩坑，
     *       导致调度器永久 MANUAL_BUSY）；安装语句本身先于 trap 设置完成，
     *       不存在 DEBUG 触发风暴</li>
     *   <li>保存旧的 PROMPT_COMMAND（_ANANOESIS_OLD_PC）和 DEBUG trap（_ANANOESIS_OLD_DEBUG）</li>
     *   <li>定义 _ananoesis_prompt_hook：发送 cmd_end + cwd + prompt 帧，然后调用旧 PROMPT_COMMAND</li>
     *   <li>定义 _ananoesis_debug_hook：首次触发时发送 cmd_start 帧，然后调用旧 DEBUG trap</li>
     *   <li>设置 PROMPT_COMMAND、启用 DEBUG trap（set -T）、安装 trap</li>
     * </ol>
     *
     * <p>WHY 单行：多行输入会让 readline 逐行输出 `>` 续行提示（即使 tty 回显已关，
     * 提示符也由 bash 进程自己打印），污染用户终端；单行命令只占一个提示符位。
     * 拼接规则：上一行以 `{`/`then`/`else` 结尾时用空格（保留字后紧跟命令），
     * 其余用 `; `（fi/} 等复合语句结束后必须有命令分隔符）。</p>
     *
     * @param nonce 会话随机 nonce，嵌入帧中供解码器过滤
     * @return 完整的 Bash 集成代码（单行，以换行结尾触发执行）
     */
    static String generateBashIntegrationCode(String nonce) {
        String[] lines = {
                "{",
                "_ANANOESIS_NONCE='" + nonce + "'",
                // WHY 保存旧钩子：design.md D3 要求"保留已有钩子"，
                // 新钩子必须在完成自身工作后调用旧钩子，确保用户自定义行为不被破坏
                "_ANANOESIS_OLD_PC=\"${PROMPT_COMMAND}\"",
                "_ANANOESIS_OLD_DEBUG=\"$(trap -p DEBUG)\"",
                "_ananoesis_prompt_hook() {",
                // WHY 先保存 $?：design.md D3 要求"结束钩子先保存 $?"，
                // 后续的 printf/eval 可能改变退出码，必须先快照
                "  local _ANANOESIS_S=$?",
                "  printf '\\033]1337;cmd_end;%s;%s;%s\\007' \"$_ANANOESIS_NONCE\" \"${_ANANOESIS_CMD_ID:-0}\" \"$_ANANOESIS_S\"",
                "  printf '\\033]1337;cwd;%s;%s;%s\\007' \"$_ANANOESIS_NONCE\" \"${_ANANOESIS_CMD_ID:-0}\" \"$(pwd)\"",
                "  printf '\\033]1337;prompt;%s;%s;\\007' \"$_ANANOESIS_NONCE\" \"${_ANANOESIS_CMD_ID:-0}\"",
                "  _ANANOESIS_CMD_ID=\"\"",
                "  if [ -n \"$_ANANOESIS_OLD_PC\" ]; then",
                "    eval \"$_ANANOESIS_OLD_PC\"",
                "  fi",
                "  return $_ANANOESIS_S",
                "}",
                "_ananoesis_debug_hook() {",
                "  if [ -z \"$_ANANOESIS_CMD_ID\" ]; then",
                "    _ANANOESIS_CMD_ID=\"$(date +%s%N)\"",
                "    printf '\\033]1337;cmd_start;%s;%s;\\007' \"$_ANANOESIS_NONCE\" \"$_ANANOESIS_CMD_ID\"",
                "  fi",
                // WHY 调用旧 DEBUG trap：保留用户已有的 DEBUG trap 行为
                "  if [ -n \"$_ANANOESIS_OLD_DEBUG\" ]; then",
                "    eval \"$_ANANOESIS_OLD_DEBUG\"",
                "  fi",
                "}",
                "PROMPT_COMMAND=_ananoesis_prompt_hook",
                "set -T",
                "trap '_ananoesis_debug_hook' DEBUG",
                "}"
        };
        StringBuilder code = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            if (code.length() > 0) {
                // 保留字 `{`/`then`/`else` 后直接跟命令用空格；其余语句边界用 `; `
                boolean spaceSeparated = code.toString().endsWith("{")
                        || code.toString().endsWith("then") || code.toString().endsWith("else");
                code.append(spaceSeparated ? " " : "; ");
            }
            code.append(line);
        }
        return code + "\n";
    }
}

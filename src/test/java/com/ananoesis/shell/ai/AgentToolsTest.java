package com.ananoesis.shell.ai;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.security.CredentialProtectionException;
import com.ananoesis.shell.service.HostNotFoundException;
import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ssh.ExecLimits;
import com.ananoesis.shell.ssh.ExecOutcome;
import com.ananoesis.shell.ssh.PtyCommandGateway;
import com.ananoesis.shell.ssh.PtyCommandScheduler;
import com.ananoesis.shell.ssh.SshConnectException;
import com.ananoesis.shell.ssh.SshConnectionService;
import com.ananoesis.shell.ssh.SshExecService;
import com.ananoesis.shell.ssh.SshFailureKind;
import com.ananoesis.shell.ssh.SshTarget;
import com.ananoesis.shell.ssh.SshTargetResolver;
import com.ananoesis.shell.support.FakeSshServer;

/**
 * 只读工具集与工具分级（tasks 9.2 / 9.3 / 4.1-4.5）。
 *
 * <h2>WHY 需要两种 exec 服务</h2>
 * <ul>
 *   <li><b>真 {@link FakeSshServer}</b>：用来证明"工具生成的那条 shell 串确实经 6.5 的
 *       exec 通道下发到了远端、且一字未改"。这是命令注入防线的唯一硬证据——
 *       断言 {@code execCommands()} 里的原始字符串，比断言返回值可靠得多。</li>
 *   <li><b>{@link CannedExecService}</b>：用来喂入指定的 {@link ExecOutcome} 与异常。
 *       内嵌服务器的迷你解释器只认十来条命令，{@code ls}/{@code sed} 一律回 127 且
 *       stdout 恒空，于是"输出截断提示""超时标注""stderr 分段"这些<b>输出整形</b>行为
 *       根本无法经它触发。要覆盖就得能指定远端返回什么。</li>
 * </ul>
 *
 * <h2>WHY 用子类而不是 mock 框架</h2>
 * <p>{@code SshExecService} 的构造器要求三个非 null 协作者，但构造过程不建立任何连接，
 * 因此可以传入真对象、只覆盖 {@code executeForHost}。这比引入 Mockito 更贴合本项目
 * "替身是可读的状态容器"的一贯风格：断言直接读 {@link CannedExecService#commands()}，
 * 失败时一眼看得出偏差。</p>
 *
 * <h2>WHY 是集成测试</h2>
 * <p>{@code read_file} 的行数上限与只读工具的输出上限都取自 {@code settings} 表，
 * 且必须是<b>当前</b>值（TRACEABILITY Q5）。用真 SQLite 才能证明这一点。</p>
 */
class AgentToolsTest extends AbstractSqliteIntegrationTest {

    private static final String MAX_LINES_KEY = "read_file.max_lines";
    private static final String MAX_OUTPUT_KEY = "run_command.max_output_bytes";
    private static final String SEEDED_MAX_LINES = "500";
    private static final String SEEDED_MAX_OUTPUT = "65536";

    /** 内嵌 SSH 服务器的登录口令；用作"明文不得回喂给模型"的探针。 */
    private static final String PASSWORD_PROBE = FakeSshServer.PASSWORD;

    private static FakeSshServer fake;

    @Autowired private SettingsService settings;
    @Autowired private DataSource dataSource;

    /** 连真内嵌服务器的工具实例，用于断言下发的 shell 串。 */
    private AgentTools tools;

    /**
     * 本用例开始前，内嵌服务器已经收到的命令条数。
     *
     * <p>WHY 需要这条基线：{@code fake} 是 {@code @BeforeAll} 起的<b>静态</b>服务器，
     * 全类共用同一个实例，而 {@code FakeSshServer} 不提供清空接口。
     * 直接断言 {@code execCommands()} 的绝对长度或内容，会把前面用例下发的命令一并算进来——
     * 症状是「这个用例单独跑能过、整类跑就挂」，而失败信息里全是别的用例的命令，
     * 让人以为是命令构造错了。</p>
     */
    private int execMark;

    private UUID hostId;

    @BeforeAll
    static void startFakeServer() throws IOException {
        fake = FakeSshServer.start();
    }

    @AfterAll
    static void stopFakeServer() {
        fake.close();
    }

    @BeforeEach
    void wireTools() {
        AgentTools.clearLastError();
        hostId = UUID.randomUUID();
        execMark = fake.execCommands().size();
        tools = new AgentTools(AgentTestWiring.execServiceOn(fake), settings);
    }

    @AfterEach
    void restoreSettings() {
        AgentTools.clearLastError();
        writeSetting(MAX_LINES_KEY, SEEDED_MAX_LINES);
        writeSetting(MAX_OUTPUT_KEY, SEEDED_MAX_OUTPUT);
    }

    // ==================================================================
    // 9.2 只读工具生成的 shell 串
    // ==================================================================

    @Test
    @DisplayName("9.2：list_dir 生成 ls -Al 长格式命令，路径被单引号包裹后原样下发")
    void listDirBuildsTheExactReadOnlyShellString() {
        tools.listDir("/var/log/nginx", context(hostId));

        assertThat(sentCommands())
                .as("命令串 MUST 一字未改地落到远端：加了引号、被拼接、被 shell 包装都算改写")
                .contains("ls -Al --time-style=long-iso '/var/log/nginx'");
    }

    @Test
    @DisplayName("9.2：路径里的 shell 元字符被单引号失效，不会被当成命令分隔符执行")
    void listDirQuotesPathsContainingShellMetacharacters() {
        tools.listDir("/tmp/a b;rm -rf /", context(hostId));

        List<String> sent = sentCommands();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isEqualTo("ls -Al --time-style=long-iso '/tmp/a b;rm -rf /'");
        // WHY 这条属于安全验收：模型给出的路径是不可信输入。
        // 少了引号，";rm -rf /" 就真的会被远端 shell 执行
    }

    @Test
    @DisplayName("9.2：路径内嵌的单引号按 POSIX 规则转义")
    void quoteEscapesEmbeddedSingleQuotes() {
        assertThat(AgentTools.quote("plain")).isEqualTo("'plain'");
        assertThat(AgentTools.quote("a'b")).isEqualTo("'a'\\''b'");
        assertThat(AgentTools.quote("")).isEqualTo("''");
        // 转义后整串仍然是一个单一参数：单引号先闭合、拼接一个转义单引号、再重新打开
        assertThat(AgentTools.quote("it's here")).isEqualTo("'it'\\''s here'");
    }

    @Test
    @DisplayName("9.2：read_file 默认读前 max_lines 行，用 sed 的行区间表达")
    void readFileDefaultsToTheFirstMaxLinesRows() {
        tools.readFile("/etc/hosts", null, null, context(hostId));

        assertThat(sentCommands()).contains("sed -n '1,500p' '/etc/hosts'");
    }

    @Test
    @DisplayName("9.2：read_file 支持 start_line，让模型能接着上次的位置往下读")
    void readFileHonoursStartLine() {
        tools.readFile("/etc/hosts", 101, null, context(hostId));

        assertThat(sentCommands()).contains("sed -n '101,$p' '/etc/hosts'");
    }

    @Test
    @DisplayName("9.2/Q5：read_file 的行数上限取自 settings 的当前值，不是启动快照")
    void readFileFollowsTheCurrentMaxLinesSetting() {
        writeSetting(MAX_LINES_KEY, "50");

        tools.readFile("/etc/hosts", null, null, context(hostId));

        assertThat(sentCommands()).contains("sed -n '1,50p' '/etc/hosts'");
        assertThat(settings.readFileMaxLines()).isEqualTo(50);
    }

    @Test
    @DisplayName("9.2/4.5：start_line 为 0 或负数时按第 1 行处理——归一为无范围读取，绝不拼出 '1,$p' 全量区间")
    void readFileTreatsNonPositiveStartLineAsTheFirstLine() {
        tools.readFile("/etc/hosts", 0, null, context(hostId));
        tools.readFile("/etc/hosts", -5, null, context(hostId));

        assertThat(sentCommands())
                .as("sed -n '0,499p' 在部分实现上是语法错误；'1,$p' 更会在超大文件上全量读取。"
                        + "非正 start_line 语义上就是「从头读」，必须落回受 max_lines 保护的无范围路径")
                .containsOnly("sed -n '1,500p' '/etc/hosts'");
    }

    @Test
    @DisplayName("9.2：system_info 跑一段固定的只读采集脚本，不含任何副作用命令")
    void systemInfoRunsTheFixedReadOnlyScript() {
        tools.systemInfo(context(hostId));

        assertThat(sentCommands()).hasSize(1);
        String script = sentCommands().get(0);

        assertThat(script).startsWith("echo '== 内核 =='");
        assertThat(script).endsWith("nproc 2>/dev/null || echo '(无 nproc 命令)'");
        assertThat(script.split("; ")).hasSize(12);
        assertThat(script).contains("uname -a").contains("uptime").contains("df -hP");
        // WHY 断言"不含"：这个工具是自动执行的，没有人会看它的命令。
        // 一旦有人往里加了 sudo/systemctl/rm，就成了绕过审批闸门的后门
        assertThat(script)
                .doesNotContain("sudo")
                .doesNotContain("systemctl")
                .doesNotContain("rm ")
                .doesNotContain("mkfs");
    }

    @Test
    @DisplayName("9.3：run_command 的方法体只会抛异常，命令绝不可能经它落到远端")
    void runCommandNeverReachesTheExecChannel() {
        assertThatThrownBy(() -> tools.runCommand("systemctl restart nginx", "重启以恢复 5xx", context(hostId)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ApprovalGate");

        assertThat(sentCommands())
                .as("工具分级是本类最重要的安全边界：这里出现任何一条命令都意味着闸门被绕过")
                .isEmpty();
    }

    // ==================================================================
    // 输出整形：回喂给模型的文本
    // ==================================================================

    @Test
    @DisplayName("输出带 exit= 前缀：让模型能区分「命令失败」与「命令成功但没输出」")
    void outputCarriesExitCodeSoTheModelCanTellSuccessFromEmptyResult() {
        CannedExecService exec = cannedReturning(new ExecOutcome(1, "", "", false, false, 3L));

        assertThat(new AgentTools(exec, settings).listDir("/var/log", context(hostId)))
                .isEqualTo("exit=1\n");
    }

    @Test
    @DisplayName("stdout 与 stderr 分段呈现：混流会让模型把警告误读成结果")
    void stderrIsDelimitedFromStdout() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "out\n", "warn\n", false, false, 3L));

        assertThat(new AgentTools(exec, settings).listDir("/var/log", context(hostId)))
                .isEqualTo("exit=0\nout\n\n--- stderr ---\nwarn\n");
    }

    @Test
    @DisplayName("stderr 为空时不渲染分段标题，免得模型去找一段不存在的内容")
    void emptyStderrIsNotRenderedAsASection() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "out\n", "", false, false, 3L));

        assertThat(new AgentTools(exec, settings).listDir("/var/log", context(hostId)))
                .doesNotContain("--- stderr ---");
    }

    @Test
    @DisplayName("8.4：超时与截断在正文之前显式标注——不完整的输出必须让模型知道")
    void timeoutAndTruncationAreFlaggedBeforeTheBody() {
        CannedExecService exec = cannedReturning(new ExecOutcome(-1, "partial", "", true, true, 3L));

        assertThat(new AgentTools(exec, settings).listDir("/var/log", context(hostId))).isEqualTo(
                "exit=-1 [执行超时，已被中断，输出可能不完整] [输出超过上限，已被截断]\npartial");
    }

    // ==================================================================
    // 4.1-4.5：read_file 行范围与摘要（ai-agent spec delta）
    // ==================================================================

    @Test
    @DisplayName("4.2：显式范围读取用 sed 精确取行，结果标注含 wc -l 取得的总行数")
    void readFileSupportsStartAndEndLineRange() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l10\nl11\nl12\n", "", false, false, 1L));
        exec.registerOutcome("wc -l", new ExecOutcome(0, "500 /etc/hosts\n", "", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", 10, 12, context(hostId));

        assertThat(exec.commands())
                .contains("sed -n '10,12p' '/etc/hosts'", "wc -l '/etc/hosts'");
        assertThat(result).contains("[行 10-12 / 总 500 行]");
        assertThat(result).contains("l10\nl11\nl12\n");
    }

    @Test
    @DisplayName("4.2：仅传 start_line 时 sed 用 $ 读到末尾，标注为「X-末尾 / 总 Z 行」")
    void readFileWithOnlyStartLineReadsToEndOfFile() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l50\nl51\n", "", false, false, 1L));
        exec.registerOutcome("wc -l", new ExecOutcome(0, "600 /etc/hosts\n", "", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", 50, null, context(hostId));

        assertThat(exec.commands()).contains("sed -n '50,$p' '/etc/hosts'");
        assertThat(result).contains("[行 50-末尾 / 总 600 行]");
    }

    @Test
    @DisplayName("4.3：无范围读取达上限时返回结构摘要——总行数、头部（复用已读内容）、尾部 10 行、分段引导")
    void readFileSummarizesWhenTheLineLimitIsReached() {
        writeSetting(MAX_LINES_KEY, "3");
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l1\nl2\nl3\n", "", false, false, 3L));
        exec.registerOutcome("wc -l", new ExecOutcome(0, "1000 /etc/hosts\n", "", false, false, 1L));
        exec.registerOutcome("tail -n 10", new ExecOutcome(0, "t991\nt1000\n", "", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", null, null, context(hostId));

        assertThat(result)
                .as("总行数来自 wc -l；省略数 = 1000 - 头 3 - 尾 10 = 987")
                .contains("[文件总 1000 行，省略中间 987 行]")
                .contains("l1\nl2\nl3\n")      // 头部直接复用第一次 sed 已读到的内容
                .contains("t991\nt1000\n")     // 尾部只能远端补取
                .contains("start_line")
                .contains("end_line");
        assertThat(exec.commands())
                .as("大文件摘要只追加 wc -l 与 tail -n 10；头部不发 head——第一次 sed 的结果里就有")
                .containsExactly("sed -n '1,3p' '/etc/hosts'", "wc -l '/etc/hosts'", "tail -n 10 '/etc/hosts'");
    }

    @Test
    @DisplayName("4.3：摘要头部最多保留 20 行——max_lines 较大时也不把已读内容全量回喂")
    void readFileCapsTheSummaryHeadAtTwentyLines() {
        writeSetting(MAX_LINES_KEY, "25");
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            body.append("l").append(i).append('\n');
        }
        CannedExecService exec = cannedReturning(new ExecOutcome(0, body.toString(), "", false, false, 3L));
        exec.registerOutcome("wc -l", new ExecOutcome(0, "1000 /etc/hosts\n", "", false, false, 1L));
        exec.registerOutcome("tail -n 10", new ExecOutcome(0, "t991\nt1000\n", "", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", null, null, context(hostId));

        assertThat(result)
                .as("省略数 = 1000 - 头 20 - 尾 10 = 970：头部压到 20 行，"
                        + "「摘要」才不会退化成「截断输出」，模型的 token 预算才不会被头部吃光")
                .contains("[文件总 1000 行，省略中间 970 行]")
                .contains("l20\n")
                .doesNotContain("l21\n")
                .doesNotContain("l25\n");
    }

    @Test
    @DisplayName("4.3/小文件：返回行数少于上限时行为与本变更前一致——一条 sed，无 wc/tail，无摘要")
    void readFileOmitsTheSummaryWhenFewerLinesComeBack() {
        writeSetting(MAX_LINES_KEY, "3");
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l1\nl2\n", "", false, false, 3L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", null, null, context(hostId));

        assertThat(result)
                .isEqualTo("exit=0\nl1\nl2\n")
                .doesNotContain("省略")
                .doesNotContain("start_line");
        assertThat(exec.commands())
                .as("wc -l / tail 都是大文件摘要的开销，小文件路径一条命令都不许多发")
                .containsExactly("sed -n '1,3p' '/etc/hosts'");
    }

    @Test
    @DisplayName("4.5 边界：start_line 超出总行数时返回空内容——不触发摘要，也不发 tail")
    void readFileWithStartLineBeyondTotalReturnsEmptyContent() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "", "", false, false, 1L));
        exec.registerOutcome("wc -l", new ExecOutcome(0, "100 /etc/hosts\n", "", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", 9999, null, context(hostId));

        assertThat(exec.commands()).contains("sed -n '9999,$p' '/etc/hosts'");
        // 标注里的总行数让模型自行看出 9999 > 100，无需额外的越界文案
        assertThat(result).contains("[行 9999-末尾 / 总 100 行]");
        assertThat(result).doesNotContain("省略");
        assertThat(exec.commands())
                .as("空结果不是大文件，摘要路径的 tail 不应出现")
                .noneMatch(command -> command.startsWith("tail"));
    }

    @Test
    @DisplayName("4.5 边界：end_line < start_line 在下发命令前即被拒绝")
    void readFileWithEndLineBeforeStartLineIsRejected() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "", "", false, false, 1L));

        assertThatThrownBy(() -> new AgentTools(exec, settings)
                .readFile("/etc/hosts", 100, 50, context(hostId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end_line")
                .hasMessageContaining("start_line");

        assertThat(exec.commands())
                .as("校验必须在下发命令之前完成")
                .isEmpty();
    }

    @Test
    @DisplayName("4.5 归一：start_line=0 配合 end_line 时按第 1 行处理，不产生非法 sed 区间")
    void readFileNormalizesNonPositiveStartLineWithEndLine() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l1\nl2\n", "", false, false, 1L));
        exec.registerOutcome("wc -l", new ExecOutcome(0, "200 /etc/hosts\n", "", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", 0, 200, context(hostId));

        assertThat(exec.commands())
                .as("0 不是合法行号；归一为 1 后与显式 1,200 等价，绝不能拼出 '0,200p'")
                .contains("sed -n '1,200p' '/etc/hosts'");
        assertThat(result).contains("[行 1-200 / 总 200 行]");
    }

    @Test
    @DisplayName("4.3 降级：无范围大文件且 wc -l 失败时不阻塞读取，退回含分块引导的截断提示")
    void readFileFallsBackToPlainHintWhenWcFails() {
        writeSetting(MAX_LINES_KEY, "3");
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l1\nl2\nl3\n", "", false, false, 3L));
        exec.registerOutcome("wc -l", new ExecOutcome(1, "", "wc: No such file or directory", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", null, null, context(hostId));

        assertThat(result)
                .as("wc 失败只是拿不到总行数，已读内容必须原样返回")
                .contains("l1\nl2\nl3\n")
                .contains("start_line")
                .contains("end_line");
        assertThat(exec.commands())
                .as("总行数未知时再发 tail 也拼不出可靠摘要，一并省掉")
                .noneMatch(command -> command.startsWith("tail"));
    }

    @Test
    @DisplayName("4.2 降级：范围读取时 wc -l 失败则标注退化为无总行数，读取结果不受影响")
    void readFileDegradesRangeLabelWhenWcFails() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l10\n", "", false, false, 1L));
        exec.registerOutcome("wc -l", new ExecOutcome(127, "", "wc: command not found", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", 10, 12, context(hostId));

        assertThat(result)
                .contains("[行 10-12]")
                .contains("l10\n");
        assertThat(result).doesNotContain("总");
    }

    @Test
    @DisplayName("4.3 降级：tail 失败时摘要保留总行数与头部，尾部缺失如实说明而不编造")
    void readFileKeepsHeadSummaryWhenTailFails() {
        writeSetting(MAX_LINES_KEY, "3");
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l1\nl2\nl3\n", "", false, false, 3L));
        exec.registerOutcome("wc -l", new ExecOutcome(0, "1000 /etc/hosts\n", "", false, false, 1L));
        exec.registerOutcome("tail -n 10", new ExecOutcome(1, "", "tail: error", false, false, 1L));

        String result = new AgentTools(exec, settings).readFile("/etc/hosts", null, null, context(hostId));

        assertThat(result)
                .contains("[文件总 1000 行]")
                .contains("l1\nl2\nl3\n")
                .contains("start_line");
    }

    @Test
    @DisplayName("4.3 降级：wc 基础设施失败不残留错误状态，后续无关调用不被旧错误污染")
    void wcFailureDoesNotPolluteSubsequentToolCalls() {
        writeSetting(MAX_LINES_KEY, "3");
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "l1\nl2\nl3\n", "", false, false, 3L));
        exec.registerFailure("wc -l", HostNotFoundException.forId("gone"));

        String readFileResult = new AgentTools(exec, settings).readFile("/etc/hosts", null, null, context(hostId));
        assertThat(readFileResult)
                .as("wc 抛基础设施异常时同样走降级——读取不中断")
                .contains("l1\nl2\nl3\n");

        exec.returnOutcome(new ExecOutcome(0, "ok\n", "", false, false, 1L));
        String listDirResult = new AgentTools(exec, settings).listDir("/var/log", context(hostId));
        assertThat(listDirResult)
                .as("wc 失败会把错误写进 ThreadLocal；残留会让下一次无关调用误报「错误：」")
                .isEqualTo("exit=0\nok\n");
    }

    @Test
    @DisplayName("8.4：只读工具的超时固定 30 秒，输出上限取自 settings 当前值")
    void readOnlyLimitsUseAFixedTimeoutAndTheConfiguredOutputCap() {
        ExecLimits seeded = tools.readOnlyLimits();
        assertThat(seeded.timeout())
                .as("只读工具是自动执行的，一次卡住就拖住整轮对话，不能跟着 run_command 的时限走")
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(seeded.maxOutputBytes()).isEqualTo(65_536);

        writeSetting(MAX_OUTPUT_KEY, "2048");
        assertThat(tools.readOnlyLimits().maxOutputBytes()).isEqualTo(2048);
    }

    // ==================================================================
    // 失败路径：错误也要变成模型能读懂的文本
    // ==================================================================

    @Test
    @DisplayName("主机配置不存在 → 回喂固定文案，模型因此能提示用户去检查服务器配置")
    void hostNotFoundBecomesAModelReadableError() {
        AgentTools stubbed = new AgentTools(cannedFailingWith(HostNotFoundException.forId("gone")), settings);

        assertThat(stubbed.systemInfo(context(hostId)))
                .isEqualTo("错误：目标服务器配置不存在或已被删除");
    }

    @Test
    @DisplayName("连接失败只透出已脱敏文案，主机地址/用户名/口令一律不回喂给模型")
    void connectFailureOnlyExposesTheSanitisedMessage() {
        SshConnectException failure = new SshConnectException(SshFailureKind.AUTH_FAILED,
                "10.20.30.40:2222 user=ops password=" + PASSWORD_PROBE);
        AgentTools stubbed = new AgentTools(cannedFailingWith(failure), settings);

        String result = stubbed.systemInfo(context(hostId));

        // WHY 断言"等于"而不是"包含"：getMessage() 里全是内部细节，
        // 而这段文本会被回喂给模型，进而可能进入模型服务商的日志
        assertThat(result).isEqualTo("错误：" + SshFailureKind.AUTH_FAILED.userMessage());
        assertThat(result)
                .doesNotContain(PASSWORD_PROBE)
                .doesNotContain("10.20.30.40")
                .doesNotContain("ops");
    }

    @Test
    @DisplayName("凭据保护不可用 → 回喂 spec 规定的提示语并给出下一步")
    void credentialProtectionFailureSurfacesTheSpecMessage() {
        AgentTools stubbed = new AgentTools(
                cannedFailingWith(CredentialProtectionException.unavailable("主密码未配置", null)), settings);

        assertThat(stubbed.systemInfo(context(hostId)))
                .isEqualTo("错误：凭据保护不可用，请在设置中配置主密码后重试");
    }

    @Test
    @DisplayName("未预期异常回落到通用文案，异常消息本身不外泄")
    void unexpectedFailureFallsBackToTheGenericMessage() {
        AgentTools stubbed = new AgentTools(
                cannedFailingWith(new IllegalStateException("内部细节 password=" + PASSWORD_PROBE)), settings);

        String result = stubbed.systemInfo(context(hostId));

        assertThat(result).isEqualTo("错误：工具执行失败：服务器内部错误，请查看后端日志获取详情");
        assertThat(result).doesNotContain(PASSWORD_PROBE).doesNotContain("内部细节");
    }

    @Test
    @DisplayName("上一次失败不会污染下一次成功——线程本地状态每轮都被清理")
    void aLaterSuccessIsNotPollutedByAnEarlierFailure() {
        CannedExecService exec = cannedFailingWith(new IllegalStateException("boom"));
        AgentTools stubbed = new AgentTools(exec, settings);

        assertThat(stubbed.systemInfo(context(hostId))).startsWith("错误：");

        exec.failWith(null);
        exec.returnOutcome(new ExecOutcome(0, "ok\n", "", false, false, 1L));
        assertThat(stubbed.systemInfo(context(hostId)))
                .as("线程池会复用工作线程，残留的错误文案会被当成新一次调用的结果")
                .isEqualTo("exit=0\nok\n")
                .doesNotContain("错误：");
    }

    // ==================================================================
    // 参数与上下文校验
    // ==================================================================

    @Test
    @DisplayName("上下文缺 hostId 时立刻抛异常，而不是拿 null 去查主机")
    void missingHostIdInToolContextIsRejectedLoudly() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "", "", false, false, 1L));
        AgentTools stubbed = new AgentTools(exec, settings);

        assertThatThrownBy(() -> stubbed.systemInfo(new ToolContext(Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("本会话未绑定目标服务器");
        assertThatThrownBy(() -> stubbed.systemInfo(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("本会话未绑定目标服务器");
        assertThat(exec.commands()).as("校验必须在下发命令之前完成").isEmpty();
    }

    @Test
    @DisplayName("上下文里的 hostId 不是合法 UUID 时报错，而不是静默连到别的机器")
    void malformedHostIdIsRejected() {
        AgentTools stubbed = new AgentTools(
                cannedReturning(new ExecOutcome(0, "", "", false, false, 1L)), settings);

        assertThatThrownBy(() -> stubbed.systemInfo(new ToolContext(
                Map.of(AgentTools.CTX_HOST_ID, "not-a-uuid"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法 UUID");
    }

    @Test
    @DisplayName("hostId 接受 UUID 与字符串两种形式——两种都可能出现在会话上下文里")
    void hostIdAcceptsEitherUuidOrStringForm() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "", "", false, false, 1L));
        AgentTools stubbed = new AgentTools(exec, settings);

        stubbed.systemInfo(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, hostId)));
        stubbed.systemInfo(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, hostId.toString())));

        assertThat(exec.requestedHostIds()).containsExactly(hostId, hostId);
    }

    @Test
    @DisplayName("path 为空或全空白时在下发命令前就被拒掉，模型可据此补参数重试")
    void blankPathIsRejectedBeforeAnyCommandIsSent() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "", "", false, false, 1L));
        AgentTools stubbed = new AgentTools(exec, settings);

        assertThatThrownBy(() -> stubbed.listDir("  ", context(hostId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path 不得为空");
        assertThatThrownBy(() -> stubbed.readFile(null, null, null, context(hostId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path 不得为空");
        assertThat(exec.commands()).isEmpty();
    }

    @Test
    @DisplayName("path 首尾空白被裁掉：模型经常从 markdown 里带出多余空格")
    void surroundingWhitespaceInPathIsTrimmed() {
        CannedExecService exec = cannedReturning(new ExecOutcome(0, "", "", false, false, 1L));

        new AgentTools(exec, settings).listDir("  /var/log  ", context(hostId));

        assertThat(exec.commands()).containsExactly("ls -Al --time-style=long-iso '/var/log'");
    }

    @Test
    @DisplayName("sessionCwdOf 读会话调度器的 cwd：命中转发、未命中/空参/无网关都返回 null 且不抛——提示词靠它告知模型用户 cd 到了哪")
    void sessionCwdOfRoutesToTheSessionScheduler() {
        // 浏览器验收发现：用户 cd /tmp/acceptance 后问「列出当前目录」，AgentTools 没有
        // 暴露会话 cwd 的查询口，AiAgentService 无法把它注入系统提示词，模型只能猜 /
        PtyCommandScheduler scheduler = Mockito.mock(PtyCommandScheduler.class);
        Mockito.when(scheduler.sessionCwd()).thenReturn("/tmp/acceptance");
        PtyCommandGateway gateway = Mockito.mock(PtyCommandGateway.class);
        Mockito.when(gateway.findScheduler("s-1")).thenReturn(scheduler);
        Mockito.when(gateway.findScheduler("missing")).thenReturn(null);

        AgentTools withGateway = new AgentTools(
                cannedReturning(new ExecOutcome(0, "", "", false, false, 1L)), settings, gateway);

        assertThat(withGateway.sessionCwdOf("s-1")).isEqualTo("/tmp/acceptance");
        assertThat(withGateway.sessionCwdOf("missing")).isNull();
        assertThat(withGateway.sessionCwdOf(null)).isNull();
        assertThat(withGateway.sessionCwdOf("   ")).isNull();
        // 两参构造没有网关（exec 回落形态）：必须安全返回 null，不能让提示词构建炸栈
        assertThat(tools.sessionCwdOf("s-1")).isNull();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** @return 本用例期间下发到远端的命令，按到达顺序。 */
    private List<String> sentCommands() {
        List<String> all = fake.execCommands();
        return all.subList(execMark, all.size());
    }

    private static ToolContext context(UUID host) {
        return new ToolContext(Map.of(AgentTools.CTX_HOST_ID, host));
    }

    /** @return 一个"无论执行什么都返回给定结果"的 exec 替身 */
    private static CannedExecService cannedReturning(ExecOutcome outcome) {
        CannedExecService exec = new CannedExecService();
        exec.returnOutcome(outcome);
        return exec;
    }

    /** @return 一个"无论执行什么都抛给定异常"的 exec 替身 */
    private static CannedExecService cannedFailingWith(RuntimeException failure) {
        CannedExecService exec = new CannedExecService();
        exec.failWith(failure);
        return exec;
    }

    private void writeSetting(String key, String value) {
        String sql = "UPDATE settings SET setting_value = ? WHERE setting_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, key);
            assertThat(statement.executeUpdate()).as("种子行 %s 必须存在", key).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 可编排的 exec 替身：返回指定的 {@link ExecOutcome}，或抛出指定的异常。
     *
     * <p>WHY 继承真类而不是自己实现接口：{@code AgentTools} 依赖的是具体的
     * {@code SshExecService}。父类构造器要求三个非 null 协作者，但它<b>不建立任何连接</b>，
     * 因此传入真对象、只覆盖 {@code executeForHost} 就够了。</p>
     */
    private static final class CannedExecService extends SshExecService {

        private final List<String> commands = new ArrayList<>();
        private final List<UUID> requestedHostIds = new ArrayList<>();
        private ExecOutcome outcome = new ExecOutcome(0, "", "", false, false, 1L);
        private RuntimeException failure;
        private final List<Map.Entry<String, ExecOutcome>> prefixOutcomes = new ArrayList<>();
        private final List<Map.Entry<String, RuntimeException>> prefixFailures = new ArrayList<>();

        CannedExecService() {
            super(new SshConnectionService(AgentTestWiring.fastSshProperties()),
                    AgentTestWiring.fastSshProperties(),
                    new NoOpResolver());
        }

        @Override
        public ExecOutcome executeForHost(UUID requestedHostId, String command, ExecLimits limits) {
            requestedHostIds.add(requestedHostId);
            commands.add(command);
            for (Map.Entry<String, RuntimeException> entry : prefixFailures) {
                if (command.startsWith(entry.getKey())) {
                    throw entry.getValue();
                }
            }
            if (failure != null) {
                throw failure;
            }
            for (Map.Entry<String, ExecOutcome> entry : prefixOutcomes) {
                if (command.startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return outcome;
        }

        void returnOutcome(ExecOutcome value) {
            this.outcome = value;
        }

        void failWith(RuntimeException value) {
            this.failure = value;
        }

        /** WHY 前缀匹配：摘要测试需要 sed/wc/tail 各自返回不同结果。 */
        void registerOutcome(String commandPrefix, ExecOutcome outcome) {
            prefixOutcomes.add(Map.entry(commandPrefix, outcome));
        }

        /** WHY 前缀匹配异常：wc 降级测试需要"主 sed 成功、wc 抛基础设施异常"的组合。 */
        void registerFailure(String commandPrefix, RuntimeException failure) {
            prefixFailures.add(Map.entry(commandPrefix, failure));
        }

        List<String> commands() {
            return List.copyOf(commands);
        }

        List<UUID> requestedHostIds() {
            return List.copyOf(requestedHostIds);
        }
    }

    /** 永不被调用的解析器：{@link CannedExecService} 覆盖了唯一的入口。 */
    private static final class NoOpResolver implements SshTargetResolver {

        @Override
        public <T> T withTarget(UUID requestedHostId, Function<SshTarget, T> action) {
            throw new AssertionError("替身不该走到解析器");
        }
    }
}

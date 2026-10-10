package com.ananoesis.shell.ai;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.ai.chat.model.ToolContext;

import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ssh.ExecOutcome;
import com.ananoesis.shell.ssh.PtyCommandGateway;
import com.ananoesis.shell.ssh.PtyCommandScheduler;
import com.ananoesis.shell.ssh.SessionRuntime;
import com.ananoesis.shell.ssh.SshExecService;
import com.ananoesis.shell.ssh.SshTerminalService;

/**
 * {@link AgentTools} 静态/包私有方法的分支覆盖。
 *
 * <p>WHY 独立测试：原测试覆盖工具调用闭环，但 format/countLines/firstLines/
 * requireHostId/hostIdOf/sessionIdOf/isNestedShell/interruptInFlightCommand
 * 等方法的分支未被覆盖。</p>
 */
@DisplayName("AgentTools 静态/工具方法分支覆盖")
class AgentToolsStaticMethodsTest {

    // ==================================================================
    // format
    // ==================================================================

    @Nested
    @DisplayName("format(ExecOutcome)")
    class Format {

        @Test
        @DisplayName("正常退出、无超时/截断、有 stdout")
        void normalWithStdout() {
            var outcome = new ExecOutcome(0, "hello\n", "", false, false, 100L);
            String result = AgentTools.format(outcome);
            assertThat(result).startsWith("exit=0\n");
            assertThat(result).contains("hello\n");
            assertThat(result).doesNotContain("超时");
            assertThat(result).doesNotContain("截断");
        }

        @Test
        @DisplayName("超时标注")
        void timedOutFlag() {
            // ExecOutcome(exitCode, stdout, stderr, truncated, timedOut, durationMillis)
            var outcome = new ExecOutcome(124, "", "", false, true, 30000L);
            String result = AgentTools.format(outcome);
            assertThat(result).contains("[执行超时");
        }

        @Test
        @DisplayName("截断标注")
        void truncatedFlag() {
            // ExecOutcome(exitCode, stdout, stderr, truncated, timedOut, durationMillis)
            var outcome = new ExecOutcome(0, "", "", true, false, 100L);
            String result = AgentTools.format(outcome);
            assertThat(result).contains("[输出超过上限");
        }

        @Test
        @DisplayName("同时超时+截断")
        void bothTimedOutAndTruncated() {
            var outcome = new ExecOutcome(1, "", "", true, true, 100L);
            String result = AgentTools.format(outcome);
            assertThat(result).contains("[执行超时");
            assertThat(result).contains("[输出超过上限");
        }

        @Test
        @DisplayName("空 stdout 不输出 stdout 段")
        void emptyStdoutNotAppended() {
            var outcome = new ExecOutcome(0, "", "", false, false, 100L);
            String result = AgentTools.format(outcome);
            assertThat(result).isEqualTo("exit=0\n");
        }

        @Test
        @DisplayName("有 stderr 时输出 stderr 段")
        void stderrAppended() {
            var outcome = new ExecOutcome(0, "out\n", "err\n", false, false, 100L);
            String result = AgentTools.format(outcome);
            assertThat(result).contains("--- stderr ---");
            assertThat(result).contains("err\n");
        }

        @Test
        @DisplayName("空 stderr 不输出 stderr 段")
        void emptyStderrNotAppended() {
            var outcome = new ExecOutcome(0, "out\n", "", false, false, 100L);
            String result = AgentTools.format(outcome);
            assertThat(result).doesNotContain("--- stderr ---");
        }

        @Test
        @DisplayName("stdout 无尾部换行 + stderr 非空时补换行")
        void stdoutNoTrailingNewlineWithStderr() {
            var outcome = new ExecOutcome(0, "no-newline", "err-msg\n", false, false, 100L);
            String result = AgentTools.format(outcome);
            // stdout 与 stderr 之间应有换行分隔
            assertThat(result).contains("no-newline\n\n--- stderr ---");
        }

        @Test
        @DisplayName("stderr 无尾部换行时补换行")
        void stderrNoTrailingNewlineGetsOne() {
            var outcome = new ExecOutcome(0, "", "err-no-nl", false, false, 100L);
            String result = AgentTools.format(outcome);
            // stderr 段末尾应有换行
            assertThat(result).endsWith("err-no-nl\n");
        }
    }

    // ==================================================================
    // countLines
    // ==================================================================

    @Nested
    @DisplayName("countLines（反射）")
    class CountLines {
        private int invoke(String text) throws Exception {
            Method m = AgentTools.class.getDeclaredMethod("countLines", String.class);
            m.setAccessible(true);
            return (int) m.invoke(null, text);
        }

        @Test
        @DisplayName("null → 0")
        void nullIsZero() throws Exception {
            assertThat(invoke(null)).isEqualTo(0);
        }

        @Test
        @DisplayName("空串 → 0")
        void emptyIsZero() throws Exception {
            assertThat(invoke("")).isEqualTo(0);
        }

        @Test
        @DisplayName("单行无换行 → 1")
        void singleLineNoNewline() throws Exception {
            assertThat(invoke("hello")).isEqualTo(1);
        }

        @Test
        @DisplayName("单行有尾部换行 → 1")
        void singleLineWithNewline() throws Exception {
            assertThat(invoke("hello\n")).isEqualTo(1);
        }

        @Test
        @DisplayName("多行 → 正确计数")
        void multipleLines() throws Exception {
            assertThat(invoke("l1\nl2\nl3\n")).isEqualTo(3);
        }

        @Test
        @DisplayName("多行无尾部换行 → 正确计数")
        void multipleLinesNoTrailingNewline() throws Exception {
            assertThat(invoke("l1\nl2\nl3")).isEqualTo(3);
        }

        @Test
        @DisplayName("连续换行不计数")
        void consecutiveNewlines() throws Exception {
            // "l1\n\nl2\n" → 2 行（中间空行不算独立行，与 wc -l 一致）
            assertThat(invoke("l1\n\nl2\n")).isEqualTo(2);
        }
    }

    // ==================================================================
    // firstLines
    // ==================================================================

    @Nested
    @DisplayName("firstLines（反射）")
    class FirstLines {
        private String invoke(String text, int limit) throws Exception {
            Method m = AgentTools.class.getDeclaredMethod("firstLines", String.class, int.class);
            m.setAccessible(true);
            return (String) m.invoke(null, text, limit);
        }

        @Test
        @DisplayName("文本行数少于 limit → 原样返回")
        void fewerLinesThanLimit() throws Exception {
            assertThat(invoke("a\nb\n", 5)).isEqualTo("a\nb\n");
        }

        @Test
        @DisplayName("文本行数等于 limit → 原样返回")
        void exactLines() throws Exception {
            assertThat(invoke("a\nb\nc\n", 3)).isEqualTo("a\nb\nc\n");
        }

        @Test
        @DisplayName("文本行数超过 limit → 截取前 limit 行")
        void moreLinesThanLimit() throws Exception {
            String result = invoke("a\nb\nc\nd\ne\n", 2);
            assertThat(result).isEqualTo("a\nb\n");
        }
    }

    // ==================================================================
    // requireHostId
    // ==================================================================

    @Nested
    @DisplayName("requireHostId（反射）")
    class RequireHostId {
        private UUID invoke(ToolContext ctx) throws Exception {
            Method m = AgentTools.class.getDeclaredMethod("requireHostId", ToolContext.class);
            m.setAccessible(true);
            try {
                return (UUID) m.invoke(null, ctx);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw e;
            }
        }

        @Test
        @DisplayName("null context → 抛 IllegalArgumentException")
        void nullContextThrows() {
            assertThatThrownBy(() -> invoke(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("缺少 hostId key → 抛 IllegalArgumentException")
        void missingKeyThrows() {
            assertThatThrownBy(() -> invoke(new ToolContext(Map.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("UUID 类型直接返回")
        void uuidTypeReturned() throws Exception {
            UUID expected = UUID.randomUUID();
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, expected)))).isEqualTo(expected);
        }

        @Test
        @DisplayName("合法 UUID 字符串可解析")
        void validStringParsed() throws Exception {
            UUID expected = UUID.randomUUID();
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, expected.toString())))).isEqualTo(expected);
        }

        @Test
        @DisplayName("非法 UUID 字符串 → 抛异常")
        void invalidStringThrows() {
            assertThatThrownBy(() -> invoke(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, "not-a-uuid"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不是合法 UUID");
        }

        @Test
        @DisplayName("其他类型 → 抛异常")
        void otherTypeThrows() {
            assertThatThrownBy(() -> invoke(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, 123))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // hostIdOf
    // ==================================================================

    @Nested
    @DisplayName("hostIdOf（反射）")
    class HostIdOf {
        private UUID invoke(ToolContext ctx) throws Exception {
            Method m = AgentTools.class.getDeclaredMethod("hostIdOf", ToolContext.class);
            m.setAccessible(true);
            return (UUID) m.invoke(null, ctx);
        }

        @Test
        @DisplayName("null context → null")
        void nullContextReturnsNull() throws Exception {
            assertThat(invoke(null)).isNull();
        }

        @Test
        @DisplayName("UUID 类型直接返回")
        void uuidTypeReturned() throws Exception {
            UUID expected = UUID.randomUUID();
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, expected)))).isEqualTo(expected);
        }

        @Test
        @DisplayName("合法 UUID 字符串可解析")
        void validStringParsed() throws Exception {
            UUID expected = UUID.randomUUID();
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, expected.toString())))).isEqualTo(expected);
        }

        @Test
        @DisplayName("非法 UUID 字符串 → null（静默失败）")
        void invalidStringReturnsNull() throws Exception {
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, "bad")))).isNull();
        }

        @Test
        @DisplayName("其他类型 → null")
        void otherTypeReturnsNull() throws Exception {
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_HOST_ID, 123)))).isNull();
        }

        @Test
        @DisplayName("缺少 key → null")
        void missingKeyReturnsNull() throws Exception {
            assertThat(invoke(new ToolContext(Map.of()))).isNull();
        }
    }

    // ==================================================================
    // sessionIdOf
    // ==================================================================

    @Nested
    @DisplayName("sessionIdOf（反射）")
    class SessionIdOf {
        private String invoke(ToolContext ctx) throws Exception {
            Method m = AgentTools.class.getDeclaredMethod("sessionIdOf", ToolContext.class);
            m.setAccessible(true);
            return (String) m.invoke(null, ctx);
        }

        @Test
        @DisplayName("null context → null")
        void nullContextReturnsNull() throws Exception {
            assertThat(invoke(null)).isNull();
        }

        @Test
        @DisplayName("String 类型直接返回")
        void stringTypeReturned() throws Exception {
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_SESSION_ID, "s-1")))).isEqualTo("s-1");
        }

        @Test
        @DisplayName("UUID 类型转 toString")
        void uuidTypeConverted() throws Exception {
            UUID id = UUID.randomUUID();
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_SESSION_ID, id)))).isEqualTo(id.toString());
        }

        @Test
        @DisplayName("其他类型 → null")
        void otherTypeReturnsNull() throws Exception {
            assertThat(invoke(new ToolContext(Map.of(AgentTools.CTX_SESSION_ID, 123)))).isNull();
        }

        @Test
        @DisplayName("缺少 key → null")
        void missingKeyReturnsNull() throws Exception {
            assertThat(invoke(new ToolContext(Map.of()))).isNull();
        }
    }

    // ==================================================================
    // isNestedShell / interruptInFlightCommand
    // ==================================================================

    @Nested
    @DisplayName("isNestedShell")
    class IsNestedShell {
        @Test
        @DisplayName("无网关 → false")
        void noGatewayReturnsFalse() {
            AgentTools tools = new AgentTools(mock(SshExecService.class), mock(SettingsService.class));
            assertThat(tools.isNestedShell("s-1")).isFalse();
        }

        @Test
        @DisplayName("null sessionId → false")
        void nullSessionReturnsFalse() {
            AgentTools tools = new AgentTools(mock(SshExecService.class), mock(SettingsService.class));
            assertThat(tools.isNestedShell(null)).isFalse();
        }

        @Test
        @DisplayName("空白 sessionId → false")
        void blankSessionReturnsFalse() {
            AgentTools tools = new AgentTools(mock(SshExecService.class), mock(SettingsService.class));
            assertThat(tools.isNestedShell("  ")).isFalse();
        }

        @Test
        @DisplayName("有网关且命中 → 转发")
        void withGatewayDelegates() throws Exception {
            // 通过反射调用 AgentTools.isNestedShell，避免编译期依赖问题
            SshTerminalService terminalService = mock(SshTerminalService.class);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);
            PtyCommandScheduler scheduler = mock(PtyCommandScheduler.class);
            when(scheduler.isNestedShell()).thenReturn(true);
            SessionRuntime runtime = mock(SessionRuntime.class);
            when(runtime.scheduler()).thenReturn(scheduler);
            when(terminalService.findRuntime("s-1")).thenReturn(runtime);
            AgentTools tools = new AgentTools(
                    mock(SshExecService.class), mock(SettingsService.class), gateway);
            assertThat(tools.isNestedShell("s-1")).isTrue();
        }
    }

    @Nested
    @DisplayName("interruptInFlightCommand")
    class InterruptInFlightCommand {
        @Test
        @DisplayName("无网关 → 静默跳过")
        void noGatewaySilentlySkips() {
            AgentTools tools = new AgentTools(mock(SshExecService.class), mock(SettingsService.class));
            tools.interruptInFlightCommand("s-1"); // 不抛即过
        }

        @Test
        @DisplayName("null sessionId → 静默跳过")
        void nullSessionSilentlySkips() {
            AgentTools tools = new AgentTools(mock(SshExecService.class), mock(SettingsService.class));
            tools.interruptInFlightCommand(null);
        }

        @Test
        @DisplayName("有网关且命中 → 转发 interruptCurrent")
        void withGatewayDelegates() {
            PtyCommandGateway gateway = mock(PtyCommandGateway.class);
            PtyCommandScheduler scheduler = mock(PtyCommandScheduler.class);
            when(gateway.findScheduler("s-1")).thenReturn(scheduler);
            AgentTools tools = new AgentTools(
                    mock(SshExecService.class), mock(SettingsService.class), gateway);
            tools.interruptInFlightCommand("s-1");
            verify(scheduler).interruptCurrent();
        }

        @Test
        @DisplayName("有网关但未命中 → 不中断")
        void withGatewayNoMatch() {
            PtyCommandGateway gateway = mock(PtyCommandGateway.class);
            when(gateway.findScheduler("missing")).thenReturn(null);
            AgentTools tools = new AgentTools(
                    mock(SshExecService.class), mock(SettingsService.class), gateway);
            tools.interruptInFlightCommand("missing"); // 不抛即过
            verify(gateway, never()).findScheduler(Mockito.isNull());
        }
    }
}

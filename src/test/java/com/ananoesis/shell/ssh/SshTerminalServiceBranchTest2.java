package com.ananoesis.shell.ssh;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.ananoesis.shell.config.SshProperties;
import com.ananoesis.shell.service.CommandExecutionService;
import com.ananoesis.shell.service.ConversationService;

/**
 * {@link SshTerminalService} 构造器 null 校验与静态辅助方法的分支覆盖。
 *
 * <p>WHY 独立测试：原测试覆盖 open/createTerminal 闭环，但构造器 7 个参数的
 * null 校验分支、extractOutputSummary 的截断/空值/无换行分支未被覆盖。</p>
 */
@DisplayName("SshTerminalService 分支覆盖")
class SshTerminalServiceBranchTest2 {

    @Nested
    @DisplayName("构造器 null 校验")
    class ConstructorNulls {

        private SshTerminalService createAll() {
            return new SshTerminalService(
                    mock(SshConnectionService.class),
                    mock(SshProperties.class),
                    mock(TerminalSessionRegistry.class),
                    mock(SshTargetResolver.class),
                    mock(SessionRecorder.class),
                    mock(CommandExecutionService.class),
                    mock(ConversationService.class));
        }

        @Test
        @DisplayName("connection 为 null 时抛异常")
        void nullConnection() {
            assertThatThrownBy(() -> new SshTerminalService(
                    null, mock(SshProperties.class), mock(TerminalSessionRegistry.class),
                    mock(SshTargetResolver.class), mock(SessionRecorder.class),
                    mock(CommandExecutionService.class), mock(ConversationService.class)))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("connection");
        }

        @Test
        @DisplayName("properties 为 null 时抛异常")
        void nullProperties() {
            assertThatThrownBy(() -> new SshTerminalService(
                    mock(SshConnectionService.class), null, mock(TerminalSessionRegistry.class),
                    mock(SshTargetResolver.class), mock(SessionRecorder.class),
                    mock(CommandExecutionService.class), mock(ConversationService.class)))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("properties");
        }

        @Test
        @DisplayName("registry 为 null 时抛异常")
        void nullRegistry() {
            assertThatThrownBy(() -> new SshTerminalService(
                    mock(SshConnectionService.class), mock(SshProperties.class), null,
                    mock(SshTargetResolver.class), mock(SessionRecorder.class),
                    mock(CommandExecutionService.class), mock(ConversationService.class)))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("registry");
        }

        @Test
        @DisplayName("resolver 为 null 时抛异常")
        void nullResolver() {
            assertThatThrownBy(() -> new SshTerminalService(
                    mock(SshConnectionService.class), mock(SshProperties.class),
                    mock(TerminalSessionRegistry.class), null, mock(SessionRecorder.class),
                    mock(CommandExecutionService.class), mock(ConversationService.class)))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("resolver");
        }

        @Test
        @DisplayName("recorder 为 null 时抛异常")
        void nullRecorder() {
            assertThatThrownBy(() -> new SshTerminalService(
                    mock(SshConnectionService.class), mock(SshProperties.class),
                    mock(TerminalSessionRegistry.class), mock(SshTargetResolver.class),
                    null, mock(CommandExecutionService.class), mock(ConversationService.class)))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("recorder");
        }

        @Test
        @DisplayName("commandExecutions 为 null 时抛异常")
        void nullCommandExecutions() {
            assertThatThrownBy(() -> new SshTerminalService(
                    mock(SshConnectionService.class), mock(SshProperties.class),
                    mock(TerminalSessionRegistry.class), mock(SshTargetResolver.class),
                    mock(SessionRecorder.class), null, mock(ConversationService.class)))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("commandExecutions");
        }

        @Test
        @DisplayName("conversations 为 null 时抛异常")
        void nullConversations() {
            assertThatThrownBy(() -> new SshTerminalService(
                    mock(SshConnectionService.class), mock(SshProperties.class),
                    mock(TerminalSessionRegistry.class), mock(SshTargetResolver.class),
                    mock(SessionRecorder.class), mock(CommandExecutionService.class), null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("conversations");
        }
    }

    @Nested
    @DisplayName("extractOutputSummary")
    class ExtractOutputSummary {
        @Test
        @DisplayName("null 输出返回空串")
        void nullOutput() throws Exception {
            Method m = SshTerminalService.class.getDeclaredMethod("extractOutputSummary", String.class, String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, null, "cmd")).isEqualTo("");
        }

        @Test
        @DisplayName("空串输出返回空串")
        void emptyOutput() throws Exception {
            Method m = SshTerminalService.class.getDeclaredMethod("extractOutputSummary", String.class, String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "", "cmd")).isEqualTo("");
        }

        @Test
        @DisplayName("无换行符时返回空串（跳过命令回显后无内容）")
        void noNewline() throws Exception {
            Method m = SshTerminalService.class.getDeclaredMethod("extractOutputSummary", String.class, String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "just-command", "cmd")).isEqualTo("");
        }

        @Test
        @DisplayName("有换行符时跳过第一行")
        void skipsFirstLine() throws Exception {
            Method m = SshTerminalService.class.getDeclaredMethod("extractOutputSummary", String.class, String.class);
            m.setAccessible(true);
            assertThat(m.invoke(null, "cmd\necho hello", "cmd")).isEqualTo("echo hello");
        }

        @Test
        @DisplayName("超长输出截断到 500 字符")
        void truncatesLongOutput() throws Exception {
            Method m = SshTerminalService.class.getDeclaredMethod("extractOutputSummary", String.class, String.class);
            m.setAccessible(true);
            String longOutput = "cmd\n" + "x".repeat(600);
            String result = (String) m.invoke(null, longOutput, "cmd");
            assertThat(result).contains("（已截断）");
        }
    }

    @Nested
    @DisplayName("findRuntime / requireRuntime")
    class RuntimeLookup {
        @Test
        @DisplayName("findRuntime(null) 返回 null")
        void findNullReturnsNull() {
            SshTerminalService service = new SshTerminalService(
                    mock(SshConnectionService.class), mock(SshProperties.class),
                    mock(TerminalSessionRegistry.class), mock(SshTargetResolver.class),
                    mock(SessionRecorder.class), mock(CommandExecutionService.class),
                    mock(ConversationService.class));
            assertThat(service.findRuntime(null)).isNull();
        }

        @Test
        @DisplayName("findRuntime(不存在的 id) 返回 null")
        void findNonExistentReturnsNull() {
            SshTerminalService service = new SshTerminalService(
                    mock(SshConnectionService.class), mock(SshProperties.class),
                    mock(TerminalSessionRegistry.class), mock(SshTargetResolver.class),
                    mock(SessionRecorder.class), mock(CommandExecutionService.class),
                    mock(ConversationService.class));
            assertThat(service.findRuntime("non-existent")).isNull();
        }

        @Test
        @DisplayName("requireRuntime(不存在的 id) 抛 IllegalArgumentException")
        void requireNonExistentThrows() {
            SshTerminalService service = new SshTerminalService(
                    mock(SshConnectionService.class), mock(SshProperties.class),
                    mock(TerminalSessionRegistry.class), mock(SshTargetResolver.class),
                    mock(SessionRecorder.class), mock(CommandExecutionService.class),
                    mock(ConversationService.class));
            assertThatThrownBy(() -> service.requireRuntime("non-existent"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

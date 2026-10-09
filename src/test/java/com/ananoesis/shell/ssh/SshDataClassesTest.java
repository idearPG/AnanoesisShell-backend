package com.ananoesis.shell.ssh;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.config.SshProperties;

/**
 * SSH 层纯数据类 / record / 枚举的分支覆盖补测。
 *
 * <p>WHY 集中在此文件：这些类的分支缺口均为简单的 null 校验、record compact constructor
 * 参数归一、枚举 fromColumnValue 匹配/不匹配等，逻辑独立且无外部依赖，集中补测效率最高。</p>
 */
@DisplayName("SSH 数据类分支覆盖")
class SshDataClassesTest {

    // ==================================================================
    // ExecOutcome (6 missed)
    // ==================================================================

    @Nested
    @DisplayName("ExecOutcome")
    class ExecOutcomeTests {

        @Test
        @DisplayName("compact constructor: null stdout 归一为空串")
        void nullStdoutNormalizedToEmpty() {
            ExecOutcome outcome = new ExecOutcome(0, null, "err", false, false, 100L);
            assertThat(outcome.stdout()).isEmpty();
            assertThat(outcome.stderr()).isEqualTo("err");
        }

        @Test
        @DisplayName("compact constructor: null stderr 归一为空串")
        void nullStderrNormalizedToEmpty() {
            ExecOutcome outcome = new ExecOutcome(0, "out", null, false, false, 100L);
            assertThat(outcome.stdout()).isEqualTo("out");
            assertThat(outcome.stderr()).isEmpty();
        }

        @Test
        @DisplayName("compact constructor: 两者均 null 时均归一为空串")
        void bothNullNormalizedToEmpty() {
            ExecOutcome outcome = new ExecOutcome(0, null, null, false, false, 100L);
            assertThat(outcome.stdout()).isEmpty();
            assertThat(outcome.stderr()).isEmpty();
        }

        @Test
        @DisplayName("isSuccess: 未超时且退出码为 0 时返回 true")
        void isSuccessTrueWhenNotTimedOutAndExitCodeZero() {
            ExecOutcome outcome = new ExecOutcome(0, "", "", false, false, 100L);
            assertThat(outcome.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("isSuccess: 超时时返回 false（即使退出码为 0）")
        void isSuccessFalseWhenTimedOut() {
            ExecOutcome outcome = new ExecOutcome(0, "", "", false, true, 100L);
            assertThat(outcome.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("isSuccess: 退出码非 0 时返回 false")
        void isSuccessFalseWhenExitCodeNonZero() {
            ExecOutcome outcome = new ExecOutcome(1, "", "", false, false, 100L);
            assertThat(outcome.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("EXIT_CODE_UNKNOWN 常量为 -1")
        void exitCodeUnknownIsMinusOne() {
            assertThat(ExecOutcome.EXIT_CODE_UNKNOWN).isEqualTo(-1);
        }
    }

    // ==================================================================
    // SshTarget (6 missed)
    // ==================================================================

    @Nested
    @DisplayName("SshTarget")
    class SshTargetTests {

        @Test
        @DisplayName("compact constructor: host 为 null 时抛异常")
        void nullHostThrows() {
            assertThatThrownBy(() -> new SshTarget(null, 22, "user", SshAuthMethod.password("pw")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("compact constructor: host 为空白时抛异常")
        void blankHostThrows() {
            assertThatThrownBy(() -> new SshTarget("  ", 22, "user", SshAuthMethod.password("pw")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("compact constructor: port 为 0 时抛异常")
        void zeroPortThrows() {
            assertThatThrownBy(() -> new SshTarget("host", 0, "user", SshAuthMethod.password("pw")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("compact constructor: port 超过 65535 时抛异常")
        void portAboveMaxThrows() {
            assertThatThrownBy(() -> new SshTarget("host", 70000, "user", SshAuthMethod.password("pw")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("compact constructor: username 为 null 时抛异常")
        void nullUsernameThrows() {
            assertThatThrownBy(() -> new SshTarget("host", 22, null, SshAuthMethod.password("pw")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("username");
        }

        @Test
        @DisplayName("compact constructor: auth 为 null 时抛异常")
        void nullAuthThrows() {
            assertThatThrownBy(() -> new SshTarget("host", 22, "user", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("of 静态工厂与构造器行为一致")
        void ofFactoryDelegatesToConstructor() {
            SshTarget target = SshTarget.of("host", 22, "user", SshAuthMethod.password("pw"));
            assertThat(target.host()).isEqualTo("host");
            assertThat(target.port()).isEqualTo(22);
        }

        @Test
        @DisplayName("toString 不泄露凭据细节")
        void toStringDoesNotLeakCredentials() {
            SshTarget target = new SshTarget("host", 22, "user", SshAuthMethod.password("secret"));
            String str = target.toString();
            assertThat(str).contains("host").contains("22").contains("user");
            assertThat(str).doesNotContain("secret");
        }
    }

    // ==================================================================
    // ShellFrame (4 missed)
    // ==================================================================

    @Nested
    @DisplayName("ShellFrame")
    class ShellFrameTests {

        @Test
        @DisplayName("compact constructor: type 为 null 时抛异常")
        void nullTypeThrows() {
            assertThatThrownBy(() -> new ShellFrame(null, "n", "c", "p"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type");
        }

        @Test
        @DisplayName("compact constructor: null nonce 归一为空串")
        void nullNonceNormalizedToEmpty() {
            ShellFrame frame = new ShellFrame(ShellFrameType.PROMPT, null, "c", "p");
            assertThat(frame.nonce()).isEmpty();
        }

        @Test
        @DisplayName("compact constructor: null commandId 归一为空串")
        void nullCommandIdNormalizedToEmpty() {
            ShellFrame frame = new ShellFrame(ShellFrameType.PROMPT, "n", null, "p");
            assertThat(frame.commandId()).isEmpty();
        }

        @Test
        @DisplayName("compact constructor: null payload 归一为空串")
        void nullPayloadNormalizedToEmpty() {
            ShellFrame frame = new ShellFrame(ShellFrameType.PROMPT, "n", "c", null);
            assertThat(frame.payload()).isEmpty();
        }
    }

    // ==================================================================
    // SshCloseReason (2 missed)
    // ==================================================================

    @Nested
    @DisplayName("SshCloseReason")
    class SshCloseReasonTests {

        @Test
        @DisplayName("fromColumnValue: 已知值返回对应枚举")
        void knownValueReturnsEnum() {
            assertThat(SshCloseReason.fromColumnValue("user_disconnect")).isEqualTo(SshCloseReason.USER_DISCONNECT);
            assertThat(SshCloseReason.fromColumnValue("timeout")).isEqualTo(SshCloseReason.TIMEOUT);
            assertThat(SshCloseReason.fromColumnValue("remote_closed")).isEqualTo(SshCloseReason.REMOTE_CLOSED);
        }

        @Test
        @DisplayName("fromColumnValue: null 返回 ERROR")
        void nullValueReturnsError() {
            assertThat(SshCloseReason.fromColumnValue(null)).isEqualTo(SshCloseReason.ERROR);
        }

        @Test
        @DisplayName("fromColumnValue: 未知值返回 ERROR")
        void unknownValueReturnsError() {
            assertThat(SshCloseReason.fromColumnValue("bogus")).isEqualTo(SshCloseReason.ERROR);
        }

        @Test
        @DisplayName("toContractEndReason: USER_DISCONNECT 有映射")
        void userDisconnectHasMapping() {
            assertThat(SshCloseReason.USER_DISCONNECT.toContractEndReason()).isNotNull();
        }

        @Test
        @DisplayName("toContractEndReason: AUTH_FAILED 返回 null")
        void authFailedReturnsNull() {
            assertThat(SshCloseReason.AUTH_FAILED.toContractEndReason()).isNull();
        }
    }

    // ==================================================================
    // ExecLimits (3 missed)
    // ==================================================================

    @Nested
    @DisplayName("ExecLimits")
    class ExecLimitsTests {

        @Test
        @DisplayName("compact constructor: timeout 为 null 时抛异常")
        void nullTimeoutThrows() {
            assertThatThrownBy(() -> new ExecLimits(null, 1024))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("compact constructor: timeout 为负数时抛异常")
        void negativeTimeoutThrows() {
            assertThatThrownBy(() -> new ExecLimits(Duration.ofSeconds(-1), 1024))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("compact constructor: timeout 为零时抛异常")
        void zeroTimeoutThrows() {
            assertThatThrownBy(() -> new ExecLimits(Duration.ZERO, 1024))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("compact constructor: maxOutputBytes 为 0 时抛异常")
        void zeroMaxOutputBytesThrows() {
            assertThatThrownBy(() -> new ExecLimits(Duration.ofSeconds(10), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxOutputBytes");
        }

        @Test
        @DisplayName("compact constructor: maxOutputBytes 为负数时抛异常")
        void negativeMaxOutputBytesThrows() {
            assertThatThrownBy(() -> new ExecLimits(Duration.ofSeconds(10), -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxOutputBytes");
        }

        @Test
        @DisplayName("from 工厂方法取自 SshProperties")
        void fromPropertiesFactory() {
            SshProperties props = new SshProperties();
            props.setExecTimeout(Duration.ofSeconds(30));
            props.setExecMaxOutputBytes(2048);
            ExecLimits limits = ExecLimits.from(props);
            assertThat(limits.timeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(limits.maxOutputBytes()).isEqualTo(2048);
        }
    }
}

package com.ananoesis.shell.ssh;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.security.SecretText;

import net.schmizz.sshj.userauth.UserAuthException;

/**
 * {@link SshConnectionService} 内部方法分支补测。
 *
 * <p>WHY 独立测试：覆盖 classify/kindOf/millisOf/requireNonBlank/isDecryptionFailure
 * 等私有静态方法的分支。</p>
 */
@DisplayName("SshConnectionService 内部方法分支")
class SshConnectionServiceClassifyTest {

    // ---- millisOf ----

    @Nested
    @DisplayName("millisOf")
    class MillisOf {
        @Test
        @DisplayName("零值返回 1")
        void zeroReturns1() throws Exception {
            assertThat(invokeMillisOf(Duration.ZERO)).isEqualTo(1);
        }

        @Test
        @DisplayName("负值返回 1")
        void negativeReturns1() throws Exception {
            assertThat(invokeMillisOf(Duration.ofMillis(-100))).isEqualTo(1);
        }

        @Test
        @DisplayName("正常值原样返回")
        void normalReturnsValue() throws Exception {
            assertThat(invokeMillisOf(Duration.ofMillis(5000))).isEqualTo(5000);
        }

        @Test
        @DisplayName("超 Integer.MAX_VALUE 返回 Integer.MAX_VALUE")
        void overflowReturnsMaxInt() throws Exception {
            assertThat(invokeMillisOf(Duration.ofDays(365 * 100))).isEqualTo(Integer.MAX_VALUE);
        }
    }

    // ---- kindOf ----

    @Nested
    @DisplayName("kindOf 异常分类")
    class KindOf {
        @Test
        @DisplayName("UserAuthException → AUTH_FAILED")
        void userAuthExceptionClassified() throws Exception {
            assertThat(invokeKindOf(new UserAuthException("bad creds")))
                    .isEqualTo(SshFailureKind.AUTH_FAILED);
        }

        @Test
        @DisplayName("SocketTimeoutException → CONNECT_TIMEOUT")
        void socketTimeoutClassified() throws Exception {
            assertThat(invokeKindOf(new SocketTimeoutException("timed out")))
                    .isEqualTo(SshFailureKind.CONNECT_TIMEOUT);
        }

        @Test
        @DisplayName("UnknownHostException → HOST_UNREACHABLE")
        void unknownHostClassified() throws Exception {
            assertThat(invokeKindOf(new UnknownHostException("no such host")))
                    .isEqualTo(SshFailureKind.HOST_UNREACHABLE);
        }

        @Test
        @DisplayName("NoRouteToHostException → HOST_UNREACHABLE")
        void noRouteClassified() throws Exception {
            assertThat(invokeKindOf(new NoRouteToHostException("no route")))
                    .isEqualTo(SshFailureKind.HOST_UNREACHABLE);
        }

        @Test
        @DisplayName("ConnectException → HOST_UNREACHABLE")
        void connectExceptionClassified() throws Exception {
            assertThat(invokeKindOf(new ConnectException("connection refused")))
                    .isEqualTo(SshFailureKind.HOST_UNREACHABLE);
        }

        @Test
        @DisplayName("SocketException → HOST_UNREACHABLE")
        void socketExceptionClassified() throws Exception {
            assertThat(invokeKindOf(new SocketException("network down")))
                    .isEqualTo(SshFailureKind.HOST_UNREACHABLE);
        }

        @Test
        @DisplayName("cause 链中有 SocketTimeoutException → CONNECT_TIMEOUT")
        void wrappedTimeoutClassified() throws Exception {
            Exception wrapped = new RuntimeException("wrapper",
                    new RuntimeException("inner", new SocketTimeoutException("timeout")));
            assertThat(invokeKindOf(wrapped)).isEqualTo(SshFailureKind.CONNECT_TIMEOUT);
        }

        @Test
        @DisplayName("消息含 timeout → CONNECT_TIMEOUT")
        void messageTimeoutClassified() throws Exception {
            assertThat(invokeKindOf(new RuntimeException("connection timeout occurred")))
                    .isEqualTo(SshFailureKind.CONNECT_TIMEOUT);
        }

        @Test
        @DisplayName("消息含 timed out → CONNECT_TIMEOUT")
        void messageTimedOutClassified() throws Exception {
            assertThat(invokeKindOf(new RuntimeException("operation timed out")))
                    .isEqualTo(SshFailureKind.CONNECT_TIMEOUT);
        }

        @Test
        @DisplayName("消息含 unreachable → HOST_UNREACHABLE")
        void messageUnreachableClassified() throws Exception {
            assertThat(invokeKindOf(new RuntimeException("host unreachable")))
                    .isEqualTo(SshFailureKind.HOST_UNREACHABLE);
        }

        @Test
        @DisplayName("消息含 resolve → HOST_UNREACHABLE")
        void messageResolveClassified() throws Exception {
            assertThat(invokeKindOf(new RuntimeException("cannot resolve host")))
                    .isEqualTo(SshFailureKind.HOST_UNREACHABLE);
        }

        @Test
        @DisplayName("无关异常 → INTERNAL")
        void unrelatedClassifiedAsInternal() throws Exception {
            assertThat(invokeKindOf(new RuntimeException("something else")))
                    .isEqualTo(SshFailureKind.INTERNAL);
        }

        @Test
        @DisplayName("null cause → INTERNAL")
        void nullCauseClassifiedAsInternal() throws Exception {
            assertThat(invokeKindOf(new RuntimeException()))
                    .isEqualTo(SshFailureKind.INTERNAL);
        }
    }

    // ---- requireNonBlank ----

    @Nested
    @DisplayName("requireNonBlank 空白校验")
    class RequireNonBlank {
        @Test
        @DisplayName("空白凭据抛 SshConnectException")
        void blankThrows() {
            assertThatThrownBy(() -> invokeRequireNonBlank(SecretText.of("   "), "密码"))
                    .isInstanceOf(SshConnectException.class)
                    .hasMessageContaining("为空");
        }

        @Test
        @DisplayName("空凭据抛 SshConnectException")
        void emptyThrows() {
            assertThatThrownBy(() -> invokeRequireNonBlank(SecretText.of(""), "密码"))
                    .isInstanceOf(SshConnectException.class);
        }

        @Test
        @DisplayName("非空白凭据不抛异常")
        void nonBlankNoThrow() throws Exception {
            invokeRequireNonBlank(SecretText.of("secret"), "密码");
            // 不抛异常即成功
        }
    }

    // ---- 反射辅助 ----

    private static int invokeMillisOf(Duration duration) throws Exception {
        Method m = SshConnectionService.class.getDeclaredMethod("millisOf", Duration.class);
        m.setAccessible(true);
        return (int) m.invoke(null, duration);
    }

    private static SshFailureKind invokeKindOf(Throwable cause) throws Exception {
        Method m = SshConnectionService.class.getDeclaredMethod("kindOf", Throwable.class);
        m.setAccessible(true);
        return (SshFailureKind) m.invoke(null, cause);
    }

    private static void invokeRequireNonBlank(SecretText secret, String what) throws Exception {
        Method m = SshConnectionService.class.getDeclaredMethod(
                "requireNonBlank", SecretText.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(null, secret, what);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}

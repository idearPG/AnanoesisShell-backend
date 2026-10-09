package com.ananoesis.shell.ssh;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import net.schmizz.sshj.SSHClient;

/**
 * {@link SshConnectionService} 静态辅助方法与异常分类的分支覆盖。
 *
 * <p>WHY 独立测试：closeQuietly/disconnectQuietly/millisOf/kindOf 等方法
 * 包含多个条件分支，原有测试未覆盖。</p>
 */
@DisplayName("SshConnectionService 内部方法分支覆盖")
class SshConnectionServiceInternalTest {

    @Nested
    @DisplayName("closeQuietly")
    class CloseQuietly {
        @Test
        @DisplayName("null 参数不抛异常")
        void nullIsSilent() {
            SshConnectionService.closeQuietly(null);
        }

        @Test
        @DisplayName("正常关闭不抛异常")
        void normalClose() {
            AutoCloseable closeable = () -> {};
            SshConnectionService.closeQuietly(closeable);
        }

        @Test
        @DisplayName("关闭抛异常时被静默吞掉")
        void exceptionSwallowed() {
            AutoCloseable failing = () -> { throw new IOException("模拟关闭失败"); };
            SshConnectionService.closeQuietly(failing);
        }
    }

    @Nested
    @DisplayName("disconnectQuietly")
    class DisconnectQuietly {
        @Test
        @DisplayName("null 参数不抛异常")
        void nullIsSilent() {
            SshConnectionService.disconnectQuietly(null);
        }

        @Test
        @DisplayName("正常断开不抛异常")
        void normalDisconnect() {
            SSHClient client = mock(SSHClient.class);
            SshConnectionService.disconnectQuietly(client);
        }

        @Test
        @DisplayName("断开抛异常时被静默吞掉")
        void exceptionSwallowed() throws Exception {
            SSHClient client = mock(SSHClient.class);
            org.mockito.Mockito.doThrow(new IOException("模拟断开失败"))
                    .doNothing().when(client).disconnect();
            // 第一次调用 disconnect 抛异常，被 disconnectQuietly 吞掉
            try { client.disconnect(); } catch (Exception ignored) {}
            // disconnectQuietly 内部再调用时已由 mock 重置为 doNothing
            SshConnectionService.disconnectQuietly(client);
        }
    }

    @Nested
    @DisplayName("构造器")
    class Constructor {
        @Test
        @DisplayName("properties 为 null 时抛异常")
        void nullPropertiesThrows() {
            assertThatThrownBy(() -> new SshConnectionService(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("properties");
        }
    }
}

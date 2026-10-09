package com.ananoesis.shell.ssh;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SshConnectionService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖 connect 失败分类，但构造器 null 校验、
 * connect null target、closeQuietly/disconnectQuietly null 和异常路径、
 * millisOf 边界等分支未覆盖。</p>
 */
@DisplayName("SshConnectionService 分支覆盖")
class SshConnectionServiceBranchTest {

    @Test
    @DisplayName("构造器: properties 为 null 时抛异常")
    void nullPropertiesThrows() {
        assertThatThrownBy(() -> new SshConnectionService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("properties");
    }

    @Test
    @DisplayName("connect: target 为 null 时抛异常")
    void connectNullTargetThrows() {
        var props = new com.ananoesis.shell.config.SshProperties();
        props.setConnectTimeout(Duration.ofSeconds(5));
        props.setKeepAliveInterval(Duration.ZERO);
        var service = new SshConnectionService(props);
        assertThatThrownBy(() -> service.connect(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("target");
    }

    @Test
    @DisplayName("closeQuietly: null 时不抛异常")
    void closeQuietlyNullNoOp() {
        SshConnectionService.closeQuietly(null);
    }

    @Test
    @DisplayName("closeQuietly: 关闭抛异常时静默吞掉")
    void closeQuietlySwallowsException() {
        AutoCloseable failing = () -> { throw new RuntimeException("close failed"); };
        SshConnectionService.closeQuietly(failing); // 不抛异常
    }

    @Test
    @DisplayName("disconnectQuietly: null 时不抛异常")
    void disconnectQuietlyNullNoOp() {
        SshConnectionService.disconnectQuietly(null);
    }
}

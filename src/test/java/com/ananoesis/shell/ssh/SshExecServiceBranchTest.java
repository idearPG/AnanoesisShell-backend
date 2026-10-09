package com.ananoesis.shell.ssh;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.config.SshProperties;

/**
 * {@link SshExecService} 构造器分支补测。
 *
 * <p>WHY 独立测试：覆盖 3 个构造器 null 校验分支。</p>
 */
@DisplayName("SshExecService 构造器分支")
class SshExecServiceBranchTest {

    @Test
    @DisplayName("构造器: connection 为 null 抛 NPE")
    void nullConnectionThrows() {
        assertThatThrownBy(() -> new SshExecService(
                null,
                new SshProperties(),
                org.mockito.Mockito.mock(SshTargetResolver.class)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connection");
    }

    @Test
    @DisplayName("构造器: properties 为 null 抛 NPE")
    void nullPropertiesThrows() {
        assertThatThrownBy(() -> new SshExecService(
                org.mockito.Mockito.mock(SshConnectionService.class),
                null,
                org.mockito.Mockito.mock(SshTargetResolver.class)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("properties");
    }

    @Test
    @DisplayName("构造器: resolver 为 null 抛 NPE")
    void nullResolverThrows() {
        assertThatThrownBy(() -> new SshExecService(
                org.mockito.Mockito.mock(SshConnectionService.class),
                new SshProperties(),
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resolver");
    }
}

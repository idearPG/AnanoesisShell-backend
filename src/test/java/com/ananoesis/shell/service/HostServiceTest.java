package com.ananoesis.shell.service;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.mapper.HostMapper;

/**
 * {@link HostService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原类无单元测试，8 个分支（null 校验、host 不存在、authType 校验）
 * 均未覆盖。</p>
 */
@DisplayName("HostService")
class HostServiceTest {

    private HostMapper hostMapper;
    private CredentialStoreService credentials;
    private HostService hostService;

    @BeforeEach
    void setUp() {
        hostMapper = mock(HostMapper.class);
        credentials = mock(CredentialStoreService.class);
        hostService = new HostService(hostMapper, credentials);
    }

    @Test
    @DisplayName("构造器: hostMapper 为 null 时抛异常")
    void nullHostMapperThrows() {
        assertThatThrownBy(() -> new HostService(null, credentials))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hostMapper");
    }

    @Test
    @DisplayName("构造器: credentials 为 null 时抛异常")
    void nullCredentialsThrows() {
        assertThatThrownBy(() -> new HostService(hostMapper, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    @DisplayName("findById: host 不存在时抛异常")
    void findByIdNotFoundThrows() {
        when(hostMapper.selectById("nonexistent")).thenReturn(null);
        assertThatThrownBy(() -> hostService.findById(UUID.randomUUID()))
                .isInstanceOf(HostNotFoundException.class);
    }

    @Test
    @DisplayName("requireTarget: host 不存在时抛异常")
    void requireTargetNotFoundThrows() {
        when(hostMapper.selectById("nonexistent")).thenReturn(null);
        assertThatThrownBy(() -> hostService.requireTarget(UUID.randomUUID()))
                .isInstanceOf(HostNotFoundException.class);
    }

    @Test
    @DisplayName("delete: host 不存在时抛异常")
    void deleteNotFoundThrows() {
        when(hostMapper.selectById("nonexistent")).thenReturn(null);
        assertThatThrownBy(() -> hostService.delete(UUID.randomUUID()))
                .isInstanceOf(HostNotFoundException.class);
    }

    @Test
    @DisplayName("list: 空表返回空列表")
    void listEmptyReturnsEmptyList() {
        when(hostMapper.selectAllOrderedByCreation()).thenReturn(List.of());
        assertThat(hostService.list()).isEmpty();
    }
}

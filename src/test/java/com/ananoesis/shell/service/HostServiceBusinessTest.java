package com.ananoesis.shell.service;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.contract.model.AuthType;
import com.ananoesis.shell.contract.model.Host;
import com.ananoesis.shell.mapper.HostMapper;

/**
 * {@link HostService} 业务逻辑分支补测。
 *
 * <p>WHY 独立测试：覆盖 requireEntity/requireAuthType/violationsOnCreate/
 * delete/list 等方法的分支。</p>
 */
@DisplayName("HostService 业务逻辑分支")
class HostServiceBusinessTest {

    private HostMapper hostMapper;
    private CredentialStoreService credentials;
    private HostService service;

    @BeforeEach
    void setUp() {
        hostMapper = mock(HostMapper.class);
        credentials = mock(CredentialStoreService.class);
        service = new HostService(hostMapper, credentials);
    }

    // ---- 构造器 ----

    @Test
    @DisplayName("构造器: hostMapper 为 null 抛 NPE")
    void nullMapperThrows() {
        assertThatThrownBy(() -> new HostService(null, credentials))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("构造器: credentials 为 null 抛 NPE")
    void nullCredentialsThrows() {
        assertThatThrownBy(() -> new HostService(hostMapper, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- requireEntity ----

    @Nested
    @DisplayName("requireEntity")
    class RequireEntity {
        @Test
        @DisplayName("null id 抛 NPE")
        void nullIdThrows() {
            assertThatThrownBy(() -> service.findById(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("不存在抛 HostNotFoundException")
        void notFoundThrows() {
            when(hostMapper.selectById(anyString())).thenReturn(null);
            assertThatThrownBy(() -> service.findById(UUID.randomUUID()))
                    .isInstanceOf(HostNotFoundException.class);
        }
    }

    // ---- requireTarget ----

    @Test
    @DisplayName("requireTarget: 不存在抛 HostNotFoundException")
    void requireTargetNotFoundThrows() {
        when(hostMapper.selectById(anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.requireTarget(UUID.randomUUID()))
                .isInstanceOf(HostNotFoundException.class);
    }

    @Test
    @DisplayName("requireTarget: 存在返回 HostTarget")
    void requireTargetFoundReturns() {
        var entity = new com.ananoesis.shell.entity.Host();
        entity.setId(UUID.randomUUID().toString());
        entity.setHost("example.com");
        entity.setPort(22);
        entity.setUsername("root");
        entity.setAuthType("password");
        when(hostMapper.selectById(anyString())).thenReturn(entity);
        var target = service.requireTarget(UUID.randomUUID());
        assertThat(target.host()).isEqualTo("example.com");
        assertThat(target.port()).isEqualTo(22);
        assertThat(target.authType()).isEqualTo(AuthType.PASSWORD);
    }

    // ---- create 校验 ----

    @Nested
    @DisplayName("create 校验")
    class CreateValidation {
        @Test
        @DisplayName("authType 为 null 抛 InvalidRequestException")
        void nullAuthTypeThrows() {
            Host request = new Host();
            request.setAuthType(null);
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        @DisplayName("password 认证但无密码抛 InvalidRequestException")
        void passwordWithoutPasswordThrows() {
            Host request = new Host();
            request.setAuthType(AuthType.PASSWORD);
            request.setHost("example.com");
            request.setPort(22);
            request.setUsername("root");
            request.setPassword(null);
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        @DisplayName("private_key 认证但无私钥抛 InvalidRequestException")
        void privateKeyWithoutKeyThrows() {
            Host request = new Host();
            request.setAuthType(AuthType.PRIVATE_KEY);
            request.setHost("example.com");
            request.setPort(22);
            request.setUsername("root");
            request.setPrivateKey(null);
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    // ---- delete ----

    @Test
    @DisplayName("delete: 不存在抛 HostNotFoundException")
    void deleteNotFoundThrows() {
        when(hostMapper.selectById(anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.delete(UUID.randomUUID()))
                .isInstanceOf(HostNotFoundException.class);
    }

    @Test
    @DisplayName("delete: 存在时删除并清理凭据")
    void deleteExistsRemoves() {
        UUID id = UUID.randomUUID();
        var entity = new com.ananoesis.shell.entity.Host();
        entity.setId(id.toString());
        entity.setHost("example.com");
        entity.setPort(22);
        entity.setUsername("root");
        entity.setAuthType("password");
        when(hostMapper.selectById(id.toString())).thenReturn(entity);
        when(credentials.deleteByOwner(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        service.delete(id);
        org.mockito.Mockito.verify(hostMapper).deleteById(id.toString());
    }

    // ---- list ----

    @Test
    @DisplayName("list: 空列表返回空")
    void listEmpty() {
        when(hostMapper.selectAllOrderedByCreation()).thenReturn(java.util.List.of());
        assertThat(service.list()).isEmpty();
    }
}

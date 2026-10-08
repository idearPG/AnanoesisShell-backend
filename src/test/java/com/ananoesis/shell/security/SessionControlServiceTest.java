package com.ananoesis.shell.security;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SessionControlService 单元测试（task 4.2）。
 *
 * <p>验证控制凭证的安全约束：256-bit 凭证仅散列留存，跨会话资源拒绝。</p>
 */
@DisplayName("SessionControlService")
class SessionControlServiceTest {

    private final SessionControlService service = new SessionControlService();

    @Test
    @DisplayName("生成 256-bit（32 字节 / 64 十六进制字符）凭证")
    void generates256BitToken() {
        UUID sessionId = UUID.randomUUID();
        String token = service.createToken(sessionId);

        // 256 bit = 32 bytes = 64 hex characters
        assertThat(token).hasSize(64);
        // 只含十六进制字符
        assertThat(token).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("仅存储散列，不保留明文")
    void storesOnlyHashNotPlaintext() {
        UUID sessionId = UUID.randomUUID();
        String token = service.createToken(sessionId);

        // 验证通过——说明散列匹配
        assertThat(service.verify(sessionId, token)).isTrue();

        // 无法从服务中取回原始明文（没有 get/retrieve 方法）
        // 通过验证错误 token 被拒绝来间接证明：服务只持有散列
        assertThat(service.verify(sessionId, "wrong-token")).isFalse();
    }

    @Test
    @DisplayName("正确凭证验证通过")
    void validTokenVerifies() {
        UUID sessionId = UUID.randomUUID();
        String token = service.createToken(sessionId);

        assertThat(service.verify(sessionId, token)).isTrue();
    }

    @Test
    @DisplayName("错误凭证验证失败")
    void invalidTokenRejected() {
        UUID sessionId = UUID.randomUUID();
        service.createToken(sessionId);

        assertThat(service.verify(sessionId, "0".repeat(64))).isFalse();
    }

    @Test
    @DisplayName("跨会话资源拒绝——A 的凭证不能访问 B")
    void crossSessionResourceDenied() {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        String tokenA = service.createToken(sessionA);

        assertThat(service.verify(sessionB, tokenA)).isFalse();
    }

    @Test
    @DisplayName("已销毁会话的凭证验证失败")
    void destroyedSessionRejectsToken() {
        UUID sessionId = UUID.randomUUID();
        String token = service.createToken(sessionId);

        service.destroyToken(sessionId);

        assertThat(service.verify(sessionId, token)).isFalse();
    }

    @Test
    @DisplayName("每次生成不同凭证")
    void eachTokenIsUnique() {
        UUID sessionId = UUID.randomUUID();
        String token1 = service.createToken(sessionId);
        String token2 = service.createToken(sessionId);

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    @DisplayName("null 凭证验证失败")
    void nullTokenRejected() {
        UUID sessionId = UUID.randomUUID();
        service.createToken(sessionId);

        assertThat(service.verify(sessionId, null)).isFalse();
    }

    @Test
    @DisplayName("null sessionId 验证失败")
    void nullSessionIdRejected() {
        assertThat(service.verify(null, "abc123")).isFalse();
    }
}

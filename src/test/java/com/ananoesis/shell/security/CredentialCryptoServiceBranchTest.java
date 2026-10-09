package com.ananoesis.shell.security;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CredentialCryptoService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖加解密闭环，但 null 校验、无可用主密钥、
 * 主密码路径的 null/blank 校验等分支未覆盖。</p>
 */
@DisplayName("CredentialCryptoService 分支覆盖")
class CredentialCryptoServiceBranchTest {

    @Test
    @DisplayName("构造器: providers 为 null 时抛异常")
    void nullProvidersThrows() {
        assertThatThrownBy(() -> new CredentialCryptoService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providers");
    }

    @Test
    @DisplayName("isAvailable: 无可用 provider 时返回 false")
    void isAvailableNoProviderReturnsFalse() {
        MasterKeyProvider failing = new StubMasterKeyProvider(false);
        CredentialCryptoService service = new CredentialCryptoService(List.of(failing));
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("encrypt: 明文为 null 时抛异常")
    void encryptNullPlaintextThrows() {
        MasterKeyProvider provider = new StubMasterKeyProvider();
        CredentialCryptoService service = new CredentialCryptoService(List.of(provider));
        assertThatThrownBy(() -> service.encrypt(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("明文");
    }

    @Test
    @DisplayName("encryptWithMasterPassword: 明文为 null 时抛异常")
    void encryptWithMasterPasswordNullPlaintextThrows() {
        CredentialCryptoService service = new CredentialCryptoService(List.of());
        assertThatThrownBy(() -> service.encryptWithMasterPassword(null, SecretText.of("pw")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("明文");
    }

    @Test
    @DisplayName("encryptWithMasterPassword: 主密码为 null 时抛异常")
    void encryptWithMasterPasswordNullPasswordThrows() {
        CredentialCryptoService service = new CredentialCryptoService(List.of());
        assertThatThrownBy(() -> service.encryptWithMasterPassword(SecretText.of("data"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("主密码");
    }

    @Test
    @DisplayName("decryptWithMasterPassword: 主密码为 null 时抛异常")
    void decryptWithMasterPasswordNullPasswordThrows() {
        CredentialCryptoService service = new CredentialCryptoService(List.of());
        assertThatThrownBy(() -> service.decryptWithMasterPassword("v1.xxx", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("主密码");
    }

    /** 测试用 MasterKeyProvider 桩。 */
    static class StubMasterKeyProvider implements MasterKeyProvider {
        private final boolean available;
        StubMasterKeyProvider() { this(true); }
        StubMasterKeyProvider(boolean available) { this.available = available; }
        @Override
        public String id() { return "stub"; }
        @Override
        public boolean isAvailable() { return available; }
        @Override
        public byte[] copyMasterKeyBytes() { return new byte[32]; }
    }
}

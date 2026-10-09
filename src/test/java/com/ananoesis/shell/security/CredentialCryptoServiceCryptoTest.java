package com.ananoesis.shell.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CredentialCryptoService} 加解密业务逻辑分支补测。
 *
 * <p>WHY 独立测试：覆盖 encrypt/decrypt 正常路径、encryptWithMasterPassword/decryptWithMasterPassword
 * 正常路径、requireNonBlank 空白校验、parse 各种格式错误等分支。</p>
 */
@DisplayName("CredentialCryptoService 加解密业务逻辑")
class CredentialCryptoServiceCryptoTest {

    /** 可用的测试 provider */
    static class TestProvider implements MasterKeyProvider {
        private final byte[] key;
        TestProvider() {
            key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
        }
        @Override public String id() { return "test"; }
        @Override public boolean isAvailable() { return true; }
        @Override public byte[] copyMasterKeyBytes() { return key.clone(); }
    }

    /** 抛异常的 provider */
    static class ThrowingProvider implements MasterKeyProvider {
        @Override public String id() { return "throwing"; }
        @Override public boolean isAvailable() { throw new RuntimeException("boom"); }
        @Override public byte[] copyMasterKeyBytes() { throw new RuntimeException("boom"); }
    }

    // ---- encrypt + decrypt 闭环 ----

    @Nested
    @DisplayName("encrypt + decrypt 闭环")
    class EncryptDecrypt {
        @Test
        @DisplayName("加密后解密得到原文")
        void roundTrip() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            SecretText original = SecretText.of("hello world");
            String envelope = service.encrypt(original);
            assertThat(envelope).startsWith("v1.");

            SecretText decrypted = service.decrypt(envelope);
            assertThat(decrypted.revealAsString()).isEqualTo("hello world");
            decrypted.wipe();
        }

        @Test
        @DisplayName("加密后明文被擦除，再次取值抛异常")
        void plaintextWipedAfterEncrypt() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            SecretText plaintext = SecretText.of("secret");
            service.encrypt(plaintext);
            // 擦除后再取值抛 IllegalStateException
            assertThatThrownBy(() -> plaintext.revealAsString())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("无可用 provider 时加密抛 CredentialProtectionException")
        void noProviderThrows() {
            var service = new CredentialCryptoService(List.of());
            assertThatThrownBy(() -> service.encrypt(SecretText.of("data")))
                    .isInstanceOf(CredentialProtectionException.class);
        }

        @Test
        @DisplayName("provider 抛异常时被跳过")
        void throwingProviderSkipped() {
            var service = new CredentialCryptoService(List.of(new ThrowingProvider()));
            assertThat(service.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("解密 OS 密钥库密文时用主密码解密抛异常")
        void decryptOsKeyWithMasterPasswordThrows() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            String envelope = service.encrypt(SecretText.of("data"));
            assertThatThrownBy(() -> service.decryptWithMasterPassword(envelope, SecretText.of("pw")))
                    .isInstanceOf(CredentialCryptoException.class)
                    .hasMessageContaining("不是由主密码");
        }
    }

    // ---- encryptWithMasterPassword + decryptWithMasterPassword 闭环 ----

    @Nested
    @DisplayName("主密码加解密闭环")
    class MasterPasswordRoundTrip {
        @Test
        @DisplayName("主密码加密后解密得到原文")
        void masterPasswordRoundTrip() {
            var service = new CredentialCryptoService(List.of());
            SecretText data = SecretText.of("secret data");
            SecretText pw = SecretText.of("my-password");
            String envelope = service.encryptWithMasterPassword(data, pw);
            assertThat(envelope).startsWith("v1.");

            SecretText decrypted = service.decryptWithMasterPassword(envelope, SecretText.of("my-password"));
            assertThat(decrypted.revealAsString()).isEqualTo("secret data");
            decrypted.wipe();
        }

        @Test
        @DisplayName("主密码为空时加密抛异常")
        void blankMasterPasswordThrows() {
            var service = new CredentialCryptoService(List.of());
            assertThatThrownBy(() -> service.encryptWithMasterPassword(
                    SecretText.of("data"), SecretText.of("   ")))
                    .isInstanceOf(CredentialProtectionException.class);
        }

        @Test
        @DisplayName("主密码为空时解密抛异常")
        void blankMasterPasswordDecryptThrows() {
            // 先正常加密
            var service = new CredentialCryptoService(List.of());
            SecretText data = SecretText.of("data");
            SecretText pw = SecretText.of("good-pw");
            String envelope = service.encryptWithMasterPassword(data, pw);
            // 用空密码解密
            assertThatThrownBy(() -> service.decryptWithMasterPassword(
                    envelope, SecretText.of("   ")))
                    .isInstanceOf(CredentialProtectionException.class);
        }

        @Test
        @DisplayName("错误密码解密抛 CredentialCryptoException")
        void wrongPasswordThrows() {
            var service = new CredentialCryptoService(List.of());
            SecretText data = SecretText.of("data");
            SecretText pw = SecretText.of("correct");
            String envelope = service.encryptWithMasterPassword(data, pw);
            assertThatThrownBy(() -> service.decryptWithMasterPassword(
                    envelope, SecretText.of("wrong")))
                    .isInstanceOf(CredentialCryptoException.class);
        }
    }

    // ---- parse 各种格式错误 ----

    @Nested
    @DisplayName("parse 格式校验")
    class ParseValidation {
        @Test
        @DisplayName("null 信封抛异常")
        void nullEnvelopeThrows() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            assertThatThrownBy(() -> service.decrypt(null))
                    .isInstanceOf(CredentialCryptoException.class)
                    .hasMessageContaining("前缀");
        }

        @Test
        @DisplayName("错误前缀抛异常")
        void wrongPrefixThrows() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            assertThatThrownBy(() -> service.decrypt("v2.xxx"))
                    .isInstanceOf(CredentialCryptoException.class)
                    .hasMessageContaining("前缀");
        }

        @Test
        @DisplayName("非 Base64 内容抛异常")
        void nonBase64Throws() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            assertThatThrownBy(() -> service.decrypt("v1.!!!invalid!!!"))
                    .isInstanceOf(CredentialCryptoException.class);
        }

        @Test
        @DisplayName("非 JSON 内容抛异常")
        void nonJsonThrows() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            String notJson = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("not-json".getBytes());
            assertThatThrownBy(() -> service.decrypt("v1." + notJson))
                    .isInstanceOf(CredentialCryptoException.class)
                    .hasMessageContaining("JSON");
        }

        @Test
        @DisplayName("版本不匹配抛异常")
        void wrongVersionThrows() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            String json = "{\"v\":99,\"kp\":\"test\",\"iv\":\"AAAAAAAAAAAAAAAA\",\"ct\":\"AAAA\"}";
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes());
            assertThatThrownBy(() -> service.decrypt("v1." + encoded))
                    .isInstanceOf(CredentialCryptoException.class)
                    .hasMessageContaining("版本");
        }

        @Test
        @DisplayName("缺少 kp 字段抛异常")
        void missingKpThrows() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            String json = "{\"v\":1,\"iv\":\"AAAAAAAAAAAAAAAA\",\"ct\":\"AAAA\"}";
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes());
            assertThatThrownBy(() -> service.decrypt("v1." + encoded))
                    .isInstanceOf(CredentialCryptoException.class)
                    .hasMessageContaining("kp");
        }

        @Test
        @DisplayName("IV 长度非法抛异常")
        void wrongIvLengthThrows() {
            var service = new CredentialCryptoService(List.of(new TestProvider()));
            String json = "{\"v\":1,\"kp\":\"test\",\"iv\":\"AA\",\"ct\":\"AAAA\"}";
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes());
            assertThatThrownBy(() -> service.decrypt("v1." + encoded))
                    .isInstanceOf(CredentialCryptoException.class)
                    .hasMessageContaining("IV");
        }
    }

    // ---- decrypt 找不到 provider ----

    @Test
    @DisplayName("decrypt: 信封 kp 与已知 provider 不匹配时抛异常")
    void decryptProviderMismatchThrows() {
        var service = new CredentialCryptoService(List.of(new TestProvider()));
        // 构造一个 kp="other" 的合法信封
        byte[] iv = new byte[12];
        byte[] ct = new byte[16];
        String json = String.format(
                "{\"v\":1,\"kp\":\"other\",\"iv\":\"%s\",\"ct\":\"%s\"}",
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(iv),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ct));
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes());
        assertThatThrownBy(() -> service.decrypt("v1." + encoded))
                .isInstanceOf(CredentialCryptoException.class)
                .hasMessageContaining("找不到");
    }
}

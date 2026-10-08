package com.ananoesis.shell.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * tasks 4.2 / 4.3 的验收：AES-GCM 凭据加解密、密文信封契约、密钥库不可用时的明确报错、
 * 以及"用户主密码派生密钥"回退路径。
 *
 * <p>本测试**不启动 Spring 上下文**：加解密是纯函数式契约，用内存主密钥替身即可确定性验证，
 * 跑得快也便于定位。数据库落地由 {@code CredentialStoreServiceTest} 覆盖。</p>
 *
 * <p>WHY 连密文信封的 JSON 字段名一起断言：信封是**持久化格式**，一旦有用户数据落盘就成了
 * 长期契约。把 {@code v/kp/iv/ct/salt/it} 钉在测试里，可防止后续重构无意间做出
 * "老密文再也解不开"的破坏性变更。</p>
 */
class CredentialCryptoServiceTest {

    private static final String PLAINTEXT = "S3cr3t-P@ssw0rd-XYZ-9f3a";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENVELOPE_PREFIX = "v1.";

    /** 真实的 PEM 私钥形态：多行、含换行与 base64 字符集，最易在编解码环节被破坏。 */
    private static final String PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gt
            ZWRyMjU1MTkAAAgQFakeKeyMaterialForTestingOnlyDoNotUseAAA
            -----END OPENSSH PRIVATE KEY-----
            """;

    private final InMemoryMasterKeyProvider keyringProvider = new InMemoryMasterKeyProvider();
    private final CredentialCryptoService crypto = new CredentialCryptoService(List.of(keyringProvider));

    // ======================================================================
    // 4.2 加解密基本契约
    // ======================================================================

    @Nested
    @DisplayName("加密：密文信封")
    class Encrypt {

        @Test
        @DisplayName("产出 v1 前缀的信封，且信封任何部分都不含明文")
        void envelopeHidesPlaintextCompletely() throws Exception {
            String envelope = crypto.encrypt(SecretText.of(PLAINTEXT));

            assertThat(envelope).startsWith(ENVELOPE_PREFIX);
            assertThat(envelope).doesNotContain(PLAINTEXT);
            // 信封体是 base64url，解码后是 JSON；明文既不在 base64 层也不在解码后的字节里
            byte[] payloadBytes = Base64.getUrlDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length()));
            assertThat(new String(payloadBytes, StandardCharsets.UTF_8)).doesNotContain(PLAINTEXT);

            JsonNode payload = JSON.readTree(payloadBytes);
            assertThat(payload.get("v").asInt()).as("信封版本").isEqualTo(1);
            assertThat(payload.get("kp").asText()).as("主密钥来源").isEqualTo(InMemoryMasterKeyProvider.ID);
            assertThat(payload.get("iv").asText()).isNotBlank();
            assertThat(payload.get("ct").asText()).isNotBlank();
            assertThat(payload.has("salt")).as("密钥库路径不应带 KDF 盐").isFalse();
        }

        @Test
        @DisplayName("IV 长度符合 AES-GCM 推荐的 12 字节")
        void ivIsTwelveBytes() throws Exception {
            JsonNode payload = payloadOf(crypto.encrypt(SecretText.of(PLAINTEXT)));

            byte[] iv = Base64.getUrlDecoder().decode(payload.get("iv").asText());
            // WHY 12 字节：NIST SP 800-38D 对 GCM 的推荐值；其它长度会触发 GHASH 派生，
            // 既无收益也增加实现分歧面。
            assertThat(iv).hasSize(12);
        }

        @Test
        @DisplayName("同一明文两次加密得到不同信封（IV 随机，防重放式比对）")
        void eachEncryptionUsesFreshIv() throws Exception {
            String first = crypto.encrypt(SecretText.of(PLAINTEXT));
            String second = crypto.encrypt(SecretText.of(PLAINTEXT));

            assertThat(first).isNotEqualTo(second);
            assertThat(payloadOf(first).get("iv").asText())
                    .isNotEqualTo(payloadOf(second).get("iv").asText());
            // 但两者都能解回同一明文
            try (SecretText a = crypto.decrypt(first); SecretText b = crypto.decrypt(second)) {
                assertThat(a.revealAsString()).isEqualTo(PLAINTEXT);
                assertThat(b.revealAsString()).isEqualTo(PLAINTEXT);
            }
        }

        @Test
        @DisplayName("加密后明文副本被擦除，不在内存中长期驻留")
        void plaintextIsWipedAfterEncryption() {
            SecretText plaintext = SecretText.of(PLAINTEXT);

            crypto.encrypt(plaintext);

            assertThat(plaintext.isWiped()).as("加密服务应承担擦除责任").isTrue();
        }
    }

    @Nested
    @DisplayName("解密：还原与防篡改")
    class Decrypt {

        @Test
        @DisplayName("往返一致")
        void roundTrips() {
            String envelope = crypto.encrypt(SecretText.of(PLAINTEXT));

            try (SecretText restored = crypto.decrypt(envelope)) {
                assertThat(restored.revealAsString()).isEqualTo(PLAINTEXT);
            }
        }

        @Test
        @DisplayName("多行 PEM 私钥与 passphrase 可无损往返（spec：保存私钥）")
        void privateKeyAndPassphraseRoundTripLosslessly() {
            String keyEnvelope = crypto.encrypt(SecretText.of(PRIVATE_KEY));
            String passphraseEnvelope = crypto.encrypt(SecretText.of("my-key-passphrase"));

            try (SecretText key = crypto.decrypt(keyEnvelope);
                 SecretText passphrase = crypto.decrypt(passphraseEnvelope)) {
                assertThat(key.revealAsString())
                        .as("换行与首尾空白必须原样保留，否则 sshj 解析 PEM 会失败")
                        .isEqualTo(PRIVATE_KEY);
                assertThat(passphrase.revealAsString()).isEqualTo("my-key-passphrase");
            }
        }

        @Test
        @DisplayName("含中文与 emoji 的凭据按 UTF-8 无损往返")
        void nonAsciiRoundTrips() {
            String value = "密码-🔐-pässword";

            try (SecretText restored = crypto.decrypt(crypto.encrypt(SecretText.of(value)))) {
                assertThat(restored.revealAsString()).isEqualTo(value);
            }
        }

        @Test
        @DisplayName("密文被篡改时 GCM 认证失败并拒绝解密")
        void tamperedCiphertextIsRejected() throws Exception {
            String tampered = tamperCiphertext(crypto.encrypt(SecretText.of(PLAINTEXT)));

            // WHY 必须失败：GCM 的认证标签是"密文没被动过"的唯一证据，
            // 若这里能解出内容，说明加密模式被误用（如退化成 CBC 无 MAC）。
            assertThatThrownBy(() -> crypto.decrypt(tampered))
                    .isInstanceOf(CredentialCryptoException.class);
        }

        @Test
        @DisplayName("用另一把主密钥解密失败")
        void wrongMasterKeyIsRejected() {
            String envelope = crypto.encrypt(SecretText.of(PLAINTEXT));
            CredentialCryptoService otherKey =
                    new CredentialCryptoService(List.of(new InMemoryMasterKeyProvider()));

            assertThatThrownBy(() -> otherKey.decrypt(envelope))
                    .isInstanceOf(CredentialCryptoException.class);
        }

        @Test
        @DisplayName("信封版本未知、格式非法、字段缺失均被明确拒绝")
        void malformedEnvelopesAreRejected() {
            List<String> bad = List.of(
                    "",
                    "garbage",
                    "v2." + base64Url("{\"v\":2,\"kp\":\"os-keyring\",\"iv\":\"AA\",\"ct\":\"AA\"}"),
                    ENVELOPE_PREFIX + base64Url("not-a-json"),
                    ENVELOPE_PREFIX + base64Url("{\"v\":1}"));

            for (String envelope : bad) {
                assertThatThrownBy(() -> crypto.decrypt(envelope))
                        .as("非法信封 %s 必须被拒绝", abbreviate(envelope))
                        .isInstanceOf(CredentialCryptoException.class);
            }
        }

        @Test
        @DisplayName("异常信息不含明文（spec：MUST NOT 将明文写入错误信息）")
        void cryptoExceptionsNeverLeakPlaintext() {
            String envelope = crypto.encrypt(SecretText.of(PLAINTEXT));

            Throwable thrown = catchIt(() -> crypto.decrypt(tamperQuietly(envelope)));

            assertThat(thrown).isNotNull();
            assertThat(thrown.getMessage()).doesNotContain(PLAINTEXT);
            assertThat(fullStackTrace(thrown)).doesNotContain(PLAINTEXT);
        }
    }

    // ======================================================================
    // 4.3 密钥库不可用：明确报错，禁止静默明文
    // ======================================================================

    @Nested
    @DisplayName("密钥库不可用（spec MUST）")
    class KeyringUnavailable {

        @Test
        @DisplayName("无可用提供者时加密抛出「凭据保护不可用」")
        void encryptFailsLoudlyWhenNoProviderAvailable() {
            InMemoryMasterKeyProvider dead = new InMemoryMasterKeyProvider();
            dead.markUnavailable();
            CredentialCryptoService service = new CredentialCryptoService(List.of(dead));

            assertThat(service.isAvailable()).isFalse();
            assertThatThrownBy(() -> service.encrypt(SecretText.of(PLAINTEXT)))
                    .isInstanceOf(CredentialProtectionException.class)
                    .hasMessageContaining("凭据保护不可用")
                    .hasMessageNotContaining(PLAINTEXT);
        }

        @Test
        @DisplayName("提供者列表为空时同样抛出「凭据保护不可用」，绝不返回明文")
        void encryptFailsLoudlyWhenNoProviderRegistered() {
            CredentialCryptoService service = new CredentialCryptoService(List.of());

            assertThat(service.isAvailable()).isFalse();
            assertThatThrownBy(() -> service.encrypt(SecretText.of(PLAINTEXT)))
                    .isInstanceOf(CredentialProtectionException.class)
                    .hasMessageContaining("凭据保护不可用");
        }

        @Test
        @DisplayName("密钥库在运行期失联时，解密也报「凭据保护不可用」而非静默返回")
        void decryptFailsLoudlyWhenProviderDiesAtRuntime() {
            String envelope = crypto.encrypt(SecretText.of(PLAINTEXT));
            keyringProvider.markUnavailable();

            assertThatThrownBy(() -> crypto.decrypt(envelope))
                    .isInstanceOf(CredentialProtectionException.class)
                    .hasMessageContaining("凭据保护不可用");
        }

        @Test
        @DisplayName("「凭据保护不可用」异常不携带明文，也不携带主密钥")
        void unavailableExceptionLeaksNothing() {
            CredentialCryptoService service = new CredentialCryptoService(List.of());

            Throwable thrown = catchIt(() -> service.encrypt(SecretText.of(PLAINTEXT)));

            assertThat(thrown).isInstanceOf(CredentialProtectionException.class);
            assertThat(fullStackTrace(thrown)).doesNotContain(PLAINTEXT);
        }
    }

    // ======================================================================
    // 4.3 用户主密码派生密钥：回退路径
    // ======================================================================

    @Nested
    @DisplayName("主密码派生密钥回退")
    class MasterPasswordFallback {

        private final SecretText masterPassword = SecretText.of("correct-horse-battery-staple");

        @Test
        @DisplayName("密钥库不可用时，回退路径仍可加解密")
        void fallbackWorksWhenKeyringIsUnavailable() {
            CredentialCryptoService service = new CredentialCryptoService(List.of());
            assertThat(service.isAvailable()).as("密钥库确实不可用").isFalse();

            String envelope = service.encryptWithMasterPassword(SecretText.of(PLAINTEXT), masterPassword);

            assertThat(envelope).startsWith(ENVELOPE_PREFIX).doesNotContain(PLAINTEXT);
            try (SecretText restored =
                         service.decryptWithMasterPassword(envelope, SecretText.of("correct-horse-battery-staple"))) {
                assertThat(restored.revealAsString()).isEqualTo(PLAINTEXT);
            }
        }

        @Test
        @DisplayName("信封记录密钥来源、KDF 盐与迭代次数")
        void envelopeRecordsKdfParameters() throws Exception {
            String envelope = crypto.encryptWithMasterPassword(SecretText.of(PLAINTEXT), masterPassword);

            JsonNode payload = payloadOf(envelope);
            assertThat(payload.get("kp").asText()).isEqualTo(MasterPasswordKeyDeriver.ID);
            assertThat(Base64.getUrlDecoder().decode(payload.get("salt").asText())).hasSize(16);
            assertThat(payload.get("it").asInt())
                    .as("迭代次数必须落盘，否则将来调参后旧密文无法解密")
                    .isEqualTo(MasterPasswordKeyDeriver.DEFAULT_ITERATIONS);
        }

        @Test
        @DisplayName("每次加密使用新盐，同一明文+同一密码得到不同信封")
        void freshSaltPerEncryption() throws Exception {
            String first = crypto.encryptWithMasterPassword(SecretText.of(PLAINTEXT), SecretText.of("pw-1"));
            String second = crypto.encryptWithMasterPassword(SecretText.of(PLAINTEXT), SecretText.of("pw-1"));

            assertThat(payloadOf(first).get("salt").asText())
                    .isNotEqualTo(payloadOf(second).get("salt").asText());
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("错误的主密码解密失败，且不泄露明文")
        void wrongMasterPasswordIsRejected() {
            String envelope = crypto.encryptWithMasterPassword(SecretText.of(PLAINTEXT), masterPassword);

            Throwable thrown = catchIt(() ->
                    crypto.decryptWithMasterPassword(envelope, SecretText.of("wrong-password")));

            assertThat(thrown).isInstanceOf(CredentialCryptoException.class);
            assertThat(fullStackTrace(thrown)).doesNotContain(PLAINTEXT);
        }

        @Test
        @DisplayName("对主密码信封调用无密码解密会得到明确指引，而非含糊失败")
        void decryptingFallbackEnvelopeWithoutPasswordFailsClearly() {
            String envelope = crypto.encryptWithMasterPassword(SecretText.of(PLAINTEXT), masterPassword);

            assertThatThrownBy(() -> crypto.decrypt(envelope))
                    .isInstanceOf(CredentialCryptoException.class)
                    .hasMessageContaining("主密码");
        }

        @Test
        @DisplayName("空主密码被拒绝——回退不等于降低强度")
        void blankMasterPasswordIsRejected() {
            assertThatThrownBy(() -> crypto.encryptWithMasterPassword(SecretText.of(PLAINTEXT), SecretText.of("")))
                    .isInstanceOf(CredentialProtectionException.class);
            assertThatThrownBy(() -> crypto.encryptWithMasterPassword(SecretText.of(PLAINTEXT), SecretText.of("   ")))
                    .isInstanceOf(CredentialProtectionException.class);
        }
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private static JsonNode payloadOf(String envelope) throws Exception {
        assertThat(envelope).startsWith(ENVELOPE_PREFIX);
        return JSON.readTree(Base64.getUrlDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length())));
    }

    /** 翻转密文首字节，模拟落盘后被篡改。 */
    private static String tamperCiphertext(String envelope) throws Exception {
        JsonNode payload = payloadOf(envelope);
        byte[] ct = Base64.getUrlDecoder().decode(payload.get("ct").asText());
        ct[0] = (byte) (ct[0] ^ 0xFF);
        com.fasterxml.jackson.databind.node.ObjectNode mutable =
                (com.fasterxml.jackson.databind.node.ObjectNode) payload;
        mutable.put("ct", Base64.getUrlEncoder().withoutPadding().encodeToString(ct));
        return ENVELOPE_PREFIX + base64Url(JSON.writeValueAsString(mutable));
    }

    private static String tamperQuietly(String envelope) {
        try {
            return tamperCiphertext(envelope);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Throwable catchIt(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static String fullStackTrace(Throwable thrown) {
        java.io.StringWriter writer = new java.io.StringWriter();
        thrown.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    private static String abbreviate(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

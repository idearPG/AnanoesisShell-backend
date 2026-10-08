package com.ananoesis.shell.security;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * tasks 4.1 的验收：主密钥托管于操作系统密钥库——能写入并读回。
 *
 * <p>本测试跑在 {@link FakeOsKeyring} 上而非真实系统密钥库。
 * WHY：credential-store spec 要求的是"托管于 OS 密钥库"这一**行为契约**
 * （首次生成、持久化、后续复用、故障时报错），该契约与具体后端无关；
 * 真实后端（Windows Credential Store / macOS Keychain / Linux SecretService）
 * 的连通性另由 {@code JavaKeyringOsKeyringTest} 以假设门控方式验证。
 * 把两者分开，才能让契约测试在无头 CI 上依然确定性通过。</p>
 */
class OsKeyringMasterKeyStoreTest {

    private FakeOsKeyring keyring;
    private OsKeyringMasterKeyStore store;

    @BeforeEach
    void setUp() {
        keyring = new FakeOsKeyring();
        store = new OsKeyringMasterKeyStore(keyring);
    }

    @Test
    @DisplayName("首次使用时生成主密钥并写入密钥库")
    void firstUseGeneratesAndPersistsMasterKey() {
        byte[] key = store.copyMasterKeyBytes();

        assertThat(key).hasSize(32);
        String stored = keyring.peek(OsKeyringMasterKeyStore.SERVICE_NAME,
                OsKeyringMasterKeyStore.ACCOUNT_NAME);
        assertThat(stored).as("主密钥必须真的落进密钥库，而不是只留在内存").isNotBlank();
        assertThat(Base64.getDecoder().decode(stored)).isEqualTo(key);
    }

    @Test
    @DisplayName("后续使用读回同一把主密钥，且不重复生成")
    void subsequentUsesReturnTheSameKey() {
        byte[] first = store.copyMasterKeyBytes();
        byte[] second = store.copyMasterKeyBytes();

        assertThat(second).isEqualTo(first);
        assertThat(keyring.size()).as("只应存在一条主密钥条目").isEqualTo(1);
    }

    @Test
    @DisplayName("主密钥为 256 位，满足 AES-256-GCM 强度要求")
    void masterKeyIs256Bits() {
        assertThat(store.copyMasterKeyBytes()).hasSize(32);
    }

    @Test
    @DisplayName("每次返回的都是副本，调用方擦除自己的副本不影响密钥库")
    void returnedKeyIsACopy() {
        byte[] first = store.copyMasterKeyBytes();
        java.util.Arrays.fill(first, (byte) 0);

        assertThat(store.copyMasterKeyBytes()).isNotEqualTo(first);
        assertThat(store.copyMasterKeyBytes()).hasSize(32);
    }

    @Test
    @DisplayName("密钥库后端故障时报「凭据保护不可用」，不静默降级")
    void backendFailureReportsProtectionUnavailable() {
        keyring.startFailing();

        assertThatThrownBy(() -> store.copyMasterKeyBytes())
                .isInstanceOf(CredentialProtectionException.class)
                .hasMessageContaining("凭据保护不可用");
        assertThat(store.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("密钥库中残留的条目不是合法密钥时明确报错，不回退到新生成")
    void corruptedEntryIsReportedRatherThanSilentlyRegenerated() {
        keyring.write(OsKeyringMasterKeyStore.SERVICE_NAME,
                OsKeyringMasterKeyStore.ACCOUNT_NAME, "not-a-valid-base64-key!!");

        // WHY 不允许静默重新生成：那会让用户已有的全部密文凭据永久无法解密，
        // 而表面上"一切正常"。必须显式失败，让人有机会去修复或走主密码回退。
        assertThatThrownBy(() -> store.copyMasterKeyBytes())
                .isInstanceOf(CredentialProtectionException.class)
                .hasMessageContaining("凭据保护不可用");
    }

    @Test
    @DisplayName("条目长度不是 32 字节时同样拒绝")
    void wrongLengthEntryIsRejected() {
        keyring.write(OsKeyringMasterKeyStore.SERVICE_NAME, OsKeyringMasterKeyStore.ACCOUNT_NAME,
                Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> store.copyMasterKeyBytes())
                .isInstanceOf(CredentialProtectionException.class)
                .hasMessageContaining("凭据保护不可用");
    }

    @Test
    @DisplayName("isAvailable 反映后端健康状态")
    void isAvailableTracksBackendHealth() {
        assertThat(store.isAvailable()).isTrue();
        assertThat(store.id()).isEqualTo(OsKeyringMasterKeyStore.ID);

        keyring.startFailing();
        assertThat(store.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("密钥库条目坐标被钉死——改名会让老用户的凭据永久失联")
    void storageCoordinatesAreStable() {
        // WHY 这条断言重要：SERVICE_NAME / ACCOUNT_NAME 是主密钥在 OS 密钥库里的**唯一坐标**。
        // 一旦有人"顺手重命名"，升级后的应用会生成一把新主密钥，
        // 用户此前保存的所有 SSH 密码与 api key 将全部无法解密——且没有任何报错提示。
        assertThat(OsKeyringMasterKeyStore.SERVICE_NAME).isEqualTo("ananoesis-shell");
        assertThat(OsKeyringMasterKeyStore.ACCOUNT_NAME).isEqualTo("credential-master-key");
    }
}

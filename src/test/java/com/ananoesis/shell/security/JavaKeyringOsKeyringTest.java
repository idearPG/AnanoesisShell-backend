package com.ananoesis.shell.security;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * tasks 4.1 的真实后端验证：{@code java-keyring} 适配器确实能对接当前操作系统的密钥库
 * （Windows Credential Store / macOS Keychain / Linux SecretService）。
 *
 * <p>WHY 用 {@link Assumptions} 门控而非直接断言：真实密钥库依赖桌面会话。
 * 在无头 CI、容器、或以服务账号运行时后端不存在，此时应**跳过**而不是失败——
 * 否则测试反映的是"跑在哪台机器上"而非"代码是否正确"。
 * 契约层面的确定性验证由 {@code OsKeyringMasterKeyStoreTest} 承担，
 * 措辞分类的确定性验证由 {@code JavaKeyringAbsentEntryClassificationTest} 承担。</p>
 *
 * <p>本测试会在真实密钥库中创建一条临时条目，并在结束时删除，不留残留。</p>
 */
class JavaKeyringOsKeyringTest {

    /** 用随机后缀，避免与开发者机器上真实的主密钥条目冲突。 */
    private static final String SERVICE = "ananoesis-shell-it";
    private static final String ACCOUNT = "probe-" + UUID.randomUUID();

    @Test
    @DisplayName("写入并读回一条秘密（真实 OS 密钥库）")
    void writesAndReadsBackFromRealOsKeyring() {
        OsKeyring keyring = openRealKeyringOrSkip();
        String token = "it-" + UUID.randomUUID();

        try {
            keyring.write(SERVICE, ACCOUNT, token);
            String readBack = keyring.read(SERVICE, ACCOUNT);

            assertThat(readBack)
                    .as("从 %s 读回的值应与写入一致", describeBackend())
                    .isEqualTo(token);
        } finally {
            quietlyDelete(keyring);
        }
    }

    @Test
    @DisplayName("读取不存在的条目返回 null 而非抛异常")
    void readingAbsentEntryReturnsNull() {
        OsKeyring keyring = openRealKeyringOrSkip();

        try {
            assertThat(keyring.read(SERVICE, "absent-" + UUID.randomUUID())).isNull();
        } finally {
            quietlyDelete(keyring);
        }
    }

    @Test
    @DisplayName("删除不存在的条目是幂等成功，不抛异常")
    void deletingAbsentEntryIsIdempotent() {
        OsKeyring keyring = openRealKeyringOrSkip();
        String absentAccount = "absent-" + UUID.randomUUID();

        // WHY 单独测这条：Windows 上 CredDelete 对不存在的目标会失败并返回 ERROR_NOT_FOUND(1168)，
        // 与 CredRead 的失败码相同但走的是不同原生调用。若"不存在"的判定只覆盖了读路径，
        // 用户在设置里删除一个从未保存过的凭据就会看到一条莫名的密钥库错误。
        try {
            assertThatCode(() -> keyring.delete(SERVICE, absentAccount)).doesNotThrowAnyException();
        } finally {
            quietlyDelete(keyring);
        }
    }

    @Test
    @DisplayName("删除后条目不再可读")
    void deletedEntryIsGone() {
        OsKeyring keyring = openRealKeyringOrSkip();
        String token = "it-" + UUID.randomUUID();

        try {
            keyring.write(SERVICE, ACCOUNT, token);
            keyring.delete(SERVICE, ACCOUNT);

            assertThat(keyring.read(SERVICE, ACCOUNT)).isNull();
        } finally {
            quietlyDelete(keyring);
        }
    }

    /**
     * 打开真实密钥库；若当前环境不支持则跳过测试。
     *
     * <p>WHY 捕获 {@link Throwable} 而非仅受检异常：java-keyring 通过 JNA 调用本地库，
     * 在缺少桌面会话或本地库加载失败时可能抛 {@code UnsatisfiedLinkError} 等 Error，
     * 只捕获 Exception 会让测试以"错误"而非"跳过"收场。</p>
     */
    private static OsKeyring openRealKeyringOrSkip() {
        try {
            OsKeyring keyring = JavaKeyringOsKeyring.open();
            Assumptions.assumeTrue(keyring != null, "当前环境无可用 OS 密钥库后端");
            return keyring;
        } catch (Throwable t) {
            Assumptions.abort("当前环境无可用 OS 密钥库后端: " + t.getClass().getSimpleName());
            throw new IllegalStateException("unreachable");
        }
    }

    private static String describeBackend() {
        try (com.github.javakeyring.Keyring probe = com.github.javakeyring.Keyring.create()) {
            return String.valueOf(probe.getKeyringStorageType());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void quietlyDelete(OsKeyring keyring) {
        try {
            keyring.delete(SERVICE, ACCOUNT);
        } catch (Throwable ignored) {
            // 清理失败不应掩盖测试结论；条目名含随机后缀，不会与真实数据冲突
        } finally {
            try {
                keyring.close();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }
}

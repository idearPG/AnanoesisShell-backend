package com.ananoesis.shell.security;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.github.javakeyring.PasswordAccessException;

/**
 * 对"条目不存在 vs 后端故障"分类逻辑的确定性单测。
 *
 * <p>WHY 需要这层单测（{@code JavaKeyringOsKeyringTest} 已覆盖真实后端还不够）：
 * 真实后端测试只能覆盖**当前这台机器**的那一种措辞。而本项目的分类错误代价极端不对称——
 * 把一次真实故障误判成"不存在"，上层会生成新主密钥覆盖旧条目，
 * 用户所有 SSH 密码与 api key 将永久静默失联；反向误判只是首次启动明确报「凭据保护不可用」。
 * 因此 macOS / KWallet / 各类 Win32 错误码的措辞必须能在无头 CI 上被逐一钉死，
 * 而不是"等有人在那台机器上跑到了再说"。</p>
 *
 * <p>本测试同时是 {@code JavaKeyringOsKeyringTest} 在 Windows 上暴露的真实缺陷的回归防线：
 * {@code WinCredentialStoreBackend} 对不存在的条目抛的是 {@code "Error code 1168"}
 * （已由 java-keyring 1.0.4 字节码的拼接模板 {@code "Error code \u0001"} 与实际运行栈双向确证），
 * 而不是同类的另一条路径 {@code "Password not Found"}。</p>
 */
class JavaKeyringAbsentEntryClassificationTest {

    @Nested
    @DisplayName("应判定为「条目不存在」")
    class Absent {

        @Test
        @DisplayName("Windows CredRead/CredDelete 失败：Error code 1168（ERROR_NOT_FOUND）")
        void windowsErrorCode1168MeansAbsent() {
            // 实测证据：本机 Windows Credential Store 读取不存在条目时，
            // WinCredentialStoreBackend.java:61 抛出的正是 "Error code 1168"
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Error code 1168"))).isTrue();
        }

        @Test
        @DisplayName("Windows 读取到零长度凭据块：Password not Found")
        void windowsPasswordNotFoundMeansAbsent() {
            // WinCredentialStoreBackend 的第二条不存在路径（CredRead 成功但 blob 长度为 0）
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Password not Found"))).isTrue();
        }

        @Test
        @DisplayName("真实原因被包在 cause 链里层时仍能识别")
        void nestedCauseIsInspected() {
            Throwable wrapped = new IllegalStateException("外层包装",
                    new IOException("中间层", new PasswordAccessException("Error code 1168")));

            assertThat(JavaKeyringOsKeyring.isAbsentEntry(wrapped)).isTrue();
        }

        @Test
        @DisplayName("macOS Keychain：errSecItemNotFound 的官方文案")
        void macOsItemNotFoundMeansAbsent() {
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(new PasswordAccessException(
                    "The specified item could not be found in the keychain."))).isTrue();
        }

        @Test
        @DisplayName("KWallet：Password is not in wallet")
        void kwalletMissingEntryMeansAbsent() {
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Password is not in wallet"))).isTrue();
        }

        @Test
        @DisplayName("大小写差异不影响判定（各后端措辞大小写不统一）")
        void matchingIsCaseInsensitive() {
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("ERROR CODE 1168"))).isTrue();
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("PASSWORD NOT FOUND"))).isTrue();
        }
    }

    @Nested
    @DisplayName("应判定为「后端故障」，绝不放行")
    class NotAbsent {

        @Test
        @DisplayName("其它 Win32 错误码不白名单化：87（参数非法）")
        void windowsErrorCode87IsAFailure() {
            // WHY 只白名单 1168：CredRead 的其它失败码（87 参数非法、1312 无此登录会话、
            // 1004 标志非法）都不代表"条目不存在"。把它们当不存在会触发主密钥重生成。
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Error code 87"))).isFalse();
        }

        @Test
        @DisplayName("其它 Win32 错误码不白名单化：1312（无此登录会话）")
        void windowsErrorCode1312IsAFailure() {
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Error code 1312"))).isFalse();
        }

        @Test
        @DisplayName("message 为 null 时按故障处理，不得 NPE")
        void nullMessageIsAFailure() {
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(new PasswordAccessException(null)))
                    .isFalse();
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(new RuntimeException((String) null)))
                    .isFalse();
        }

        @Test
        @DisplayName("密钥环被锁 / 后端不可用属于故障，不是「不存在」")
        void lockedOrUnavailableBackendIsAFailure() {
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Keyring is locked"))).isFalse();
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("No keyring backend available"))).isFalse();
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Secret Service is not available"))).isFalse();
        }

        @Test
        @DisplayName("数字巧合不构成匹配：1168 必须紧跟在 error code 之后")
        void digitsElsewhereDoNotMatch() {
            // WHY 这条断言重要：若用裸 contains("1168")，任何带该数字的消息
            // （例如时间戳、端口、blob 长度）都会被误判为"不存在"，直接导致主密钥被覆盖。
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Read 1168 bytes from keyring"))).isFalse();
            assertThat(JavaKeyringOsKeyring.isAbsentEntry(
                    new PasswordAccessException("Timeout after 11681168 ms"))).isFalse();
        }

        @Test
        @DisplayName("cause 链中出现环时不死循环")
        void cyclicCauseChainTerminates() {
            // 现实中罕见，但 Throwable.initCause 允许构造出环；
            // 遍历 cause 链若不设上限会让线程在这里永久自旋。
            Exception a = new PasswordAccessException("boom");
            Exception b = new PasswordAccessException("wrapper", a);
            a.initCause(b);

            assertThat(JavaKeyringOsKeyring.isAbsentEntry(a)).isFalse();
        }
    }
}

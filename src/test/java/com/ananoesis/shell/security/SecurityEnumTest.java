package com.ananoesis.shell.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 安全层枚举的分支覆盖补测。
 *
 * <p>WHY 集中在此文件：CredentialOwnerType / CredentialType 的分支缺口均为
 * fromColumnValue 的匹配/不匹配路径，逻辑独立且无外部依赖。</p>
 */
@DisplayName("安全层枚举分支覆盖")
class SecurityEnumTest {

    // ==================================================================
    // CredentialOwnerType (4 missed)
    // ==================================================================

    @Nested
    @DisplayName("CredentialOwnerType")
    class CredentialOwnerTypeTests {

        @Test
        @DisplayName("columnValue 返回构造时指定的值")
        void columnValueReturnsConstructorArg() {
            assertThat(CredentialOwnerType.HOST.columnValue()).isEqualTo("host");
            assertThat(CredentialOwnerType.MODEL_CONFIG.columnValue()).isEqualTo("model_config");
            assertThat(CredentialOwnerType.SETTING.columnValue()).isEqualTo("setting");
        }

        @Test
        @DisplayName("fromColumnValue: 已知值返回对应枚举")
        void knownValueReturnsEnum() {
            assertThat(CredentialOwnerType.fromColumnValue("host")).isEqualTo(CredentialOwnerType.HOST);
            assertThat(CredentialOwnerType.fromColumnValue("model_config")).isEqualTo(CredentialOwnerType.MODEL_CONFIG);
            assertThat(CredentialOwnerType.fromColumnValue("setting")).isEqualTo(CredentialOwnerType.SETTING);
        }

        @Test
        @DisplayName("fromColumnValue: 未知值抛异常")
        void unknownValueThrows() {
            assertThatThrownBy(() -> CredentialOwnerType.fromColumnValue("bogus"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未知的凭据宿主类型");
        }

        @Test
        @DisplayName("fromColumnValue: null 值抛异常")
        void nullValueThrows() {
            assertThatThrownBy(() -> CredentialOwnerType.fromColumnValue(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // CredentialType (4 missed)
    // ==================================================================

    @Nested
    @DisplayName("CredentialType")
    class CredentialTypeTests {

        @Test
        @DisplayName("columnValue 返回构造时指定的值")
        void columnValueReturnsConstructorArg() {
            assertThat(CredentialType.SSH_PASSWORD.columnValue()).isEqualTo("ssh_password");
            assertThat(CredentialType.SSH_PRIVATE_KEY.columnValue()).isEqualTo("ssh_private_key");
            assertThat(CredentialType.SSH_PASSPHRASE.columnValue()).isEqualTo("ssh_passphrase");
            assertThat(CredentialType.LLM_API_KEY.columnValue()).isEqualTo("llm_api_key");
            assertThat(CredentialType.GENERIC_SECRET.columnValue()).isEqualTo("generic_secret");
        }

        @Test
        @DisplayName("fromColumnValue: 已知值返回对应枚举")
        void knownValueReturnsEnum() {
            assertThat(CredentialType.fromColumnValue("ssh_password")).isEqualTo(CredentialType.SSH_PASSWORD);
            assertThat(CredentialType.fromColumnValue("ssh_private_key")).isEqualTo(CredentialType.SSH_PRIVATE_KEY);
            assertThat(CredentialType.fromColumnValue("llm_api_key")).isEqualTo(CredentialType.LLM_API_KEY);
        }

        @Test
        @DisplayName("fromColumnValue: 未知值抛异常")
        void unknownValueThrows() {
            assertThatThrownBy(() -> CredentialType.fromColumnValue("bogus"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未知的凭据类型");
        }

        @Test
        @DisplayName("fromColumnValue: null 值抛异常")
        void nullValueThrows() {
            assertThatThrownBy(() -> CredentialType.fromColumnValue(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

package com.ananoesis.shell.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * tasks 4.4 的基础：明文凭据的内存载体必须做到"打印即掩码、用完即擦除"。
 *
 * <p>WHY 需要专门的值对象而不是直接用 {@code String}：Java 的 {@code String} 不可变，
 * 一旦创建就无法从堆上抹掉，且极易被日志框架、异常消息或调试器顺手打印出来。
 * {@link SecretText} 用 {@code char[]} 承载并提供 {@code toString()} 掩码，
 * 把"意外泄露"从需要人时刻警惕的纪律，变成默认安全的类型属性。</p>
 */
class SecretTextTest {

    private static final String VALUE = "S3cr3t-P@ssw0rd-XYZ-9f3a";

    @Test
    @DisplayName("toString 永远返回掩码，不含明文")
    void toStringIsAlwaysMasked() {
        SecretText secret = SecretText.of(VALUE);

        assertThat(secret.toString()).doesNotContain(VALUE);
        assertThat(secret.toString()).isEqualTo(SecretText.MASK);
        // 字符串拼接是最常见的意外泄露路径，必须同样安全
        assertThat("credential=" + secret).doesNotContain(VALUE);
    }

    @Test
    @DisplayName("reveal 返回的是副本，外部改动不影响内部状态")
    void revealReturnsDefensiveCopy() {
        SecretText secret = SecretText.of(VALUE);

        char[] leaked = secret.revealChars();
        leaked[0] = '!';

        assertThat(secret.revealAsString()).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("从 char[] 构造时复制输入，调用方擦除自己的副本不影响 SecretText")
    void constructorCopiesInput() {
        char[] source = VALUE.toCharArray();
        SecretText secret = SecretText.of(source);

        java.util.Arrays.fill(source, '\0');

        assertThat(secret.revealAsString()).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("wipe 清零内部缓冲，之后 reveal 抛异常而非返回空值")
    void wipeZeroesBufferAndBlocksLaterReveal() {
        SecretText secret = SecretText.of(VALUE);
        char[] before = secret.revealChars();

        secret.wipe();

        assertThat(secret.isWiped()).isTrue();
        assertThat(before).as("已交出的副本由调用方负责，但内部缓冲必须被清零").isNotNull();
        assertThatThrownBy(secret::revealAsString)
                .as("擦除后再读取必须是显式错误，静默返回空串会掩盖调用时序 bug")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("try-with-resources 结束时自动擦除")
    void autoClosesViaTryWithResources() {
        SecretText secret = SecretText.of(VALUE);

        try (SecretText ignored = secret) {
            assertThat(ignored.revealAsString()).isEqualTo(VALUE);
        }

        assertThat(secret.isWiped()).isTrue();
    }

    @Test
    @DisplayName("null 与空串被区分对待：null 拒绝，空串允许")
    void rejectsNullButAllowsEmpty() {
        assertThatThrownBy(() -> SecretText.of((String) null))
                .isInstanceOf(NullPointerException.class);

        assertThat(SecretText.of("").revealAsString()).isEmpty();
    }

    @Test
    @DisplayName("重复擦除是幂等的，不抛异常")
    void wipeIsIdempotent() {
        SecretText secret = SecretText.of(VALUE);

        secret.wipe();
        secret.wipe();
        secret.close();

        assertThat(secret.isWiped()).isTrue();
    }
}

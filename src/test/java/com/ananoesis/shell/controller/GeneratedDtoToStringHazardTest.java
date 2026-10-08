package com.ananoesis.shell.controller;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.AuthType;
import com.ananoesis.shell.contract.model.Host;

/**
 * 特征化测试（characterization test）：锁定 openapi-generator 生成物中一个**已知的不安全行为**，
 * 以便它被修好的那一刻我们能立刻知道，并撤掉为此而设的配置防线。
 *
 * <p>事实：{@code openapi.yaml} 给 {@code password}、{@code private_key}、{@code passphrase}
 * 三者都标了 {@code writeOnly: true}，语义上都是"只进不出"的秘密。
 * 但生成器决定 {@code toString()} 里是否打掩码时，看的是 {@code format: password} 而**不是}
 * {@code writeOnly}：前两者带该 format 因而被掩码成 {@code "*"}，
 * {@code private_key} 没有 format，于是整把私钥被原样拼进字符串。</p>
 *
 * <p>后果：任何一句 {@code LOG.debug("{}", hostDto)}——包括 Spring MVC 自己在 DEBUG/TRACE
 * 下记录 {@code @RequestBody} 的那一句——都会把用户私钥写进日志文件，
 * 而全部功能测试照旧通过。目前的缓解是 {@code application.yml} 里把
 * {@code org.springframework.web.servlet.mvc.method.annotation} 与
 * {@code org.springframework.web.client} 钉在 INFO，
 * 以及 {@code HostsApiCredentialLoggingTest} 的全链路回归。</p>
 *
 * <p>WHY 用测试而不是仅写注释来记录：注释不会失败。若哪天契约给 {@code private_key}
 * 补上了 {@code format: password}（已作为契约缺口上报架构师），下面第三条断言会立刻失败，
 * 提示维护者"隐患已消除，配置防线可以评估撤销"——这正是我们希望被告知的时刻。</p>
 */
class GeneratedDtoToStringHazardTest {

    private static final String PASSWORD = "S3cr3t-P@ssw0rd-XYZ-9f3a";
    private static final String PASSPHRASE = "k3y-P@ssphrase-QRS-7d2e";
    private static final String KEY_MATERIAL = "FakeKeyMaterialForTestingOnlyDoNotUse";
    private static final String PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gt
            ZWRyMjU1MTkAAAgQ%sAAA
            -----END OPENSSH PRIVATE KEY-----
            """.formatted(KEY_MATERIAL);

    @Test
    @DisplayName("生成物现状：password/passphrase 被掩码，private_key 被完整打印")
    void generatedHostToStringMasksOnlyFormatPasswordFields() {
        Host dto = new Host("10.91.9.99", 22, "ops", AuthType.PRIVATE_KEY);
        dto.setPassword(PASSWORD);
        dto.setPassphrase(PASSPHRASE);
        dto.setPrivateKey(PRIVATE_KEY);

        String printed = dto.toString();

        assertThat(printed).as("password 带 format: password，应被掩码").doesNotContain(PASSWORD);
        assertThat(printed).as("passphrase 带 format: password，应被掩码").doesNotContain(PASSPHRASE);
        // 隐患本身：若此断言失败，说明 private_key 已被掩码 —— 好消息，
        // 此时应重新评估 application.yml 中那两条 logger 级别钉死是否还必要
        assertThat(printed).as("private_key 无 format，生成器不掩码（已知隐患）").contains(KEY_MATERIAL);
    }

    @Test
    @DisplayName("非敏感字段仍可正常打印，故排障能力不因钉住 logger 而全失")
    void generatedHostToStringStillShowsCoordinates() {
        Host dto = new Host("10.91.9.99", 2222, "ops", AuthType.PASSWORD);

        String printed = dto.toString();

        // WHY 值得断言：把 Spring MVC 的 logger 钉在 INFO 是有代价的，
        // 至少要确认"主机坐标"这类排障必需信息仍能通过我们自己的 DEBUG 日志获得
        assertThat(printed).contains("10.91.9.99").contains("2222").contains("ops").contains("password");
    }
}

package com.ananoesis.shell.controller;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.AuthType;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.Host;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * credential-store spec「MUST NOT 将明文凭据写入日志」在 {@code /api/hosts} 全链路上的回归。
 *
 * <p>WHY 需要专门一条测试，而不是靠 {@code HostsApiIntegrationTest} 顺带覆盖：
 * openapi-generator 生成的 {@code Host#toString()} 只把 {@code password} 与
 * {@code passphrase} 打成 {@code *}，却把 {@code privateKey} **原样拼接**进字符串。
 * 也就是说，一句再普通不过的 {@code LOG.debug("收到请求: {}", host)} 就会把用户私钥
 * 写进日志文件——而所有功能测试都照样通过。只有"把日志开到最啰嗦、跑完整链路、
 * 再在日志文本里搜私钥特征串"这一种断言能挡住它。</p>
 *
 * <p>WHY 把 root logger 调到 DEBUG（沿用 Wave 1 {@code CredentialLoggingTest} 的手法）：
 * MyBatis 在 DEBUG 下会打印 SQL 参数，Spring 在 DEBUG 下会打印 handler 与消息转换信息。
 * 只在 INFO 下断言"没有明文"是自欺欺人——那只是没开日志而已。</p>
 *
 * <p>WHY 还要断言日志里**确实有**非敏感坐标：否则"一条日志都没捕获到"也会让
 * {@code doesNotContain} 通过，测试形同虚设。这条反向断言是防止空跑的关键。</p>
 */
class HostsApiCredentialLoggingTest extends AbstractSqliteIntegrationTest {

    private static final String PASSWORD = "S3cr3t-P@ssw0rd-XYZ-9f3a";
    private static final String PASSPHRASE = "k3y-P@ssphrase-QRS-7d2e";
    /** 私钥中最具辨识度的片段：只要它出现在日志里，就等于整把私钥泄露了。 */
    private static final String KEY_MATERIAL = "FakeKeyMaterialForTestingOnlyDoNotUse";
    private static final String PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gt
            ZWRyMjU1MTkAAAgQ%sAAA
            -----END OPENSSH PRIVATE KEY-----
            """.formatted(KEY_MATERIAL);

    @Autowired
    private TestRestTemplate rest;

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void attachCaptureAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        originalLevel = rootLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        rootLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachCaptureAppender() {
        // WHY 必须复原：所有集成测试共享同一个 LoggerContext，
        // 把 root 留在 DEBUG 会污染后续测试的输出并显著拖慢构建
        rootLogger.detachAppender(appender);
        appender.stop();
        rootLogger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("密码认证主机的建/改/查/删全链路日志中不出现明文密码")
    void passwordNeverReachesLogs() {
        Host created = post(passwordHost("10.91.1.11", PASSWORD));

        put(created.getId(), passwordHost("10.91.1.11", "R0tated-P@ssw0rd-LmN-55"));
        rest.getForEntity("/api/hosts", String.class);
        rest.getForEntity("/api/hosts/{id}", String.class, created.getId());
        // 触发一条错误路径：错误处理最容易顺手把请求体打出来
        rest.postForEntity("/api/hosts", json(passwordHost("10.91.1.12", null)), Error.class);
        rest.exchange("/api/hosts/{id}", HttpMethod.DELETE, null, Void.class, created.getId());

        String logs = capturedLogText();
        assertThat(logs).as("DEBUG 级别下的全部日志输出")
                .doesNotContain(PASSWORD)
                .doesNotContain("R0tated-P@ssw0rd-LmN-55");
        assertThat(logs).as("反向断言：确已捕获到本服务的非敏感日志")
                .contains("10.91.1.11");
    }

    @Test
    @DisplayName("私钥认证主机的建/改/查/删全链路日志中不出现私钥内容与 passphrase")
    void privateKeyNeverReachesLogs() {
        Host created = post(privateKeyHost("10.91.2.21"));

        put(created.getId(), privateKeyHost("10.91.2.21"));
        rest.getForEntity("/api/hosts", String.class);
        rest.getForEntity("/api/hosts/{id}", String.class, created.getId());
        rest.exchange("/api/hosts/{id}", HttpMethod.DELETE, null, Void.class, created.getId());

        String logs = capturedLogText();
        // WHY 同时断言特征串与 PEM 头：只查 PEM 头会漏掉"日志里只剩 base64 主体"的情况
        assertThat(logs).as("DEBUG 级别下的全部日志输出")
                .doesNotContain(KEY_MATERIAL)
                .doesNotContain("BEGIN OPENSSH PRIVATE KEY")
                .doesNotContain(PASSPHRASE);
        assertThat(logs).as("反向断言：确已捕获到本服务的非敏感日志")
                .contains("10.91.2.21");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private Host post(Host request) {
        ResponseEntity<Host> response = rest.postForEntity("/api/hosts", json(request), Host.class);
        assertThat(response.getStatusCode()).as("前置：创建应成功").isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void put(java.util.UUID id, Host request) {
        ResponseEntity<Host> response =
                rest.exchange("/api/hosts/{id}", HttpMethod.PUT, json(request), Host.class, id);
        assertThat(response.getStatusCode()).as("前置：更新应成功").isEqualTo(HttpStatus.OK);
    }

    private static Host passwordHost(String host, String password) {
        Host request = new Host(host, 22, "ops", AuthType.PASSWORD);
        request.setNote("logging-" + host);
        request.setPassword(password);
        return request;
    }

    private static Host privateKeyHost(String host) {
        Host request = new Host(host, 22, "deploy", AuthType.PRIVATE_KEY);
        request.setNote("logging-" + host);
        request.setPrivateKey(PRIVATE_KEY);
        request.setPassphrase(PASSPHRASE);
        return request;
    }

    private HttpEntity<String> json(Host host) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return new HttpEntity<>(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(host), headers);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    /**
     * 汇总所有被捕获日志的文本：格式化消息 + 异常类名/消息 + 完整堆栈。
     * WHY 三者都要：明文可能藏在任何一个位置，只查消息会漏掉异常链里的泄露。
     */
    private String capturedLogText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
            IThrowableProxy proxy = event.getThrowableProxy();
            while (proxy != null) {
                text.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
                for (StackTraceElementProxy element : proxy.getStackTraceElementProxyArray()) {
                    text.append(element.toString()).append('\n');
                }
                proxy = proxy.getCause();
            }
        }
        return text.toString();
    }
}

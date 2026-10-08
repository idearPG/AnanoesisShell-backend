package com.ananoesis.shell.controller;

import java.net.URI;
import java.util.UUID;

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
import com.ananoesis.shell.contract.model.ModelConfig;
import com.ananoesis.shell.contract.model.ThinkingMode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * credential-store spec「MUST NOT 将明文凭据写入日志」在 {@code /api/model-configs} 全链路上的回归。
 *
 * <p>WHY 必须专门一条测试：openapi-generator 生成的 {@code ModelConfig#toString()} 把
 * {@code apiKey} **原样拼接**进字符串（与 Wave 2 的 {@code Host#privateKey} 同类缺陷——
 * 生成器只对带 {@code format: password} 的字段打掩码，而契约给 {@code api_key} 标的是
 * {@code writeOnly} 而非 {@code format}）。也就是说，一句再普通不过的
 * {@code LOG.debug("收到请求: {}", modelConfig)} 就会把用户的模型 api key 写进日志文件，
 * 而全部功能测试照样通过。</p>
 *
 * <p>WHY 把 root logger 调到 DEBUG：MyBatis 在 DEBUG 下会打印 SQL 参数，
 * Spring 在 DEBUG 下会打印 handler 与消息转换信息。只在 INFO 下断言"没有明文"是自欺欺人——
 * 那只是没开日志而已。</p>
 *
 * <p>WHY 还要断言日志里<b>确实有</b>非敏感坐标：否则"一条日志都没捕获到"也会让
 * {@code doesNotContain} 通过，测试形同虚设。这条反向断言是防止空跑的关键。</p>
 */
class ModelConfigsApiCredentialLoggingTest extends AbstractSqliteIntegrationTest {

    /** api key 中最具辨识度的片段：只要它出现在日志里，就等于整个密钥泄露了。 */
    private static final String KEY_MATERIAL = "7Qx2ZtR9vBnM4wKpLdYeHcAa";
    private static final String API_KEY = "sk-mindie-" + KEY_MATERIAL + "-DO-NOT-LEAK";
    private static final String ROTATED_MATERIAL = "8Hs3kLm5nPq2rSt7uVw4xYz";
    private static final String ROTATED_API_KEY = "sk-mindie-ROTATED-" + ROTATED_MATERIAL;

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
    @DisplayName("模型配置的建/改/查/切换/删全链路日志中不出现 api key 明文")
    void apiKeyNeverReachesLogs() {
        ModelConfig created = post(config("10.96.1.11", API_KEY));

        put(created.getId(), config("10.96.1.11", ROTATED_API_KEY));
        rest.getForEntity("/api/model-configs", String.class);
        rest.getForEntity("/api/model-configs/{id}", String.class, created.getId());
        // 触发错误路径：错误处理最容易顺手把请求体打出来
        rest.exchange("/api/model-configs/active", HttpMethod.PUT,
                json(config("10.96.1.12", API_KEY)), String.class);
        rest.postForEntity("/api/model-configs", json(brokenConfig(API_KEY)), String.class);
        rest.exchange("/api/model-configs/{id}", HttpMethod.DELETE, HttpEntity.EMPTY,
                String.class, UUID.randomUUID());
        rest.exchange("/api/model-configs/{id}", HttpMethod.DELETE, HttpEntity.EMPTY,
                String.class, created.getId());

        String logs = capturedLogText();
        assertThat(logs).as("DEBUG 级别下的全部日志输出")
                .doesNotContain(KEY_MATERIAL)
                .doesNotContain(ROTATED_MATERIAL)
                .doesNotContain(API_KEY)
                .doesNotContain(ROTATED_API_KEY);
        assertThat(logs).as("反向断言：确已捕获到本服务的非敏感日志")
                .contains("10.96.1.11");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private ModelConfig post(ModelConfig request) {
        ResponseEntity<ModelConfig> response =
                rest.postForEntity("/api/model-configs", json(request), ModelConfig.class);
        assertThat(response.getStatusCode()).as("前置：创建应成功").isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void put(UUID id, ModelConfig request) {
        ResponseEntity<ModelConfig> response = rest.exchange("/api/model-configs/{id}", HttpMethod.PUT,
                json(request), ModelConfig.class, id);
        assertThat(response.getStatusCode()).as("前置：更新应成功").isEqualTo(HttpStatus.OK);
    }

    private static ModelConfig config(String address, String apiKey) {
        ModelConfig request = new ModelConfig("mindie", URI.create("http://" + address + ":8080/v1"), "Qwen3-30B");
        request.setApiKey(apiKey);
        request.setDefaultThinkingMode(ThinkingMode.THINKING);
        return request;
    }

    /** 缺必填 model：用于触发 400 分支。 */
    private static ModelConfig brokenConfig(String apiKey) {
        ModelConfig request = new ModelConfig("mindie", URI.create("http://10.96.1.13:8080/v1"), null);
        request.setApiKey(apiKey);
        return request;
    }

    private HttpEntity<String> json(ModelConfig config) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return new HttpEntity<>(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(config), headers);
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

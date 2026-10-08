package com.ananoesis.shell.security;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.service.CredentialStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * tasks 4.4 的验收：明文凭据不写入日志、审计与错误信息。
 *
 * <p>WHY 把 root logger 调到 DEBUG 再断言：泄露往往发生在"最啰嗦"的日志级别上——
 * MyBatis 在 DEBUG 下会打印 SQL 参数，Spring 在 DEBUG 下会打印 bean 属性。
 * 只在默认 INFO 级别下断言"没有明文"是自欺欺人：那只是没开日志而已。
 * 把级别开到最坏情况再断言，结论才对生产环境有意义。</p>
 *
 * <p>WHY 同时检查异常消息与完整堆栈：spec 明确禁止明文出现在**错误信息**中。
 * 一个把明文塞进异常 message 的实现，在日志里会以堆栈形式呈现，
 * 只查 {@code getFormattedMessage()} 会漏掉它。</p>
 */
class CredentialLoggingTest extends AbstractSqliteIntegrationTest {

    private static final String SSH_PASSWORD = "S3cr3t-P@ssw0rd-XYZ-9f3a";
    private static final String LLM_API_KEY = "sk-FAKE-aBcD0123456789xyz-DoNotUse";
    private static final String MASTER_PASSWORD = "correct-horse-battery-staple";
    private static final String HOST_ID = "logging-test-host";
    private static final String MODEL_CONFIG_ID = "logging-test-model";
    private static final String ENVELOPE_PREFIX = "v1.";

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CredentialLoggingTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private CredentialStoreService store;

    @Autowired
    private CredentialCryptoService crypto;

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
        // WHY 必须复原：所有集成测试共享同一个 Spring 上下文与同一个 LoggerContext，
        // 把 root 留在 DEBUG 会污染后续测试的输出并显著拖慢构建。
        rootLogger.detachAppender(appender);
        appender.stop();
        rootLogger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("保存与读取 SSH 密码的全流程日志中不出现明文（含 MyBatis DEBUG SQL）")
    void noPlaintextInLogsAcrossSaveAndLoadCycle() {
        store.save(CredentialOwnerType.HOST, HOST_ID, CredentialType.SSH_PASSWORD,
                SecretText.of(SSH_PASSWORD));
        try (SecretText restored = store.find(CredentialOwnerType.HOST, HOST_ID,
                CredentialType.SSH_PASSWORD).orElseThrow()) {
            // 故意在调用侧也打一条日志，覆盖"业务代码自己记录凭据对象"的场景
            LOG.info("已取回凭据: {}", restored);
            assertThat(restored.revealAsString()).isEqualTo(SSH_PASSWORD);
        }

        String logs = capturedLogText();
        assertThat(logs).as("DEBUG 级别下的全部日志输出").doesNotContain(SSH_PASSWORD);
        assertThat(logs).as("SecretText 插值应呈现掩码").contains(SecretText.MASK);
    }

    @Test
    @DisplayName("保存与读取 api key 的全流程日志中不出现明文")
    void noPlaintextInLogsForLlmApiKey() {
        store.save(CredentialOwnerType.MODEL_CONFIG, MODEL_CONFIG_ID,
                CredentialType.LLM_API_KEY, SecretText.of(LLM_API_KEY));
        try (SecretText apiKey = store.requireLlmApiKey(MODEL_CONFIG_ID)) {
            assertThat(apiKey.revealAsString()).isEqualTo(LLM_API_KEY);
        }

        assertThat(capturedLogText()).doesNotContain(LLM_API_KEY);
    }

    @Test
    @DisplayName("未配置 api key 时的报错不含任何凭据明文，且给出 spec 指定提示")
    void missingApiKeyErrorCarriesNoSecret() {
        assertThatThrownBy(() -> store.requireLlmApiKey("logging-never-configured"))
                .isInstanceOf(MissingModelApiKeyException.class)
                .hasMessageContaining("请先在设置中配置模型 api key");

        String logs = capturedLogText();
        assertThat(logs).doesNotContain(LLM_API_KEY).doesNotContain(SSH_PASSWORD);
    }

    @Test
    @DisplayName("主密码回退路径的日志中既不出现凭据明文，也不出现主密码")
    void noPlaintextInLogsForMasterPasswordFallback() {
        String envelope = crypto.encryptWithMasterPassword(
                SecretText.of(SSH_PASSWORD), SecretText.of(MASTER_PASSWORD));
        try (SecretText restored = crypto.decryptWithMasterPassword(
                envelope, SecretText.of(MASTER_PASSWORD))) {
            LOG.debug("回退解密结果: {}", restored);
            assertThat(restored.revealAsString()).isEqualTo(SSH_PASSWORD);
        }

        String logs = capturedLogText();
        assertThat(logs).doesNotContain(SSH_PASSWORD);
        assertThat(logs).as("主密码本身也是秘密，同样不得进日志").doesNotContain(MASTER_PASSWORD);
    }

    @Test
    @DisplayName("密钥库不可用时的报错日志含「凭据保护不可用」但不含明文")
    void unavailableKeyringErrorCarriesNoSecret() {
        CredentialCryptoService noProvider = new CredentialCryptoService(List.of());

        Throwable thrown = catchThrowable(() -> noProvider.encrypt(SecretText.of(SSH_PASSWORD)));
        LOG.error("凭据加密失败", thrown);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).doesNotContain(SSH_PASSWORD);
        String logs = capturedLogText();
        assertThat(logs).contains("凭据保护不可用");
        assertThat(logs).doesNotContain(SSH_PASSWORD);
    }

    @Test
    @DisplayName("解密失败的异常消息与堆栈均不含明文")
    void decryptionFailureLeaksNothing() {
        String tampered = tamper(crypto.encrypt(SecretText.of(SSH_PASSWORD)));

        Throwable thrown = catchThrowable(() -> crypto.decrypt(tampered));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).doesNotContain(SSH_PASSWORD);
        assertThat(stackTraceOf(thrown)).doesNotContain(SSH_PASSWORD);
        assertThat(capturedLogText()).doesNotContain(SSH_PASSWORD);
    }

    @Test
    @DisplayName("SecretText 被直接插值进日志时只呈现掩码")
    void secretTextIsMaskedWhenInterpolated() {
        SecretText secret = SecretText.of(SSH_PASSWORD);

        LOG.info("password={} key={}", secret, secret);

        assertThat(capturedLogText()).doesNotContain(SSH_PASSWORD).contains(SecretText.MASK);
    }

    // ======================================================================
    // 辅助
    // ======================================================================

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

    private static Throwable catchThrowable(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static String stackTraceOf(Throwable thrown) {
        StringWriter writer = new StringWriter();
        thrown.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /** 翻转密文首字节，制造 GCM 认证失败。 */
    private static String tamper(String envelope) {
        try {
            JsonNode payload = JSON.readTree(
                    Base64.getUrlDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length())));
            byte[] ct = Base64.getUrlDecoder().decode(payload.get("ct").asText());
            ct[0] = (byte) (ct[0] ^ 0xFF);
            ObjectNode mutable = (ObjectNode) payload;
            mutable.put("ct", Base64.getUrlEncoder().withoutPadding().encodeToString(ct));
            return ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    JSON.writeValueAsString(mutable).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

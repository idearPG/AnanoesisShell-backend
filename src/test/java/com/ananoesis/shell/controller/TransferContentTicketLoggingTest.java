package com.ananoesis.shell.controller;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ananoesis.shell.service.TransferService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * 14.7 安全测试：验证 ticket 不进日志（task 14.7.1）。
 *
 * <p>WHY 把 root logger 调到 DEBUG 再断言：泄露往往发生在"最啰嗦"的日志级别上。
 * 只在默认 INFO 级别下断言"没有明文"是自欺欺人：那只是没开日志而已。
 * 把级别开到最坏情况再断言，结论才对生产环境有意义。</p>
 *
 * <p>WHY 同时检查异常消息与完整堆栈：spec 明确禁止明文凭据出现在错误信息中。
 * 一个把明文塞进异常 message 的实现，在日志里会以堆栈形式呈现。</p>
 */
@WebMvcTest(TransferContentController.class)
class TransferContentTicketLoggingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

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
    @DisplayName("14.7：下载票据值不出现在任何日志输出中（含 DEBUG 级别与异常堆栈）")
    void ticketValueDoesNotAppearInLogs() throws Exception {
        UUID transferId = UUID.randomUUID();
        String sensitiveTicket = "sensitive-ticket-value-7f3a9b2c";

        // WHY 模拟票据无效场景：即使 startDownload 抛出异常，
        // 异常消息和日志中也不应包含票据明文
        doThrow(new com.ananoesis.shell.service.ConflictException("票据无效或已过期"))
                .when(transferService).startDownload(eq(transferId.toString()), eq(sensitiveTicket));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/transfers/{id}/content", transferId)
                        .param("ticket", sensitiveTicket))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());

        // 验证日志中不包含票据明文
        String logs = capturedLogText();
        assertThat(logs)
                .as("DEBUG 级别下的全部日志输出不应包含票据明文")
                .doesNotContain(sensitiveTicket);
    }

    @Test
    @DisplayName("14.7：上传流日志中不包含文件内容")
    void uploadContentNotLoggedInDetail() throws Exception {
        UUID transferId = UUID.randomUUID();
        String fileContent = "SENSITIVE-FILE-CONTENT-PASSWORD=hunter2";

        doNothing().when(transferService).uploadContent(eq(transferId.toString()), any());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/transfers/{id}/content", transferId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                        .content(fileContent.getBytes()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        String logs = capturedLogText();
        assertThat(logs)
                .as("上传文件内容不应出现在日志中")
                .doesNotContain(fileContent)
                .doesNotContain("hunter2");
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

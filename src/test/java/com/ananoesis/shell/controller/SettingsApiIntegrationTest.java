package com.ananoesis.shell.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.Settings;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * tasks 7.1 的验收（设置侧）：{@code /api/settings} 的全局默认思考模式读写。
 *
 * <p>WHY 每个用例前都把设置还原成 V1 迁移的种子值：所有集成测试共享同一个 Spring 上下文与
 * 同一个 SQLite 文件（见 {@link AbstractSqliteIntegrationTest}），而 {@code settings} 是
 * <b>全局单行语义</b>的键值表——上一个用例改了 {@code model.default_thinking_mode}，
 * 下一个用例的断言就会依赖 JUnit 的方法执行顺序，出现"单独跑绿、一起跑红"的最难排查的一类失败。</p>
 *
 * <p>WHY 数据库断言走原生 JDBC 而不是复用 {@code SettingMapper}：
 * 用被测代码验证被测代码，等于让"映射漏了一列"这类缺陷自己给自己判无罪。</p>
 */
class SettingsApiIntegrationTest extends AbstractSqliteIntegrationTest {

    private static final String THINKING_KEY = "model.default_thinking_mode";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SettingsService settingsService;

    @BeforeEach
    void restoreSeededDefault() {
        // 直接写库而非经 REST：还原动作本身不应依赖被测端点的正确性
        writeSetting(THINKING_KEY, "false", "boolean");
    }

    // ======================================================================
    // 读取
    // ======================================================================

    @Test
    @DisplayName("GET /api/settings → 200，返回 V1 种子的默认非思考模式")
    void getSettingsReturnsSeededDefault() {
        ResponseEntity<String> raw = rest.getForEntity("/api/settings", String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        Settings settings = parse(raw.getBody());
        assertThat(settings.getDefaultThinkingMode())
                .as("种子里 model.default_thinking_mode=false，对应契约的 non_thinking")
                .isEqualTo(ThinkingMode.NON_THINKING);
    }

    @Test
    @DisplayName("GET /api/settings 的响应体只含契约声明的字段，不多不少")
    void getSettingsEmitsExactlyContractFields() throws Exception {
        ResponseEntity<String> raw = rest.getForEntity("/api/settings", String.class);

        // WHY 要断言字段集合：冻结契约的 Settings 只有 default_thinking_mode 一个属性
        // （TRACEABILITY Q5 裁定：超时/上限走 settings 键值、不经 REST 暴露）。
        // 擅自加字段就是破坏契约，前端按契约生成的类型会对不上。
        assertThat(objectMapper.readTree(raw.getBody()).fieldNames())
                .toIterable()
                .containsExactly("default_thinking_mode");
    }

    // ======================================================================
    // 更新
    // ======================================================================

    @Test
    @DisplayName("PUT /api/settings 切到 thinking → 200 回显，且 settings 表落 true")
    void updateSettingsToThinkingPersists() {
        ResponseEntity<String> raw = rest.exchange("/api/settings", HttpMethod.PUT,
                jsonEntity(new Settings(ThinkingMode.THINKING)), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(raw.getBody()).getDefaultThinkingMode()).isEqualTo(ThinkingMode.THINKING);
        assertThat(readSetting(THINKING_KEY)).as("布尔种子值应被写成 true").isEqualTo("true");
    }

    @Test
    @DisplayName("PUT /api/settings 再切回 non_thinking → 落 false（证明是更新而非只插入）")
    void updateSettingsBackToNonThinkingPersists() {
        writeSetting(THINKING_KEY, "true", "boolean");

        ResponseEntity<String> raw = rest.exchange("/api/settings", HttpMethod.PUT,
                jsonEntity(new Settings(ThinkingMode.NON_THINKING)), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readSetting(THINKING_KEY)).isEqualTo("false");
    }

    @Test
    @DisplayName("PUT /api/settings 缺少必填 default_thinking_mode → 400 validation_error")
    void updateSettingsWithoutRequiredFieldIsRejected() {
        ResponseEntity<String> raw = rest.exchange("/api/settings", HttpMethod.PUT,
                rawJson("{}"), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error error = parseError(raw.getBody());
        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(error.getDetails()).as("应指出是哪个字段缺失").isNotEmpty();
        assertThat(error.getDetails().get(0).getField()).isEqualTo("default_thinking_mode");
    }

    @Test
    @DisplayName("PUT /api/settings 非法枚举取值 → 400 validation_error，且不复述用户输入")
    void updateSettingsWithUnknownModeIsRejected() {
        ResponseEntity<String> raw = rest.exchange("/api/settings", HttpMethod.PUT,
                rawJson("{\"default_thinking_mode\":\"deep_think\"}"), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error error = parseError(raw.getBody());
        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        // WHY 断言不复述：把非法输入原样拼回响应既可用于探测解析器，也会让前端 bug 变成怪异的 4xx 文案
        assertThat(error.getMessage()).doesNotContain("deep_think");
        assertThat(readSetting(THINKING_KEY)).as("非法请求不得改动库里的值").isEqualTo("false");
    }

    // ======================================================================
    // 阈值键值（TRACEABILITY Q5：实值取自 settings，不经 REST 暴露）
    // ======================================================================

    @Test
    @DisplayName("SettingsService 暴露的四项阈值取自 V1 种子（审批超时/执行超时/输出上限/读取行数）")
    void thresholdDefaultsComeFromSeededSettings() {
        assertThat(settingsService.approvalTimeoutSeconds())
                .as("approval.timeout.seconds 种子值").isEqualTo(120);
        assertThat(settingsService.runCommandTimeoutSeconds())
                .as("run_command.timeout.seconds 种子值").isEqualTo(60);
        assertThat(settingsService.runCommandMaxOutputBytes())
                .as("run_command.max_output_bytes 种子值").isEqualTo(65536);
        assertThat(settingsService.readFileMaxLines())
                .as("read_file.max_lines 种子值").isEqualTo(500);
    }

    @Test
    @DisplayName("SettingsService 读到被覆盖的阈值时使用库里的新值，而不是配置文件的旧值")
    void thresholdsFollowDatabaseOverrides() {
        writeSetting("approval.timeout.seconds", "7", "number");
        writeSetting("run_command.max_output_bytes", "1024", "number");
        try {
            // WHY 这条断言重要：application.yml 里也有 exec-max-output-bytes=65536，
            // 若实现偷懒读属性而不读库，用户在设置界面改的值就永远不生效，
            // 而且不会有任何报错——症状是"改了没用"
            assertThat(settingsService.approvalTimeoutSeconds()).isEqualTo(7);
            assertThat(settingsService.runCommandMaxOutputBytes()).isEqualTo(1024);
        } finally {
            writeSetting("approval.timeout.seconds", "120", "number");
            writeSetting("run_command.max_output_bytes", "65536", "number");
        }
    }

    @Test
    @DisplayName("SettingsService 遇到被写坏的阈值行时退回内置默认，而不是让整条 AI 链路 500")
    void corruptedThresholdFallsBackToDefault() {
        writeSetting("read_file.max_lines", "not-a-number", "number");
        try {
            assertThat(settingsService.readFileMaxLines())
                    .as("损坏的库值不得让服务不可用").isEqualTo(SettingsService.DEFAULT_READ_FILE_MAX_LINES);
        } finally {
            writeSetting("read_file.max_lines", "500", "number");
        }
    }

    @Test
    @DisplayName("SettingsService 对非法阈值（0 或负数）也退回默认：0 秒超时会让审批立即失败")
    void nonPositiveThresholdFallsBackToDefault() {
        writeSetting("approval.timeout.seconds", "0", "number");
        try {
            assertThat(settingsService.approvalTimeoutSeconds())
                    .isEqualTo(SettingsService.DEFAULT_APPROVAL_TIMEOUT_SECONDS);
        } finally {
            writeSetting("approval.timeout.seconds", "120", "number");
        }
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private HttpEntity<String> jsonEntity(Settings settings) {
        return rawJson(write(settings));
    }

    private HttpEntity<String> rawJson(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String write(Settings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    private Settings parse(String body) {
        try {
            return objectMapper.readValue(body, Settings.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析响应体: " + body, e);
        }
    }

    private Error parseError(String body) {
        try {
            return objectMapper.readValue(body, Error.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析错误响应体: " + body, e);
        }
    }

    private String readSetting(String key) {
        String sql = "SELECT setting_value FROM settings WHERE setting_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeSetting(String key, String value, String valueType) {
        String sql = "UPDATE settings SET setting_value = ?, value_type = ? WHERE setting_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, valueType);
            statement.setString(3, key);
            int updated = statement.executeUpdate();
            assertThat(updated).as("种子行 %s 必须存在，否则本用例的前置条件不成立", key).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

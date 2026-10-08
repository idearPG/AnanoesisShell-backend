package com.ananoesis.shell.ai;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.ModelConfig;
import com.ananoesis.shell.contract.model.OutputLimitField;
import com.ananoesis.shell.contract.model.ThinkingRequestFormat;
import com.ananoesis.shell.service.ModelConfigService;
import com.ananoesis.shell.service.ModelConfigService.ActiveModelConfig;

/**
 * 旧模型配置的幂等迁移验证（tasks 8.2 / design D8）。
 *
 * <p>WHY 直接往 settings 表写旧格式 JSON：迁移的输入是"V2 部署后库里残留的 V1 载荷"，
 * 只有把原始 JSON 原样写入，才能证明服务层在读取时正确补全了新字段，
 * 而不是依赖"服务层自己写进去的数据恰好没丢字段"这种循环论证。</p>
 *
 * <p>迁移规则（design D8）：
 * <ul>
 *   <li>旧 provider=openai → thinking_request_format=none</li>
 *   <li>旧 provider=其他值 → thinking_request_format=qwen_compatible</li>
 *   <li>新配置默认 none（由 ModelConfigService.create 保证）</li>
 *   <li>迁移重复运行不再改写（幂等）</li>
 * </ul>
 */
class ModelConfigMigrationTest extends AbstractSqliteIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ModelConfigService modelConfigService;

    // ======================================================================
    // thinking_request_format 迁移
    // ======================================================================

    @Test
    @DisplayName("旧 provider=openai 配置迁移为 thinking_request_format=none")
    void oldOpenAiConfigMigratesToNoneThinkingFormat() {
        insertLegacyConfig("openai",
                "https://api.openai.com/v1", "gpt-4o-mini");

        ActiveModelConfig active = modelConfigService.findActive().orElseThrow();

        assertThat(active.thinkingRequestFormat())
                .as("旧 OpenAI 配置不应再发送 Qwen 兼容的思考开关")
                .isEqualTo(ThinkingRequestFormat.NONE);
    }

    @Test
    @DisplayName("旧 provider=mindie 配置迁移为 thinking_request_format=qwen_compatible")
    void oldMindieConfigMigratesToQwenCompatibleThinkingFormat() {
        insertLegacyConfig("mindie",
                "http://10.95.1.11:8080/v1", "Qwen3-30B");

        ActiveModelConfig active = modelConfigService.findActive().orElseThrow();

        assertThat(active.thinkingRequestFormat())
                .as("旧非 OpenAI 配置应保留 Qwen 兼容的思考开关行为")
                .isEqualTo(ThinkingRequestFormat.QWEN_COMPATIBLE);
    }

    @Test
    @DisplayName("旧 provider=ollama 配置迁移为 thinking_request_format=qwen_compatible")
    void oldOllamaConfigMigratesToQwenCompatibleThinkingFormat() {
        insertLegacyConfig("ollama",
                "http://localhost:11434/v1", "llama3");

        ActiveModelConfig active = modelConfigService.findActive().orElseThrow();

        assertThat(active.thinkingRequestFormat())
                .isEqualTo(ThinkingRequestFormat.QWEN_COMPATIBLE);
    }

    // ======================================================================
    // 预算字段默认值迁移
    // ======================================================================

    @Test
    @DisplayName("旧配置迁移后获得默认预算值：context_window_tokens=8192, max_output_tokens=1024")
    void oldConfigGetsDefaultBudgetValues() {
        insertLegacyConfig("mindie",
                "http://10.95.1.11:8080/v1", "Qwen3-30B");

        ActiveModelConfig active = modelConfigService.findActive().orElseThrow();

        assertThat(active.contextWindowTokens()).isEqualTo(8192);
        assertThat(active.maxOutputTokens()).isEqualTo(1024);
    }

    @Test
    @DisplayName("旧配置迁移后 output_limit_field 默认为 max_tokens")
    void oldConfigGetsDefaultOutputLimitField() {
        insertLegacyConfig("mindie",
                "http://10.95.1.11:8080/v1", "Qwen3-30B");

        ActiveModelConfig active = modelConfigService.findActive().orElseThrow();

        assertThat(active.outputLimitField()).isEqualTo(OutputLimitField.MAX_TOKENS);
    }

    // ======================================================================
    // 幂等性
    // ======================================================================

    @Test
    @DisplayName("迁移幂等：首次读取迁移后，后续读取不再改写 JSON 载荷")
    void migrationIsIdempotentAndDoesNotRewriteOnSubsequentReads() {
        String configId = insertLegacyConfig("mindie",
                "http://10.95.1.11:8080/v1", "Qwen3-30B");

        // 第一次读取触发迁移
        ActiveModelConfig first = modelConfigService.findActive().orElseThrow();
        assertThat(first.thinkingRequestFormat()).isEqualTo(ThinkingRequestFormat.QWEN_COMPATIBLE);

        // 记录迁移后 JSON 载荷
        String jsonAfterFirstRead = selectConfigJson(configId);
        assertThat(jsonAfterFirstRead).contains("qwen_compatible");

        // 第二次读取不应再改写
        ActiveModelConfig second = modelConfigService.findActive().orElseThrow();
        String jsonAfterSecondRead = selectConfigJson(configId);

        assertThat(second.thinkingRequestFormat()).isEqualTo(ThinkingRequestFormat.QWEN_COMPATIBLE);
        assertThat(jsonAfterSecondRead)
                .as("重复读取不应改变已迁移的 JSON 载荷")
                .isEqualTo(jsonAfterFirstRead);
    }

    @Test
    @DisplayName("已含新字段的配置不被迁移覆盖")
    void configWithNewFieldsIsNotOverwrittenByMigration() {
        // 写入已包含新字段的配置（模拟已迁移或新建的配置）
        String json = "{"
                + "\"provider\":\"mindie\","
                + "\"base_url\":\"http://10.95.1.11:8080/v1\","
                + "\"model\":\"Qwen3-30B\","
                + "\"thinking_request_format\":\"none\","
                + "\"context_window_tokens\":16384,"
                + "\"max_output_tokens\":2048,"
                + "\"output_limit_field\":\"max_completion_tokens\","
                + "\"created_at\":\"2025-01-01T00:00:00Z\","
                + "\"updated_at\":\"2025-01-01T00:00:00Z\""
                + "}";
        String configId = insertRawConfig(json);
        activateConfig(configId);

        ActiveModelConfig active = modelConfigService.findActive().orElseThrow();

        // 已有值不被迁移覆盖
        assertThat(active.thinkingRequestFormat()).isEqualTo(ThinkingRequestFormat.NONE);
        assertThat(active.contextWindowTokens()).isEqualTo(16384);
        assertThat(active.maxOutputTokens()).isEqualTo(2048);
        assertThat(active.outputLimitField()).isEqualTo(OutputLimitField.MAX_COMPLETION_TOKENS);
    }

    // ======================================================================
    // 双重前缀回归（list() 迁移时传参错误导致 setting key 变为 model.config.model.config.*）
    // ======================================================================

    @Test
    @DisplayName("list() 触发迁移后 setting key 不会出现双重前缀 model.config.model.config.*")
    void listDoesNotCreateDoublePrefixOnMigration() {
        String configId = insertLegacyConfig("openai",
                "https://api.openai.com/v1", "gpt-4o-mini");

        // list() 内部触发迁移，迁移回写不应产生双重前缀
        List<ModelConfig> configs = modelConfigService.list();

        // 找到刚插入的配置（共享数据库可能有其他测试的配置）
        ModelConfig target = configs.stream()
                .filter(c -> c.getId().toString().equals(configId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应能找到刚插入的配置"));

        // 关键断言：id 可正常解析（不会抛 UUID string too large）
        assertThat(target.getId().toString()).isEqualTo(configId);

        // 数据库中的 key 仍然是 model.config.{uuid}，而非 model.config.model.config.{uuid}
        String actualKey = selectSettingKey(configId);
        assertThat(actualKey)
                .as("迁移后 setting key 不应出现双重前缀")
                .isEqualTo(ModelConfigService.configKeyOf(configId));
    }

    @Test
    @DisplayName("list() 连续调用两次不会因双重前缀导致第二次失败")
    void listCalledTwiceDoesNotFailOnSecondCall() {
        String configId = insertLegacyConfig("ollama",
                "http://localhost:11434/v1", "llama3");

        // 第一次 list() 触发迁移
        List<ModelConfig> first = modelConfigService.list();
        assertThat(first.stream().map(c -> c.getId().toString()))
                .contains(configId);

        // 第二次 list() 不应因 key 损坏而抛异常
        List<ModelConfig> second = modelConfigService.list();
        assertThat(second.stream().map(c -> c.getId().toString()))
                .contains(configId);
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    /**
     * 向 settings 表写入旧格式（无预算字段、无 thinking_request_format）的模型配置 JSON，
     * 并自动设为生效配置。
     */
    private String insertLegacyConfig(String provider, String baseUrl, String model) {
        String json = "{"
                + "\"provider\":\"" + provider + "\","
                + "\"base_url\":\"" + baseUrl + "\","
                + "\"model\":\"" + model + "\","
                + "\"created_at\":\"2025-01-01T00:00:00Z\","
                + "\"updated_at\":\"2025-01-01T00:00:00Z\""
                + "}";
        return insertRawConfig(json);
    }

    private String insertRawConfig(String json) {
        String configId = java.util.UUID.randomUUID().toString();
        // WHY 包含 updated_at：settings 表的 updated_at 列为 NOT NULL，
        // 缺少它会导致 SQLITE_CONSTRAINT_NOTNULL 错误
        String sql = "INSERT INTO settings (setting_key, setting_value, value_type, description, updated_at) "
                + "VALUES (?, ?, 'json', '模型配置: 迁移测试', '1970-01-01T00:00:00')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ModelConfigService.configKeyOf(configId));
            ps.setString(2, json);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        activateConfig(configId);
        return configId;
    }

    private void activateConfig(String configId) {
        // WHY 使用 INSERT OR REPLACE：集成测试共享同一个 SQLite 数据库，
        // 其他测试可能已写入 model.active_config_id，普通 INSERT 会触发 UNIQUE 约束冲突
        String sql = "INSERT OR REPLACE INTO settings (setting_key, setting_value, value_type, description, updated_at) "
                + "VALUES ('model.active_config_id', ?, 'string', '当前生效配置', '1970-01-01T00:00:00')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, configId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String selectSettingKey(String configId) {
        String sql = "SELECT setting_key FROM settings WHERE setting_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ModelConfigService.configKeyOf(configId));
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String selectConfigJson(String configId) {
        String sql = "SELECT setting_value FROM settings WHERE setting_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ModelConfigService.configKeyOf(configId));
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

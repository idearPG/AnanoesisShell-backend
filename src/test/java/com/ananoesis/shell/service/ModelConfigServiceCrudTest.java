package com.ananoesis.shell.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.contract.model.ModelConfig;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.entity.Setting;
import com.ananoesis.shell.mapper.SettingMapper;
import com.ananoesis.shell.security.MissingModelApiKeyException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ModelConfigService} CRUD 业务逻辑分支覆盖。
 *
 * <p>WHY 独立测试：覆盖 list/findById/findActive/create/delete/resolveThinkingMode
 * 中的条件分支。</p>
 */
@DisplayName("ModelConfigService CRUD 分支覆盖")
class ModelConfigServiceCrudTest {

    private SettingMapper settingMapper;
    private SettingsService settingsService;
    private CredentialStoreService credentials;
    private ObjectMapper objectMapper;
    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        settingMapper = mock(SettingMapper.class);
        settingsService = mock(SettingsService.class);
        credentials = mock(CredentialStoreService.class);
        objectMapper = new ObjectMapper();
        service = new ModelConfigService(settingMapper, settingsService, credentials, objectMapper);
    }

    private String validJson() {
        return "{\"provider\":\"openai\",\"base_url\":\"https://api.example.com/v1\","
                + "\"model\":\"gpt-4\",\"default_thinking_mode\":\"thinking\","
                + "\"context_window_tokens\":8192,\"max_output_tokens\":1024,"
                + "\"output_limit_field\":\"max_tokens\","
                + "\"thinking_request_format\":\"none\","
                + "\"created_at\":\"2026-01-01T00:00:00+08:00\","
                + "\"updated_at\":\"2026-01-01T00:00:00+08:00\"}";
    }

    @Nested
    @DisplayName("list")
    class ListConfigs {
        @Test
        @DisplayName("空列表返回空")
        void emptyList() {
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.empty());
            when(settingMapper.findByKeyPrefix("model.config.")).thenReturn(List.of());
            assertThat(service.list()).isEmpty();
        }

        @Test
        @DisplayName("坏 JSON 行被跳过")
        void badJsonSkipped() {
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.empty());
            Setting bad = new Setting();
            bad.setSettingKey("model.config.bad");
            bad.setSettingValue("not-json");
            when(settingMapper.findByKeyPrefix("model.config.")).thenReturn(List.of(bad));
            assertThat(service.list()).isEmpty();
        }

        @Test
        @DisplayName("空载荷被跳过")
        void emptyPayloadSkipped() {
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.empty());
            Setting empty = new Setting();
            empty.setSettingKey("model.config.empty");
            empty.setSettingValue("");
            when(settingMapper.findByKeyPrefix("model.config.")).thenReturn(List.of(empty));
            assertThat(service.list()).isEmpty();
        }

        @Test
        @DisplayName("有效配置正确返回")
        void validReturned() {
            String cid = UUID.randomUUID().toString();
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.of(cid));
            Setting row = new Setting();
            row.setSettingKey("model.config." + cid);
            row.setSettingValue(validJson());
            when(settingMapper.findByKeyPrefix("model.config.")).thenReturn(List.of(row));
            when(credentials.exists(any(), anyString(), any())).thenReturn(false);
            assertThat(service.list()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {
        @Test
        @DisplayName("null id 抛 NPE")
        void nullThrows() {
            assertThatThrownBy(() -> service.findById(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("不存在抛 NotFoundException")
        void notFoundThrows() {
            when(settingMapper.findByKey(anyString())).thenReturn(null);
            assertThatThrownBy(() -> service.findById(UUID.randomUUID()))
                    .isInstanceOf(ModelConfigNotFoundException.class);
        }

        @Test
        @DisplayName("存在正确返回")
        void foundReturns() {
            UUID id = UUID.randomUUID();
            Setting row = new Setting();
            row.setSettingKey("model.config." + id);
            row.setSettingValue(validJson());
            when(settingMapper.findByKey("model.config." + id.toString())).thenReturn(row);
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.of(id.toString()));
            when(credentials.exists(any(), anyString(), any())).thenReturn(true);
            ModelConfig result = service.findById(id);
            assertThat(result.getProvider()).isEqualTo("openai");
            assertThat(result.getIsActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("findActive / requireActive")
    class FindActive {
        @Test
        @DisplayName("无生效配置返回 empty")
        void noActiveEmpty() {
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.empty());
            assertThat(service.findActive()).isEmpty();
        }

        @Test
        @DisplayName("生效记录不存在返回 empty")
        void recordMissingEmpty() {
            String id = UUID.randomUUID().toString();
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.of(id));
            when(settingMapper.findByKey("model.config." + id)).thenReturn(null);
            assertThat(service.findActive()).isEmpty();
        }

        @Test
        @DisplayName("requireActive 无配置抛异常")
        void requireActiveThrows() {
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requireActive())
                    .isInstanceOf(MissingModelApiKeyException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {
        @Test
        @DisplayName("null id 抛 NPE")
        void nullThrows() {
            assertThatThrownBy(() -> service.delete(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("删除生效配置清除指针")
        void deleteActiveClearsPointer() {
            UUID id = UUID.randomUUID();
            Setting row = new Setting();
            row.setSettingKey("model.config." + id);
            row.setSettingValue(validJson());
            when(settingMapper.findByKey("model.config." + id.toString())).thenReturn(row);
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.of(id.toString()));
            service.delete(id);
            verify(settingsService).deleteKey("model.active_config_id");
        }

        @Test
        @DisplayName("删除非生效配置保留指针")
        void deleteNonActiveKeeps() {
            UUID id = UUID.randomUUID();
            String other = UUID.randomUUID().toString();
            Setting row = new Setting();
            row.setSettingKey("model.config." + id);
            row.setSettingValue(validJson());
            when(settingMapper.findByKey("model.config." + id.toString())).thenReturn(row);
            when(settingsService.rawValue("model.active_config_id")).thenReturn(Optional.of(other));
            service.delete(id);
            verify(settingsService, never()).deleteKey("model.active_config_id");
        }
    }

    @Nested
    @DisplayName("resolveThinkingMode")
    class ResolveThinking {
        @Test
        @DisplayName("null configId 抛 NPE")
        void nullThrows() {
            assertThatThrownBy(() -> service.resolveThinkingMode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("per-config 有值取 per-config")
        void perConfigOverrides() {
            UUID id = UUID.randomUUID();
            Setting row = new Setting();
            row.setSettingKey("model.config." + id);
            row.setSettingValue(validJson());
            when(settingMapper.findByKey("model.config." + id.toString())).thenReturn(row);
            assertThat(service.resolveThinkingMode(id.toString())).isEqualTo(ThinkingMode.THINKING);
        }

        @Test
        @DisplayName("per-config null 回落全局")
        void fallsToGlobal() {
            UUID id = UUID.randomUUID();
            String json = validJson().replace("\"default_thinking_mode\":\"thinking\"",
                    "\"default_thinking_mode\":null");
            Setting row = new Setting();
            row.setSettingKey("model.config." + id);
            row.setSettingValue(json);
            when(settingMapper.findByKey("model.config." + id.toString())).thenReturn(row);
            when(settingsService.defaultThinkingMode()).thenReturn(ThinkingMode.NON_THINKING);
            assertThat(service.resolveThinkingMode(id.toString())).isEqualTo(ThinkingMode.NON_THINKING);
        }
    }
}

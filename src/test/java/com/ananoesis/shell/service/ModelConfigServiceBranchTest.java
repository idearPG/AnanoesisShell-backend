package com.ananoesis.shell.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.ananoesis.shell.mapper.SettingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ModelConfigService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖 CRUD 闭环，但构造器 null 校验等分支未覆盖。</p>
 */
@DisplayName("ModelConfigService 分支覆盖")
class ModelConfigServiceBranchTest {

    @Test
    @DisplayName("构造器: settingMapper 为 null 时抛异常")
    void nullSettingMapperThrows() {
        assertThatThrownBy(() -> new ModelConfigService(
                null, mock(SettingsService.class), mock(CredentialStoreService.class), new ObjectMapper()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingMapper");
    }

    @Test
    @DisplayName("构造器: settingsService 为 null 时抛异常")
    void nullSettingsServiceThrows() {
        assertThatThrownBy(() -> new ModelConfigService(
                mock(SettingMapper.class), null, mock(CredentialStoreService.class), new ObjectMapper()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsService");
    }

    @Test
    @DisplayName("构造器: credentials 为 null 时抛异常")
    void nullCredentialsThrows() {
        assertThatThrownBy(() -> new ModelConfigService(
                mock(SettingMapper.class), mock(SettingsService.class), null, new ObjectMapper()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    @DisplayName("构造器: objectMapper 为 null 时抛异常")
    void nullObjectMapperThrows() {
        assertThatThrownBy(() -> new ModelConfigService(
                mock(SettingMapper.class), mock(SettingsService.class), mock(CredentialStoreService.class), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("objectMapper");
    }

    @Test
    @DisplayName("常量值正确")
    void constantsCorrect() {
        assertThat(ModelConfigService.CONFIG_KEY_PREFIX).isEqualTo("model.config.");
        assertThat(ModelConfigService.ACTIVE_CONFIG_KEY).isEqualTo("model.active_config_id");
        assertThat(ModelConfigService.DEFAULT_CONTEXT_WINDOW_TOKENS).isEqualTo(8192);
        assertThat(ModelConfigService.DEFAULT_MAX_OUTPUT_TOKENS).isEqualTo(1024);
    }
}

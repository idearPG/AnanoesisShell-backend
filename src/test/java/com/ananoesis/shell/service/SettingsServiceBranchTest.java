package com.ananoesis.shell.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.entity.Setting;
import com.ananoesis.shell.mapper.SettingMapper;

/**
 * {@link SettingsService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖读写闭环，但构造器 null 校验、
 * 设置键不存在/非法值的兜底路径未覆盖。</p>
 */
@DisplayName("SettingsService 分支覆盖")
class SettingsServiceBranchTest {

    private SettingMapper settingMapper;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        settingMapper = mock(SettingMapper.class);
        service = new SettingsService(settingMapper);
    }

    @Test
    @DisplayName("构造器: settingMapper 为 null 时抛异常")
    void nullMapperThrows() {
        assertThatThrownBy(() -> new SettingsService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingMapper");
    }

    @Test
    @DisplayName("defaultThinkingMode: 键不存在时返回默认值")
    void defaultThinkingModeMissingReturnsDefault() {
        when(settingMapper.findByKey(SettingsService.KEY_DEFAULT_THINKING_MODE)).thenReturn(null);
        assertThat(service.defaultThinkingMode()).isEqualTo(ThinkingMode.NON_THINKING);
    }

    @Test
    @DisplayName("defaultThinkingMode: 值为 1 时返回 ENABLED")
    void defaultThinkingModeEnabledReturnsEnabled() {
        var setting = new Setting();
        setting.setSettingKey(SettingsService.KEY_DEFAULT_THINKING_MODE);
        setting.setSettingValue("1");
        when(settingMapper.findByKey(SettingsService.KEY_DEFAULT_THINKING_MODE)).thenReturn(setting);
        assertThat(service.defaultThinkingMode()).isEqualTo(ThinkingMode.THINKING);
    }

    @Test
    @DisplayName("defaultThinkingMode: 值为 0 时返回 DISABLED")
    void defaultThinkingModeDisabledReturnsDisabled() {
        var setting = new Setting();
        setting.setSettingKey(SettingsService.KEY_DEFAULT_THINKING_MODE);
        setting.setSettingValue("0");
        when(settingMapper.findByKey(SettingsService.KEY_DEFAULT_THINKING_MODE)).thenReturn(setting);
        assertThat(service.defaultThinkingMode()).isEqualTo(ThinkingMode.NON_THINKING);
    }

    @Test
    @DisplayName("defaultThinkingMode: 非法值时返回默认值")
    void defaultThinkingModeIllegalReturnsDefault() {
        var setting = new Setting();
        setting.setSettingKey(SettingsService.KEY_DEFAULT_THINKING_MODE);
        setting.setSettingValue("not_a_boolean");
        when(settingMapper.findByKey(SettingsService.KEY_DEFAULT_THINKING_MODE)).thenReturn(setting);
        assertThat(service.defaultThinkingMode()).isEqualTo(ThinkingMode.NON_THINKING);
    }

    @Test
    @DisplayName("approvalTimeoutSeconds: 键不存在时返回默认值")
    void approvalTimeoutMissingReturnsDefault() {
        when(settingMapper.findByKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS)).thenReturn(null);
        assertThat(service.approvalTimeoutSeconds()).isEqualTo(SettingsService.DEFAULT_APPROVAL_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("approvalTimeoutSeconds: 合法值正常返回")
    void approvalTimeoutValidReturnsValue() {
        var setting = new Setting();
        setting.setSettingKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS);
        setting.setSettingValue("60");
        when(settingMapper.findByKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS)).thenReturn(setting);
        assertThat(service.approvalTimeoutSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("approvalTimeoutSeconds: 非法值返回默认值")
    void approvalTimeoutIllegalReturnsDefault() {
        var setting = new Setting();
        setting.setSettingKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS);
        setting.setSettingValue("abc");
        when(settingMapper.findByKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS)).thenReturn(setting);
        assertThat(service.approvalTimeoutSeconds()).isEqualTo(SettingsService.DEFAULT_APPROVAL_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("approvalTimeoutSeconds: 0 返回默认值")
    void approvalTimeoutZeroReturnsDefault() {
        var setting = new Setting();
        setting.setSettingKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS);
        setting.setSettingValue("0");
        when(settingMapper.findByKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS)).thenReturn(setting);
        assertThat(service.approvalTimeoutSeconds()).isEqualTo(SettingsService.DEFAULT_APPROVAL_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("approvalTimeoutSeconds: 负数返回默认值")
    void approvalTimeoutNegativeReturnsDefault() {
        var setting = new Setting();
        setting.setSettingKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS);
        setting.setSettingValue("-5");
        when(settingMapper.findByKey(SettingsService.KEY_APPROVAL_TIMEOUT_SECONDS)).thenReturn(setting);
        assertThat(service.approvalTimeoutSeconds()).isEqualTo(SettingsService.DEFAULT_APPROVAL_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("update: request 为 null 时抛异常")
    void updateNullRequestThrows() {
        assertThatThrownBy(() -> service.update(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("常量值正确")
    void constantsCorrect() {
        assertThat(SettingsService.DEFAULT_APPROVAL_TIMEOUT_SECONDS).isEqualTo(120);
        assertThat(SettingsService.DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS).isEqualTo(1800);
        assertThat(SettingsService.DEFAULT_RUN_COMMAND_MAX_OUTPUT_BYTES).isEqualTo(65536);
        assertThat(SettingsService.DEFAULT_READ_FILE_MAX_LINES).isEqualTo(500);
    }
}

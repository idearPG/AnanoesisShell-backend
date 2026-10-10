package com.ananoesis.shell;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.desktop.DesktopSessionStore;
import com.ananoesis.shell.mapper.SettingMapper;
import com.ananoesis.shell.service.TransferProgressWatchdog;
import com.ananoesis.shell.ssh.SshAuthMethod;
import com.ananoesis.shell.ssh.SshTarget;
import com.ananoesis.shell.support.Timestamps;
import com.ananoesis.shell.ws.ToolName;

/**
 * 小型工具类的最后分支补测。
 *
 * <p>WHY 独立测试：覆盖率已达 79.79%，只差 5 个分支。
 * 本文件覆盖 Timestamps/ToolName 等只有 1 missed branch 的类。</p>
 */
@DisplayName("小型工具类最后分支补测")
class SmallClassFinalBranchTest {

    // ---- Timestamps ----

    @Test
    @DisplayName("Timestamps.toOffset(null) → null")
    void timestampsToOffsetNull() {
        assertThat(Timestamps.toOffset(null)).isNull();
    }

    @Test
    @DisplayName("Timestamps.toOffset(非null) → OffsetDateTime")
    void timestampsToOffsetNonNull() {
        LocalDateTime ldt = LocalDateTime.of(2025, 6, 15, 12, 0, 0);
        OffsetDateTime result = Timestamps.toOffset(ldt);
        assertThat(result).isNotNull();
        assertThat(result.toLocalDateTime()).isEqualTo(ldt);
    }

    @Test
    @DisplayName("Timestamps.toLocal(null) → null")
    void timestampsToLocalNull() {
        assertThat(Timestamps.toLocal(null)).isNull();
    }

    @Test
    @DisplayName("Timestamps.toLocal(非null) → LocalDateTime")
    void timestampsToLocalNonNull() {
        OffsetDateTime odt = OffsetDateTime.now();
        LocalDateTime result = Timestamps.toLocal(odt);
        assertThat(result).isNotNull();
    }

    // ---- ToolName ----

    @Test
    @DisplayName("ToolName.fromValue(null) → null")
    void toolNameFromNullValue() {
        assertThat(ToolName.fromValue(null)).isNull();
    }

    @Test
    @DisplayName("ToolName.fromValue(合法值) → 对应枚举")
    void toolNameFromValidValue() {
        assertThat(ToolName.fromValue("list_dir")).isEqualTo(ToolName.LIST_DIR);
        assertThat(ToolName.fromValue("read_file")).isEqualTo(ToolName.READ_FILE);
        assertThat(ToolName.fromValue("system_info")).isEqualTo(ToolName.SYSTEM_INFO);
        assertThat(ToolName.fromValue("run_command")).isEqualTo(ToolName.RUN_COMMAND);
    }

    @Test
    @DisplayName("ToolName.fromValue(未知值) → null")
    void toolNameFromUnknownValue() {
        assertThat(ToolName.fromValue("nonexistent_tool")).isNull();
    }

    // ---- ToolName.autoExecuted ----

    @Test
    @DisplayName("ToolName 只读工具 autoExecuted=true")
    void readOnlyToolsAutoExecuted() {
        assertThat(ToolName.LIST_DIR.autoExecuted()).isTrue();
        assertThat(ToolName.READ_FILE.autoExecuted()).isTrue();
        assertThat(ToolName.SYSTEM_INFO.autoExecuted()).isTrue();
    }

    @Test
    @DisplayName("ToolName.RUN_COMMAND autoExecuted=false")
    void runCommandNotAutoExecuted() {
        assertThat(ToolName.RUN_COMMAND.autoExecuted()).isFalse();
    }

    // ---- TransferProgressWatchdog ----

    @Test
    @DisplayName("TransferProgressWatchdog.unregister(null) 不抛异常")
    void watchdogUnregisterNull() {
        var watchdog = new TransferProgressWatchdog();
        watchdog.unregister(null); // 不抛即过
    }

    // ---- SshTarget ----

    @Test
    @DisplayName("SshTarget: port 超出范围时抛异常")
    void sshTargetPortOutOfRange() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new SshTarget("host", 0, "user", SshAuthMethod.password("pw")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    @DisplayName("SshTarget: port 上限超出时抛异常")
    void sshTargetPortAboveMax() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new SshTarget("host", 70000, "user", SshAuthMethod.password("pw")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    // ---- DesktopSessionStore ----

    @Test
    @DisplayName("DesktopSessionStore.isValid(null) → false")
    void sessionStoreNullIsInvalid() {
        var store = new DesktopSessionStore();
        assertThat(store.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("DesktopSessionStore.isValid(不存在的票) → false")
    void sessionStoreUnknownIsInvalid() {
        var store = new DesktopSessionStore();
        assertThat(store.isValid("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("DesktopSessionStore: issue → isValid 闭环")
    void sessionStoreIssueAndValidate() {
        var store = new DesktopSessionStore();
        String ticket = store.issue();
        assertThat(store.isValid(ticket)).isTrue();
    }

    // ---- SettingsService ----

    @Test
    @DisplayName("SettingsService.update: mode 为 null 时抛 InvalidRequestException")
    void settingsServiceUpdateNullMode() {
        var service = new com.ananoesis.shell.service.SettingsService(
                org.mockito.Mockito.mock(SettingMapper.class));
        var request = new com.ananoesis.shell.contract.model.Settings();
        request.setDefaultThinkingMode(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update(request))
                .isInstanceOf(com.ananoesis.shell.service.InvalidRequestException.class);
    }
}

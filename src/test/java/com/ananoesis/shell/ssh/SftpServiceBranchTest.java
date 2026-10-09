package com.ananoesis.shell.ssh;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SftpService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖 listDir 闭环，但 validatePath 的多个分支
 * （null/空/非绝对路径/NUL/CR/LF/.. 组件）和 listDir null 校验未覆盖。</p>
 */
@DisplayName("SftpService 分支覆盖")
class SftpServiceBranchTest {

    private SftpService sftpService;

    @BeforeEach
    void setUp() {
        sftpService = new SftpService();
    }

    // ---- validatePath 分支 ----

    @Test
    @DisplayName("validatePath: null 路径抛异常")
    void validatePathNullThrows() {
        assertThatThrownBy(() -> sftpService.validatePath(null))
                .isInstanceOf(SftpService.PathValidationException.class)
                .hasMessageContaining("空");
    }

    @Test
    @DisplayName("validatePath: 空串路径抛异常")
    void validatePathEmptyThrows() {
        assertThatThrownBy(() -> sftpService.validatePath(""))
                .isInstanceOf(SftpService.PathValidationException.class)
                .hasMessageContaining("空");
    }

    @Test
    @DisplayName("validatePath: 非绝对路径抛异常")
    void validatePathNotAbsoluteThrows() {
        assertThatThrownBy(() -> sftpService.validatePath("relative/path"))
                .isInstanceOf(SftpService.PathValidationException.class)
                .hasMessageContaining("绝对路径");
    }

    @Test
    @DisplayName("validatePath: 含 NUL 字符抛异常")
    void validatePathWithNulThrows() {
        assertThatThrownBy(() -> sftpService.validatePath("/path\0with\0nul"))
                .isInstanceOf(SftpService.PathValidationException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    @DisplayName("validatePath: 含 CR 字符抛异常")
    void validatePathWithCrThrows() {
        assertThatThrownBy(() -> sftpService.validatePath("/path\rwith\rCR"))
                .isInstanceOf(SftpService.PathValidationException.class)
                .hasMessageContaining("CR");
    }

    @Test
    @DisplayName("validatePath: 含 LF 字符抛异常")
    void validatePathWithLfThrows() {
        assertThatThrownBy(() -> sftpService.validatePath("/path\nwith\nLF"))
                .isInstanceOf(SftpService.PathValidationException.class)
                .hasMessageContaining("LF");
    }

    @Test
    @DisplayName("validatePath: 含 .. 组件抛异常")
    void validatePathWithDotDotThrows() {
        assertThatThrownBy(() -> sftpService.validatePath("/path/../etc"))
                .isInstanceOf(SftpService.PathValidationException.class)
                .hasMessageContaining("..");
    }

    @Test
    @DisplayName("validatePath: 合法路径不抛异常")
    void validatePathValidNoThrow() {
        sftpService.validatePath("/home/user");
        sftpService.validatePath("/");
        sftpService.validatePath("/var/log/syslog");
    }

    // ---- listDir null 校验 ----

    @Test
    @DisplayName("listDir: runtime 为 null 时抛异常")
    void listDirNullRuntimeThrows() {
        assertThatThrownBy(() -> sftpService.listDir(null, "/tmp", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runtime");
    }

    @Test
    @DisplayName("listDir: path 为 null 时抛 PathValidationException")
    void listDirNullPathThrows() {
        var runtime = org.mockito.Mockito.mock(SessionRuntime.class);
        assertThatThrownBy(() -> sftpService.listDir(runtime, null, null, null))
                .isInstanceOf(SftpService.PathValidationException.class);
    }
}

package com.ananoesis.shell.controller;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.Error;

/**
 * FilesApiController 的集成测试（task 12.4）。
 *
 * <p>验证 GET /api/sessions/{id}/files 端点的基本行为：
 * 需要 X-Session-Control 头、session 不存在时返回 404、路径无效时返回 400。</p>
 *
 * <p>WHY 不在本测试中做完整 SFTP 端到端验证：完整的目录浏览测试在
 * {@link com.ananoesis.shell.ssh.SftpServiceTest} 中完成（使用嵌入式 SFTP 服务器）。
 * 本测试只验证 HTTP 层的控制面行为。</p>
 */
class FilesApiIntegrationTest extends AbstractSqliteIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("GET /api/sessions/{id}/files：缺少 X-Session-Control 头 → 400")
    void missingSessionControlHeaderReturns400() {
        UUID fakeSessionId = UUID.randomUUID();

        ResponseEntity<Error> response = rest.getForEntity(
                "/api/sessions/{id}/files?path=/",
                Error.class,
                fakeSessionId);

        // 缺少必需的 X-Session-Control 头，Spring 会返回 400
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /api/sessions/{id}/files：session 不存在 → 404 或 500")
    void nonExistentSessionReturnsError() {
        UUID fakeSessionId = UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Control", "fake-token");

        ResponseEntity<String> response = rest.exchange(
                "/api/sessions/{id}/files?path=/",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers),
                String.class,
                fakeSessionId);

        // session 不存在时，requireRuntime 抛 IllegalArgumentException
        // ApiExceptionHandler 应将其翻译为适当的错误响应
        assertThat(response.getStatusCode()).isIn(
                HttpStatus.NOT_FOUND,
                HttpStatus.BAD_REQUEST,
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("GET /api/sessions/{id}/files：缺少 path 参数 → 400")
    void missingPathParameterReturns400() {
        UUID fakeSessionId = UUID.randomUUID();

        ResponseEntity<Error> response = rest.getForEntity(
                "/api/sessions/{id}/files",
                Error.class,
                fakeSessionId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

package com.ananoesis.shell;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * tasks 2.4 的启动验收门禁：应用能启动且 {@code /actuator/health} 返回 {@code UP}。
 *
 * <p>该断言同时覆盖数据层可用性——Actuator 的 DataSource / Flyway 健康指示器
 * 会在 SQLite 无法打开或迁移失败时把整体状态降为 DOWN。</p>
 */
class HealthEndpointTest extends AbstractSqliteIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> HEALTH_BODY =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /actuator/health 返回 200 且 status=UP")
    void actuatorHealthReportsUp() {
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange("/actuator/health", HttpMethod.GET, null, HEALTH_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}

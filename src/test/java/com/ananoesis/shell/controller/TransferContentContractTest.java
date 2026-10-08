package com.ananoesis.shell.controller;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.ssh.SftpService;
import com.ananoesis.shell.ssh.SftpService.PathValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * TransferContent 端点的契约合规与安全测试（tasks 2.5 / 2.6 / 14.7 / design D10）。
 *
 * <p>WHY 手写这个测试：PUT/GET {@code /api/transfers/{id}/content} 标记了
 * {@code x-skip-codegen: true}，从代码生成集合中排除，由手写流式适配实现。
 * 正因为它们不在生成集合里，没有生成代码来"自动"保证路径、媒体类型、状态码与 OpenAPI 一致——
 * 本测试就是那道安全网：逐项核对这两个端点在 openapi.yaml 中确实有定义，
 * 防止例外脱离契约。</p>
 *
 * <p>安全部分（task 14.7）验证：
 * <ul>
 *   <li>票据 GET 端点仅接受 ticket 参数，不接受执行操作</li>
 *   <li>本地绝对路径被路径校验拒绝，不用于磁盘读写</li>
 *   <li>二进制内容端点不定义 JSON 请求/响应体，内容不流入模型和审计正文</li>
 * </ul></p>
 */
class TransferContentContractTest {

    private static Map<String, Object> spec;

    @BeforeAll
    static void loadSpec() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = TransferContentContractTest.class.getResourceAsStream("/contract/openapi.yaml")) {
            // 优先从 classpath 加载（如果 contract 被打包进测试资源）
            if (is != null) {
                spec = yamlMapper.readValue(is, Map.class);
                return;
            }
        }
        // 回退到文件系统路径（开发环境下 contract/ 在仓库根目录）
        java.nio.file.Path specPath = java.nio.file.Path.of("../contract/openapi.yaml");
        if (!java.nio.file.Files.exists(specPath)) {
            specPath = java.nio.file.Path.of("contract/openapi.yaml");
        }
        spec = yamlMapper.readValue(java.nio.file.Files.readString(specPath, StandardCharsets.UTF_8), Map.class);
    }

    // ======================================================================
    // PUT /api/transfers/{id}/content
    // ======================================================================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("PUT /api/transfers/{id}/content 在 OpenAPI 中有定义，媒体类型为 application/octet-stream")
    void putTransferContentIsDefinedWithOctetStreamMediaType() {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertThat(paths).containsKey("/api/transfers/{id}/content");

        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/api/transfers/{id}/content");
        assertThat(pathItem).containsKey("put");

        Map<String, Object> put = (Map<String, Object>) pathItem.get("put");

        // 验证 tag
        List<String> tags = (List<String>) put.get("tags");
        assertThat(tags).contains("TransferContent");

        // 验证 operationId
        assertThat(put.get("operationId")).isEqualTo("uploadTransferContent");

        // 验证请求体媒体类型
        Map<String, Object> requestBody = (Map<String, Object>) put.get("requestBody");
        assertThat(requestBody).isNotNull();
        Map<String, Object> content = (Map<String, Object>) requestBody.get("content");
        assertThat(content).containsKey("application/octet-stream");

        // 验证状态码
        Map<String, Object> responses = (Map<String, Object>) put.get("responses");
        assertThat(responses).containsKeys("200", "409", "413");
    }

    // ======================================================================
    // GET /api/transfers/{id}/content
    // ======================================================================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("GET /api/transfers/{id}/content 在 OpenAPI 中有定义，响应包含必要的安全头")
    void getTransferContentIsDefinedWithSecurityHeaders() {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertThat(paths).containsKey("/api/transfers/{id}/content");

        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/api/transfers/{id}/content");
        assertThat(pathItem).containsKey("get");

        Map<String, Object> get = (Map<String, Object>) pathItem.get("get");

        // 验证 tag
        List<String> tags = (List<String>) get.get("tags");
        assertThat(tags).contains("TransferContent");

        // 验证 operationId
        assertThat(get.get("operationId")).isEqualTo("downloadTransferContent");

        // 验证 ticket 查询参数
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) get.get("parameters");
        assertThat(parameters).anySatisfy(param -> {
            assertThat(param.get("name")).isEqualTo("ticket");
            assertThat(param.get("in")).isEqualTo("query");
        });

        // 验证响应
        Map<String, Object> responses = (Map<String, Object>) get.get("responses");
        assertThat(responses).containsKeys("200", "404", "409");

        // 验证 200 响应的安全头
        Map<String, Object> ok = (Map<String, Object>) responses.get("200");
        Map<String, Object> headers = (Map<String, Object>) ok.get("headers");
        assertThat(headers).containsKeys("Content-Disposition", "Cache-Control", "Referrer-Policy");

        // 验证 200 响应的媒体类型
        Map<String, Object> content = (Map<String, Object>) ok.get("content");
        assertThat(content).containsKey("application/octet-stream");
    }

    // ======================================================================
    // TransferContent tag 定义
    // ======================================================================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("TransferContent tag 在顶层 tags 中已注册")
    void transferContentTagIsRegistered() {
        List<Map<String, String>> tags = (List<Map<String, String>>) spec.get("tags");
        assertThat(tags).anySatisfy(tag ->
                assertThat(tag.get("name")).isEqualTo("TransferContent"));
    }

    // ======================================================================
    // 安全验证（task 14.7）
    // ======================================================================

    @Nested
    @DisplayName("14.7 安全验证")
    class SecurityVerification {

        // ------------------------------------------------------------------
        // 14.7.3 票据 GET 不接受通用执行操作
        // ------------------------------------------------------------------

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("14.7：下载票据端点仅定义 POST，不接受执行操作参数")
        void downloadTicketEndpointDoesNotAcceptExecutionOperations() {
            Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
            assertThat(paths).containsKey("/api/transfers/{id}/download-ticket");

            Map<String, Object> pathItem = (Map<String, Object>) paths.get("/api/transfers/{id}/download-ticket");

            // WHY 票据端点只应有 POST（领票），不应有 GET/PUT/DELETE 等可能被误用为执行操作的 HTTP 方法
            assertThat(pathItem).containsKey("post");
            assertThat(pathItem).doesNotContainKey("get");
            assertThat(pathItem).doesNotContainKey("put");
            assertThat(pathItem).doesNotContainKey("delete");

            // 验证 POST 操作仅领票，不涉及执行
            Map<String, Object> post = (Map<String, Object>) pathItem.get("post");
            assertThat(post.get("operationId")).isEqualTo("claimDownloadTicket");

            // 请求体不应存在（领票不需要执行参数）
            assertThat(post.get("requestBody")).isNull();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("14.7：GET 下载端点仅接受 ticket 查询参数，无执行相关参数")
        void downloadContentEndpointOnlyAcceptsTicketParameter() {
            Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
            Map<String, Object> pathItem = (Map<String, Object>) paths.get("/api/transfers/{id}/content");
            Map<String, Object> get = (Map<String, Object>) pathItem.get("get");

            List<Map<String, Object>> parameters = (List<Map<String, Object>>) get.get("parameters");

            // WHY 下载端点只接受 ticket 和路径参数中的 id，不应有 command/action/execute 等执行参数
            for (Map<String, Object> param : parameters) {
                String name = (String) param.get("name");
                assertThat(name)
                        .as("下载端点参数名不应为执行操作相关")
                        .isNotIn("command", "action", "execute", "script", "payload");
            }

            // 确认 ticket 参数存在
            assertThat(parameters).anySatisfy(p -> {
                assertThat(p.get("name")).isEqualTo("ticket");
                assertThat(p.get("in")).isEqualTo("query");
            });
        }

        // ------------------------------------------------------------------
        // 14.7.4 本地绝对路径不被后端用于磁盘读写
        // ------------------------------------------------------------------

        @Test
        @DisplayName("14.7：路径校验拒绝 Windows 本地绝对路径（如 C:\\Windows\\System32）")
        void localWindowsAbsolutePathRejected() {
            SftpService sftpService = new SftpService();

            // WHY SftpService.validatePath 要求路径以 / 开头（远端 Unix 路径），
            // Windows 本地路径（C:\...）不以 / 开头，会被拒绝。
            // 这确保后端不会将用户输入的路径误用于本地磁盘读写。
            assertThatThrownBy(() -> sftpService.validatePath("C:\\Windows\\System32"))
                    .isInstanceOf(PathValidationException.class);

            assertThatThrownBy(() -> sftpService.validatePath("D:\\Users\\data"))
                    .isInstanceOf(PathValidationException.class);
        }

        @Test
        @DisplayName("14.7：路径校验拒绝相对路径（不含前导 /）")
        void relativePathRejected() {
            SftpService sftpService = new SftpService();

            // WHY 相对路径可能被用于路径穿越攻击，访问预期目录之外的文件
            assertThatThrownBy(() -> sftpService.validatePath("relative/path"))
                    .isInstanceOf(PathValidationException.class);

            assertThatThrownBy(() -> sftpService.validatePath("..\\etc\\passwd"))
                    .isInstanceOf(PathValidationException.class);
        }

        @Test
        @DisplayName("14.7：路径校验拒绝含 .. 组件的路径（防止路径穿越）")
        void pathTraversalRejected() {
            SftpService sftpService = new SftpService();

            // WHY 即使路径以 / 开头，.. 组件仍可穿越到父目录
            assertThatThrownBy(() -> sftpService.validatePath("/home/user/../etc/passwd"))
                    .isInstanceOf(PathValidationException.class);

            assertThatThrownBy(() -> sftpService.validatePath("/srv/../../../etc/shadow"))
                    .isInstanceOf(PathValidationException.class);
        }

        // ------------------------------------------------------------------
        // 14.7.5 上传下载内容不流入模型和审计正文
        // ------------------------------------------------------------------

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("14.7：二进制内容端点不定义 JSON 请求/响应体，内容不流入模型和审计")
        void binaryContentEndpointsDoNotDefineJsonBodies() {
            Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
            Map<String, Object> pathItem = (Map<String, Object>) paths.get("/api/transfers/{id}/content");

            // WHY PUT 请求体是 application/octet-stream（二进制流），不是 JSON。
            // 这确保上传的文件内容不会被解析为 JSON 流入模型输入或审计记录。
            Map<String, Object> put = (Map<String, Object>) pathItem.get("put");
            Map<String, Object> putRequestBody = (Map<String, Object>) put.get("requestBody");
            Map<String, Object> putContent = (Map<String, Object>) putRequestBody.get("content");
            assertThat(putContent).containsKey("application/octet-stream");
            assertThat(putContent).doesNotContainKey("application/json");

            // WHY GET 200 响应是 application/octet-stream（二进制流），不是 JSON。
            // 这确保下载的文件内容不会作为 JSON 流入模型上下文或审计正文。
            Map<String, Object> get = (Map<String, Object>) pathItem.get("get");
            Map<String, Object> getResponses = (Map<String, Object>) get.get("responses");
            Map<String, Object> getOk = (Map<String, Object>) getResponses.get("200");
            Map<String, Object> getResponseContent = (Map<String, Object>) getOk.get("content");
            assertThat(getResponseContent).containsKey("application/octet-stream");
            assertThat(getResponseContent).doesNotContainKey("application/json");
        }
    }
}

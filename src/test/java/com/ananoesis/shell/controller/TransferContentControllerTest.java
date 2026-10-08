package com.ananoesis.shell.controller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.ananoesis.shell.service.ConflictException;
import com.ananoesis.shell.service.NotFoundException;
import com.ananoesis.shell.service.TransferConflictException;
import com.ananoesis.shell.service.TransferService;
import com.ananoesis.shell.service.TransferService.DownloadResult;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 二进制内容端点的 HTTP 适配层测试（JaCoCo 0.75 门禁补覆盖）。
 *
 * <p>只测控制器自身的状态码翻译、响应头装配与流式体执行；
 * 传输业务语义（票据消费、槽位、发布）在
 * {@code TransferFailureTest}/{@code TransferPublicationTest} 已覆盖。</p>
 */
class TransferContentControllerTest {

    private TransferService transferService;
    private TransferContentController controller;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transferService = mock(TransferService.class);
        controller = new TransferContentController(transferService);
    }

    private HttpServletRequest requestWithEmptyBody() {
        return new MockHttpServletRequest();
    }

    @Nested
    @DisplayName("PUT 上传状态码翻译")
    class Upload {

        @Test
        @DisplayName("成功 → 200")
        void ok() {
            assertThat(controller.uploadContent(id, requestWithEmptyBody()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            verify(transferService).uploadContent(anyString(), any());
        }

        @Test
        @DisplayName("目标已存在（TransferConflictException）→ 409")
        void transferConflict() {
            doThrow(new TransferConflictException("exists", "snapshot"))
                    .when(transferService).uploadContent(anyString(), any());
            assertThat(controller.uploadContent(id, requestWithEmptyBody()).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("通用冲突 → 409")
        void genericConflict() {
            doThrow(new ConflictException("bad state"))
                    .when(transferService).uploadContent(anyString(), any());
            assertThat(controller.uploadContent(id, requestWithEmptyBody()).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("运行期异常 → 500")
        void runtimeError() {
            doThrow(new IllegalStateException("boom"))
                    .when(transferService).uploadContent(anyString(), any());
            assertThat(controller.uploadContent(id, requestWithEmptyBody()).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("GET 下载状态码、响应头与流式体")
    class Download {

        private DownloadResult stubResult(String fileName, byte[] payload) throws Exception {
            DownloadResult result = mock(DownloadResult.class);
            when(result.getFileName()).thenReturn(fileName);
            when(result.getInputStream()).thenReturn(new ByteArrayInputStream(payload));
            return result;
        }

        @Test
        @DisplayName("成功：安全响应头齐备且流体如实转发，收尾 completeDownload")
        void okStreamsAndHeaders() throws Exception {
            byte[] payload = "hello-bytes".getBytes(StandardCharsets.UTF_8);
            // WHY 先构创建 stub：在 when(...) 链内另起 mock 会触发 UnfinishedStubbing
            DownloadResult result = stubResult("报告 \"/weird\".txt", payload);
            when(transferService.startDownload(anyString(), anyString())).thenReturn(result);

            ResponseEntity<StreamingResponseBody> resp = controller.downloadContent(id, "tkt");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(resp.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
            String cd = resp.getHeaders().getFirst("Content-Disposition");
            assertThat(cd)
                    .contains("filename*=UTF-8''")
                    .contains("attachment;");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            resp.getBody().writeTo(out);
            assertThat(out.toByteArray()).isEqualTo(payload);
            verify(transferService).completeDownload(id.toString());
        }

        @Test
        @DisplayName("冲突 → 409、不存在 → 404、其他异常 → 500")
        void errorTranslations() {
            // WHY 用 doThrow 而非 when：对已 stub 成抛异常的方法再次 when(...) 求值时，
            // 会直接触发旧 stub 把异常抛到测试自身（UnfinishedStubbing/穿透）
            doThrow(new ConflictException("not ready"))
                    .when(transferService).startDownload(anyString(), anyString());
            assertThat(controller.downloadContent(id, "t").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            doThrow(new NotFoundException("gone") { })
                    .when(transferService).startDownload(anyString(), anyString());
            assertThat(controller.downloadContent(id, "t").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            doThrow(new IllegalStateException("boom"))
                    .when(transferService).startDownload(anyString(), anyString());
            assertThat(controller.downloadContent(id, "t").getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("completeDownload 失败只告警，不击穿响应流")
        void completeFailureSwallowed() throws Exception {
            byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
            DownloadResult result = stubResult("a.txt", payload);
            when(transferService.startDownload(anyString(), anyString())).thenReturn(result);
            doThrow(new IllegalStateException("db down"))
                    .when(transferService).completeDownload(anyString());

            ResponseEntity<StreamingResponseBody> resp = controller.downloadContent(id, "tkt");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            resp.getBody().writeTo(out);
            assertThat(out.toByteArray()).isEqualTo(payload);
        }

        @Test
        @DisplayName("ASCII 回退名：控制字符与非 ASCII 一律替换为下划线")
        void asciiFallback() throws Exception {
            byte[] payload = "z".getBytes(StandardCharsets.UTF_8);
            DownloadResult result = stubResult("中文\nname", payload);
            when(transferService.startDownload(anyString(), anyString())).thenReturn(result);
            ResponseEntity<StreamingResponseBody> resp = controller.downloadContent(id, "tkt");
            String cd = resp.getHeaders().getFirst("Content-Disposition");
            // “中文”两字各替换一个下划线；\n 控制字符再替换一个 → "___name"
            assertThat(cd).contains("filename=\"___name\"");
        }

        @Test
        @DisplayName("空文件名为回退名 download")
        void emptyFileNameFallback() throws Exception {
            byte[] payload = "z".getBytes(StandardCharsets.UTF_8);
            DownloadResult result = stubResult("", payload);
            when(transferService.startDownload(anyString(), anyString())).thenReturn(result);
            ResponseEntity<StreamingResponseBody> resp = controller.downloadContent(id, "tkt");
            assertThat(resp.getHeaders().getFirst("Content-Disposition")).contains("filename=\"download\"");
        }
    }
}

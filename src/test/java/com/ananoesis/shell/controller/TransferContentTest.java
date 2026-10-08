package com.ananoesis.shell.controller;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ananoesis.shell.service.TransferService;

/**
 * 二进制上传/下载 HTTP 适配层测试（design.md D9）。
 *
 * <p>验证 TransferContentController 的 HTTP 行为：
 * PUT 上传的流式处理、GET 下载的票据验证和响应头。</p>
 */
@WebMvcTest(TransferContentController.class)
class TransferContentTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @Test
    @DisplayName("PUT /api/transfers/{id}/content：上传成功 → 200")
    void uploadSuccess() throws Exception {
        UUID transferId = UUID.randomUUID();

        // uploadContent 正常完成不抛异常
        doNothing().when(transferService).uploadContent(eq(transferId.toString()), any());

        mockMvc.perform(put("/api/transfers/{id}/content", transferId)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("test content".getBytes()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /api/transfers/{id}/content：状态冲突 → 409")
    void uploadConflict() throws Exception {
        UUID transferId = UUID.randomUUID();

        doThrow(new com.ananoesis.shell.service.ConflictException("状态不是 ready"))
                .when(transferService).uploadContent(eq(transferId.toString()), any());

        mockMvc.perform(put("/api/transfers/{id}/content", transferId)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("test content".getBytes()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PUT /api/transfers/{id}/content：目标已存在 → 409")
    void uploadTargetExists() throws Exception {
        UUID transferId = UUID.randomUUID();

        doThrow(new com.ananoesis.shell.service.TransferConflictException("目标已存在", "{}"))
                .when(transferService).uploadContent(eq(transferId.toString()), any());

        mockMvc.perform(put("/api/transfers/{id}/content", transferId)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("test content".getBytes()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/transfers/{id}/content：缺少 ticket 参数 → 400")
    void downloadMissingTicket() throws Exception {
        UUID transferId = UUID.randomUUID();

        mockMvc.perform(get("/api/transfers/{id}/content", transferId))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/transfers/{id}/content：票据无效 → 409")
    void downloadInvalidTicket() throws Exception {
        UUID transferId = UUID.randomUUID();

        doThrow(new com.ananoesis.shell.service.ConflictException("票据无效"))
                .when(transferService).startDownload(eq(transferId.toString()), eq("bad-ticket"));

        mockMvc.perform(get("/api/transfers/{id}/content", transferId)
                        .param("ticket", "bad-ticket"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/transfers/{id}/content：任务不存在 → 404")
    void downloadNotFound() throws Exception {
        UUID transferId = UUID.randomUUID();

        doThrow(new com.ananoesis.shell.service.NotFoundException("传输任务不存在") {})
                .when(transferService).startDownload(eq(transferId.toString()), eq("some-ticket"));

        mockMvc.perform(get("/api/transfers/{id}/content", transferId)
                        .param("ticket", "some-ticket"))
                .andExpect(status().isNotFound());
    }
}

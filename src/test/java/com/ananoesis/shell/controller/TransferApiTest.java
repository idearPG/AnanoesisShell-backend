package com.ananoesis.shell.controller;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ananoesis.shell.contract.model.Transfer;
import com.ananoesis.shell.contract.model.TransferDirection;
import com.ananoesis.shell.contract.model.TransferStatus;
import com.ananoesis.shell.service.TransferService;
import com.ananoesis.shell.ssh.SshTerminalService;

/**
 * 传输管理 API 的 HTTP 层测试（design.md D9）。
 *
 * <p>验证 TransfersApiController 的路由、参数绑定、响应码等 HTTP 行为。
 * 业务逻辑由 TransferService 处理，此处使用 mock。</p>
 */
@WebMvcTest(TransfersApiController.class)
class TransferApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private SshTerminalService terminalService;

    @Test
    @DisplayName("GET /api/transfers/{id}：返回传输状态")
    void getTransferReturnsStatus() throws Exception {
        UUID transferId = UUID.randomUUID();
        Transfer mockTransfer = new Transfer();
        mockTransfer.setId(transferId);
        mockTransfer.setDirection(TransferDirection.UPLOAD);
        mockTransfer.setFileName("test.txt");
        mockTransfer.setRemotePath("/test/test.txt");
        mockTransfer.setStatus(TransferStatus.QUEUED);
        mockTransfer.setBytesTransferred(0L);
        mockTransfer.setTotalBytes(100L);

        when(transferService.getTransfer(transferId.toString())).thenReturn(mockTransfer);

        mockMvc.perform(get("/api/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId.toString()))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.direction").value("upload"));
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/cancel：取消传输")
    void cancelTransferReturnsResult() throws Exception {
        UUID transferId = UUID.randomUUID();
        Transfer mockTransfer = new Transfer();
        mockTransfer.setId(transferId);
        mockTransfer.setDirection(TransferDirection.UPLOAD);
        mockTransfer.setFileName("test.txt");
        mockTransfer.setRemotePath("/test/test.txt");
        mockTransfer.setStatus(TransferStatus.CANCELLED);
        mockTransfer.setBytesTransferred(0L);
        mockTransfer.setTotalBytes(100L);

        when(transferService.cancelTransfer(transferId.toString())).thenReturn(mockTransfer);

        mockMvc.perform(post("/api/transfers/{id}/cancel", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/download-ticket：缺少 X-Session-Control 头 → 400")
    void claimTicketWithoutControlHeaderReturns400() throws Exception {
        UUID transferId = UUID.randomUUID();

        mockMvc.perform(post("/api/transfers/{id}/download-ticket", transferId))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/transfers/{id}：不存在的任务 → 404")
    void getNonExistentTransferReturns404() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.getTransfer(transferId.toString()))
                .thenThrow(new com.ananoesis.shell.service.NotFoundException("传输任务不存在") {});

        mockMvc.perform(get("/api/transfers/{id}", transferId))
                .andExpect(status().isNotFound());
    }
}

package com.ananoesis.shell.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.ananoesis.shell.contract.api.TransfersApi;
import com.ananoesis.shell.contract.model.CreateTransferRequest;
import com.ananoesis.shell.contract.model.DownloadTicket;
import com.ananoesis.shell.contract.model.Transfer;
import com.ananoesis.shell.service.TransferService;
import com.ananoesis.shell.ssh.SshTerminalService;

/**
 * {@code /api/sessions/{id}/transfers} 与 {@code /api/transfers/{id}} 的 REST 实现（design.md D9）。
 *
 * <p>WHY 实现 {@link TransfersApi}：与 {@link FilesApiController} 同理——
 * 路由、参数、响应类型全部来自 openapi-generator 生成的接口，
 * 实现与契约的漂移在编译期即不可能。</p>
 *
 * <p>控制面校验（session 存在性、控制令牌验证）在此完成，
 * 传输逻辑委托给 {@link TransferService}。</p>
 */
@Validated
@RestController
public class TransfersApiController implements TransfersApi {

    private final TransferService transferService;
    private final SshTerminalService terminalService;

    public TransfersApiController(TransferService transferService,
                                  SshTerminalService terminalService) {
        this.transferService = transferService;
        this.terminalService = terminalService;
    }

    @Override
    public ResponseEntity<Transfer> createTransfer(UUID id,
                                                   String xSessionControl,
                                                   CreateTransferRequest request) {
        String sessionId = id.toString();
        // WHY 验证 session 存在性：确保传输任务关联到活跃的会话
        terminalService.requireRuntime(sessionId);

        Transfer transfer = transferService.createTransfer(sessionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(transfer);
    }

    @Override
    public ResponseEntity<Transfer> getTransfer(UUID id) {
        Transfer transfer = transferService.getTransfer(id.toString());
        return ResponseEntity.ok(transfer);
    }

    @Override
    public ResponseEntity<Transfer> cancelTransfer(UUID id) {
        Transfer transfer = transferService.cancelTransfer(id.toString());
        return ResponseEntity.ok(transfer);
    }

    @Override
    public ResponseEntity<DownloadTicket> claimDownloadTicket(UUID id, String xSessionControl) {
        String ticket = transferService.claimDownloadTicket(id.toString());
        DownloadTicket dto = new DownloadTicket(ticket);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }
}

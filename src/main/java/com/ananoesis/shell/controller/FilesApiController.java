package com.ananoesis.shell.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.ananoesis.shell.contract.api.FilesApi;
import com.ananoesis.shell.contract.model.DirectoryListResponse;
import com.ananoesis.shell.ssh.SessionRuntime;
import com.ananoesis.shell.ssh.SftpService;
import com.ananoesis.shell.ssh.SshTerminalService;

/**
 * {@code /api/sessions/{id}/files} 的 REST 实现（task 12.4）。
 *
 * <p>WHY 实现 {@link FilesApi}：与 {@link HostsController} 同理——路由、参数、
 * 响应类型全部来自 openapi-generator 从冻结契约生成的接口，实现与契约的漂移在编译期即不可能。</p>
 *
 * <p>本类只做转发：控制面校验（session 存在性、控制令牌）在此完成，
 * SFTP 操作委托给 {@link SftpService}。</p>
 *
 * <p>WHY {@code @Validated}：接口 {@link FilesApi} 的参数带有 {@code @NotNull} 等校验注解，
 * 但 Spring MVC 不会自动继承接口上的参数校验——必须在实现类上也标 {@code @Validated}，
 * 否则缺失必需参数时不会触发 400，而是走到 controller 内部变成 500。</p>
 */
@Validated
@RestController
public class FilesApiController implements FilesApi {

    private final SshTerminalService terminalService;
    private final SftpService sftpService;

    public FilesApiController(SshTerminalService terminalService,
                              SftpService sftpService) {
        this.terminalService = terminalService;
        this.sftpService = sftpService;
    }

    @Override
    public ResponseEntity<DirectoryListResponse> listSessionFiles(
            UUID id,
            String xSessionControl,
            String path,
            String cursor,
            Integer limit) {

        // 1. 查找 session runtime
        String sessionId = id.toString();
        SessionRuntime runtime = terminalService.requireRuntime(sessionId);

        // 2. 委托给 SftpService 执行目录浏览
        DirectoryListResponse response = sftpService.listDir(runtime, path, cursor, limit);

        return ResponseEntity.ok(response);
    }
}

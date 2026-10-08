package com.ananoesis.shell.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.ananoesis.shell.contract.api.SessionsApi;
import com.ananoesis.shell.contract.model.Session;
import com.ananoesis.shell.contract.model.SessionStatus;
import com.ananoesis.shell.service.SessionService;

/**
 * {@code /api/sessions} 的 REST 实现（ssh-connection spec「连接会话生命周期」）。
 *
 * <p>WHY 实现 {@link SessionsApi}：与 {@link HostsController} 同理——路由、查询参数名
 * （{@code host_id} 而非 {@code hostId}）、{@code @Valid}/{@code @Nullable} 全部来自
 * openapi-generator 从冻结契约生成的接口，实现与契约的漂移在编译期即不可能。</p>
 *
 * <p>本类只做转发。状态映射（4 个内部状态 → 2 个契约状态）在 {@link SessionService}，
 * 非法查询参数 → 契约错误的翻译在 {@link ApiExceptionHandler}。</p>
 */
@RestController
public class SessionsController implements SessionsApi {

    private final SessionService sessionService;

    public SessionsController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public ResponseEntity<List<Session>> listSessions(UUID hostId, SessionStatus status) {
        return ResponseEntity.ok(sessionService.list(hostId, status));
    }
}

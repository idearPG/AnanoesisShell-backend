package com.ananoesis.shell.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.ananoesis.shell.contract.api.HostsApi;
import com.ananoesis.shell.contract.model.Host;
import com.ananoesis.shell.service.HostService;

/**
 * {@code /api/hosts} 的 REST 实现（tasks 5.1）。
 *
 * <p>WHY 实现 {@link HostsApi} 而不是自己写 {@code @RequestMapping}：
 * 路由、HTTP 方法、{@code produces}/{@code consumes}、参数上的 {@code @Valid}/
 * {@code @PathVariable} 全部来自 openapi-generator 从冻结契约生成的接口，
 * Spring MVC 会通过 {@code HandlerMethod#getInterfaceParameterAnnotations()} 继承它们。
 * 这样"实现与契约漂移"在编译期就不可能发生：契约改了路径或字段，
 * 生成的接口随之改变，本类若没跟上就编译不过。</p>
 *
 * <p>WHY 本类几乎是空的：分层约定（见 {@code package-info}）要求控制器只做转发。
 * 业务规则在 {@link HostService}，异常→契约错误的翻译在 {@link ApiExceptionHandler}。
 * 这里出现任何 {@code if} 都是分层被侵蚀的信号。</p>
 *
 * <p><b>安全</b>：MUST NOT 在此记录请求 DTO——生成的 {@code Host#toString()}
 * 会把 {@code privateKey} 完整打印出来。</p>
 */
@RestController
public class HostsController implements HostsApi {

    private final HostService hostService;

    public HostsController(HostService hostService) {
        this.hostService = hostService;
    }

    @Override
    public ResponseEntity<Host> createHost(Host host) {
        // WHY 201 而不是接口默认可能给出的 200：契约把 POST /api/hosts 的成功响应
        // 明确声明为 201 Created，且响应体带服务端分配的 id
        return ResponseEntity.status(201).body(hostService.create(host));
    }

    @Override
    public ResponseEntity<List<Host>> listHosts() {
        return ResponseEntity.ok(hostService.list());
    }

    @Override
    public ResponseEntity<Host> getHost(UUID id) {
        return ResponseEntity.ok(hostService.findById(id));
    }

    @Override
    public ResponseEntity<Host> updateHost(UUID id, Host host) {
        return ResponseEntity.ok(hostService.update(id, host));
    }

    @Override
    public ResponseEntity<Void> deleteHost(UUID id) {
        hostService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

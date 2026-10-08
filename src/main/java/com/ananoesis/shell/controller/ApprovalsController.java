package com.ananoesis.shell.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestController;

import com.ananoesis.shell.approval.ApprovalAuditService;
import com.ananoesis.shell.contract.api.ApprovalsApi;
import com.ananoesis.shell.contract.model.ApprovalDecision;
import com.ananoesis.shell.contract.model.ApprovalsPage;

/**
 * {@code /api/approvals} 的 HTTP 适配层（tasks 8.5）。
 *
 * <p>WHY 实现生成的 {@link ApprovalsApi}：与 {@link ModelConfigsController} 同理——
 * 路由、{@code @Min}/{@code @Max} 校验、查询参数名全部来自冻结契约。</p>
 *
 * <p>本类<b>只做转发</b>。审计行的组装、{@code pending} 的排除、分页 SQL 全在
 * {@link ApprovalAuditService} 里；本类不判断"哪些行该被看见"。</p>
 *
 * <h2>这是审批链路的<b>事后</b>视图</h2>
 * <p>实时裁决走 {@code /ws/approval}（{@code approval_request} 下行、
 * {@code approval_response} 上行）；本端点只回答"过去批准/拒绝/超时过哪些命令，
 * 执行结果如何"。command-approval「审批审计日志」要求记录时间、目标服务器、
 * 工具名与参数、AI 分析、用户决定与执行结果——全部体现在 {@code Approval} DTO 上，
 * 且 MUST NOT 含明文凭据（命令文本本身可能含口令，但那是用户批准的原文，
 * 属于审计的必要内容；凭据从不进入命令行，见 {@code AgentTools} 的提示纪律）。</p>
 */
@RestController
public class ApprovalsController implements ApprovalsApi {

    /** 契约 {@code page} 参数的 {@code defaultValue}；此处复制一份用于非 HTTP 调用路径。 */
    private static final int DEFAULT_PAGE = 1;

    /** 契约 {@code size} 参数的 {@code defaultValue}。 */
    private static final int DEFAULT_SIZE = 20;

    private final ApprovalAuditService approvalAuditService;

    public ApprovalsController(ApprovalAuditService approvalAuditService) {
        this.approvalAuditService = approvalAuditService;
    }

    @Override
    public ResponseEntity<ApprovalsPage> listApprovals(Integer page, Integer size,
                                                       @Nullable UUID hostId,
                                                       @Nullable ApprovalDecision decision) {
        return ResponseEntity.ok(approvalAuditService.page(
                orDefault(page, DEFAULT_PAGE), orDefault(size, DEFAULT_SIZE), hostId, decision));
    }

    /**
     * WHY 在控制器里补默认值：{@code defaultValue="1"} 只在 Spring MVC 的参数解析路径上生效。
     * 直接调用本方法（单元测试、将来的内部转发）时 {@code page} 可能是 null，
     * 而 {@code ApprovalAuditService#page} 收的是 {@code int}——拆箱会抛 NPE，
     * 最终变成 500 {@code internal_error}。契约对"不传 page"的语义是第 1 页，
     * 不该因为调用方式不同而分叉。
     */
    private static int orDefault(@Nullable Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}

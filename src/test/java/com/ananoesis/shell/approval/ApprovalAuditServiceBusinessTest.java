package com.ananoesis.shell.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ApprovalDecision;
import com.ananoesis.shell.contract.model.ExecutionStatus;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.ApprovalMapper;
import com.ananoesis.shell.mapper.HostMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ApprovalAuditService} 业务逻辑分支补测。
 *
 * <p>WHY 独立测试：覆盖 toExecution/parseMap/noteOf/asText/page/decisionOf/finalCommandOf
 * 等方法的多种条件分支。</p>
 */
@DisplayName("ApprovalAuditService 业务逻辑分支")
class ApprovalAuditServiceBusinessTest {

    private ApprovalMapper approvalMapper;
    private HostMapper hostMapper;
    private ApprovalAuditService service;

    @BeforeEach
    void setUp() {
        approvalMapper = mock(ApprovalMapper.class);
        hostMapper = mock(HostMapper.class);
        service = new ApprovalAuditService(approvalMapper, hostMapper, new ObjectMapper());
    }

    // ---- decisionOf ----

    @Nested
    @DisplayName("decisionOf")
    class DecisionOf {
        @Test
        @DisplayName("null approvalId 返回 null")
        void nullIdReturnsNull() {
            assertThat(service.decisionOf(null)).isNull();
        }

        @Test
        @DisplayName("行不存在返回 null")
        void missingRowReturnsNull() {
            when(approvalMapper.selectById(anyString())).thenReturn(null);
            assertThat(service.decisionOf(UUID.randomUUID())).isNull();
        }

        @Test
        @DisplayName("pending 行返回 null outcome")
        void pendingRowReturnsNullOutcome() {
            var row = new com.ananoesis.shell.entity.Approval();
            row.setDecision("pending");
            when(approvalMapper.selectById(anyString())).thenReturn(row);
            assertThat(service.decisionOf(UUID.randomUUID())).isNull();
        }

        @Test
        @DisplayName("approved 行返回 APPROVED")
        void approvedRowReturnsApproved() {
            var row = new com.ananoesis.shell.entity.Approval();
            row.setDecision("approved");
            when(approvalMapper.selectById(anyString())).thenReturn(row);
            ApprovalOutcome outcome = service.decisionOf(UUID.randomUUID());
            assertThat(outcome).isEqualTo(ApprovalOutcome.APPROVED);
        }
    }

    // ---- finalCommandOf ----

    @Nested
    @DisplayName("finalCommandOf")
    class FinalCommandOf {
        @Test
        @DisplayName("null approvalId 返回 null")
        void nullIdReturnsNull() {
            assertThat(service.finalCommandOf(null)).isNull();
        }

        @Test
        @DisplayName("行不存在返回 null")
        void missingRowReturnsNull() {
            when(approvalMapper.selectById(anyString())).thenReturn(null);
            assertThat(service.finalCommandOf(UUID.randomUUID())).isNull();
        }

        @Test
        @DisplayName("有 finalCommand 正确返回")
        void withCommandReturns() {
            var row = new com.ananoesis.shell.entity.Approval();
            row.setDecision("approved");
            row.setFinalCommand("ls -la");
            when(approvalMapper.selectById(anyString())).thenReturn(row);
            assertThat(service.finalCommandOf(UUID.randomUUID())).isEqualTo("ls -la");
        }

        @Test
        @DisplayName("无 finalCommand 返回 null")
        void noCommandReturnsNull() {
            var row = new com.ananoesis.shell.entity.Approval();
            row.setDecision("approved");
            row.setFinalCommand(null);
            when(approvalMapper.selectById(anyString())).thenReturn(row);
            assertThat(service.finalCommandOf(UUID.randomUUID())).isNull();
        }
    }

    // ---- page ----

    @Nested
    @DisplayName("page")
    class PageQuery {
        @Test
        @DisplayName("page < 1 被钳制到 1")
        void negativePageClamped() {
            when(approvalMapper.selectCount(any())).thenReturn(0L);
            when(approvalMapper.selectList(any())).thenReturn(List.of());
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(-1, 10, null, null);
            assertThat(result.getPage()).isEqualTo(1);
        }

        @Test
        @DisplayName("size > 200 被钳制到 200")
        void overMaxSizeClamped() {
            when(approvalMapper.selectCount(any())).thenReturn(0L);
            when(approvalMapper.selectList(any())).thenReturn(List.of());
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 999, null, null);
            assertThat(result.getSize()).isEqualTo(200);
        }

        @Test
        @DisplayName("size < 1 被钳制到 1")
        void zeroSizeClamped() {
            when(approvalMapper.selectCount(any())).thenReturn(0L);
            when(approvalMapper.selectList(any())).thenReturn(List.of());
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 0, null, null);
            assertThat(result.getSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("total 为 null 时返回 0")
        void nullTotalReturnsZero() {
            when(approvalMapper.selectCount(any())).thenReturn(null);
            when(approvalMapper.selectList(any())).thenReturn(List.of());
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, null, null);
            assertThat(result.getTotal()).isEqualTo(0L);
        }

        @Test
        @DisplayName("有 hostId 过滤时正常工作")
        void withHostIdFilter() {
            when(approvalMapper.selectCount(any())).thenReturn(0L);
            when(approvalMapper.selectList(any())).thenReturn(List.of());
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, UUID.randomUUID(), null);
            assertThat(result.getItems()).isEmpty();
        }

        @Test
        @DisplayName("有 decision 过滤时正常工作")
        void withDecisionFilter() {
            when(approvalMapper.selectCount(any())).thenReturn(0L);
            when(approvalMapper.selectList(any())).thenReturn(List.of());
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, null, ApprovalDecision.APPROVED);
            assertThat(result.getItems()).isEmpty();
        }
    }

    // ---- toExecution 通过 page 间接测试 ----

    @Nested
    @DisplayName("toExecution 各执行状态")
    class ExecutionStatusMapping {
        @Test
        @DisplayName("approved + timeout 状态映射为 EXECUTION_TIMEOUT")
        void timeoutMapped() {
            var row = buildApprovalRow("approved", "timeout", 0, 1,
                    "{\"stdout\":\"\",\"stderr\":\"\",\"note\":\"超时\"}");
            when(approvalMapper.selectCount(any())).thenReturn(1L);
            when(approvalMapper.selectList(any())).thenReturn(List.of(row));
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, null, null);
            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getExecution().getStatus())
                    .isEqualTo(ExecutionStatus.EXECUTION_TIMEOUT);
        }

        @Test
        @DisplayName("approved + truncated 状态映射为 OUTPUT_TRUNCATED")
        void truncatedMapped() {
            var row = buildApprovalRow("approved", "truncated", 0, 1,
                    "{\"stdout\":\"data\",\"stderr\":\"\",\"note\":\"截断\"}");
            when(approvalMapper.selectCount(any())).thenReturn(1L);
            when(approvalMapper.selectList(any())).thenReturn(List.of(row));
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, null, null);
            assertThat(result.getItems().get(0).getExecution().getStatus())
                    .isEqualTo(ExecutionStatus.OUTPUT_TRUNCATED);
        }

        @Test
        @DisplayName("approved + success 状态映射为 EXECUTED")
        void successMapped() {
            var row = buildApprovalRow("approved", "success", 0, 0,
                    "{\"stdout\":\"ok\",\"stderr\":\"\",\"note\":\"\"}");
            when(approvalMapper.selectCount(any())).thenReturn(1L);
            when(approvalMapper.selectList(any())).thenReturn(List.of(row));
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, null, null);
            assertThat(result.getItems().get(0).getExecution().getStatus())
                    .isEqualTo(ExecutionStatus.EXECUTED);
        }

        @Test
        @DisplayName("cancelled 映射为 REJECTED + REJECTED_NOTE")
        void cancelledMappedToRejected() {
            var row = buildApprovalRow("cancelled", "not_executed", null, 0,
                    "{\"stdout\":\"\",\"stderr\":\"\",\"note\":\"\"}");
            when(approvalMapper.selectCount(any())).thenReturn(1L);
            when(approvalMapper.selectList(any())).thenReturn(List.of(row));
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, null, null);
            assertThat(result.getItems().get(0).getExecution().getStatus())
                    .isEqualTo(ExecutionStatus.REJECTED);
            assertThat(result.getItems().get(0).getExecution().getNote())
                    .isEqualTo(ApprovalAuditService.REJECTED_NOTE);
        }

        @Test
        @DisplayName("timed_out 映射为 REJECTED + TIMED_OUT_NOTE")
        void timedOutMappedToRejectedWithNote() {
            // WHY "timeout" 而非 "timed_out"：ApprovalOutcome.TIMED_OUT.dbDecision() = "timeout"
            var row = buildApprovalRow("timeout", "not_executed", null, 0,
                    "{\"stdout\":\"\",\"stderr\":\"\",\"note\":\"\"}");
            when(approvalMapper.selectCount(any())).thenReturn(1L);
            when(approvalMapper.selectList(any())).thenReturn(List.of(row));
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, null, null);
            assertThat(result.getItems().get(0).getExecution().getNote())
                    .isEqualTo(ApprovalAuditService.TIMED_OUT_NOTE);
        }

        @Test
        @DisplayName("outputTruncated 为 1 时 truncated = true")
        void truncatedFlagTrue() {
            var row = buildApprovalRow("approved", "success", 0, 1,
                    "{\"stdout\":\"ok\",\"stderr\":\"\",\"note\":\"\"}");
            when(approvalMapper.selectCount(any())).thenReturn(1L);
            when(approvalMapper.selectList(any())).thenReturn(List.of(row));
            when(hostMapper.selectList(null)).thenReturn(List.of());
            var result = service.page(1, 10, null, null);
            assertThat(result.getItems().get(0).getExecution().getTruncated()).isTrue();
        }
    }

    // ---- toDto 辅助 ----

    @Test
    @DisplayName("toDto: null createdAt 回落 requestedAt")
    void nullCreatedAtFallsToRequestedAt() {
        var row = buildApprovalRow("approved", "success", 0, 0,
                "{\"stdout\":\"\",\"stderr\":\"\",\"note\":\"\"}");
        row.setCreatedAt(null);
        row.setRequestedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        when(approvalMapper.selectCount(any())).thenReturn(1L);
        when(approvalMapper.selectList(any())).thenReturn(List.of(row));
        when(hostMapper.selectList(null)).thenReturn(List.of());
        var result = service.page(1, 10, null, null);
        assertThat(result.getItems().get(0).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("toDto: null hostId 不解析 UUID")
    void nullHostIdNoParse() {
        var row = buildApprovalRow("approved", "success", 0, 0,
                "{\"stdout\":\"\",\"stderr\":\"\",\"note\":\"\"}");
        row.setHostId(null);
        when(approvalMapper.selectCount(any())).thenReturn(1L);
        when(approvalMapper.selectList(any())).thenReturn(List.of(row));
        when(hostMapper.selectList(null)).thenReturn(List.of());
        var result = service.page(1, 10, null, null);
        assertThat(result.getItems().get(0).getHostId()).isNull();
    }

    @Test
    @DisplayName("toDto: hostLabel 正确填充")
    void hostLabelFilled() {
        var row = buildApprovalRow("approved", "success", 0, 0,
                "{\"stdout\":\"\",\"stderr\":\"\",\"note\":\"\"}");
        String hostId = UUID.randomUUID().toString();
        row.setHostId(hostId);
        Host host = new Host();
        host.setId(hostId);
        host.setName("server1");
        when(approvalMapper.selectCount(any())).thenReturn(1L);
        when(approvalMapper.selectList(any())).thenReturn(List.of(row));
        when(hostMapper.selectList(null)).thenReturn(List.of(host));
        var result = service.page(1, 10, null, null);
        assertThat(result.getItems().get(0).getHostLabel()).isEqualTo("server1");
    }

    // ---- parseMap 边界 ----

    @Test
    @DisplayName("parseMap: 坏 JSON 返回空 Map（通过 toDto 间接测试）")
    void badJsonReturnsEmptyMap() {
        var row = buildApprovalRow("approved", "success", 0, 0, "not-json");
        when(approvalMapper.selectCount(any())).thenReturn(1L);
        when(approvalMapper.selectList(any())).thenReturn(List.of(row));
        when(hostMapper.selectList(null)).thenReturn(List.of());
        var result = service.page(1, 10, null, null);
        // 不崩溃即成功
        assertThat(result.getItems()).hasSize(1);
    }

    // ---- 辅助 ----

    private com.ananoesis.shell.entity.Approval buildApprovalRow(
            String decision, String execStatus, Integer exitCode,
            int truncated, String execResult) {
        var row = new com.ananoesis.shell.entity.Approval();
        row.setId(UUID.randomUUID().toString());
        row.setHostId(UUID.randomUUID().toString());
        row.setToolName("run_command");
        row.setToolArguments("{}");
        row.setDecision(decision);
        row.setExecutionStatus(execStatus);
        row.setExitCode(exitCode);
        row.setOutputTruncated(truncated);
        row.setExecutionResult(execResult);
        row.setRequestedAt(java.time.LocalDateTime.now());
        row.setCreatedAt(java.time.LocalDateTime.now());
        return row;
    }
}

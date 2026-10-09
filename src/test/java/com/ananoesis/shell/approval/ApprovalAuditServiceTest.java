package com.ananoesis.shell.approval;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.mapper.ApprovalMapper;
import com.ananoesis.shell.mapper.HostMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ApprovalAuditService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原类无单元测试，15 个分支（null 校验、审计写入）均未覆盖。</p>
 */
@DisplayName("ApprovalAuditService")
class ApprovalAuditServiceTest {

    private ApprovalMapper approvalMapper;
    private HostMapper hostMapper;
    private ObjectMapper objectMapper;
    private ApprovalAuditService auditService;

    @BeforeEach
    void setUp() {
        approvalMapper = mock(ApprovalMapper.class);
        hostMapper = mock(HostMapper.class);
        objectMapper = new ObjectMapper();
        auditService = new ApprovalAuditService(approvalMapper, hostMapper, objectMapper);
    }

    @Test
    @DisplayName("构造器: approvals 为 null 时抛异常")
    void nullApprovalsThrows() {
        assertThatThrownBy(() -> new ApprovalAuditService(null, hostMapper, objectMapper))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approvals");
    }

    @Test
    @DisplayName("构造器: hosts 为 null 时抛异常")
    void nullHostsThrows() {
        assertThatThrownBy(() -> new ApprovalAuditService(approvalMapper, null, objectMapper))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hosts");
    }

    @Test
    @DisplayName("构造器: objectMapper 为 null 时抛异常")
    void nullObjectMapperThrows() {
        assertThatThrownBy(() -> new ApprovalAuditService(approvalMapper, hostMapper, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("objectMapper");
    }

    @Test
    @DisplayName("insertPending: approvalId 为 null 时抛异常")
    void insertPendingNullApprovalIdThrows() {
        assertThatThrownBy(() -> auditService.insertPending(null, mock(ApprovalProposal.class), null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approvalId");
    }

    @Test
    @DisplayName("insertPending: proposal 为 null 时抛异常")
    void insertPendingNullProposalThrows() {
        assertThatThrownBy(() -> auditService.insertPending(UUID.randomUUID(), null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("proposal");
    }

    @Test
    @DisplayName("recordModification: approvalId 为 null 时抛异常")
    void recordModificationNullApprovalIdThrows() {
        assertThatThrownBy(() -> auditService.recordModification(null, "cmd", 2))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approvalId");
    }

    @Test
    @DisplayName("recordModification: modifiedCommand 为 null 时抛异常")
    void recordModificationNullCommandThrows() {
        assertThatThrownBy(() -> auditService.recordModification(UUID.randomUUID(), null, 2))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modifiedCommand");
    }

    @Test
    @DisplayName("decisionOf: 无记录时返回 null")
    void decisionOfNoRecordReturnsNull() {
        when(approvalMapper.selectById("nonexistent")).thenReturn(null);
        assertThat(auditService.decisionOf(UUID.randomUUID())).isNull();
    }

    @Test
    @DisplayName("REJECTED_NOTE 常量值")
    void rejectedNoteConstant() {
        assertThat(ApprovalAuditService.REJECTED_NOTE).isEqualTo("用户已拒绝");
    }

    @Test
    @DisplayName("TIMED_OUT_NOTE 常量值")
    void timedOutNoteConstant() {
        assertThat(ApprovalAuditService.TIMED_OUT_NOTE).isEqualTo("审批超时，已自动取消");
    }
}

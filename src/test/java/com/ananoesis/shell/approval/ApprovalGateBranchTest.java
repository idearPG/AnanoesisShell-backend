package com.ananoesis.shell.approval;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import org.springframework.beans.factory.ObjectProvider;

import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ws.ApprovalResponseFrame;

/**
 * {@link ApprovalGate} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖审批闭环，但构造器 null 校验、respond/respondWithVersion
 * 的 null/不存在路径等分支未覆盖。</p>
 */
@DisplayName("ApprovalGate 分支覆盖")
class ApprovalGateBranchTest {

    private ApprovalAuditService audit;
    private SettingsService settings;
    private ObjectProvider<ApprovalNotifier> notifiers;

    @BeforeEach
    void setUp() {
        audit = mock(ApprovalAuditService.class);
        settings = mock(SettingsService.class);
        notifiers = mock(ObjectProvider.class);
    }

    @Test
    @DisplayName("构造器: audit 为 null 时抛异常")
    void nullAuditThrows() {
        assertThatThrownBy(() -> new ApprovalGate(null, settings, notifiers))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("audit");
    }

    @Test
    @DisplayName("构造器: settings 为 null 时抛异常")
    void nullSettingsThrows() {
        assertThatThrownBy(() -> new ApprovalGate(audit, null, notifiers))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settings");
    }

    @Test
    @DisplayName("构造器: notifiers 为 null 时抛异常")
    void nullNotifiersThrows() {
        assertThatThrownBy(() -> new ApprovalGate(audit, settings, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("notifiers");
    }

    @Test
    @DisplayName("submit: proposal 为 null 时抛异常")
    void submitNullProposalThrows() {
        ApprovalGate gate = new ApprovalGate(audit, settings, notifiers);
        assertThatThrownBy(() -> gate.submit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("proposal");
    }

    @Test
    @DisplayName("await: ticket 为 null 时抛异常")
    void awaitNullTicketThrows() throws Exception {
        ApprovalGate gate = new ApprovalGate(audit, settings, notifiers);
        assertThatThrownBy(() -> gate.await(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ticket");
    }

    @Test
    @DisplayName("respond: approvalId 为 null 时返回 false")
    void respondNullApprovalIdReturnsFalse() {
        ApprovalGate gate = new ApprovalGate(audit, settings, notifiers);
        assertThat(gate.respond(null, ApprovalResponseFrame.Decision.APPROVE)).isFalse();
    }

    @Test
    @DisplayName("respond: decision 为 null 时返回 false")
    void respondNullDecisionReturnsFalse() {
        ApprovalGate gate = new ApprovalGate(audit, settings, notifiers);
        assertThat(gate.respond(UUID.randomUUID(), null)).isFalse();
    }

    @Test
    @DisplayName("respondWithVersion: approvalId 为 null 时返回 false")
    void respondWithVersionNullApprovalIdReturnsFalse() {
        ApprovalGate gate = new ApprovalGate(audit, settings, notifiers);
        assertThat(gate.respondWithVersion(null, ApprovalResponseFrame.Decision.APPROVE, 1)).isFalse();
    }

    @Test
    @DisplayName("respondWithVersion: decision 为 null 时返回 false")
    void respondWithVersionNullDecisionReturnsFalse() {
        ApprovalGate gate = new ApprovalGate(audit, settings, notifiers);
        assertThat(gate.respondWithVersion(UUID.randomUUID(), null, 1)).isFalse();
    }

    @Test
    @DisplayName("respondWithVersion: 不存在的 approvalId 返回 false")
    void respondWithVersionNonExistentReturnsFalse() {
        ApprovalGate gate = new ApprovalGate(audit, settings, notifiers);
        assertThat(gate.respondWithVersion(UUID.randomUUID(), ApprovalResponseFrame.Decision.APPROVE, 1)).isFalse();
    }
}

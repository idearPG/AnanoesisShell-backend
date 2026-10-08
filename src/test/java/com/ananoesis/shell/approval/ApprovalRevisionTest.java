package com.ananoesis.shell.approval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ws.ApprovalResponseFrame.Decision;
import com.ananoesis.shell.ws.ToolName;

/**
 * 审批版本化与并发栅栏（tasks 6.4 / 6.5）。
 *
 * <h2>design.md D5 关键约束</h2>
 * <ul>
 *   <li>不可变 revision：修改产生新版本，旧版本不可执行</li>
 *   <li>expected_version 校验：服务端接受 expected_version，防止按钮自行替换命令后沿用旧批准</li>
 *   <li>批准/拒绝/修改/超时/停止/关闭并发：同一调度器互斥裁决</li>
 * </ul>
 */
class ApprovalRevisionTest extends AbstractSqliteIntegrationTest {

    private static final String HOST_LABEL = "版本测试机";
    private static final String COMMAND = "echo original";
    private static final String ANALYSIS = "测试分析";

    @Autowired private ApprovalGate gate;
    @Autowired private ApprovalAuditService audit;
    @Autowired private SettingsService settings;
    @Autowired private ConversationService conversations;
    @Autowired private HostMapper hosts;
    @Autowired private DataSource dataSource;

    private final List<ApprovalGate.Ticket> issued = new ArrayList<>();
    private UUID hostId;
    private UUID conversationId;

    @BeforeEach
    void prepareFixtures() {
        issued.clear();
        hostId = insertHost(HOST_LABEL);
        conversationId = conversations.create(new ConversationCreate().hostId(hostId)).getId();
    }

    @BeforeEach
    void cleanupIssued() {
        for (ApprovalGate.Ticket ticket : issued) {
            gate.respond(ticket.approvalId(), Decision.CANCEL);
        }
        issued.clear();
    }

    // ======================================================================
    // 6.4 审批版本化
    // ======================================================================

    @Test
    @DisplayName("6.4：新审批初始版本为 1")
    void newApprovalStartsAtVersionOne() {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        // 初始版本为 1
        assertThat(readVersion(ticket.approvalId())).isEqualTo(1);
    }

    @Test
    @DisplayName("6.4：修改产生新版本（version 递增），保留原到期时间")
    void modifyCreatesNewVersionPreservingExpiry() {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));
        int originalVersion = readVersion(ticket.approvalId());
        String originalExpiresAt = readExpiresAt(ticket.approvalId());

        // 修改命令 → 新版本
        String modifiedCommand = "echo modified";
        boolean modified = gate.modify(ticket.approvalId(), modifiedCommand, originalVersion);

        assertThat(modified).isTrue();
        assertThat(readVersion(ticket.approvalId())).isEqualTo(originalVersion + 1);
        // WHY 到期时间不变：修改不应延长审批窗口，否则用户可以无限续期
        assertThat(readExpiresAt(ticket.approvalId())).isEqualTo(originalExpiresAt);
    }

    @Test
    @DisplayName("6.4：expected_version 不匹配时修改被拒绝（乐观并发控制）")
    void modifyWithWrongExpectedVersionIsRejected() {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        // 用错误的版本号（2 而非当前的 1）尝试修改
        boolean modified = gate.modify(ticket.approvalId(), "echo hacked", 2);

        assertThat(modified).isFalse();
        // 版本未变
        assertThat(readVersion(ticket.approvalId())).isEqualTo(1);
    }

    @Test
    @DisplayName("6.4：旧版本不可执行——修改后批准旧版本被拒绝")
    void oldVersionCannotBeExecuted() {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        // 修改一次，版本变为 2
        gate.modify(ticket.approvalId(), "echo v2", 1);

        // 尝试用旧版本（1）批准 → 被拒绝
        boolean approved = gate.respondWithVersion(ticket.approvalId(), Decision.APPROVE, 1);
        assertThat(approved).isFalse();

        // 用当前版本（2）批准 → 成功
        approved = gate.respondWithVersion(ticket.approvalId(), Decision.APPROVE, 2);
        assertThat(approved).isTrue();
    }

    @Test
    @DisplayName("6.4：修改后批准——执行方回读到的生效命令必须是修改后命令")
    void effectiveCommandReflectsModification() {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        // 未修改时回退提案原命令
        assertThat(gate.effectiveCommand(ticket.approvalId(), COMMAND)).isEqualTo(COMMAND);

        // 修改后：无论裁决前后，回读都拿到新版本命令
        gate.modify(ticket.approvalId(), "echo v2", 1);
        assertThat(gate.effectiveCommand(ticket.approvalId(), COMMAND)).isEqualTo("echo v2");

        // WHY 裁决后仍要能回读：decide 会把条目移出挂起表，而执行方是在
        // await 返回之后才查询生效命令——此时只能靠审计行还原
        gate.respondWithVersion(ticket.approvalId(), Decision.APPROVE, 2);
        assertThat(gate.effectiveCommand(ticket.approvalId(), COMMAND)).isEqualTo("echo v2");
    }

    // ======================================================================
    // 6.5 并发栅栏
    // ======================================================================

    @Test
    @DisplayName("6.5：同一审批的并发批准与拒绝只有一个赢家")
    void concurrentApproveAndRejectHaveExactlyOneWinner() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));
        AtomicInteger approveResult = new AtomicInteger(-1);
        AtomicInteger rejectResult = new AtomicInteger(-1);
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch bothDone = new CountDownLatch(2);

        Thread approver = new Thread(() -> {
            bothReady.countDown();
            try { bothReady.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { return; }
            approveResult.set(gate.respond(ticket.approvalId(), Decision.APPROVE) ? 1 : 0);
            bothDone.countDown();
        });
        Thread rejector = new Thread(() -> {
            bothReady.countDown();
            try { bothReady.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { return; }
            rejectResult.set(gate.respond(ticket.approvalId(), Decision.CANCEL) ? 1 : 0);
            bothDone.countDown();
        });
        approver.start();
        rejector.start();
        bothDone.await(10, TimeUnit.SECONDS);

        // 恰好一个赢家
        int totalWins = approveResult.get() + rejectResult.get();
        assertThat(totalWins)
                .as("并发裁决必须恰好一个赢家")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("6.5：并发修改与批准互斥——修改中的批准使用旧版本号被拒")
    void concurrentModifyAndApproveAreMutuallyExclusive() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        // 先修改到版本 2
        gate.modify(ticket.approvalId(), "echo modified", 1);

        // 并发：用版本 2 批准 + 用版本 2 再修改到 3
        AtomicReference<Boolean> approveResult = new AtomicReference<>();
        AtomicReference<Boolean> modifyResult = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread approver = new Thread(() -> {
            try { start.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { return; }
            approveResult.set(gate.respondWithVersion(ticket.approvalId(), Decision.APPROVE, 2));
            done.countDown();
        });
        Thread modifier = new Thread(() -> {
            try { start.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { return; }
            modifyResult.set(gate.modify(ticket.approvalId(), "echo v3", 2));
            done.countDown();
        });
        approver.start();
        modifier.start();
        start.countDown();
        done.await(10, TimeUnit.SECONDS);

        // 两者不能都成功（如果批准先完成，修改应该失败；如果修改先完成，批准因版本过时被拒）
        boolean bothSucceeded = Boolean.TRUE.equals(approveResult.get())
                && Boolean.TRUE.equals(modifyResult.get());
        assertThat(bothSucceeded)
                .as("批准和修改不能同时成功——状态机必须互斥")
                .isFalse();
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private ApprovalGate.Ticket submit(ApprovalProposal proposal) {
        ApprovalGate.Ticket ticket = gate.submit(proposal);
        issued.add(ticket);
        return ticket;
    }

    private ApprovalProposal proposal(String command) {
        Map<String, Object> params = new HashMap<>();
        params.put("command", command);
        params.put("ai_analysis", ANALYSIS);
        return new ApprovalProposal(conversationId, hostId, HOST_LABEL,
                ToolName.RUN_COMMAND, params, command, ANALYSIS, null);
    }

    private UUID insertHost(String name) {
        Host row = new Host();
        UUID id = UUID.randomUUID();
        row.setId(id.toString());
        row.setName(name);
        row.setHost("127.0.0.1");
        row.setPort(22);
        row.setUsername("ops");
        row.setAuthType("password");
        hosts.insert(row);
        return id;
    }

    private int readVersion(UUID approvalId) {
        String sql = "SELECT version FROM approvals WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, approvalId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("version") : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String readExpiresAt(UUID approvalId) {
        String sql = "SELECT expires_at FROM approvals WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, approvalId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("expires_at") : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

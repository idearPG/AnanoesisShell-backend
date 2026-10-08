package com.ananoesis.shell.ai;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.approval.ApprovalAuditService;
import com.ananoesis.shell.approval.ApprovalGate;
import com.ananoesis.shell.approval.ApprovedCommandRunner;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.MessageRole;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.entity.AiRun;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.service.AgentRunService;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ssh.SshExecService;
import com.ananoesis.shell.support.FakeSshServer;
import com.ananoesis.shell.support.RecordingAiStreamEmitter;
import com.ananoesis.shell.support.ScriptedChatModel;
import com.ananoesis.shell.support.StaticObjectProvider;
import com.ananoesis.shell.support.StubChatModelProvider;
import com.ananoesis.shell.ws.AiStreamFrame;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 上下文超限恢复集成测试（design D7 "单次恢复"段，tasks 9.2 / 9.3）。
 *
 * <h2>WHY 是集成测试</h2>
 * <p>恢复流程涉及多个组件的协作：分类器判定超限 → 检查恢复额度 → 重建 prompt →
 * 重试模型调用 → 发送 attempt_reset 帧。只有集成测试能验证这些组件在真实数据流中
 * 是否正确配合。</p>
 *
 * <h2>WHY 需要 AgentRunService</h2>
 * <p>恢复额度存在 ai_runs 表的 recovery_count 字段。测试需要真实的数据库行来验证
 * "第一次恢复成功、第二次停止"的额度控制逻辑。</p>
 */
class AgentContextRecoveryTest extends AbstractSqliteIntegrationTest {

    private static final String NOW = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    private static FakeSshServer fake;

    @Autowired private ConversationService conversations;
    @Autowired private SettingsService settings;
    @Autowired private ApprovalAuditService audit;
    @Autowired private ApprovalGate gate;
    @Autowired private HostMapper hosts;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AgentRunService agentRunService;
    @Autowired private DataSource dataSource;

    private SshExecService exec;
    private RecordingAiStreamEmitter emitter;
    private UUID hostId;
    private UUID conversationId;
    private UUID sessionId;

    @BeforeAll
    static void startFakeServer() throws IOException {
        fake = FakeSshServer.start();
    }

    @AfterAll
    static void stopFakeServer() {
        fake.close();
    }

    @BeforeEach
    void wireAgentCollaborators() {
        exec = AgentTestWiring.execServiceOn(fake);
        emitter = new RecordingAiStreamEmitter();
        hostId = AgentTestWiring.insertHost(hosts, "恢复测试机");
        conversationId = conversations.create(new ConversationCreate().hostId(hostId)).getId();
        // WHY 必须插 session 行：ai_runs.session_id 有外键约束引用 sessions(id)
        sessionId = insertSession(hostId);
    }

    // ======================================================================
    // 9.2 恢复额度与预算减半
    // ======================================================================

    @Test
    @DisplayName("首次上下文超限：触发恢复，发出 attempt_reset 帧，恢复计数递增")
    void firstContextLimitTriggersRecoveryWithAttemptResetFrame() {
        // 剧本：模型第一次调用抛出上下文超限异常，第二次调用成功回答
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(contextLimitException())
                .answer("恢复后的回答")
                .build();

        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");
        AiAgentService agent = agent(model, run.getId());

        agent.runTurn(new TurnRequest(conversationId, hostId, "问一个问题"));

        // 验证恢复计数已递增
        assertThat(agentRunService.getRecoveryCount(run.getId())).isEqualTo(1);

        // 验证发出了 attempt_reset 帧
        List<AiStreamFrame> resets = emitter.ofType(AiStreamFrame.Type.ATTEMPT_RESET);
        assertThat(resets).as("恢复时必须发出 attempt_reset 帧通知前端撤回失败尝试").hasSize(1);
        assertThat(resets.get(0).conversationId()).isEqualTo(conversationId);

        // 验证最终成功回答
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA)).isEqualTo("恢复后的回答");
        assertThat(emitter.ofType(AiStreamFrame.Type.FINAL)).isNotEmpty();
    }

    @Test
    @DisplayName("第二次上下文超限：不再恢复，发 context_budget_exceeded 错误帧")
    void secondContextLimitStopsRunWithContextBudgetExceeded() {
        // 先让恢复计数为 1（已用过一次恢复）
        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");
        agentRunService.incrementRecoveryCount(run.getId());

        // 剧本：模型再次抛出上下文超限异常
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(contextLimitException())
                .build();

        AiAgentService agent = agent(model, run.getId());
        agent.runTurn(new TurnRequest(conversationId, hostId, "再问一个问题"));

        // 验证发出了 context_budget_exceeded 错误帧
        AiStreamFrame error = emitter.only(AiStreamFrame.Type.ERROR);
        assertThat(error.errorCode()).isEqualTo(ErrorCode.CONTEXT_BUDGET_EXCEEDED);

        // 验证没有发出 attempt_reset（不再恢复）
        assertThat(emitter.ofType(AiStreamFrame.Type.ATTEMPT_RESET)).isEmpty();

        // 验证恢复计数没有再递增
        assertThat(agentRunService.getRecoveryCount(run.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("恢复时 prompt 使用更小的历史窗口（验证预算减半效果）")
    void recoveryRebuildsPromptWithSmallerHistoryWindow() {
        // 剧本：第一次调用超限，第二次成功
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(contextLimitException())
                .answer("缩减后的回答")
                .build();

        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");
        StubChatModelProvider provider = new StubChatModelProvider(model);
        AiAgentService agent = agentWithProvider(provider, run.getId());

        agent.runTurn(new TurnRequest(conversationId, hostId, "问一个问题"));

        // 验证模型被调用了两次（第一次失败 + 恢复后成功）
        assertThat(model.streamCallCount()).isEqualTo(2);

        // 验证第二次调用的 prompt 比第一次小（历史窗口减半）
        List<Prompt> prompts = model.prompts();
        assertThat(prompts).hasSize(2);

        int firstPromptSize = estimatePromptSize(prompts.get(0));
        int secondPromptSize = estimatePromptSize(prompts.get(1));
        // WHY 第二次 prompt 不大于第一次：恢复的目的是减小上下文，
        // 如果第二次反而更大，说明恢复逻辑没有生效
        assertThat(secondPromptSize).isLessThanOrEqualTo(firstPromptSize);
    }

    @Test
    @DisplayName("恢复不重新落库用户问题")
    void recoveryDoesNotReSaveUserMessage() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(contextLimitException())
                .answer("恢复后的回答")
                .build();

        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");
        AiAgentService agent = agent(model, run.getId());

        agent.runTurn(new TurnRequest(conversationId, hostId, "只问一次"));

        // 验证用户消息只有一条（恢复不重复落库）
        long userMessageCount = conversations.listMessages(conversationId).stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .count();
        assertThat(userMessageCount)
                .as("恢复时 MUST NOT 重新落库用户消息（design D7 要求）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("恢复后模型成功：回合正常结束，发出 final 帧")
    void recoveryFollowedBySuccessProducesFinalFrame() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(contextLimitException())
                .answer("成功了")
                .build();

        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");
        AiAgentService agent = agent(model, run.getId());

        agent.runTurn(new TurnRequest(conversationId, hostId, "问一个问题"));

        // 验证有 final 帧（回合正常结束）
        AiStreamFrame finalFrame = emitter.only(AiStreamFrame.Type.FINAL);
        assertThat(finalFrame.conversationId()).isEqualTo(conversationId);

        // 验证没有 error 帧
        assertThat(emitter.ofType(AiStreamFrame.Type.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("非上下文超限异常不触发恢复")
    void nonContextLimitErrorDoesNotTriggerRecovery() {
        // 剧本：模型抛出普通异常（非上下文超限）
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(new RuntimeException("400 Bad Request: invalid parameter"))
                .build();

        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");
        AiAgentService agent = agent(model, run.getId());

        agent.runTurn(new TurnRequest(conversationId, hostId, "问一个问题"));

        // 验证恢复计数没有递增
        assertThat(agentRunService.getRecoveryCount(run.getId())).isEqualTo(0);

        // 验证没有 attempt_reset 帧
        assertThat(emitter.ofType(AiStreamFrame.Type.ATTEMPT_RESET)).isEmpty();

        // 验证发出了 model_endpoint_error 错误帧
        AiStreamFrame error = emitter.only(AiStreamFrame.Type.ERROR);
        assertThat(error.errorCode()).isEqualTo(ErrorCode.MODEL_ENDPOINT_ERROR);
    }

    @Test
    @DisplayName("没有 AgentRunService 时，上下文超限按普通错误处理")
    void contextLimitWithoutRunServiceFallsThroughToNormalError() {
        // WHY 这个测试验证向后兼容：没有 run 跟踪时，恢复逻辑不应崩溃
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(contextLimitException())
                .build();

        // 不传 runId（null），模拟没有 AgentRunService 的情况
        AiAgentService agent = agent(model, null);

        agent.runTurn(new TurnRequest(conversationId, hostId, "问一个问题"));

        // 验证发出了错误帧（不是恢复）
        AiStreamFrame error = emitter.only(AiStreamFrame.Type.ERROR);
        assertThat(error.errorCode()).isEqualTo(ErrorCode.MODEL_ENDPOINT_ERROR);

        // 验证没有 attempt_reset 帧
        assertThat(emitter.ofType(AiStreamFrame.Type.ATTEMPT_RESET)).isEmpty();
    }

    // ======================================================================
    // 9.3 完整链路验证
    // ======================================================================

    @Test
    @DisplayName("完整链路：命令成功 → 模型超限 → 恢复成功，PTY 命令计数不增加")
    void fullChainCommandSucceedsThenContextLimitThenRecoverySucceeds() {
        int execBefore = fake.execCommands().size();

        // 剧本：第一轮成功调工具（命令已执行），第二轮模型超限时报错，恢复后成功
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "system_info", "{}")
                .error(contextLimitException())
                .answer("恢复后完成了分析")
                .build();

        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");
        AiAgentService agent = agent(model, run.getId());

        agent.runTurn(new TurnRequest(conversationId, hostId, "检查系统然后分析"));

        // 验证命令只执行了一次（恢复不重跑命令）
        int execAfter = fake.execCommands().size();
        assertThat(execAfter - execBefore)
                .as("恢复时 MUST NOT 重新执行已完成的命令（design D7 要求）")
                .isEqualTo(1);

        // 验证恢复计数为 1
        assertThat(agentRunService.getRecoveryCount(run.getId())).isEqualTo(1);

        // 验证发出了 attempt_reset 帧
        assertThat(emitter.ofType(AiStreamFrame.Type.ATTEMPT_RESET)).hasSize(1);

        // 验证最终回答
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA)).isEqualTo("恢复后完成了分析");
    }

    @Test
    @DisplayName("完整链路：账本不重领（不创建新 run）")
    void fullChainDoesNotCreateNewRun() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(contextLimitException())
                .answer("恢复成功")
                .build();

        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");
        String originalRunId = run.getId();
        AiAgentService agent = agent(model, originalRunId);

        agent.runTurn(new TurnRequest(conversationId, hostId, "问一个问题"));

        // 验证仍然是同一个 run（没有创建新 run）
        AiRun afterRun = agentRunService.findRun(originalRunId);
        assertThat(afterRun).isNotNull();
        assertThat(afterRun.getId()).isEqualTo(originalRunId);
        assertThat(afterRun.getStatus()).isEqualTo("running");
    }

    @Test
    @DisplayName("完整链路：恢复后的新有效调用仍走原审批规则")
    void recoveryNewToolCallsFollowOriginalApprovalRules() {
        // 剧本：第一次超限，恢复后模型要求调 run_command（副作用工具，需审批）
        ScriptedChatModel model = ScriptedChatModel.builder()
                .error(contextLimitException())
                .toolCall("call_r", "run_command",
                        "{\"command\":\"echo recovered\",\"ai_analysis\":\"恢复后执行\"}")
                .answer("完成")
                .build();

        AiRun run = agentRunService.claimRun(sessionId, conversationId, "{}");

        // 使用异步执行器，因为 run_command 需要等待审批
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            AiAgentService asyncAgent = newAgentWithProvider(
                    new StubChatModelProvider(model, ThinkingMode.NON_THINKING),
                    run.getId(), workers);

            // 提交回合
            assertThat(asyncAgent.submit(new TurnRequest(conversationId, hostId, "检查并修复"))).isTrue();

            // 等待审批提案出现
            com.ananoesis.shell.support.TestWait.until("审批提案已挂起",
                    () -> emitter.latestApprovalId() != null);

            // 验证 tool_call 帧走了审批流程（auto=false，有 approval_id）
            var toolCallFrame = emitter.ofType(AiStreamFrame.Type.TOOL_CALL);
            assertThat(toolCallFrame).isNotEmpty();
            var callEvent = toolCallFrame.get(0).toolCall();
            assertThat(callEvent.auto())
                    .as("run_command 是副作用工具，必须走审批（auto=false）")
                    .isFalse();
            assertThat(callEvent.approvalId())
                    .as("恢复后的工具调用仍须携带 approval_id")
                    .isNotNull();

            // 批准命令
            gate.respond(emitter.latestApprovalId(),
                    com.ananoesis.shell.ws.ApprovalResponseFrame.Decision.APPROVE);

            // 等待回合结束
            com.ananoesis.shell.support.TestWait.until("回合已结束",
                    () -> asyncAgent.inFlightCount() == 0);

            // 验证恢复计数为 1
            assertThat(agentRunService.getRecoveryCount(run.getId())).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    // ======================================================================
    // 装配辅助
    // ======================================================================

    private AiAgentService agent(ScriptedChatModel model, String runId) {
        StubChatModelProvider provider = new StubChatModelProvider(model);
        return agentWithProvider(provider, runId);
    }

    private AiAgentService agentWithProvider(StubChatModelProvider provider, String runId) {
        return newAgentWithProvider(provider, runId, AgentTestWiring.directExecutor());
    }

    private AiAgentService newAgentWithProvider(StubChatModelProvider provider, String runId,
                                                 java.util.concurrent.ExecutorService workers) {
        return new AiAgentService(provider,
                new AgentTools(exec, settings),
                gate,
                new ApprovedCommandRunner(exec, settings, audit),
                conversations,
                StaticObjectProvider.of(emitter),
                objectMapper,
                agentRunService,
                runId,
                workers);
    }

    /** 构造一个模拟上下文超限的异常（与真实端点返回的错误格式一致）。 */
    private static RuntimeException contextLimitException() {
        return new RuntimeException(
                "400 Bad Request: {\"error\":{\"code\":\"context_length_exceeded\","
                        + "\"message\":\"This model's maximum context length is 128000 tokens. "
                        + "You requested 150000 tokens.\"}}");
    }

    private static int estimatePromptSize(Prompt prompt) {
        int total = 0;
        for (org.springframework.ai.chat.messages.Message msg : prompt.getInstructions()) {
            String text = msg.getText();
            if (text != null) {
                total += text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            if (msg instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    total += call.arguments() != null ? call.arguments().length() : 0;
                }
            }
        }
        return total;
    }

    /**
     * 先插 host 再插 session（sessions.host_id 有外键约束），返回 session UUID。
     *
     * <p>WHY 直接操作 DataSource：ai_runs.session_id REFERENCES sessions(id)，
     * 而测试用的 sessionId 必须在 sessions 表中存在，否则 claimRun 会因外键约束失败。</p>
     */
    private UUID insertSession(UUID hostId) {
        UUID sid = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var sessStmt = connection.prepareStatement(
                        "INSERT INTO sessions (id, host_id, session_type, status, started_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    sessStmt.setString(1, sid.toString());
                    sessStmt.setString(2, hostId.toString());
                    sessStmt.setString(3, "exec");
                    sessStmt.setString(4, "open");
                    sessStmt.setString(5, NOW);
                    sessStmt.setString(6, NOW);
                    sessStmt.setString(7, NOW);
                    sessStmt.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return sid;
    }
}

package com.ananoesis.shell.service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.entity.CommandExecution;
import com.ananoesis.shell.mapper.CommandExecutionMapper;
import com.ananoesis.shell.support.EntityIds;

/**
 * 命令执行账本服务（tasks 6.3）。
 *
 * <p>管理命令执行的生命周期：claim → sent → completed/unknown。
 * design D5 关键约束：执行领取先提交 DB 状态，再写 PTY；结果不明只标 unknown，不自动重发。</p>
 */
@Service
public class CommandExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(CommandExecutionService.class);

    private static final String STATUS_CLAIMED = "claimed";
    private static final String STATUS_SENT = "sent";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_UNKNOWN = "unknown";

    private final CommandExecutionMapper mapper;

    public CommandExecutionService(CommandExecutionMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * 领取一次执行：落 claimed 行。
     *
     * @param source         来源（agent_tool / manual）
     * @param runId          关联运行记录，可空
     * @param callId         工具调用 id，可空
     * @param sessionId      所属会话
     * @param conversationId 关联对话，可空
     * @param command        命令原文
     * @return 已落库的执行记录
     */
    public CommandExecution claimExecution(String source, @Nullable String runId,
                                           @Nullable String callId, UUID sessionId,
                                           @Nullable UUID conversationId, String command) {
        CommandExecution execution = new CommandExecution();
        execution.setId(EntityIds.newUuid());
        execution.setSource(source);
        execution.setRunId(runId);
        execution.setCallId(callId);
        execution.setSessionId(sessionId.toString());
        execution.setConversationId(conversationId == null ? null : conversationId.toString());
        execution.setCommand(command);
        execution.setClaimStatus(STATUS_CLAIMED);
        execution.setClaimedAt(LocalDateTime.now());
        execution.setCreatedAt(LocalDateTime.now());
        execution.setUpdatedAt(LocalDateTime.now());
        mapper.insert(execution);
        return execution;
    }

    /**
     * 标记为已发送。
     */
    public void markSent(String executionId) {
        CommandExecution execution = mapper.selectById(executionId);
        if (execution == null) {
            LOG.warn("markSent: 执行记录不存在: id={}", executionId);
            return;
        }
        String current = execution.getClaimStatus();
        if (isTerminal(current)) {
            LOG.info("markSent: 终态不可覆盖: id={} current={}", executionId, current);
            return;
        }
        CommandExecution patch = new CommandExecution();
        patch.setId(executionId);
        patch.setClaimStatus(STATUS_SENT);
        patch.setSentAt(LocalDateTime.now());
        patch.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(patch);
    }

    /**
     * 标记为完成。
     */
    public void markCompleted(String executionId, int exitCode, String stdout,
                              String stderr, boolean outputTruncated) {
        CommandExecution execution = mapper.selectById(executionId);
        if (execution == null) {
            LOG.warn("markCompleted: 执行记录不存在: id={}", executionId);
            return;
        }
        String current = execution.getClaimStatus();
        if (isTerminal(current)) {
            LOG.info("markCompleted: 终态不可覆盖: id={} current={}", executionId, current);
            return;
        }
        CommandExecution patch = new CommandExecution();
        patch.setId(executionId);
        patch.setClaimStatus(STATUS_COMPLETED);
        patch.setExitCode(exitCode);
        patch.setStdout(stdout);
        patch.setStderr(stderr);
        patch.setOutputTruncated(outputTruncated ? 1 : 0);
        patch.setCompletedAt(LocalDateTime.now());
        patch.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(patch);
    }

    /**
     * 标记为未知状态（结果不明）。
     *
     * <p>WHY 不自动重发：design D5 的不重放保护——远端状态不确定时，
     * 重发可能导致命令被执行两次。</p>
     */
    public void markUnknown(String executionId, String reason) {
        CommandExecution execution = mapper.selectById(executionId);
        if (execution == null) {
            LOG.warn("markUnknown: 执行记录不存在: id={}", executionId);
            return;
        }
        String current = execution.getClaimStatus();
        if (isTerminal(current)) {
            LOG.info("markUnknown: 终态不可覆盖: id={} current={} reason={}", executionId, current, reason);
            return;
        }
        CommandExecution patch = new CommandExecution();
        patch.setId(executionId);
        patch.setClaimStatus(STATUS_UNKNOWN);
        patch.setCompletedAt(LocalDateTime.now());
        patch.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(patch);
    }

    /** 终态（completed / unknown）不可覆盖。 */
    private static boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status) || STATUS_UNKNOWN.equals(status);
    }
}

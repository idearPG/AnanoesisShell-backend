package com.ananoesis.shell.mapper;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.entity.AiRun;
import com.ananoesis.shell.entity.CommandExecution;
import com.ananoesis.shell.entity.FileTransfer;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.entity.SshSession;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * V2 新增实体（AiRun、CommandExecution、FileTransfer）的 MyBatis-Plus Mapper 最小 CRUD 验收。
 *
 * <p>WHY 用"插入 → 按主键查 → 更新 → 删除"的最小闭环：
 * 这四步足以暴露实体字段与 DDL 列不匹配、下划线转驼峰失效、主键策略错误等真实缺陷。</p>
 */
class WorkspaceMapperTest extends AbstractSqliteIntegrationTest {

    @Autowired
    private HostMapper hostMapper;
    @Autowired
    private SshSessionMapper sshSessionMapper;
    @Autowired
    private AiConversationMapper aiConversationMapper;
    @Autowired
    private AiMessageMapper aiMessageMapper;
    @Autowired
    private AiRunMapper aiRunMapper;
    @Autowired
    private CommandExecutionMapper commandExecutionMapper;
    @Autowired
    private FileTransferMapper fileTransferMapper;

    // ========== AiRun CRUD ==========

    @Test
    @DisplayName("ai_runs：运行记录可创建、更新状态、删除")
    void aiRunMapperCrud() {
        Host host = insertHost();
        SshSession session = insertSession(host.getId());

        AiRun run = new AiRun();
        run.setSessionId(session.getId());
        run.setStatus("running");
        run.setModelConfigSnapshot("{\"model\":\"gpt-4\"}");
        run.setContextRecoveryCount(0);
        run.setCancellationGeneration(0);
        run.setStartedAt(LocalDateTime.now());

        assertThat(aiRunMapper.insert(run)).isEqualTo(1);
        assertThat(run.getId()).as("主键应自动分配").isNotBlank();

        AiRun loaded = aiRunMapper.selectById(run.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getSessionId()).isEqualTo(session.getId());
        assertThat(loaded.getStatus()).isEqualTo("running");
        assertThat(loaded.getModelConfigSnapshot()).contains("gpt-4");
        assertThat(loaded.getContextRecoveryCount()).isZero();
        assertThat(loaded.getCancellationGeneration()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();

        // 更新状态
        loaded.setStatus("completed");
        loaded.setEndedAt(LocalDateTime.now());
        assertThat(aiRunMapper.updateById(loaded)).isEqualTo(1);
        assertThat(aiRunMapper.selectById(run.getId()).getStatus()).isEqualTo("completed");

        // 删除
        assertThat(aiRunMapper.deleteById(run.getId())).isEqualTo(1);
        assertThat(aiRunMapper.selectById(run.getId())).isNull();
    }

    @Test
    @DisplayName("ai_runs：同一会话同时只允许一条 running 状态（唯一索引）")
    void aiRunUniqueActiveIndex() {
        Host host = insertHost();
        SshSession session = insertSession(host.getId());

        AiRun run1 = new AiRun();
        run1.setSessionId(session.getId());
        run1.setStatus("running");
        run1.setStartedAt(LocalDateTime.now());
        aiRunMapper.insert(run1);

        AiRun run2 = new AiRun();
        run2.setSessionId(session.getId());
        run2.setStatus("running");
        run2.setStartedAt(LocalDateTime.now());
        assertConstraintViolation(catchThrowable(() -> aiRunMapper.insert(run2)));

        // 清理
        aiRunMapper.deleteById(run1.getId());
    }

    @Test
    @DisplayName("ai_runs：关联会话删除时级联清理（ON DELETE CASCADE）")
    void aiRunCascadeOnSessionDelete() {
        Host host = insertHost();
        SshSession session = insertSession(host.getId());

        AiRun run = new AiRun();
        run.setSessionId(session.getId());
        run.setStatus("completed");
        run.setStartedAt(LocalDateTime.now());
        aiRunMapper.insert(run);

        // 删除会话 → 级联删除 ai_runs
        sshSessionMapper.deleteById(session.getId());
        assertThat(aiRunMapper.selectById(run.getId())).isNull();

        // 清理
        hostMapper.deleteById(host.getId());
    }

    // ========== CommandExecution CRUD ==========

    @Test
    @DisplayName("command_executions：命令执行账本可创建、更新、查询")
    void commandExecutionMapperCrud() {
        Host host = insertHost();
        SshSession session = insertSession(host.getId());

        CommandExecution exec = new CommandExecution();
        exec.setSource("agent_tool");
        exec.setSessionId(session.getId());
        exec.setCommand("ls -la /tmp");
        exec.setClaimStatus("claimed");
        exec.setCwd("/home/user");

        assertThat(commandExecutionMapper.insert(exec)).isEqualTo(1);
        assertThat(exec.getId()).isNotBlank();

        CommandExecution loaded = commandExecutionMapper.selectById(exec.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getSource()).isEqualTo("agent_tool");
        assertThat(loaded.getCommand()).isEqualTo("ls -la /tmp");
        assertThat(loaded.getClaimStatus()).isEqualTo("claimed");
        assertThat(loaded.getCwd()).isEqualTo("/home/user");
        assertThat(loaded.getOutputTruncated()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();

        // 更新执行结果
        loaded.setClaimStatus("completed");
        loaded.setExitCode(0);
        loaded.setStdout("total 8\ndrwxr-xr-x 2 root root 4096");
        loaded.setCompletedAt(LocalDateTime.now());
        assertThat(commandExecutionMapper.updateById(loaded)).isEqualTo(1);

        CommandExecution updated = commandExecutionMapper.selectById(exec.getId());
        assertThat(updated.getClaimStatus()).isEqualTo("completed");
        assertThat(updated.getExitCode()).isEqualTo(0);
        assertThat(updated.getStdout()).contains("total 8");

        // 按 session 查询
        List<CommandExecution> bySession = commandExecutionMapper.selectList(
                new QueryWrapper<CommandExecution>().eq("session_id", session.getId()));
        assertThat(bySession).hasSize(1);

        // 清理
        commandExecutionMapper.deleteById(exec.getId());
        sshSessionMapper.deleteById(session.getId());
        hostMapper.deleteById(host.getId());
    }

    @Test
    @DisplayName("command_executions：agent_tool 来源的 (run_id, call_id) 唯一约束")
    void commandExecutionUniqueRunCallIndex() {
        Host host = insertHost();
        SshSession session = insertSession(host.getId());

        AiRun run = new AiRun();
        run.setSessionId(session.getId());
        run.setStatus("running");
        run.setStartedAt(LocalDateTime.now());
        aiRunMapper.insert(run);

        CommandExecution exec1 = new CommandExecution();
        exec1.setSource("agent_tool");
        exec1.setRunId(run.getId());
        exec1.setCallId("call_001");
        exec1.setSessionId(session.getId());
        exec1.setCommand("pwd");
        exec1.setClaimStatus("completed");
        commandExecutionMapper.insert(exec1);

        CommandExecution exec2 = new CommandExecution();
        exec2.setSource("agent_tool");
        exec2.setRunId(run.getId());
        exec2.setCallId("call_001");
        exec2.setSessionId(session.getId());
        exec2.setCommand("pwd");
        exec2.setClaimStatus("completed");
        assertConstraintViolation(catchThrowable(() -> commandExecutionMapper.insert(exec2)));

        // 清理
        commandExecutionMapper.deleteById(exec1.getId());
        aiRunMapper.deleteById(run.getId());
        sshSessionMapper.deleteById(session.getId());
        hostMapper.deleteById(host.getId());
    }

    // ========== FileTransfer CRUD ==========

    @Test
    @DisplayName("file_transfers：文件传输记录可创建、更新进度、删除")
    void fileTransferMapperCrud() {
        Host host = insertHost();
        SshSession session = insertSession(host.getId());

        FileTransfer transfer = new FileTransfer();
        transfer.setSessionId(session.getId());
        transfer.setDirection("download");
        transfer.setRemotePath("/var/log/syslog");
        transfer.setFileName("syslog");
        transfer.setDeclaredSize(102400L);
        transfer.setTransferredBytes(0L);
        transfer.setStatus("queued");
        transfer.setOverwrite(0);
        transfer.setQueuedAt(LocalDateTime.now());

        assertThat(fileTransferMapper.insert(transfer)).isEqualTo(1);
        assertThat(transfer.getId()).isNotBlank();

        FileTransfer loaded = fileTransferMapper.selectById(transfer.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getDirection()).isEqualTo("download");
        assertThat(loaded.getRemotePath()).isEqualTo("/var/log/syslog");
        assertThat(loaded.getFileName()).isEqualTo("syslog");
        assertThat(loaded.getDeclaredSize()).isEqualTo(102400L);
        assertThat(loaded.getStatus()).isEqualTo("queued");
        assertThat(loaded.getTransferredBytes()).isZero();
        assertThat(loaded.getOverwrite()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();

        // 更新传输进度
        loaded.setStatus("transferring");
        loaded.setTransferredBytes(51200L);
        loaded.setTransferStartedAt(LocalDateTime.now());
        assertThat(fileTransferMapper.updateById(loaded)).isEqualTo(1);

        FileTransfer updated = fileTransferMapper.selectById(transfer.getId());
        assertThat(updated.getStatus()).isEqualTo("transferring");
        assertThat(updated.getTransferredBytes()).isEqualTo(51200L);
        assertThat(updated.getTransferStartedAt()).isNotNull();

        // 按 session + status 查询
        List<FileTransfer> bySession = fileTransferMapper.selectList(
                new QueryWrapper<FileTransfer>()
                        .eq("session_id", session.getId())
                        .eq("status", "transferring"));
        assertThat(bySession).hasSize(1);

        // 清理
        fileTransferMapper.deleteById(transfer.getId());
        sshSessionMapper.deleteById(session.getId());
        hostMapper.deleteById(host.getId());
    }

    @Test
    @DisplayName("file_transfers：关联会话删除时级联清理（ON DELETE CASCADE）")
    void fileTransferCascadeOnSessionDelete() {
        Host host = insertHost();
        SshSession session = insertSession(host.getId());

        FileTransfer transfer = new FileTransfer();
        transfer.setSessionId(session.getId());
        transfer.setDirection("upload");
        transfer.setRemotePath("/tmp/upload.txt");
        transfer.setFileName("upload.txt");
        transfer.setDeclaredSize(512L);
        transfer.setStatus("queued");
        transfer.setOverwrite(0);
        transfer.setQueuedAt(LocalDateTime.now());
        fileTransferMapper.insert(transfer);

        // 删除会话 → 级联删除 file_transfers
        sshSessionMapper.deleteById(session.getId());
        assertThat(fileTransferMapper.selectById(transfer.getId())).isNull();

        // 清理
        hostMapper.deleteById(host.getId());
    }

    // ========== 辅助方法 ==========

    private Host insertHost() {
        Host host = new Host();
        host.setName("test-host");
        host.setHost("10.0.0.1");
        host.setPort(22);
        host.setUsername("root");
        host.setAuthType("password");
        hostMapper.insert(host);
        return host;
    }

    private SshSession insertSession(String hostId) {
        SshSession session = new SshSession();
        session.setHostId(hostId);
        session.setSessionType("exec");
        session.setStatus("open");
        session.setStartedAt(LocalDateTime.now());
        sshSessionMapper.insert(session);
        return session;
    }

    /** 断言一次数据库约束冲突：Spring 数据访问异常 + 根因为含 "constraint" 的 SQLException。 */
    private static void assertConstraintViolation(Throwable thrown) {
        assertThat(thrown).as("约束冲突应被拒绝").isInstanceOf(DataAccessException.class);
        assertThat(thrown).rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("constraint");
    }
}

package com.ananoesis.shell.service;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.entity.CommandExecution;
import com.ananoesis.shell.mapper.CommandExecutionMapper;

/**
 * {@link CommandExecutionService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原类无单元测试，14 个分支（null 校验、执行记录不存在、状态流转）均未覆盖。</p>
 */
@DisplayName("CommandExecutionService")
class CommandExecutionServiceTest {

    private CommandExecutionMapper mapper;
    private CommandExecutionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CommandExecutionMapper.class);
        service = new CommandExecutionService(mapper);
    }

    @Test
    @DisplayName("构造器: mapper 为 null 时抛异常")
    void nullMapperThrows() {
        assertThatThrownBy(() -> new CommandExecutionService(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("claimExecution: 正常创建")
    void claimExecutionNormal() {
        UUID sessionId = UUID.randomUUID();
        when(mapper.insert(org.mockito.ArgumentMatchers.any(CommandExecution.class))).thenReturn(1);

        CommandExecution exec = service.claimExecution("agent_tool", "run-1", "call-1",
                sessionId, UUID.randomUUID(), "ls -la");

        assertThat(exec.getSource()).isEqualTo("agent_tool");
        assertThat(exec.getClaimStatus()).isEqualTo("claimed");
        verify(mapper).insert(org.mockito.ArgumentMatchers.any(CommandExecution.class));
    }

    @Test
    @DisplayName("markSent: 执行记录不存在时不报错")
    void markSentNotFoundNoError() {
        when(mapper.selectById("nonexistent")).thenReturn(null);
        service.markSent("nonexistent");
    }

    @Test
    @DisplayName("markSent: 正常流转")
    void markSentNormal() {
        CommandExecution exec = new CommandExecution();
        exec.setId("exec-1");
        exec.setClaimStatus("claimed");
        when(mapper.selectById("exec-1")).thenReturn(exec);
        when(mapper.updateById(org.mockito.ArgumentMatchers.any(CommandExecution.class))).thenReturn(1);

        service.markSent("exec-1");

        verify(mapper).updateById(org.mockito.ArgumentMatchers.any(CommandExecution.class));
    }

    @Test
    @DisplayName("markCompleted: 执行记录不存在时不报错")
    void markCompletedNotFoundNoError() {
        when(mapper.selectById("nonexistent")).thenReturn(null);
        service.markCompleted("nonexistent", 0, "", "", false);
    }

    @Test
    @DisplayName("markCompleted: 正常流转")
    void markCompletedNormal() {
        CommandExecution exec = new CommandExecution();
        exec.setId("exec-1");
        exec.setClaimStatus("sent");
        when(mapper.selectById("exec-1")).thenReturn(exec);
        when(mapper.updateById(org.mockito.ArgumentMatchers.any(CommandExecution.class))).thenReturn(1);

        service.markCompleted("exec-1", 0, "output", "", false);

        verify(mapper).updateById(org.mockito.ArgumentMatchers.any(CommandExecution.class));
    }

    @Test
    @DisplayName("markUnknown: 执行记录不存在时不报错")
    void markUnknownNotFoundNoError() {
        when(mapper.selectById("nonexistent")).thenReturn(null);
        service.markUnknown("nonexistent", "timeout");
    }
}

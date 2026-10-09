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

import com.ananoesis.shell.entity.AiRun;
import com.ananoesis.shell.mapper.AiRunMapper;

/**
 * {@link AgentRunService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原类无单元测试，9 个分支（null 校验、run 不存在、代次/恢复计数 null 兜底）
 * 均未覆盖。</p>
 */
@DisplayName("AgentRunService")
class AgentRunServiceTest {

    private AiRunMapper runMapper;
    private AgentRunService runService;

    @BeforeEach
    void setUp() {
        runMapper = mock(AiRunMapper.class);
        runService = new AgentRunService(runMapper);
    }

    @Test
    @DisplayName("构造器: runMapper 为 null 时抛异常")
    void nullMapperThrows() {
        assertThatThrownBy(() -> new AgentRunService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runMapper");
    }

    @Test
    @DisplayName("claimRun: sessionId 为 null 时抛异常")
    void claimRunNullSessionIdThrows() {
        assertThatThrownBy(() -> runService.claimRun(null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("claimRun: 正常创建")
    void claimRunNormal() {
        UUID sessionId = UUID.randomUUID();
        when(runMapper.insert(org.mockito.ArgumentMatchers.any(AiRun.class))).thenReturn(1);

        AiRun run = runService.claimRun(sessionId, UUID.randomUUID(), "{\"model\":\"test\"}");

        assertThat(run.getSessionId()).isEqualTo(sessionId.toString());
        assertThat(run.getStatus()).isEqualTo("running");
        verify(runMapper).insert(org.mockito.ArgumentMatchers.any(AiRun.class));
    }

    @Test
    @DisplayName("transitionTo: runId 为 null 时抛异常")
    void transitionToNullRunIdThrows() {
        assertThatThrownBy(() -> runService.completeRun(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runId");
    }

    @Test
    @DisplayName("completeRun: 正常流转")
    void completeRunNormal() {
        when(runMapper.updateById(org.mockito.ArgumentMatchers.any(AiRun.class))).thenReturn(1);
        runService.completeRun("run-1");
        verify(runMapper).updateById(org.mockito.ArgumentMatchers.any(AiRun.class));
    }

    @Test
    @DisplayName("incrementCancellationGeneration: run 不存在时抛异常")
    void incrementCancellationGenerationRunNotFoundThrows() {
        when(runMapper.selectById("nonexistent")).thenReturn(null);
        assertThatThrownBy(() -> runService.incrementCancellationGeneration("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("incrementCancellationGeneration: 正常递增")
    void incrementCancellationGenerationNormal() {
        AiRun current = new AiRun();
        current.setId("run-1");
        current.setCancellationGeneration(2);
        when(runMapper.selectById("run-1")).thenReturn(current);
        when(runMapper.updateById(org.mockito.ArgumentMatchers.any(AiRun.class))).thenReturn(1);

        int newGen = runService.incrementCancellationGeneration("run-1");

        assertThat(newGen).isEqualTo(3);
    }

    @Test
    @DisplayName("isCancelled: run 不存在时返回 true")
    void isCancelledRunNotFoundReturnsTrue() {
        when(runMapper.selectById("nonexistent")).thenReturn(null);
        assertThat(runService.isCancelled("nonexistent", 0)).isTrue();
    }

    @Test
    @DisplayName("isCancelled: 调用方代次小于活跃代次时返回 true")
    void isCancelledWhenCallerBehindReturnsTrue() {
        AiRun current = new AiRun();
        current.setId("run-1");
        current.setCancellationGeneration(3);
        when(runMapper.selectById("run-1")).thenReturn(current);

        assertThat(runService.isCancelled("run-1", 1)).isTrue();
    }

    @Test
    @DisplayName("isCancelled: 调用方代次等于活跃代次时返回 false")
    void isCancelledWhenCallerEqualReturnsFalse() {
        AiRun current = new AiRun();
        current.setId("run-1");
        current.setCancellationGeneration(2);
        when(runMapper.selectById("run-1")).thenReturn(current);

        assertThat(runService.isCancelled("run-1", 2)).isFalse();
    }

    @Test
    @DisplayName("getRecoveryCount: run 不存在时返回 -1")
    void getRecoveryCountRunNotFoundReturnsMinusOne() {
        when(runMapper.selectById("nonexistent")).thenReturn(null);
        assertThat(runService.getRecoveryCount("nonexistent")).isEqualTo(-1);
    }

    @Test
    @DisplayName("getRecoveryCount: 正常返回")
    void getRecoveryCountNormal() {
        AiRun current = new AiRun();
        current.setId("run-1");
        current.setContextRecoveryCount(2);
        when(runMapper.selectById("run-1")).thenReturn(current);

        assertThat(runService.getRecoveryCount("run-1")).isEqualTo(2);
    }

    @Test
    @DisplayName("incrementRecoveryCount: run 不存在时抛异常")
    void incrementRecoveryCountRunNotFoundThrows() {
        when(runMapper.selectById("nonexistent")).thenReturn(null);
        assertThatThrownBy(() -> runService.incrementRecoveryCount("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("findRun: runId 为 null 时返回 null")
    void findRunNullIdReturnsNull() {
        assertThat(runService.findRun(null)).isNull();
    }

    @Test
    @DisplayName("findRun: 正常返回")
    void findRunNormal() {
        AiRun expected = new AiRun();
        expected.setId("run-1");
        when(runMapper.selectById("run-1")).thenReturn(expected);

        assertThat(runService.findRun("run-1")).isSameAs(expected);
    }
}

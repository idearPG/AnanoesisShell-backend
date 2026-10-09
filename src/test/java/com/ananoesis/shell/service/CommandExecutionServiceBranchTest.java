package com.ananoesis.shell.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.ananoesis.shell.mapper.CommandExecutionMapper;

/**
 * {@link CommandExecutionService} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖 mark* 方法，但构造器 null 校验、
 * findById 不存在路径等分支未覆盖。</p>
 */
@DisplayName("CommandExecutionService 分支覆盖补充")
class CommandExecutionServiceBranchTest {

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
}

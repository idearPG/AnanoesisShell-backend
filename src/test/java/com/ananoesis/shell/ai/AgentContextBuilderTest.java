package com.ananoesis.shell.ai;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.entity.AiMessage;
import com.ananoesis.shell.service.ConversationService;

/**
 * 智能体上下文构建器测试（design D7 步骤 1-4，tasks 8.4/8.5/8.6）。
 *
 * <p>WHY 纯单元测试：上下文构建逻辑是纯数据转换，不涉及网络或数据库。
 * 用 Mockito 模拟 ConversationService 的查询结果，验证原子组校验、投影与淘汰逻辑。</p>
 */
class AgentContextBuilderTest {

    private final ConversationService conversations = mock(ConversationService.class);
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final ContextTokenEstimator estimator = new ContextTokenEstimator();
    private final AgentContextBuilder builder = new AgentContextBuilder(conversations, objectMapper, estimator);

    // ======================================================================
    // 8.4 基本上下文构建
    // ======================================================================

    @Test
    @DisplayName("空会话返回空上下文")
    void emptyConversationReturnsEmptyContext() {
        UUID conversationId = UUID.randomUUID();
        when(conversations.loadRecentMessages(conversationId, AgentContextBuilder.WINDOW_SIZE))
                .thenReturn(List.of());

        AgentContextBuilder.BuildResult result = builder.buildContext(conversationId, 8000);

        assertThat(result.messages()).isEmpty();
        assertThat(result.estimatedSize()).isEqualTo(256); // 只有请求结构余量
    }

    @Test
    @DisplayName("用户消息被正确转换为 Spring AI UserMessage")
    void userMessageIsConvertedCorrectly() {
        UUID conversationId = UUID.randomUUID();
        AiMessage userRow = createUserMessage("你好");
        when(conversations.loadRecentMessages(conversationId, AgentContextBuilder.WINDOW_SIZE))
                .thenReturn(List.of(userRow));

        AgentContextBuilder.BuildResult result = builder.buildContext(conversationId, 8000);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(result.messages().get(0).getText()).isEqualTo("你好");
    }

    // ======================================================================
    // 8.5 原子组校验
    // ======================================================================

    @Test
    @DisplayName("完整的 assistant + tool 组被保留")
    void completeAssistantToolGroupIsRetained() {
        UUID conversationId = UUID.randomUUID();
        AiMessage userRow = createUserMessage("查看文件");
        AiMessage assistantRow = createAssistantMessageWithToolCall("call_1", "list_dir", "{\"path\":\"/tmp\"}");
        AiMessage toolRow = createToolMessage("call_1", "list_dir", "文件列表...");

        when(conversations.loadRecentMessages(conversationId, AgentContextBuilder.WINDOW_SIZE))
                .thenReturn(List.of(userRow, assistantRow, toolRow));

        AgentContextBuilder.BuildResult result = builder.buildContext(conversationId, 8000);

        // 用户消息 + assistant + tool = 3 条
        assertThat(result.messages()).hasSize(3);
    }

    @Test
    @DisplayName("孤立的 tool 消息（没有对应的 assistant）被排除")
    void orphanToolMessageIsExcluded() {
        UUID conversationId = UUID.randomUUID();
        AiMessage userRow = createUserMessage("查看文件");
        // 只有 tool 消息，没有对应的 assistant tool_calls
        AiMessage toolRow = createToolMessage("call_orphan", "list_dir", "文件列表...");

        when(conversations.loadRecentMessages(conversationId, AgentContextBuilder.WINDOW_SIZE))
                .thenReturn(List.of(userRow, toolRow));

        AgentContextBuilder.BuildResult result = builder.buildContext(conversationId, 8000);

        // 只有用户消息被保留，孤立的 tool 被排除
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
    }

    @Test
    @DisplayName("不完整的 assistant（有 tool_calls 但缺少 tool 结果）被排除")
    void incompleteAssistantGroupIsExcluded() {
        UUID conversationId = UUID.randomUUID();
        AiMessage userRow = createUserMessage("查看文件");
        // assistant 声明了 tool_call，但没有对应的 tool 结果
        AiMessage assistantRow = createAssistantMessageWithToolCall("call_incomplete", "list_dir", "{\"path\":\"/tmp\"}");

        when(conversations.loadRecentMessages(conversationId, AgentContextBuilder.WINDOW_SIZE))
                .thenReturn(List.of(userRow, assistantRow));

        AgentContextBuilder.BuildResult result = builder.buildContext(conversationId, 8000);

        // 只有用户消息被保留，不完整的 assistant 被排除
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
    }

    @Test
    @DisplayName("重复 call_id 的 tool 结果导致整组排除")
    void duplicateCallIdCausesGroupExclusion() {
        UUID conversationId = UUID.randomUUID();
        AiMessage userRow = createUserMessage("查看文件");
        AiMessage assistantRow = createAssistantMessageWithToolCall("call_dup", "list_dir", "{\"path\":\"/tmp\"}");
        AiMessage toolRow1 = createToolMessage("call_dup", "list_dir", "结果1");
        AiMessage toolRow2 = createToolMessage("call_dup", "list_dir", "结果2"); // 重复

        when(conversations.loadRecentMessages(conversationId, AgentContextBuilder.WINDOW_SIZE))
                .thenReturn(List.of(userRow, assistantRow, toolRow1, toolRow2));

        AgentContextBuilder.BuildResult result = builder.buildContext(conversationId, 8000);

        // 重复 call_id 导致整组排除，只保留用户消息
        assertThat(result.messages()).hasSize(1);
    }

    // ======================================================================
    // 辅助方法
    // ======================================================================

    private static AiMessage createUserMessage(String content) {
        AiMessage msg = new AiMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setSeq(1);
        msg.setRole(ConversationService.ROLE_USER);
        msg.setContent(content);
        msg.setSource(ConversationService.SOURCE_AI);
        return msg;
    }

    private static AiMessage createAssistantMessageWithToolCall(String callId, String toolName, String args) {
        AiMessage msg = new AiMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setSeq(2);
        msg.setRole(ConversationService.ROLE_ASSISTANT);
        msg.setContent("");
        msg.setToolCalls("[{\"id\":\"" + callId + "\",\"name\":\"" + toolName + "\",\"arguments\":" + args + "}]");
        msg.setSource(ConversationService.SOURCE_AI);
        return msg;
    }

    private static AiMessage createToolMessage(String callId, String toolName, String result) {
        AiMessage msg = new AiMessage();
        msg.setId(UUID.randomUUID().toString());
        msg.setSeq(3);
        msg.setRole(ConversationService.ROLE_TOOL);
        msg.setToolCallId(callId);
        msg.setToolName(toolName);
        msg.setToolResult(result);
        msg.setSource(ConversationService.SOURCE_AI);
        return msg;
    }
}

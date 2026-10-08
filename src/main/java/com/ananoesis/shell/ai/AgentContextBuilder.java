package com.ananoesis.shell.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.lang.Nullable;

import com.ananoesis.shell.entity.AiMessage;
import com.ananoesis.shell.service.ConversationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 智能体上下文构建器（design D7 步骤 1-4，tasks 8.4/8.5/8.6）。
 *
 * <p>负责从持久化消息构建发给模型的上下文序列，核心约束：</p>
 * <ul>
 *   <li>每次模型调用重查当前 seq 水位内最新最多 60 条（倒序 LIMIT 60 再转正序）</li>
 *   <li>assistant/call_id/tool 原子组校验：残组、多调用少一结果、重复结果、孤立结果整组排除</li>
 *   <li>单结果投影最多占输入预算 1/4 的头尾投影</li>
 *   <li>从最旧完整组淘汰，保护当前问题与最近必要执行组</li>
 * </ul>
 *
 * <h2>WHY 每次调用都重建</h2>
 * <p>模型有上下文长度上限，而一次排障对话可能积累上百条工具输出。
 * 超限时端点回 400，用户看到的是"模型服务返回错误"——完全无法自助。
 * 每次从持久化快照重建，确保上下文始终反映最新状态，同时通过预算控制避免超限。</p>
 */
public class AgentContextBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(AgentContextBuilder.class);

    /** 60 条窗口上限（design D7 步骤 1）。 */
    public static final int WINDOW_SIZE = 60;

    private static final TypeReference<List<Map<String, Object>>> TOOL_CALL_LIST = new TypeReference<>() {};

    private final ConversationService conversations;
    private final ObjectMapper objectMapper;
    private final ContextTokenEstimator estimator;

    public AgentContextBuilder(ConversationService conversations, ObjectMapper objectMapper,
                               ContextTokenEstimator estimator) {
        this.conversations = Objects.requireNonNull(conversations);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.estimator = Objects.requireNonNull(estimator);
    }

    /**
     * 构建上下文消息序列。
     *
     * @param conversationId 会话 ID
     * @param inputBudget    输入预算（UTF-8 字节）
     * @return 构建结果，包含消息序列和可能的裁剪通知
     */
    public BuildResult buildContext(java.util.UUID conversationId, int inputBudget) {
        // 步骤 1：倒序 LIMIT 60 查询当前水位内最新消息
        List<AiMessage> rows = conversations.loadRecentMessages(conversationId, WINDOW_SIZE);
        if (rows.isEmpty()) {
            // WHY 256 而非 0：即使没有消息，请求结构本身（system prompt 模板、模型元数据等）
            // 也占用一定的 token 预算。256 是保守估计的"请求结构余量"，
            // 让上层在估算可用空间时不会把这部分预算误算给消息
            return new BuildResult(List.of(), List.of(), 256);
        }

        // 步骤 2：转正序（数据库返回的是倒序）
        List<AiMessage> ascending = new ArrayList<>(rows);
        java.util.Collections.reverse(ascending);

        // 步骤 3：原子组校验（design D7 步骤 3）
        List<AiMessage> validated = validateToolGroups(ascending);

        // 步骤 4：转换为 Spring AI 消息
        List<Message> messages = convertToMessages(validated);

        // 步骤 5：长结果投影与淘汰（design D7 步骤 6）
        ProjectionResult projected = projectAndEvict(messages, inputBudget);

        return new BuildResult(projected.messages(), projected.notices(), projected.estimatedSize());
    }

    /**
     * 原子组校验（design D7 步骤 3，tasks 8.5）。
     *
     * <p>严格按 call_id 组装 assistant + 全部对应 tool 消息：</p>
     * <ul>
     *   <li>孤立 tool 消息（没有对应的 assistant tool_calls）→ 整组排除</li>
     *   <li>assistant 有 tool_calls 但缺少对应 tool 结果 → 整组排除</li>
     *   <li>重复 call_id 的 tool 结果 → 整组排除</li>
     * </ul>
     *
     * <p>WHY 整组排除而不是补假响应：补假响应等于往上下文里注入模型没说过的话，
     * 它会把当真。丢弃是最安全的处理。</p>
     */
    List<AiMessage> validateToolGroups(List<AiMessage> messages) {
        // 收集所有 assistant 声明的 call_id
        Map<String, AiMessage> assistantCalls = new LinkedHashMap<>();
        for (AiMessage msg : messages) {
            if (ConversationService.ROLE_ASSISTANT.equals(msg.getRole()) && msg.getToolCalls() != null) {
                List<Map<String, Object>> calls = parseToolCalls(msg.getToolCalls());
                for (Map<String, Object> call : calls) {
                    String callId = (String) call.get("id");
                    if (callId != null && !callId.isBlank()) {
                        assistantCalls.put(callId, msg);
                    }
                }
            }
        }

        // 收集所有 tool 结果
        Map<String, AiMessage> toolResults = new LinkedHashMap<>();
        for (AiMessage msg : messages) {
            if (ConversationService.ROLE_TOOL.equals(msg.getRole()) && msg.getToolCallId() != null) {
                String callId = msg.getToolCallId();
                if (toolResults.containsKey(callId)) {
                    // 重复 call_id → 标记为无效
                    toolResults.put(callId, null);
                } else {
                    toolResults.put(callId, msg);
                }
            }
        }

        // 构建有效消息集合
        List<AiMessage> result = new ArrayList<>();
        for (AiMessage msg : messages) {
            String role = msg.getRole();

            if (ConversationService.ROLE_USER.equals(role)) {
                // 用户消息始终保留
                result.add(msg);
            } else if (ConversationService.ROLE_ASSISTANT.equals(role)) {
                if (msg.getToolCalls() == null || msg.getToolCalls().isBlank()) {
                    // 纯文本 assistant 消息
                    result.add(msg);
                } else {
                    // 带 tool_calls 的 assistant：检查是否所有 call_id 都有对应结果
                    List<Map<String, Object>> calls = parseToolCalls(msg.getToolCalls());
                    boolean allComplete = true;
                    for (Map<String, Object> call : calls) {
                        String callId = (String) call.get("id");
                        if (callId == null || !toolResults.containsKey(callId) || toolResults.get(callId) == null) {
                            allComplete = false;
                            break;
                        }
                    }
                    if (allComplete) {
                        result.add(msg);
                    } else {
                        LOG.debug("排除不完整的 assistant 工具组: id={}", msg.getId());
                    }
                }
            } else if (ConversationService.ROLE_TOOL.equals(role)) {
                // tool 消息：只在对应 assistant 完整时保留（由上一步保证）
                String callId = msg.getToolCallId();
                if (callId != null && assistantCalls.containsKey(callId) && toolResults.get(callId) == msg) {
                    result.add(msg);
                } else {
                    LOG.debug("排除孤立 tool 消息: callId={}", callId);
                }
            }
            // system 消息忽略：系统提示每回合按当前状态重新构造
        }

        return result;
    }

    /**
     * 长结果投影与淘汰（design D7 步骤 6，tasks 8.6）。
     *
     * <p>单结果最多占输入预算 1/4，超出时做头尾投影。
     * 总量仍超限时从最旧完整组开始淘汰，保护当前问题与最近必要执行组。</p>
     */
    private ProjectionResult projectAndEvict(List<Message> messages, int inputBudget) {
        List<String> notices = new ArrayList<>();
        int maxSingleResult = inputBudget / 4;

        // 投影长结果
        List<Message> projected = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage toolResponse) {
                Message projectedMsg = projectToolResponse(toolResponse, maxSingleResult, notices);
                projected.add(projectedMsg);
            } else {
                projected.add(msg);
            }
        }

        // 估算当前大小
        int estimated = estimator.estimate(projected, List.of());

        // 如果仍超限，从最旧完整组淘汰
        while (estimated > inputBudget && projected.size() > 2) {
            // 找到最旧的完整工具组并移除
            int removedIndex = findOldestCompleteGroup(projected);
            if (removedIndex < 0) {
                break;
            }
            projected.remove(removedIndex);
            estimated = estimator.estimate(projected, List.of());
            notices.add("已淘汰旧工具组以适配上下文预算");
        }

        return new ProjectionResult(projected, notices, estimated);
    }

    /**
     * 对超长工具结果做头尾投影。
     */
    private Message projectToolResponse(ToolResponseMessage msg, int maxBytes, List<String> notices) {
        List<ToolResponseMessage.ToolResponse> projected = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse response : msg.getResponses()) {
            String data = response.responseData();
            if (data != null && data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
                // 头尾各占一半
                int halfMax = maxBytes / 2;
                String head = truncateToBytes(data, halfMax);
                String tail = truncateFromEnd(data, halfMax);
                String truncated = head + "\n\n... [已截断] ...\n\n" + tail;
                projected.add(new ToolResponseMessage.ToolResponse(
                        response.id(), response.name(), truncated));
                notices.add("工具结果已截断: " + response.name());
            } else {
                projected.add(response);
            }
        }
        return ToolResponseMessage.builder().responses(projected).build();
    }

    /**
     * 从头开始截断到指定字节数。
     */
    private static String truncateToBytes(String s, int maxBytes) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        return new String(bytes, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 从尾开始截断到指定字节数。
     */
    private static String truncateFromEnd(String s, int maxBytes) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        return new String(bytes, bytes.length - maxBytes, maxBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 找到最旧的完整工具组（assistant + 所有 tool 响应）的起始索引。
     */
    private static int findOldestCompleteGroup(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                // 检查后续是否有对应的 tool 响应
                int callCount = assistant.getToolCalls().size();
                int foundCount = 0;
                for (int j = i + 1; j < messages.size() && foundCount < callCount; j++) {
                    if (messages.get(j) instanceof ToolResponseMessage) {
                        foundCount++;
                    }
                }
                if (foundCount == callCount) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 将实体消息转换为 Spring AI 消息。
     */
    private List<Message> convertToMessages(List<AiMessage> rows) {
        List<Message> result = new ArrayList<>();
        for (AiMessage row : rows) {
            String role = row.getRole();
            if (ConversationService.ROLE_USER.equals(role)) {
                result.add(new UserMessage(nullToEmpty(row.getContent())));
            } else if (ConversationService.ROLE_ASSISTANT.equals(role)) {
                AssistantMessage.Builder builder = AssistantMessage.builder()
                        .content(nullToEmpty(row.getContent()));
                List<AssistantMessage.ToolCall> calls = parseToolCallsForSpring(row.getToolCalls());
                if (!calls.isEmpty()) {
                    builder.toolCalls(calls);
                }
                result.add(builder.build());
            } else if (ConversationService.ROLE_TOOL.equals(role)) {
                result.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                nullToEmpty(row.getToolCallId()),
                                nullToEmpty(row.getToolName()),
                                nullToEmpty(row.getToolResult()))))
                        .build());
            }
        }
        return result;
    }

    private List<Map<String, Object>> parseToolCalls(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, TOOL_CALL_LIST);
        } catch (Exception e) {
            LOG.warn("tool_calls 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<AssistantMessage.ToolCall> parseToolCallsForSpring(@Nullable String json) {
        List<Map<String, Object>> raw = parseToolCalls(json);
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            String id = (String) item.get("id");
            String name = (String) item.get("name");
            String args = item.get("arguments") instanceof String s ? s : "{}";
            if (name != null && !name.isBlank()) {
                result.add(new AssistantMessage.ToolCall(
                        id == null || id.isBlank() ? "call_" + result.size() : id,
                        "function", name, args));
            }
        }
        return result;
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    /**
     * 上下文构建结果。
     */
    public record BuildResult(List<Message> messages, List<String> notices, int estimatedSize) {}

    private record ProjectionResult(List<Message> messages, List<String> notices, int estimatedSize) {}
}

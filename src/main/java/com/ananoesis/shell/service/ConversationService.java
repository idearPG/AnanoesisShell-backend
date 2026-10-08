package com.ananoesis.shell.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ananoesis.shell.contract.model.Conversation;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.contract.model.Message;
import com.ananoesis.shell.contract.model.MessageRole;
import com.ananoesis.shell.contract.model.MessageSource;
import com.ananoesis.shell.contract.model.ToolCallRecord;
import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.ananoesis.shell.entity.AiConversation;
import com.ananoesis.shell.entity.AiMessage;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.AiConversationMapper;
import com.ananoesis.shell.mapper.AiMessageMapper;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.support.EntityIds;
import com.ananoesis.shell.support.Timestamps;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ConversationService {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationService.class);

    public static final String STATUS_ACTIVE = "active";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";
    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_SHELL_EVENT = "shell_event";
    private static final int TITLE_MAX_CHARS = 40;
    public static final int LIST_DEFAULT_LIMIT = 30;
    public static final int LIST_MAX_LIMIT = 100;
    public static final int MSG_DEFAULT_LIMIT = 50;
    public static final int MSG_MAX_LIMIT = 200;

    /** 上下文窗口查询的默认条数（design D7 步骤 1）。 */
    public static final int CONTEXT_WINDOW_LIMIT = 60;

    private static final TypeReference<List<ToolCallRecord>> TOOL_CALL_RECORDS = new TypeReference<>() {};

    private final AiConversationMapper conversations;
    private final AiMessageMapper messages;
    private final HostMapper hosts;
    private final ObjectMapper objectMapper;

    public ConversationService(AiConversationMapper conversations, AiMessageMapper messages,
                               HostMapper hosts, ObjectMapper objectMapper) {
        this.conversations = Objects.requireNonNull(conversations);
        this.messages = Objects.requireNonNull(messages);
        this.hosts = Objects.requireNonNull(hosts);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public List<Conversation> list(@Nullable String cursor, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), LIST_MAX_LIMIT);
        QueryWrapper<AiConversation> query = new QueryWrapper<>();
        if (cursor != null && !cursor.isBlank()) {
            CursorPair decoded = decodeCursor(cursor);
            query.and(w -> w.lt("updated_at", decoded.first)
                    .or(w2 -> w2.eq("updated_at", decoded.first).lt("id", decoded.second)));
        }
        query.orderByDesc("updated_at", "id");
        query.last("LIMIT " + (effectiveLimit + 1));
        List<Conversation> result = new ArrayList<>();
        for (AiConversation row : conversations.selectList(query)) {
            result.add(toDto(row));
        }
        return result;
    }

    public List<Conversation> list() { return list(null, LIST_DEFAULT_LIMIT); }

    @Transactional
    public Conversation create(@Nullable ConversationCreate request) {
        ConversationCreate body = request == null ? new ConversationCreate() : request;
        UUID hostId = body.getHostId();
        if (hostId != null && hosts.selectById(hostId.toString()) == null) {
            throw new InvalidRequestException("host_id", "Target host not found or deleted");
        }
        AiConversation row = new AiConversation();
        row.setId(EntityIds.newUuid());
        row.setHostId(hostId == null ? null : hostId.toString());
        row.setTitle(normalizeTitle(body.getTitle()));
        row.setStatus(STATUS_ACTIVE);
        conversations.insert(row);
        LOG.info("Created AI conversation: id={} hostId={}", row.getId(), hostId);
        return toDto(conversations.selectById(row.getId()));
    }

    public KeysetPage<Message> listMessagesPaged(UUID conversationId, @Nullable String cursor, int limit) {
        requireRow(conversationId);
        int effectiveLimit = Math.min(Math.max(limit, 1), MSG_MAX_LIMIT);
        List<AiMessage> rows = loadMessagesKeyset(conversationId, cursor, effectiveLimit);
        boolean hasMore = rows.size() > effectiveLimit;
        if (hasMore) { rows = new ArrayList<>(rows.subList(0, effectiveLimit)); }
        List<AiMessage> ascending = new ArrayList<>(rows);
        Collections.reverse(ascending);
        List<Message> items = new ArrayList<>();
        for (AiMessage row : ascending) { items.add(toDto(conversationId, row)); }
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            AiMessage oldest = rows.get(rows.size() - 1);
            nextCursor = encodeCursor(oldest.getSeq(), oldest.getId());
        }
        return new KeysetPage<>(items, nextCursor, hasMore);
    }

    public List<Message> listMessages(UUID conversationId) {
        requireRow(conversationId);
        List<Message> result = new ArrayList<>();
        for (AiMessage row : loadHistory(conversationId)) { result.add(toDto(conversationId, row)); }
        return result;
    }

    public AiConversation requireRow(UUID conversationId) {
        Objects.requireNonNull(conversationId);
        AiConversation row = conversations.selectById(conversationId.toString());
        if (row == null) { throw ConversationNotFoundException.forId(conversationId.toString()); }
        return row;
    }

    public Conversation requireAndToDto(UUID conversationId) { return toDto(requireRow(conversationId)); }

    @Nullable
    public UUID hostIdOf(AiConversation row) { return parseUuid(row == null ? null : row.getHostId()); }

    public List<AiMessage> loadHistory(UUID conversationId) {
        Objects.requireNonNull(conversationId);
        QueryWrapper<AiMessage> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId.toString()).orderByAsc("seq", "id");
        return messages.selectList(query);
    }

    /**
     * 加载最近 N 条消息（倒序返回）。
     *
     * <p>WHY 倒序：design D7 步骤 1 要求"每次模型调用重查当前 seq 水位内最新最多 60 条"。
     * 倒序 LIMIT 可以高效获取最新消息，调用方根据需要再转正序。</p>
     *
     * @param conversationId 会话 ID
     * @param limit          最多返回条数
     * @return 消息列表，按 seq 倒序（最新在前）
     */
    public List<AiMessage> loadRecentMessages(UUID conversationId, int limit) {
        Objects.requireNonNull(conversationId);
        QueryWrapper<AiMessage> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId.toString())
                .orderByDesc("seq", "id")
                .last("LIMIT " + limit);
        return messages.selectList(query);
    }

    @Nullable
    public String hostLabelOf(@Nullable UUID hostId) {
        if (hostId == null) return null;
        Host host = hosts.selectById(hostId.toString());
        return host == null ? null : host.getName();
    }

    @Transactional
    public UUID saveUserMessage(UUID conversationId, String content) {
        Objects.requireNonNull(content);
        UUID id = insert(conversationId, ROLE_USER, content, null, null,
                null, null, null, null, null, SOURCE_AI, null, null);
        AiConversation row = conversations.selectById(conversationId.toString());
        if (row != null && (row.getTitle() == null || row.getTitle().isBlank())) {
            AiConversation patch = new AiConversation();
            patch.setId(row.getId());
            patch.setTitle(summarize(content));
            conversations.updateById(patch);
        }
        touch(conversationId);
        return id;
    }

    @Transactional
    public UUID saveAssistantMessage(UUID conversationId, @Nullable String content,
                                     @Nullable String reasoningContent,
                                     @Nullable List<Map<String, Object>> toolCalls) {
        UUID id = insert(conversationId, ROLE_ASSISTANT, content, reasoningContent, toJson(toolCalls),
                null, null, null, null, null, SOURCE_AI, null, null);
        touch(conversationId);
        return id;
    }

    @Transactional
    public UUID saveToolMessage(UUID conversationId, @Nullable String toolCallId, String toolName,
                                @Nullable Map<String, Object> toolParams, ToolResultStatus status,
                                @Nullable String result, @Nullable UUID approvalId, boolean rejected) {
        Objects.requireNonNull(toolName);
        Objects.requireNonNull(status);
        Map<String, Object> params = toolParams == null ? Map.of() : toolParams;
        ToolCallRecord record = new ToolCallRecord()
                .toolName(toolName).toolParams(params).resultStatus(status)
                .result(result).approvalId(approvalId);
        return insert(conversationId, ROLE_TOOL, result, null, toJson(List.of(record)),
                toolCallId, toolName, toJson(params), result, rejected ? 1 : 0,
                SOURCE_AI, null, null);
    }

    @Transactional
    public UUID saveShellEventMessage(UUID conversationId, String content,
                                      @Nullable UUID commandId, @Nullable UUID runId) {
        Objects.requireNonNull(content);
        UUID id = insert(conversationId, ROLE_USER, content, null, null,
                null, null, null, null, null, SOURCE_SHELL_EVENT,
                commandId == null ? null : commandId.toString(),
                runId == null ? null : runId.toString());
        touch(conversationId);
        return id;
    }

    @Transactional
    public void bindSession(UUID conversationId, UUID sessionId) {
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(sessionId);
        requireRow(conversationId);
        AiConversation patch = new AiConversation();
        patch.setId(conversationId.toString());
        patch.setSessionId(sessionId.toString());
        conversations.updateById(patch);
    }

    @Transactional
    public void unbindSession(UUID conversationId) {
        Objects.requireNonNull(conversationId);
        requireRow(conversationId);
        UpdateWrapper<AiConversation> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", conversationId.toString()).set("session_id", null);
        conversations.update(null, wrapper);
    }

    public List<Conversation> findBySession(UUID sessionId) {
        Objects.requireNonNull(sessionId);
        QueryWrapper<AiConversation> query = new QueryWrapper<>();
        query.eq("session_id", sessionId.toString()).orderByDesc("created_at", "id");
        List<Conversation> result = new ArrayList<>();
        for (AiConversation row : conversations.selectList(query)) { result.add(toDto(row)); }
        return result;
    }

    @Transactional
    public void delete(UUID conversationId) {
        Objects.requireNonNull(conversationId);
        requireRow(conversationId);
        rejectIfActive(conversationId);
        LOG.info("Deleting conversation: id={}", conversationId);
        conversations.deleteById(conversationId.toString());
    }

    @Transactional
    public void batchDelete(List<UUID> ids) {
        Objects.requireNonNull(ids);
        if (ids.isEmpty()) return;
        if (ids.size() > 100) {
            throw new InvalidRequestException("ids", "Batch delete max 100, got: " + ids.size());
        }
        for (UUID id : ids) { requireRow(id); rejectIfActive(id); }
        for (UUID id : ids) { conversations.deleteById(id.toString()); }
        LOG.info("Batch deleted conversations: count={}", ids.size());
    }

    private void rejectIfActive(UUID conversationId) {
        // TODO: When AiRunService is implemented, check ai_runs for active status
    }

    private List<AiMessage> loadMessagesKeyset(UUID conversationId, @Nullable String cursor, int limit) {
        QueryWrapper<AiMessage> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId.toString());
        if (cursor != null && !cursor.isBlank()) {
            CursorPair decoded = decodeCursor(cursor);
            int cursorSeq = Integer.parseInt(decoded.first);
            query.and(w -> w.lt("seq", cursorSeq)
                    .or(w2 -> w2.eq("seq", cursorSeq).lt("id", decoded.second)));
        }
        query.orderByDesc("seq", "id");
        query.last("LIMIT " + (limit + 1));
        return messages.selectList(query);
    }

    private UUID insert(UUID conversationId, String role, @Nullable String content,
                        @Nullable String reasoningContent, @Nullable String toolCalls,
                        @Nullable String toolCallId, @Nullable String toolName,
                        @Nullable String toolArguments, @Nullable String toolResult,
                        @Nullable Integer toolRejected,
                        String source, @Nullable String commandId, @Nullable String runId) {
        Objects.requireNonNull(conversationId);
        AiMessage row = new AiMessage();
        UUID id = UUID.fromString(EntityIds.newUuid());
        row.setId(id.toString());
        row.setConversationId(conversationId.toString());
        row.setSeq(nextSeq(conversationId));
        row.setRole(role);
        row.setContent(content);
        row.setReasoningContent(reasoningContent);
        row.setToolCalls(toolCalls);
        row.setToolCallId(toolCallId);
        row.setToolName(toolName);
        row.setToolArguments(toolArguments);
        row.setToolResult(toolResult);
        row.setToolRejected(toolRejected == null ? 0 : toolRejected);
        row.setSource(source != null ? source : SOURCE_AI);
        row.setCommandId(commandId);
        row.setRunId(runId);
        messages.insert(row);
        return id;
    }

    private int nextSeq(UUID conversationId) {
        QueryWrapper<AiMessage> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId.toString()).orderByDesc("seq").last("LIMIT 1");
        AiMessage last = messages.selectOne(query);
        if (last == null || last.getSeq() == null) return 1;
        return last.getSeq() + 1;
    }

    private void touch(UUID conversationId) {
        AiConversation patch = new AiConversation();
        patch.setId(conversationId.toString());
        patch.setStatus(STATUS_ACTIVE);
        patch.setUpdatedAt(LocalDateTime.now());
        conversations.updateById(patch);
    }

    private Conversation toDto(AiConversation row) {
        return new Conversation()
                .id(parseUuid(row.getId()))
                .hostId(parseUuid(row.getHostId()))
                .sessionId(parseUuid(row.getSessionId()))
                .title(row.getTitle())
                .createdAt(Timestamps.toOffset(row.getCreatedAt()))
                .updatedAt(Timestamps.toOffset(row.getUpdatedAt()));
    }

    private Message toDto(UUID conversationId, AiMessage row) {
        boolean toolRow = ROLE_TOOL.equals(row.getRole());
        Message dto = new Message()
                .id(parseUuid(row.getId()))
                .conversationId(conversationId)
                .seq(row.getSeq() == null ? null : row.getSeq().longValue())
                .role(roleOf(row.getRole()))
                .content(toolRow ? coalesce(row.getToolResult(), row.getContent()) : row.getContent())
                .thinkingContent(row.getReasoningContent())
                .source(sourceOf(row.getSource()))
                .commandId(parseUuid(row.getCommandId()))
                .runId(parseUuid(row.getRunId()))
                .createdAt(Timestamps.toOffset(row.getCreatedAt()));
        if (toolRow) { dto.setToolCalls(parseRecords(row.getToolCalls())); }
        return dto;
    }

    private static MessageRole roleOf(@Nullable String role) {
        if (ROLE_USER.equals(role)) return MessageRole.USER;
        if (ROLE_ASSISTANT.equals(role)) return MessageRole.ASSISTANT;
        if (ROLE_TOOL.equals(role)) return MessageRole.TOOL;
        return MessageRole.SYSTEM;
    }

    @Nullable
    private static MessageSource sourceOf(@Nullable String source) {
        if (source == null || SOURCE_AI.equals(source)) return MessageSource.AGENT;
        if (SOURCE_SHELL_EVENT.equals(source)) return MessageSource.SHELL_EVENT;
        return null;
    }

    private List<ToolCallRecord> parseRecords(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<ToolCallRecord> parsed = objectMapper.readValue(json, TOOL_CALL_RECORDS);
            return parsed == null ? List.of() : Collections.unmodifiableList(parsed);
        } catch (RuntimeException | java.io.IOException e) {
            LOG.warn("tool_calls parse failed: {}", String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    @Nullable
    private String toJson(@Nullable Object value) {
        if (isEmptyPayload(value)) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.error("toJson failed: {}", String.valueOf(e.getMessage()));
            return null;
        }
    }

    private static boolean isEmptyPayload(@Nullable Object value) {
        if (value == null) return true;
        if (value instanceof Collection<?> c) return c.isEmpty();
        if (value instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    static String summarize(String text) {
        String single = text.strip().replaceAll("\\s+", " ");
        if (single.length() <= TITLE_MAX_CHARS) return single;
        return single.substring(0, TITLE_MAX_CHARS) + "\u2026";
    }

    @Nullable
    private static String normalizeTitle(@Nullable String title) {
        if (title == null) return null;
        String trimmed = title.strip();
        return trimmed.isEmpty() ? null : summarize(trimmed);
    }

    private static String coalesce(@Nullable String first, @Nullable String second) {
        return first != null ? first : second;
    }

    @Nullable
    static UUID parseUuid(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value.trim()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public record KeysetPage<T>(List<T> items, @Nullable String nextCursor, boolean hasMore) {}
    record CursorPair(String first, String second) {}

    static String encodeCursor(Object first, Object second) {
        String raw = first + "," + second;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    static CursorPair decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor));
            int comma = raw.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("Invalid cursor");
            return new CursorPair(raw.substring(0, comma), raw.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("cursor", "Invalid cursor format");
        }
    }
}

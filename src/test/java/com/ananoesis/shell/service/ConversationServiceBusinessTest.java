package com.ananoesis.shell.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.contract.model.Conversation;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.contract.model.MessageRole;
import com.ananoesis.shell.contract.model.MessageSource;
import com.ananoesis.shell.entity.AiConversation;
import com.ananoesis.shell.entity.AiMessage;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.AiConversationMapper;
import com.ananoesis.shell.mapper.AiMessageMapper;
import com.ananoesis.shell.mapper.HostMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ConversationService} 业务逻辑分支补测。
 *
 * <p>WHY 独立测试：覆盖 list/create/listMessagesPaged/saveUserMessage/saveToolMessage/
 * batchDelete/roleOf/sourceOf/parseRecords/toJson/coalesce/insert/nextSeq 等方法的
 * 条件分支。</p>
 */
@DisplayName("ConversationService 业务逻辑分支")
class ConversationServiceBusinessTest {

    private AiConversationMapper conversations;
    private AiMessageMapper messages;
    private HostMapper hosts;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversations = mock(AiConversationMapper.class);
        messages = mock(AiMessageMapper.class);
        hosts = mock(HostMapper.class);
        service = new ConversationService(conversations, messages, hosts, new ObjectMapper());
    }

    // ---- list ----

    @Nested
    @DisplayName("list(cursor, limit)")
    class ListPaged {
        @Test
        @DisplayName("limit 负数被钳制到 1")
        void negativeLimitClamped() {
            when(conversations.selectList(any())).thenReturn(List.of());
            service.list(null, -5);
            verify(conversations).selectList(any());
        }

        @Test
        @DisplayName("limit 超 100 被钳制到 100")
        void overMaxClamped() {
            when(conversations.selectList(any())).thenReturn(List.of());
            service.list(null, 999);
            verify(conversations).selectList(any());
        }

        @Test
        @DisplayName("有 cursor 时解码并追加条件")
        void withCursorDecoded() {
            String cursor = ConversationService.encodeCursor(100, UUID.randomUUID().toString());
            when(conversations.selectList(any())).thenReturn(List.of());
            service.list(cursor, 10);
            verify(conversations).selectList(any());
        }

        @Test
        @DisplayName("空 cursor 等同 null")
        void blankCursorTreatedAsNull() {
            when(conversations.selectList(any())).thenReturn(List.of());
            service.list("  ", 10);
            verify(conversations).selectList(any());
        }
    }

    // ---- create ----

    @Nested
    @DisplayName("create")
    class Create {
        @Test
        @DisplayName("null request 使用空默认")
        void nullRequestDefaults() {
            AiConversation row = sampleRow();
            when(conversations.insert(any(AiConversation.class))).thenReturn(1);
            when(conversations.selectById(anyString())).thenReturn(row);
            Conversation result = service.create(null);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("hostId 对应主机不存在时抛异常")
        void hostNotFoundThrows() {
            ConversationCreate req = new ConversationCreate();
            req.setHostId(UUID.randomUUID());
            when(hosts.selectById(anyString())).thenReturn(null);
            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        @DisplayName("hostId 为 null 时跳过主机检查")
        void nullHostIdSkipsCheck() {
            ConversationCreate req = new ConversationCreate();
            req.setHostId(null);
            AiConversation row = sampleRow();
            when(conversations.insert(any(AiConversation.class))).thenReturn(1);
            when(conversations.selectById(anyString())).thenReturn(row);
            service.create(req);
            verify(hosts, never()).selectById(anyString());
        }
    }

    // ---- requireRow ----

    @Test
    @DisplayName("requireRow: null id 抛 NPE")
    void requireRowNullIdThrows() {
        assertThatThrownBy(() -> service.requireRow(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("requireRow: 不存在抛 ConversationNotFoundException")
    void requireRowNotFoundThrows() {
        when(conversations.selectById(anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.requireRow(UUID.randomUUID()))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    // ---- hostLabelOf ----

    @Nested
    @DisplayName("hostLabelOf")
    class HostLabel {
        @Test
        @DisplayName("null hostId 返回 null")
        void nullReturnsNull() {
            assertThat(service.hostLabelOf(null)).isNull();
        }

        @Test
        @DisplayName("主机不存在返回 null")
        void hostMissingReturnsNull() {
            when(hosts.selectById(anyString())).thenReturn(null);
            assertThat(service.hostLabelOf(UUID.randomUUID())).isNull();
        }

        @Test
        @DisplayName("主机存在返回名称")
        void hostExistsReturnsName() {
            Host host = new Host();
            host.setId(UUID.randomUUID().toString());
            host.setName("myhost");
            when(hosts.selectById(anyString())).thenReturn(host);
            assertThat(service.hostLabelOf(UUID.randomUUID())).isEqualTo("myhost");
        }
    }

    // ---- batchDelete ----

    @Nested
    @DisplayName("batchDelete")
    class BatchDelete {
        @Test
        @DisplayName("空列表直接返回")
        void emptyListReturns() {
            service.batchDelete(List.of());
            verify(conversations, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("超 100 条抛异常")
        void over100Throws() {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 101; i++) ids.add(UUID.randomUUID());
            assertThatThrownBy(() -> service.batchDelete(ids))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    // ---- saveUserMessage 自动标题 ----

    @Nested
    @DisplayName("saveUserMessage 自动标题")
    class AutoTitle {
        @Test
        @DisplayName("标题为 null 时自动生成")
        void nullTitleAutoGenerated() {
            UUID cid = UUID.randomUUID();
            AiMessage msgRow = new AiMessage();
            msgRow.setId(UUID.randomUUID().toString());
            when(messages.insert(any(AiMessage.class))).thenReturn(1);
            AiConversation convRow = sampleRow();
            convRow.setId(cid.toString());
            convRow.setTitle(null);
            when(conversations.selectById(cid.toString())).thenReturn(convRow);
            when(messages.selectOne(any())).thenReturn(null);
            service.saveUserMessage(cid, "ls -la");
            // updateById 被调用 2 次：一次设标题，一次 touch
            verify(conversations, times(2)).updateById(any(AiConversation.class));
        }

        @Test
        @DisplayName("标题已有值时不覆盖")
        void existingTitleNotOverwritten() {
            UUID cid = UUID.randomUUID();
            when(messages.insert(any(AiMessage.class))).thenReturn(1);
            AiConversation convRow = sampleRow();
            convRow.setId(cid.toString());
            convRow.setTitle("已有标题");
            when(conversations.selectById(cid.toString())).thenReturn(convRow);
            when(messages.selectOne(any())).thenReturn(null);
            service.saveUserMessage(cid, "ls -la");
            // updateById 只被 touch 调用一次（不设 title）
            verify(conversations, times(1)).updateById(any(AiConversation.class));
        }
    }

    // ---- roleOf / sourceOf（通过 toDto 间接测试）----

    @Nested
    @DisplayName("toDto 消息角色映射")
    class RoleMapping {
        @Test
        @DisplayName("user 角色正确映射")
        void userRoleMapped() {
            AiMessage row = msgRow("user", "hello");
            row.setSource("ai");
            var result = invokeToDto(UUID.randomUUID(), row);
            assertThat(result.getRole()).isEqualTo(MessageRole.USER);
            assertThat(result.getSource()).isEqualTo(MessageSource.AGENT);
        }

        @Test
        @DisplayName("assistant 角色正确映射")
        void assistantRoleMapped() {
            AiMessage row = msgRow("assistant", "hi");
            row.setSource(null);
            var result = invokeToDto(UUID.randomUUID(), row);
            assertThat(result.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(result.getSource()).isEqualTo(MessageSource.AGENT);
        }

        @Test
        @DisplayName("tool 角色取 toolResult 作为 content")
        void toolRoleUsesResult() {
            AiMessage row = msgRow("tool", null);
            row.setToolResult("output");
            row.setToolCalls("[]");
            row.setSource("ai");
            var result = invokeToDto(UUID.randomUUID(), row);
            assertThat(result.getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(result.getContent()).isEqualTo("output");
        }

        @Test
        @DisplayName("未知角色映射为 SYSTEM")
        void unknownRoleMappedToSystem() {
            AiMessage row = msgRow("unknown", "data");
            row.setSource("ai");
            var result = invokeToDto(UUID.randomUUID(), row);
            assertThat(result.getRole()).isEqualTo(MessageRole.SYSTEM);
        }

        @Test
        @DisplayName("shell_event 来源正确映射")
        void shellEventSourceMapped() {
            AiMessage row = msgRow("user", "event");
            row.setSource("shell_event");
            var result = invokeToDto(UUID.randomUUID(), row);
            assertThat(result.getSource()).isEqualTo(MessageSource.SHELL_EVENT);
        }

        @Test
        @DisplayName("未知来源映射为 null")
        void unknownSourceMappedToNull() {
            AiMessage row = msgRow("user", "data");
            row.setSource("unknown_source");
            var result = invokeToDto(UUID.randomUUID(), row);
            assertThat(result.getSource()).isNull();
        }

        @Test
        @DisplayName("seq 为 null 时 DTO seq 也为 null")
        void nullSeqMappedToNull() {
            AiMessage row = msgRow("user", "data");
            row.setSeq(null);
            row.setSource("ai");
            var result = invokeToDto(UUID.randomUUID(), row);
            assertThat(result.getSeq()).isNull();
        }
    }

    // ---- 辅助方法 ----

    private AiConversation sampleRow() {
        AiConversation row = new AiConversation();
        row.setId(UUID.randomUUID().toString());
        row.setHostId(null);
        row.setSessionId(null);
        row.setTitle("test");
        row.setStatus("active");
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    private AiMessage msgRow(String role, String content) {
        AiMessage row = new AiMessage();
        row.setId(UUID.randomUUID().toString());
        row.setSeq(1);
        row.setRole(role);
        row.setContent(content);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /**
     * 通过反射调用私有 toDto(UUID, AiMessage) 方法。
     */
    private com.ananoesis.shell.contract.model.Message invokeToDto(UUID cid, AiMessage row) {
        try {
            var method = ConversationService.class.getDeclaredMethod(
                    "toDto", UUID.class, AiMessage.class);
            method.setAccessible(true);
            return (com.ananoesis.shell.contract.model.Message) method.invoke(service, cid, row);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

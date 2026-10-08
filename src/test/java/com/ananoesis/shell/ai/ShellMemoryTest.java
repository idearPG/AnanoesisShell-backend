package com.ananoesis.shell.ai;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.Conversation;
import com.ananoesis.shell.entity.AiMessage;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.entity.SshSession;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.mapper.SshSessionMapper;
import com.ananoesis.shell.service.ConversationService;

/**
 * tasks 7.1 / 7.2 的验收：人工记忆与对话绑定 session。
 *
 * <p>本测试盯的是三件最容易悄悄跑偏的事：</p>
 * <ol>
 *   <li><b>人工命令持久化</b>——完成的人工命令必须作为 {@code role=user, source=shell_event}
 *       落入活动对话，关联 {@code command_id}/{@code run_id}，不伪造 {@code tool_calls}；</li>
 *   <li><b>同 tab 可见、跨 tab 隔离</b>——同一对话的人工结果可供下一问题引用，
 *       不同对话之间完全隔离；</li>
 *   <li><b>对话绑定 session</b>——创建/继续对话时绑定 session，只有空闲同 session 可切换。</li>
 * </ol>
 *
 * <p>WHY 走 {@code ConversationService} 而不是直接 INSERT：要验的正是服务层写入方法
 * 产出的形状能否被 {@code loadHistory} 原样取回。直接 SQL 会绕开服务层的字段赋值逻辑。</p>
 */
class ShellMemoryTest extends AbstractSqliteIntegrationTest {

    @Autowired
    private ConversationService conversations;

    @Autowired
    private HostMapper hostMapper;

    @Autowired
    private SshSessionMapper sessionMapper;

    // ======================================================================
    // 7.1 人工命令持久化
    // ======================================================================

    @Nested
    @DisplayName("7.1 人工命令持久化")
    class ShellEventPersistence {

        @Test
        @DisplayName("完成的人工命令作为 role=user, source=shell_event 落库，关联 command_id/run_id")
        void completedManualCommandIsPersistedAsShellEvent() {
            UUID conversationId = conversations.create(null).getId();
            UUID commandId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();

            conversations.saveShellEventMessage(conversationId,
                    "ls -la\nexit=0\ntotal 42", commandId, runId);

            List<AiMessage> history = conversations.loadHistory(conversationId);
            assertThat(history).hasSize(1);

            AiMessage event = history.get(0);
            assertThat(event.getRole()).isEqualTo("user");
            assertThat(event.getSource()).isEqualTo("shell_event");
            assertThat(event.getContent()).isEqualTo("ls -la\nexit=0\ntotal 42");
            assertThat(event.getCommandId()).isEqualTo(commandId.toString());
            assertThat(event.getRunId()).isEqualTo(runId.toString());
        }

        @Test
        @DisplayName("人工记录不伪造 tool_calls——该列为 null")
        void shellEventDoesNotFabricateToolCalls() {
            UUID conversationId = conversations.create(null).getId();

            conversations.saveShellEventMessage(conversationId,
                    "pwd\nexit=0\n/home/user", UUID.randomUUID(), UUID.randomUUID());

            AiMessage event = conversations.loadHistory(conversationId).get(0);
            assertThat(event.getToolCalls())
                    .as("人工记录不是工具调用，不应伪造 tool_calls")
                    .isNull();
            assertThat(event.getToolName()).isNull();
            assertThat(event.getToolCallId()).isNull();
        }

        @Test
        @DisplayName("同 tab 人工结果可供下一问题引用——loadHistory 包含人工记录")
        void manualResultInSameTabIsAvailableForNextQuestion() {
            UUID conversationId = conversations.create(null).getId();
            UUID commandId = UUID.randomUUID();

            // 人工命令完成
            conversations.saveShellEventMessage(conversationId,
                    "cat /etc/hosts\nexit=0\n127.0.0.1 localhost", commandId, UUID.randomUUID());
            // 用户接着提问
            conversations.saveUserMessage(conversationId, "帮我看看 hosts 文件有没有异常");

            List<AiMessage> history = conversations.loadHistory(conversationId);
            assertThat(history).hasSize(2);
            // WHY 先断言顺序：seq 顺序错了的话，智能体拿到的上下文就是反的
            assertThat(history.get(0).getSource()).isEqualTo("shell_event");
            assertThat(history.get(0).getRole()).isEqualTo("user");
            assertThat(history.get(1).getRole()).isEqualTo("user");
            assertThat(history.get(1).getContent()).isEqualTo("帮我看看 hosts 文件有没有异常");
        }

        @Test
        @DisplayName("人工记录不跨 tab——不同对话之间完全隔离")
        void manualRecordsDoNotCrossTabs() {
            UUID tab1 = conversations.create(null).getId();
            UUID tab2 = conversations.create(null).getId();

            conversations.saveShellEventMessage(tab1,
                    "tab1 command result", UUID.randomUUID(), UUID.randomUUID());

            assertThat(conversations.loadHistory(tab1)).hasSize(1);
            assertThat(conversations.loadHistory(tab2))
                    .as("另一个 tab 的对话不应看到本 tab 的人工记录")
                    .isEmpty();
        }

        @Test
        @DisplayName("不记录每次按键——只有一次完成命令的调用")
        void doNotRecordIndividualKeystrokes() {
            UUID conversationId = conversations.create(null).getId();

            // 只有命令完成后才调用一次
            conversations.saveShellEventMessage(conversationId,
                    "whoami\nexit=0\nroot", UUID.randomUUID(), UUID.randomUUID());

            List<AiMessage> history = conversations.loadHistory(conversationId);
            assertThat(history).hasSize(1);
        }
    }

    // ======================================================================
    // 7.2 对话绑定 session
    // ======================================================================

    @Nested
    @DisplayName("7.2 对话绑定 session")
    class ConversationSessionBinding {

        @Test
        @DisplayName("bindSession 把对话绑定到指定 session")
        void bindSessionAssociatesConversationWithSession() {
            UUID conversationId = conversations.create(null).getId();
            // WHY 必须先播种真实的 host + session：ai_conversations.session_id 有外键引用 sessions(id)
            String sessionId = seedSession();

            conversations.bindSession(conversationId, UUID.fromString(sessionId));

            Conversation bound = conversations.requireAndToDto(conversationId);
            assertThat(bound.getSessionId()).isEqualTo(UUID.fromString(sessionId));
        }

        @Test
        @DisplayName("findBySession 返回该 session 下所有对话")
        void findBySessionReturnsConversationsBoundToThatSession() {
            String session1 = seedSession();
            String session2 = seedSession();
            UUID sessionId1 = UUID.fromString(session1);
            UUID sessionId2 = UUID.fromString(session2);

            UUID c1 = conversations.create(null).getId();
            UUID c2 = conversations.create(null).getId();
            UUID c3 = conversations.create(null).getId();

            conversations.bindSession(c1, sessionId1);
            conversations.bindSession(c2, sessionId1);
            conversations.bindSession(c3, sessionId2);

            List<Conversation> found = conversations.findBySession(sessionId1);
            assertThat(found).hasSize(2);
            assertThat(found.stream().map(Conversation::getId))
                    .containsExactlyInAnyOrder(c1, c2);
        }

        @Test
        @DisplayName("unbindSession 解除对话与 session 的绑定")
        void unbindSessionRemovesTheAssociation() {
            UUID conversationId = conversations.create(null).getId();
            String sessionId = seedSession();

            conversations.bindSession(conversationId, UUID.fromString(sessionId));
            conversations.unbindSession(conversationId);

            Conversation unbound = conversations.requireAndToDto(conversationId);
            assertThat(unbound.getSessionId()).isNull();
        }
    }

    // ======================================================================
    // Test helpers
    // ======================================================================

    /**
     * 播种一个 host + session 到数据库，返回 session id（带连字符的 UUID 字符串）。
     * WHY 不直接用随机 UUID：ai_conversations.session_id 有外键约束到 sessions(id)。
     */
    private String seedSession() {
        Host host = new Host();
        host.setId(UUID.randomUUID().toString());
        host.setName("test-host");
        host.setHost("127.0.0.1");
        host.setPort(22);
        host.setUsername("test");
        host.setAuthType("password");
        hostMapper.insert(host);

        SshSession session = new SshSession();
        session.setId(UUID.randomUUID().toString());
        session.setHostId(host.getId());
        session.setSessionType("interactive_pty");
        session.setStatus("open");
        session.setStartedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session.getId();
    }
}

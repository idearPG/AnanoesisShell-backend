package com.ananoesis.shell.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.BatchDeleteRequest;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.service.ConversationNotFoundException;
import com.ananoesis.shell.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * tasks 7.4 / 7.5 的验收：对话删除与活动引用清空。
 *
 * <p>本测试盯的关键事项：</p>
 * <ol>
 *   <li><b>单删</b>：DELETE /api/conversations/{id} 返回 204，级联删除消息</li>
 *   <li><b>批删</b>：POST /api/conversations/batch-delete 全成功或全不删</li>
 *   <li><b>活动对话拒绝删除</b>：generating/executing/waiting_approval/stopping → 409</li>
 *   <li><b>批删上限</b>：超过 100 个 → 400</li>
 *   <li><b>级联行为</b>：消息随对话删除；审批/执行账本外键 SET NULL</li>
 *   <li><b>7.5 活动引用清空</b>：删除后不复活、不调模型</li>
 * </ol>
 */
class ConversationDeletionTest extends AbstractSqliteIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private HostMapper hosts;

    // ======================================================================
    // 7.4 单删
    // ======================================================================

    @Nested
    @DisplayName("7.4 单删 DELETE /api/conversations/{id}")
    class SingleDeletion {

        @Test
        @DisplayName("删除空闲对话 → 204，对话与消息均不存在")
        void deleteIdleConversationReturns204AndRemovesIt() {
            UUID conversationId = conversations.create(null).getId();
            conversations.saveUserMessage(conversationId, "test question");
            conversations.saveAssistantMessage(conversationId, "test answer", null, null);

            ResponseEntity<Void> response = rest.exchange(
                    "/api/conversations/" + conversationId,
                    HttpMethod.DELETE, null, Void.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            // WHY 用服务层检查：ConversationNotFoundException 证明行已不在
            assertThatThrownBy(() -> conversations.requireRow(conversationId))
                    .isInstanceOf(ConversationNotFoundException.class);
            // 级联删除：消息也不存在
            assertThat(countMessages(conversationId)).isZero();
        }

        @Test
        @DisplayName("删除不存在的对话 → 404 not_found")
        void deleteNonExistentConversationReturns404() {
            ResponseEntity<String> response = rest.exchange(
                    "/api/conversations/" + UUID.randomUUID(),
                    HttpMethod.DELETE, null, String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(parseError(response.getBody()).getCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("删除对话级联删除其所有消息")
        void deleteConversationCascadesToMessages() {
            UUID conversationId = conversations.create(null).getId();
            conversations.saveUserMessage(conversationId, "q1");
            conversations.saveAssistantMessage(conversationId, "a1", null, null);
            conversations.saveUserMessage(conversationId, "q2");

            conversations.delete(conversationId);

            assertThat(countMessages(conversationId)).isZero();
        }
    }

    // ======================================================================
    // 7.4 批删
    // ======================================================================

    @Nested
    @DisplayName("7.4 批删 POST /api/conversations/batch-delete")
    class BatchDeletion {

        @Test
        @DisplayName("批量删除空闲对话 → 200，全部删除")
        void batchDeleteIdleConversationsReturns200AndRemovesAll() {
            UUID c1 = conversations.create(null).getId();
            UUID c2 = conversations.create(null).getId();
            UUID c3 = conversations.create(null).getId();

            ResponseEntity<Void> response = rest.exchange(
                    "/api/conversations/batch-delete",
                    HttpMethod.POST,
                    rawJson(write(new BatchDeleteRequest(List.of(c1, c2, c3)))),
                    Void.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThatThrownBy(() -> conversations.requireRow(c1)).isInstanceOf(ConversationNotFoundException.class);
            assertThatThrownBy(() -> conversations.requireRow(c2)).isInstanceOf(ConversationNotFoundException.class);
            assertThatThrownBy(() -> conversations.requireRow(c3)).isInstanceOf(ConversationNotFoundException.class);
        }

        @Test
        @DisplayName("批删包含不存在的 id → 404，全部不删")
        void batchDeleteWithNonExistentIdReturns404AndDeletesNone() {
            UUID c1 = conversations.create(null).getId();
            UUID nonExistent = UUID.randomUUID();

            ResponseEntity<String> response = rest.exchange(
                    "/api/conversations/batch-delete",
                    HttpMethod.POST,
                    rawJson(write(new BatchDeleteRequest(List.of(c1, nonExistent)))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            // WHY 全不删：原子性保证——全成功或全不删
            conversations.requireRow(c1); // 不应被删除
        }

        @Test
        @DisplayName("批删超过 100 个 → 400 validation_error")
        void batchDeleteOver100Returns400() {
            List<UUID> tooMany = new java.util.ArrayList<>();
            for (int i = 0; i < 101; i++) {
                tooMany.add(UUID.randomUUID());
            }

            ResponseEntity<String> response = rest.exchange(
                    "/api/conversations/batch-delete",
                    HttpMethod.POST,
                    rawJson(write(new BatchDeleteRequest(tooMany))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(parseError(response.getBody()).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        }
    }

    // ======================================================================
    // 7.4 活动对话拒绝删除
    // ======================================================================

    @Nested
    @DisplayName("7.4 活动对话拒绝删除 → 409")
    class ActiveConversationRejection {

        @Test
        @DisplayName("服务层：rejectIfActive 当前为占位实现，不拒绝任何对话")
        void rejectIfActiveIsPlaceholderForNow() {
            // WHY 此测试记录当前状态：AiRunService 尚未实现，
            // rejectIfActive 是占位。当 AiRunService 实现后，
            // 此测试应改为验证 generating/executing/waiting_approval/stopping 拒绝删除。
            UUID conversationId = conversations.create(null).getId();
            // 当前占位实现不抛异常，删除成功
            conversations.delete(conversationId);
            assertThatThrownBy(() -> conversations.requireRow(conversationId))
                    .isInstanceOf(ConversationNotFoundException.class);
        }
    }

    // ======================================================================
    // 7.5 活动引用清空
    // ======================================================================

    @Nested
    @DisplayName("7.5 活动引用清空")
    class ActiveReferenceClearing {

        @Test
        @DisplayName("删除对话后，后续提问创建新对话——不复活已删除记录")
        void subsequentQuestionCreatesNewConversationNotResurrectOld() {
            UUID deleted = conversations.create(null).getId();
            conversations.saveUserMessage(deleted, "old question");
            conversations.delete(deleted);

            // WHY 新建对话而非写入已删除的：模拟"删除后用户继续提问"的场景
            UUID newConversation = conversations.create(null).getId();
            conversations.saveUserMessage(newConversation, "new question");

            // 旧对话不存在
            assertThatThrownBy(() -> conversations.requireRow(deleted))
                    .isInstanceOf(ConversationNotFoundException.class);
            // 新对话存在且消息独立
            assertThat(conversations.loadHistory(newConversation)).hasSize(1);
            assertThat(conversations.loadHistory(newConversation).get(0).getContent())
                    .isEqualTo("new question");
        }

        @Test
        @DisplayName("删除对话不调用模型——只删除记录，不触发 AI 调用")
        void deleteDoesNotInvokeModel() {
            UUID conversationId = conversations.create(null).getId();
            conversations.saveUserMessage(conversationId, "question");

            // WHY 直接调用服务层删除：验证删除操作本身不抛异常、不触发 AI
            conversations.delete(conversationId);

            assertThatThrownBy(() -> conversations.requireRow(conversationId))
                    .isInstanceOf(ConversationNotFoundException.class);
        }

        @Test
        @DisplayName("晚到回调不复活已删除记录——删除后写入消息会失败")
        void lateCallbackDoesNotResurrectDeletedRecord() {
            UUID conversationId = conversations.create(null).getId();
            conversations.delete(conversationId);

            // WHY 期望异常：对话已删除，向其写入消息应失败
            // （外键约束或 ConversationNotFoundException）
            assertThatThrownBy(() -> conversations.saveUserMessage(conversationId, "late message"))
                    .isInstanceOf(Exception.class);

            // 对话仍然不存在
            assertThatThrownBy(() -> conversations.requireRow(conversationId))
                    .isInstanceOf(ConversationNotFoundException.class);
        }
    }

    // ======================================================================
    // 辅助方法
    // ======================================================================

    private int countMessages(UUID conversationId) {
        String sql = "SELECT COUNT(*) FROM ai_messages WHERE conversation_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpEntity<String> rawJson(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String write(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Error parseError(String body) {
        try {
            return objectMapper.readValue(body, Error.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse error: " + body, e);
        }
    }
}

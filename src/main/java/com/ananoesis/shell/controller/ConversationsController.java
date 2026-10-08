package com.ananoesis.shell.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.ananoesis.shell.contract.api.ConversationsApi;
import com.ananoesis.shell.contract.model.BatchDeleteRequest;
import com.ananoesis.shell.contract.model.Conversation;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.contract.model.MessageListResponse;
import com.ananoesis.shell.service.ConversationService;

/**
 * {@code /api/conversations} 的 HTTP 适配层（tasks 9.4 / 9.5 / 7.1-7.5）。
 *
 * <p>WHY 实现生成的 {@link ConversationsApi}：与 {@link ModelConfigsController} 同理——
 * 路由、HTTP 方法、{@code @Valid} 全部来自冻结契约，端点漂移在编译期就不可能发生。</p>
 *
 * <p>本类<b>只做转发</b>，这里出现任何业务判断都是分层被侵蚀的信号。
 * 会话不存在时的 404 由 {@code ConversationNotFoundException} 冒泡到
 * {@link ApiExceptionHandler} 统一翻译成契约错误体。</p>
 *
 * <h2>与 {@code /ws/ai} 的分工</h2>
 * <p>WebSocket 承载<b>流式过程</b>（增量、工具事件），本 REST 端点承载<b>历史检索</b>。
 * 用户刷新页面后，前端靠 {@code GET /api/conversations/{id}/messages} 重建整段对话——
 * 包括每次工具调用的名称、参数与结果（{@code tool_calls}），
 * 以及被拒绝的那一步（{@code result_status=rejected}、{@code result="用户已拒绝"}）。
 * ai-agent「操作透明性」因此不止在实时流里成立，事后回看同样成立。</p>
 *
 * <p><b>日志纪律</b>：MUST NOT 记录 {@link Message} 或提问正文——里面可能含用户粘贴的
 * 口令/密钥片段。生成物的 {@code toString()} 会原样打印所有字段。</p>
 */
@RestController
public class ConversationsController implements ConversationsApi {

    private final ConversationService conversationService;

    public ConversationsController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public ResponseEntity<Conversation> createConversation(ConversationCreate conversationCreate) {
        // 201 由契约声明（createConversation 的 responses 里 201 是唯一成功码）。
        // WHY 显式写状态而不是 ResponseEntity.ok()：生成接口的方法体是空的，
        // 状态码完全由实现方决定，写错不会有编译期提示，只会在集成测试里露出来
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.create(conversationCreate));
    }

    @Override
    public ResponseEntity<List<Conversation>> listConversations() {
        // WHY 不分页：冻结契约把返回类型定为裸数组。会话是低频对象
        // （一次排障一条），与高频的 approvals 审计行不同
        return ResponseEntity.ok(conversationService.list());
    }

    @Override
    public ResponseEntity<MessageListResponse> listConversationMessages(UUID id, String cursor, Integer limit) {
        // WHY 走 keyset 分页（tasks 7.3）：不先读全表再切片。
        // 契约默认 limit=50、最大 200，由生成接口的 @Min/@Max 约束保证。
        int effectiveLimit = limit != null ? limit : ConversationService.MSG_DEFAULT_LIMIT;
        ConversationService.KeysetPage<com.ananoesis.shell.contract.model.Message> page =
                conversationService.listMessagesPaged(id, cursor, effectiveLimit);
        MessageListResponse response = new MessageListResponse();
        response.setItems(page.items());
        response.setHasMore(page.hasMore());
        response.setNextCursor(page.nextCursor());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteConversation(UUID id) {
        // WHY 204 无响应体：契约声明删除成功只返回状态码，不含实体。
        // ConversationNotFoundException / ConflictException 由 ApiExceptionHandler 翻译为 404/409
        conversationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> batchDeleteConversations(BatchDeleteRequest batchDeleteRequest) {
        // WHY 200 而非 204：契约声明批量删除成功返回 200。
        // 全成功或全不删的原子性由 ConversationService.batchDelete 的事务保证
        conversationService.batchDelete(batchDeleteRequest.getIds());
        return ResponseEntity.ok().build();
    }
}

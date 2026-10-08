package com.ananoesis.shell.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
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
import com.ananoesis.shell.contract.model.Conversation;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.Message;
import com.ananoesis.shell.contract.model.MessageListResponse;
import com.ananoesis.shell.contract.model.MessageRole;
import com.ananoesis.shell.contract.model.ToolCallRecord;
import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.ws.ToolName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * tasks 9.4 的验收（HTTP 侧）：会话与历史消息的读写契约。
 *
 * <p>本测试盯的是三件最容易悄悄跑偏的事：</p>
 * <ol>
 *   <li><b>形状</b>——响应字段必须与冻结契约一字不差。多一个字段（例如把库里的
 *       {@code seq}/{@code tool_call_id} 直接漏出去）会让前端按契约生成的类型对不上，
 *       而且不报错；</li>
 *   <li><b>思考内容与回答内容的区分</b>——契约用 {@code thinking_content} 与 {@code content}
 *       两个字段承载，库里对应 {@code reasoning_content} 与 {@code content}。
 *       改名没做、或两者被拼成一坨，前端就没法把「思考过程」折叠起来；</li>
 *   <li><b>被拒绝的工具调用</b>——{@code result_status=rejected} 且 {@code result} 是
 *       spec 规定的「用户已拒绝」，并带上 {@code approval_id} 让前端能回链到审计页。</li>
 * </ol>
 *
 * <p>WHY 消息用 {@code ConversationService} 的写入方法造、而不是直接 INSERT：
 * 要验的正是「智能体写下的形状」能否被 REST 原样呈现。自己拼 SQL 会绕开
 * {@code tool_calls} 列那套 JSON 组装，一旦组装漏了 {@code approval_id}，本测试依然全绿。</p>
 */
class ConversationsApiIntegrationTest extends AbstractSqliteIntegrationTest {

    private static final String HOST_LABEL = "会话接口测试机";
    private static final String QUESTION = "帮我看看 nginx 为什么一直返回 502，先查错误日志再决定要不要重启";
    private static final String THINKING = "用户报的是 502，先看上游错误日志确认是超时还是连接被拒，再决定处置动作";
    private static final String ANSWER = "已确认是上游超时，建议重启 nginx 并观察 5 分钟";
    private static final String READ_FILE_RESULT = "exit=0\n2026-09-21 10:00:00 [error] upstream timed out\n";
    private static final String REJECTED_NOTE = "用户已拒绝";

    /** 契约 {@code Message} 声明的全部字段，一个不多一个不少。 */
    private static final List<String> MESSAGE_FIELDS =
            List.of("id", "conversation_id", "seq", "role", "content", "thinking_content",
                    "tool_calls", "source", "command_id", "run_id", "created_at");

    /** 契约 {@code Conversation} 声明的全部字段（V2 新增 session_id）。 */
    private static final List<String> CONVERSATION_FIELDS =
            List.of("id", "host_id", "session_id", "title", "created_at", "updated_at");

    private static final TypeReference<List<Conversation>> CONVERSATION_LIST = new TypeReference<>() {
    };

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private HostMapper hosts;

    // ======================================================================
    // POST /api/conversations
    // ======================================================================

    @Test
    @DisplayName("POST /api/conversations 空请求体 → 201，字段集合与契约完全一致")
    void createConversationReturns201WithExactlyTheContractFields() throws Exception {
        ResponseEntity<String> raw = post("{}");

        assertThat(raw.getStatusCode())
                .as("契约声明创建成功是 201，回 200 会让前端的「已创建」判定落空")
                .isEqualTo(HttpStatus.CREATED);
        Conversation created = parseConversation(raw.getBody());
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCreatedAt()).isNotNull();
        // Q7：host_id 可选，未绑定时就是 null，而不是编一个空串或随机值
        assertThat(created.getHostId()).isNull();
        assertThat(created.getTitle()).isNull();

        JsonNode tree = objectMapper.readTree(raw.getBody());
        assertThat(tree.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(CONVERSATION_FIELDS);
    }

    @Test
    @DisplayName("POST /api/conversations 带 host_id → 201 回显，且库里绑定的就是这台主机")
    void createConversationBindsTheGivenHost() {
        UUID hostId = insertHost(HOST_LABEL);

        ResponseEntity<String> raw = post(write(new ConversationCreate().hostId(hostId)));

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Conversation created = parseConversation(raw.getBody());
        assertThat(created.getHostId()).isEqualTo(hostId);
        assertThat(readConversationColumn(created.getId(), "host_id")).isEqualTo(hostId.toString());
    }

    @Test
    @DisplayName("POST /api/conversations 带 title → 原样保留，不被首条提问覆盖")
    void explicitTitleSurvivesTheFirstUserMessage() {
        Conversation created = parseConversation(
                post(write(new ConversationCreate().title("值班排查 2026-09-21"))).getBody());

        conversations.saveUserMessage(created.getId(), QUESTION);

        assertThat(titleOf(created.getId())).isEqualTo("值班排查 2026-09-21");
    }

    @Test
    @DisplayName("POST /api/conversations 的 host_id 指向不存在的主机 → 400 validation_error")
    void createConversationWithUnknownHostIsRejectedAs400() {
        ResponseEntity<String> raw = post(write(new ConversationCreate().hostId(UUID.randomUUID())));

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error error = parseError(raw.getBody());
        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(error.getDetails()).as("应指出是哪个字段无效").isNotEmpty();
        assertThat(error.getDetails().get(0).getField()).isEqualTo("host_id");
    }

    @Test
    @DisplayName("POST /api/conversations 请求体不是合法 JSON → 400 validation_error，而不是 500")
    void createConversationWithMalformedBodyIsRejectedAs400() {
        ResponseEntity<String> raw = post("this-is-not-json");

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parseError(raw.getBody()).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ======================================================================
    // GET /api/conversations
    // ======================================================================

    @Test
    @DisplayName("GET /api/conversations → 200 裸数组（契约无分页包装），最近更新在前")
    void listConversationsReturnsABareArrayNewestFirst() throws Exception {
        UUID own = insertHost(HOST_LABEL);
        Conversation older = parseConversation(post(write(new ConversationCreate().hostId(own))).getBody());
        Conversation newer = parseConversation(post(write(new ConversationCreate().hostId(own))).getBody());

        // 给「先创建的那个」写一条消息，让它的 updated_at 明确晚于另一个。
        // WHY 要等一秒：created_at/updated_at 在库里是 TEXT，同秒写入时排序会退到
        // created_at 与 id 上，断言就会依赖 JUnit 的方法执行顺序
        sleep(1100);
        conversations.saveUserMessage(older.getId(), QUESTION);

        ResponseEntity<String> raw = rest.getForEntity("/api/conversations", String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        // WHY 断言根节点是数组：契约的 listConversations 返回裸数组。
        // 若哪天有人「顺手」加上 {items,total} 包装，前端会静默拿到 undefined
        assertThat(objectMapper.readTree(raw.getBody()).isArray()).isTrue();

        List<Conversation> all = objectMapper.readValue(raw.getBody(), CONVERSATION_LIST);
        List<UUID> ids = all.stream().map(Conversation::getId).toList();
        assertThat(ids).contains(older.getId(), newer.getId());
        assertThat(ids.indexOf(older.getId()))
                .as("刚被写入消息的会话应排在前面")
                .isLessThan(ids.indexOf(newer.getId()));
    }

    // ======================================================================
    // GET /api/conversations/{id}/messages
    // ======================================================================

    @Test
    @DisplayName("GET /api/conversations/{id}/messages 会话不存在 → 404 not_found")
    void messagesOfUnknownConversationIs404() {
        ResponseEntity<String> raw = rest.getForEntity(
                "/api/conversations/" + UUID.randomUUID() + "/messages", String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parseError(raw.getBody()).getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/conversations/{id}/messages 的 id 不是合法 UUID → 400 而不是 500")
    void messagesOfMalformedIdIs400() {
        ResponseEntity<String> raw = rest.getForEntity("/api/conversations/not-a-uuid/messages", String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parseError(raw.getBody()).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("刚创建的会话没有消息 → 200 空响应（items 为空数组）")
    void emptyConversationReturnsAnEmptyArray() throws Exception {
        UUID id = parseConversation(post("{}").getBody()).getId();

        ResponseEntity<String> raw = rest.getForEntity("/api/conversations/" + id + "/messages", String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        // V2：消息响应从裸数组改为 {items, next_cursor, has_more} 包装
        JsonNode tree = objectMapper.readTree(raw.getBody());
        assertThat(tree.isObject()).as("V2 消息响应必须是对象（含 items 字段）").isTrue();
        assertThat(tree.has("items")).isTrue();
        assertThat(tree.get("items").isArray()).isTrue();
        assertThat(tree.get("items")).isEmpty();
    }

    @Test
    @DisplayName("一整轮对话（提问→思考→只读工具→副作用工具被拒）按 seq 顺序映射成契约形状")
    void aFullRoundMapsOntoTheContractShapes() {
        UUID hostId = insertHost(HOST_LABEL);
        UUID conversationId = parseConversation(
                post(write(new ConversationCreate().hostId(hostId))).getBody()).getId();
        UUID approvalId = UUID.randomUUID();

        conversations.saveUserMessage(conversationId, QUESTION);
        conversations.saveAssistantMessage(conversationId, null, THINKING, List.of(
                Map.of("id", "call_1",
                        "name", ToolName.READ_FILE.getValue(),
                        "arguments", "{\"path\":\"/var/log/nginx/error.log\"}")));
        conversations.saveToolMessage(conversationId, "call_1", ToolName.READ_FILE.getValue(),
                Map.of("path", "/var/log/nginx/error.log"),
                ToolResultStatus.SUCCESS, READ_FILE_RESULT, null, false);
        conversations.saveAssistantMessage(conversationId, ANSWER, THINKING, List.of(
                Map.of("id", "call_2",
                        "name", ToolName.RUN_COMMAND.getValue(),
                        "arguments", "{\"command\":\"systemctl restart nginx\"}",
                        "approval_id", approvalId.toString())));
        conversations.saveToolMessage(conversationId, "call_2", ToolName.RUN_COMMAND.getValue(),
                Map.of("command", "systemctl restart nginx"),
                ToolResultStatus.REJECTED, REJECTED_NOTE, approvalId, true);

        List<Message> messages = fetch(conversationId);

        // WHY 先断言条数与角色序列：seq 顺序错了的话，后面每条断言都会「碰巧」对上别的行，
        // 失败信息会变成一堆看不懂的内容不匹配
        assertThat(messages).hasSize(5);
        assertThat(messages).extracting(Message::getRole).containsExactly(
                MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL,
                MessageRole.ASSISTANT, MessageRole.TOOL);
        for (Message message : messages) {
            assertThat(message.getId()).isNotNull();
            assertThat(message.getConversationId()).isEqualTo(conversationId);
            assertThat(message.getCreatedAt()).isNotNull();
        }

        Message question = messages.get(0);
        assertThat(question.getContent()).isEqualTo(QUESTION);
        assertThat(question.getThinkingContent()).as("用户消息没有思考过程").isNull();
        assertThat(question.getToolCalls()).isEmpty();

        Message proposal = messages.get(1);
        assertThat(proposal.getThinkingContent())
                .as("契约用 thinking_content 承载模型的思考过程，与 content 分列")
                .isEqualTo(THINKING);
        assertThat(proposal.getContent()).as("这一轮只提议了工具调用，没有正文回答").isNull();
        // WHY 断言 assistant 行不带 tool_calls：该行的库里存的是模型<b>提议</b>形状
        // （id/name/arguments），而契约 ToolCallRecord 把 tool_name/tool_params/result_status
        // 列为 required。硬映射过去只会产出一堆字段为 null 的非法记录；
        // 完整的调用明细由紧随其后的 tool 消息承载
        assertThat(proposal.getToolCalls())
                .as("assistant 行不得把「提议」伪装成契约的调用记录")
                .isEmpty();

        Message readOnly = messages.get(2);
        assertThat(readOnly.getToolCalls()).hasSize(1);
        ToolCallRecord readRecord = readOnly.getToolCalls().get(0);
        assertThat(readRecord.getToolName()).isEqualTo(ToolName.READ_FILE.getValue());
        assertThat(readRecord.getToolParams()).containsEntry("path", "/var/log/nginx/error.log");
        assertThat(readRecord.getResultStatus()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(readRecord.getResult()).isEqualTo(READ_FILE_RESULT);
        assertThat(readRecord.getApprovalId()).as("只读工具不经审批，不该有 approval_id").isNull();
        assertThat(readOnly.getContent())
                .as("tool 行的正文就是工具结果，前端不展开 tool_calls 也能看到这一步得到了什么")
                .isEqualTo(READ_FILE_RESULT);
    }

    @Test
    @DisplayName("被用户拒绝的副作用工具：result_status=rejected、result=「用户已拒绝」、带 approval_id")
    void rejectedToolCallCarriesTheSpecNoteAndItsApprovalId() {
        UUID conversationId = parseConversation(post("{}").getBody()).getId();
        UUID approvalId = UUID.randomUUID();

        conversations.saveToolMessage(conversationId, "call_9", ToolName.RUN_COMMAND.getValue(),
                Map.of("command", "rm -rf /var/lib/mysql"),
                ToolResultStatus.REJECTED, REJECTED_NOTE, approvalId, true);

        Message rejected = fetch(conversationId).get(0);
        assertThat(rejected.getToolCalls()).hasSize(1);
        ToolCallRecord record = rejected.getToolCalls().get(0);
        assertThat(record.getToolName()).isEqualTo(ToolName.RUN_COMMAND.getValue());
        assertThat(record.getResultStatus()).isEqualTo(ToolResultStatus.REJECTED);
        assertThat(record.getResult()).isEqualTo(REJECTED_NOTE);
        assertThat(record.getApprovalId())
                .as("前端要靠它把这条拒绝回链到审计页")
                .isEqualTo(approvalId);
        assertThat(record.getToolParams()).containsEntry("command", "rm -rf /var/lib/mysql");
    }

    @Test
    @DisplayName("消息响应只用契约字段名：库里的内部列不得漏出去")
    void messageJsonUsesOnlyContractFieldNames() throws Exception {
        UUID conversationId = parseConversation(post("{}").getBody()).getId();
        conversations.saveUserMessage(conversationId, QUESTION);
        conversations.saveAssistantMessage(conversationId, ANSWER, THINKING, null);
        conversations.saveToolMessage(conversationId, "call_1", ToolName.SYSTEM_INFO.getValue(),
                Map.of(), ToolResultStatus.SUCCESS, "exit=0\n", null, false);

        ResponseEntity<String> raw = rest.getForEntity(
                "/api/conversations/" + conversationId + "/messages", String.class);
        String body = raw.getBody();

        // V2：响应从裸数组改为 {items: [...], next_cursor, has_more}
        JsonNode itemsArray = objectMapper.readTree(body).get("items");
        for (JsonNode node : itemsArray) {
            assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(MESSAGE_FIELDS);
        }
        // WHY 逐个点名这些列：它们是 ai_messages 表里真实存在、但对客户端毫无意义的内部字段。
        // 把 reasoning_content 原样漏出去（而不是改名成 thinking_content）不会让任何测试变红，
        // 只会让前端的「思考过程」面板永远空白
        assertThat(body)
                .doesNotContain("reasoning_content")
                .doesNotContain("tool_call_id")
                .doesNotContain("tool_arguments")
                .doesNotContain("tool_result")
                .doesNotContain("tool_rejected");
    }

    // ======================================================================
    // 标题摘要
    // ======================================================================

    @Test
    @DisplayName("未命名会话的标题取首条提问的摘要，超长时截断并加省略号")
    void titleIsSummarizedFromTheFirstUserMessage() {
        UUID conversationId = parseConversation(post("{}").getBody()).getId();

        conversations.saveUserMessage(conversationId, QUESTION);

        // QUESTION 未超过 40 字符的上限，应原样成为标题
        assertThat(titleOf(conversationId)).isEqualTo(QUESTION);

        String longQuestion = "第一行\n第二行  第三行" + "x".repeat(80);
        UUID other = parseConversation(post("{}").getBody()).getId();
        conversations.saveUserMessage(other, longQuestion);

        String title = titleOf(other);
        assertThat(title).endsWith("…");
        assertThat(title).as("40 个字符 + 省略号").hasSize(41);
        assertThat(title).as("换行与连续空白应被折叠成一个空格").doesNotContain("\n").doesNotContain("  ");
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    /** V2：消息响应从 {@code List<Message>} 改为 {@code MessageListResponse}（含 items/next_cursor/has_more）。 */
    private List<Message> fetch(UUID conversationId) {
        ResponseEntity<String> raw = rest.getForEntity(
                "/api/conversations/" + conversationId + "/messages", String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            MessageListResponse response = objectMapper.readValue(raw.getBody(), MessageListResponse.class);
            return response.getItems();
        } catch (Exception e) {
            throw new IllegalStateException("无法解析消息响应体: " + raw.getBody(), e);
        }
    }

    private ResponseEntity<String> post(String jsonBody) {
        return rest.exchange("/api/conversations", HttpMethod.POST, rawJson(jsonBody), String.class);
    }

    private HttpEntity<String> rawJson(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String write(ConversationCreate request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    private Conversation parseConversation(String body) {
        try {
            return objectMapper.readValue(body, Conversation.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析响应体: " + body, e);
        }
    }

    private Error parseError(String body) {
        try {
            return objectMapper.readValue(body, Error.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析错误响应体: " + body, e);
        }
    }

    /**
     * 直接读库取会话的某一列。
     *
     * <p>WHY 走原生 JDBC 而不是复用 {@code AiConversationMapper}：
     * 用被测代码验证被测代码，等于让「映射漏了一列」这类缺陷自己给自己判无罪。</p>
     */
    private String readConversationColumn(UUID conversationId, String column) {
        String sql = "SELECT " + column + " FROM ai_conversations WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取会话行: " + conversationId, e);
        }
    }

    private String titleOf(UUID conversationId) {
        return readConversationColumn(conversationId, "title");
    }

    private UUID insertHost(String name) {
        Host row = new Host();
        UUID id = UUID.randomUUID();
        // WHY 显式 setId：实体主键策略是 ASSIGN_UUID，MyBatis-Plus 会生成无连字符的 32 位串，
        // 与全链路使用的 UUID#toString() 对不上，会话就再也关联不到这台机器
        row.setId(id.toString());
        row.setName(name);
        row.setHost("127.0.0.1");
        row.setPort(22);
        row.setUsername("ops");
        row.setAuthType("password");
        hosts.insert(row);
        return id;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}

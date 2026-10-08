package com.ananoesis.shell.ws;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link AiStreamFrame} 的行为测试。
 *
 * <p>WHY 与 {@code WsContractAlignmentTest} 分开：那边钉的是<b>形状</b>
 * （字段名集合、枚举取值、required 与 @Nullable 的对应关系），是"契约漂移"的守卫；
 * 这边钉的是<b>行为</b>——工厂方法填了什么、没填什么、什么情况下拒绝构造。
 * 两类失败的原因和修法完全不同，混在一起时一条断言红了要先猜它属于哪一类。</p>
 *
 * <p>WHY 用"序列化后的 JSON 键"断言而不是读 record 访问器：
 * record 上挂了 {@code @JsonInclude(NON_NULL)}，未填的组件<b>不会</b>出现在线上帧里。
 * 断言 {@code frame.content() == null} 与断言"线上没有 content 键"是两件事——
 * 后者才是前端真正会看到的形态。若哪天有人把 NON_NULL 改成 ALWAYS，
 * 一堆 {@code null} 字段会涌进每一帧，只有这类断言会变红。</p>
 */
class AiStreamFrameTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final UUID CONVERSATION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * 契约对 {@code AiStream} 声明 {@code required: [type, conversation_id]}。
     *
     * <p>因为本 record 双向复用同一 schema（Q2），Java 侧所有组件都标了
     * {@code @Nullable}，required 义务就落到了<b>静态工厂</b>上。
     * 本用例逐个调用出站工厂并检查序列化结果——它守的是"某条出站路径忘了填 conversation_id"
     * 这种缺陷：编译不会报错、契约对齐测试也照样绿（形状是对的），
     * 但前端拿到一个没有会话归属的增量帧，会把它渲染到错误的会话里，或在多会话界面上直接丢弃。</p>
     */
    @Test
    @DisplayName("每个出站工厂产出的 JSON 都含契约 required 的 type 与 conversation_id")
    void everyOutboundFactoryPopulatesContractRequiredFields() {
        Map<String, AiStreamFrame> outbound = new LinkedHashMap<>();
        outbound.put("thinkingDelta", AiStreamFrame.thinkingDelta(CONVERSATION, "让我想想"));
        outbound.put("answerDelta", AiStreamFrame.answerDelta(CONVERSATION, "结论是"));
        outbound.put("toolCall(auto)", AiStreamFrame.toolCall(CONVERSATION,
                ToolCallEventFrame.autoExecuted(ToolName.LIST_DIR, Map.of("path", "/etc"))));
        outbound.put("toolCall(pending)", AiStreamFrame.toolCall(CONVERSATION,
                ToolCallEventFrame.pendingApproval(ToolName.RUN_COMMAND, Map.of("command", "rm -rf /tmp/x"),
                        UUID.randomUUID())));
        outbound.put("toolResult", AiStreamFrame.toolResult(CONVERSATION,
                ToolCallEventFrame.result(ToolName.READ_FILE, Map.of("path", "/etc/hosts"), true,
                        null, ToolResultStatus.SUCCESS, "内容")));
        outbound.put("finished", AiStreamFrame.finished(CONVERSATION, UUID.randomUUID(), "stop"));
        outbound.put("error", AiStreamFrame.error(CONVERSATION, ErrorCode.API_KEY_MISSING,
                "请先在设置中配置模型 api key"));

        for (Map.Entry<String, AiStreamFrame> entry : outbound.entrySet()) {
            JsonNode json = writeValue(entry.getValue());
            assertThat(json.hasNonNull("type"))
                    .as("%s 产出的帧缺少 required 字段 type", entry.getKey()).isTrue();
            assertThat(json.hasNonNull("conversation_id"))
                    .as("%s 产出的帧缺少 required 字段 conversation_id", entry.getKey()).isTrue();
            assertThat(json.get("conversation_id").asText())
                    .as("%s 产出的帧 conversation_id 不是传入值", entry.getKey())
                    .isEqualTo(CONVERSATION.toString());
        }
    }

    @Test
    @DisplayName("conversationId 为 null 时工厂直接抛错，而不是发一帧没有归属的增量")
    void factoriesRejectNullConversationId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiStreamFrame.thinkingDelta(null, "x"))
                .withMessageContaining("conversationId");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiStreamFrame.answerDelta(null, "x"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiStreamFrame.finished(null, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiStreamFrame.error(null, ErrorCode.INTERNAL_ERROR, "x"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiStreamFrame.toolCall(null,
                        ToolCallEventFrame.autoExecuted(ToolName.SYSTEM_INFO, Map.of())));
    }

    /**
     * 思考/非思考双模式（tasks 7.3）在线上的区分靠 {@code segment}：
     * 前端把 {@code segment=thinking} 折叠灰显、{@code segment=answer} 正文渲染。
     * 两者对调的症状是"思考过程被当成最终回答展示"——用户会看到模型的自我怀疑被当成结论。
     */
    @Test
    @DisplayName("thinking_delta 带 segment=thinking，answer_delta 带 segment=answer")
    void deltasCarryTheirSegment() {
        JsonNode thinking = writeValue(AiStreamFrame.thinkingDelta(CONVERSATION, "先看目录"));
        assertThat(thinking.get("type").asText()).isEqualTo("thinking_delta");
        assertThat(thinking.get("segment").asText()).isEqualTo("thinking");
        assertThat(thinking.get("content").asText()).isEqualTo("先看目录");

        JsonNode answer = writeValue(AiStreamFrame.answerDelta(CONVERSATION, "目录内容如下"));
        assertThat(answer.get("type").asText()).isEqualTo("answer_delta");
        assertThat(answer.get("segment").asText()).isEqualTo("answer");
        assertThat(answer.get("content").asText()).isEqualTo("目录内容如下");
    }

    /**
     * WHY 断言"未填的字段不出现"：{@code NON_NULL} 让每一帧都只带自己需要的字段。
     * 若把它改成 ALWAYS，增量帧会带上 {@code tool_call: null}、{@code error_code: null}，
     * 前端那些用 {@code 'tool_call' in frame} 判断分支的代码会全部走错分支——
     * 一个纯序列化配置的改动，症状却出现在业务分派上，非常难查。
     */
    @Test
    @DisplayName("增量帧只带 type/conversation_id/content/segment 四个键")
    void deltaFrameOmitsIrrelevantFields() {
        JsonNode json = writeValue(AiStreamFrame.answerDelta(CONVERSATION, "hi"));
        assertThat(json.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("type", "conversation_id", "content", "segment");
    }

    @Test
    @DisplayName("工具帧把明细放在 tool_call 子对象里，且 auto/approval_id 按分级填")
    void toolFramesNestToolCallEvent() {
        JsonNode auto = writeValue(AiStreamFrame.toolCall(CONVERSATION,
                ToolCallEventFrame.autoExecuted(ToolName.LIST_DIR, Map.of("path", "/etc"))));
        assertThat(auto.get("type").asText()).isEqualTo("tool_call");
        JsonNode autoCall = auto.get("tool_call");
        assertThat(autoCall.get("tool_name").asText()).isEqualTo("list_dir");
        assertThat(autoCall.get("auto").asBoolean()).isTrue();
        assertThat(autoCall.has("approval_id"))
                .as("只读工具不经审批，不应出现 approval_id").isFalse();
        assertThat(autoCall.get("tool_params").get("path").asText()).isEqualTo("/etc");

        UUID approvalId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        JsonNode gated = writeValue(AiStreamFrame.toolCall(CONVERSATION,
                ToolCallEventFrame.pendingApproval(ToolName.RUN_COMMAND,
                        Map.of("command", "systemctl restart nginx"), approvalId)));
        JsonNode gatedCall = gated.get("tool_call");
        assertThat(gatedCall.get("tool_name").asText()).isEqualTo("run_command");
        assertThat(gatedCall.get("auto").asBoolean()).isFalse();
        assertThat(gatedCall.get("approval_id").asText()).isEqualTo(approvalId.toString());
    }

    /**
     * spec 原文要求被拒绝的工具调用回喂「用户已拒绝」，
     * 且状态是 {@code rejected} 而不是 {@code error}。
     *
     * <p>WHY 严格区分：{@code error} 在模型看来是"工具坏了，也许换个姿势再试一次"，
     * 它可能反复重试同一条命令；{@code rejected} 是"人类明确说不"，
     * 模型应当据此调整方案。用错状态会让智能体在被拒绝后不停骚扰审批弹框。</p>
     */
    @Test
    @DisplayName("拒绝帧：result_status=rejected 且 result 为「用户已拒绝」")
    void rejectedToolResultCarriesRejectedStatus() {
        UUID approvalId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        JsonNode json = writeValue(AiStreamFrame.toolResult(CONVERSATION,
                ToolCallEventFrame.result(ToolName.RUN_COMMAND, Map.of("command", "rm -rf /"), false,
                        approvalId, ToolResultStatus.REJECTED, "用户已拒绝")));
        assertThat(json.get("type").asText()).isEqualTo("tool_result");
        JsonNode call = json.get("tool_call");
        assertThat(call.get("result_status").asText()).isEqualTo("rejected");
        assertThat(call.get("result").asText()).isEqualTo("用户已拒绝");
        assertThat(call.get("approval_id").asText()).isEqualTo(approvalId.toString());
    }

    /**
     * tasks 7.2 规定的 api key 缺失文案是逐字冻结的（spec 原文），
     * 前端按 {@code error_code=api_key_missing} 跳设置页，文案则直接展示给用户。
     */
    @Test
    @DisplayName("api_key_missing 错误帧使用 spec 规定的文案")
    void missingApiKeyErrorFrameUsesSpecMessage() {
        JsonNode json = writeValue(AiStreamFrame.error(CONVERSATION, ErrorCode.API_KEY_MISSING,
                "请先在设置中配置模型 api key"));
        assertThat(json.get("type").asText()).isEqualTo("error");
        assertThat(json.get("error_code").asText()).isEqualTo("api_key_missing");
        assertThat(json.get("message").asText()).isEqualTo("请先在设置中配置模型 api key");
    }

    @Test
    @DisplayName("错误帧不带 error_code 会被拒绝构造")
    void errorFrameRequiresErrorCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiStreamFrame.error(CONVERSATION, null, "出错了"))
                .withMessageContaining("error_code");
    }

    @Test
    @DisplayName("final 帧带 message_id 与 finish_reason；两者可缺省")
    void finalFrameCarriesMessageIdAndFinishReason() {
        UUID messageId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        JsonNode json = writeValue(AiStreamFrame.finished(CONVERSATION, messageId, "stop"));
        assertThat(json.get("type").asText()).isEqualTo("final");
        assertThat(json.get("message_id").asText()).isEqualTo(messageId.toString());
        assertThat(json.get("finish_reason").asText()).isEqualTo("stop");

        JsonNode bare = writeValue(AiStreamFrame.finished(CONVERSATION, null, null));
        assertThat(bare.has("message_id")).isFalse();
        assertThat(bare.has("finish_reason")).isFalse();
    }

    @Test
    @DisplayName("with* 复制方法保留原有字段，只补充目标字段")
    void copyMethodsPreserveOtherFields() {
        AiStreamFrame base = AiStreamFrame.answerDelta(CONVERSATION, "hi");
        UUID hostId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID messageId = UUID.fromString("77777777-7777-7777-7777-777777777777");

        AiStreamFrame enriched = base.withThinkingMode(true).withHostId(hostId).withMessageId(messageId);

        JsonNode json = writeValue(enriched);
        assertThat(json.get("type").asText()).isEqualTo("answer_delta");
        assertThat(json.get("content").asText()).isEqualTo("hi");
        assertThat(json.get("segment").asText()).isEqualTo("answer");
        assertThat(json.get("thinking_mode").asBoolean()).isTrue();
        assertThat(json.get("host_id").asText()).isEqualTo(hostId.toString());
        assertThat(json.get("message_id").asText()).isEqualTo(messageId.toString());
    }

    /**
     * 入站方向：客户端上行的 {@code type=user_message} 帧必须能被解析回来（Q2）。
     *
     * <p>WHY 单独验证反序列化：出站工厂从不产出 {@code USER_MESSAGE}，
     * 若 Jackson 的命名策略与 {@code @JsonProperty} 在这个方向上出了偏差
     * （例如某个字段被 {@code @JsonNaming} 转成了 {@code conversationId}），
     * 出站测试全绿而入站帧全部解析失败——症状是"发消息毫无反应"。</p>
     */
    @Test
    @DisplayName("客户端上行的 user_message 帧可被解析（含 conversation_id/content/host_id）")
    void inboundUserMessageFrameIsParsed() throws Exception {
        String wire = "{\"type\":\"user_message\",\"conversation_id\":\"" + CONVERSATION
                + "\",\"content\":\"看看磁盘\",\"host_id\":\"66666666-6666-6666-6666-666666666666\"}";

        AiStreamFrame parsed = mapper.readValue(wire, AiStreamFrame.class);

        assertThat(parsed.type()).isEqualTo(AiStreamFrame.Type.USER_MESSAGE);
        assertThat(parsed.conversationId()).isEqualTo(CONVERSATION);
        assertThat(parsed.content()).isEqualTo("看看磁盘");
        assertThat(parsed.hostId()).isEqualTo(UUID.fromString("66666666-6666-6666-6666-666666666666"));
    }

    /**
     * 同 {@link TerminalInput} 的理由：字段缺失的帧必须走到处理器的校验分支，
     * 由它按契约回 {@code type=error, error_code=validation_error}，
     * 而不是在 Jackson 阶段抛异常导致连接被容器关掉。
     */
    @Test
    @DisplayName("字段缺失/多余的入站帧不抛异常，交由处理器判为 validation_error")
    void inboundFrameToleratesMissingAndUnknownFields() throws Exception {
        assertThat(mapper.readValue("{}", AiStreamFrame.class).type()).isNull();
        assertThat(mapper.readValue("{\"type\":\"error\"}", AiStreamFrame.class).conversationId()).isNull();
        // 前端若按更新版契约多发了字段，后端必须容忍而不是断线
        assertThat(mapper.readValue("{\"type\":\"final\",\"conversation_id\":\"" + CONVERSATION
                + "\",\"some_future_field\":1}", AiStreamFrame.class).type())
                .isEqualTo(AiStreamFrame.Type.FINAL);
    }

    private JsonNode writeValue(AiStreamFrame frame) {
        try {
            return mapper.readTree(mapper.writeValueAsString(frame));
        } catch (Exception e) {
            throw new AssertionError("序列化 ai_stream 帧失败: " + frame, e);
        }
    }
}

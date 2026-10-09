package com.ananoesis.shell.ai;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.web.client.ResourceAccessException;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.approval.ApprovalAuditService;
import com.ananoesis.shell.approval.ApprovalGate;
import com.ananoesis.shell.approval.ApprovedCommandRunner;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.Message;
import com.ananoesis.shell.contract.model.MessageRole;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.security.MissingModelApiKeyException;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ssh.PtyCommandGateway;
import com.ananoesis.shell.ssh.PtyCommandScheduler;
import com.ananoesis.shell.ssh.SshExecService;
import com.ananoesis.shell.support.FakeSshServer;
import com.ananoesis.shell.support.RecordingAiStreamEmitter;
import com.ananoesis.shell.support.ScriptedChatModel;
import com.ananoesis.shell.support.StaticObjectProvider;
import com.ananoesis.shell.support.StubChatModelProvider;
import static com.ananoesis.shell.support.TestWait.until;
import com.ananoesis.shell.ws.AiStreamFrame;
import com.ananoesis.shell.ws.ApprovalResponseFrame;
import com.ananoesis.shell.ws.ToolCallEventFrame;
import com.ananoesis.shell.ws.ToolName;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 智能体回合循环的核心行为（tasks 7.2 / 7.3 / 7.4 / 9.1 / 9.2 / 9.4 / 9.5）。
 *
 * <h2>WHY 是集成测试而不是纯单元测试</h2>
 * <p>回合循环的产物<b>一半在帧里、一半在库里</b>：帧证明"用户看到了什么"，
 * {@code ai_messages} 行证明"刷新页面后还能看到什么"。这两者必须同时成立，
 * 否则会出现"流式回答正常显示、历史面板一片空白"这类只有集成才暴露的缺陷。
 * 因此这里用真 SQLite + 真 {@code ConversationService} + 真 {@code ApprovalGate}，
 * 只把<b>模型端点</b>与<b>远端 SSH</b>换成替身——那两样是唯一无法在测试里真实存在的东西。</p>
 *
 * <h2>WHY 手工 new 被测对象而不注入容器里的 bean</h2>
 * <p>容器里的 {@code AiAgentService} 装配的是真 {@code ChatModelProvider}
 * （它会去读 {@code model_configs} 表并尝试连真实端点）与真线程池。
 * 手工 new 才能：① 塞进剧本模型；② 用同步执行器，让"提交→断言帧序列"不必猜时序。
 * 这正是 {@code AiAgentService} 保留包级全参构造器与包级 {@code runTurn} 的原因。</p>
 *
 * <h2>WHY 审批相关的用例不在本类</h2>
 * <p>它们要跨线程协作（一个线程阻塞在 {@code gate.await}、另一个线程裁决），
 * 与本类"同步跑完一个回合再断言"的节奏完全不同，混在一起会让两类失败互相干扰。
 * 见 {@code AiAgentApprovalTest}。</p>
 *
 * <h2>WHY 有些用例刻意不绑定服务器</h2>
 * <p>{@code FakeSshServer} 的迷你解释器只认十来条命令，{@code ls}/{@code sed} 一律回 127。
 * 对"轮次上限""参数非法"这类<b>与远端无关</b>的用例，走"未绑定服务器"分支
 * 既断言到同一套路由逻辑，又免去几十次（上限轮数）真实 SSH 往返的等待。</p>
 */
class AiAgentServiceTest extends AbstractSqliteIntegrationTest {

    private static final String HOST_LABEL = "AI 测试机";

    /**
     * 内联思考标签的开/闭对：用拼接构造而非字面量（与模型真实输出同语义，
     * 但避免与本文件工具链对成对尖括号标记的处理冲突）。
     */
    static final String THINK_OPEN = "<" + "think" + ">";
    static final String THINK_CLOSE = "<" + "/" + "think" + ">";

    private static FakeSshServer fake;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private SettingsService settings;

    @Autowired
    private ApprovalAuditService audit;

    @Autowired
    private ApprovalGate gate;

    @Autowired
    private HostMapper hosts;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private SshExecService exec;
    private RecordingAiStreamEmitter emitter;
    private UUID hostId;
    private UUID conversationId;

    @BeforeAll
    static void startFakeServer() throws IOException {
        fake = FakeSshServer.start();
    }

    @AfterAll
    static void stopFakeServer() {
        fake.close();
    }

    @BeforeEach
    void wireAgentCollaborators() {
        exec = AgentTestWiring.execServiceOn(fake);
        emitter = new RecordingAiStreamEmitter();
        hostId = AgentTestWiring.insertHost(hosts, HOST_LABEL);
        conversationId = newConversation(hostId);
    }

    // ==================================================================
    // 9.1 工具集与分级
    // ==================================================================

    @Test
    @DisplayName("注册的工具名与契约 ToolName 枚举逐一对应（工具集与分级表不得漂移）")
    void registeredToolNamesMatchContractToolNameEnum() {
        AiAgentService agent = agent(ScriptedChatModel.builder().answer("好").build());

        List<String> expected = new ArrayList<>();
        for (ToolName name : ToolName.values()) {
            expected.add(name.getValue());
        }

        assertThat(agent.registeredToolNames())
                .as("@Tool 声明与契约枚举必须一一对应：多一个说明有工具没进契约，"
                        + "少一个说明契约里的工具模型看不见")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("只读工具自动执行：auto=true、无 approval_id，且命令确实经 exec 通道落到远端")
    void readOnlyToolIsAutoExecutedOverExecChannel() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "list_dir", "{\"path\":\"/var/log/nginx\"}")
                .answer("目录已列出")
                .build();
        int execBefore = fake.execCommands().size();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "看看 nginx 日志目录"));

        ToolCallEventFrame call = emitter.only(AiStreamFrame.Type.TOOL_CALL).toolCall();
        assertThat(call.toolName()).isEqualTo(ToolName.LIST_DIR);
        assertThat(call.auto()).as("只读工具必须标记为自动执行").isTrue();
        assertThat(call.approvalId()).as("只读工具不经审批，不该有 approval_id").isNull();

        ToolCallEventFrame result = emitter.only(AiStreamFrame.Type.TOOL_RESULT).toolCall();
        assertThat(result.resultStatus())
                .as("迷你解释器不认 ls，但工具本身正常工作了：远端回 127 是一个事实，"
                        + "不是基础设施故障，因此仍是 success（见 CommandExecution 的类注释）")
                .isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.result()).startsWith("exit=127").contains("command not found");

        assertThat(fake.execCommands().subList(execBefore, fake.execCommands().size()))
                .as("list_dir 必须以长格式 ls 落到远端，且路径被单引号包裹（防 shell 注入）")
                .containsExactly("ls -Al --time-style=long-iso '/var/log/nginx'");

        assertThat(approvalRowCount(conversationId))
                .as("只读工具 MUST NOT 产生审批审计行（TRACEABILITY Q8）").isZero();
    }

    @Test
    @DisplayName("未绑定服务器的会话：不下发任何工具，系统提示改为「无工具」段（TRACEABILITY Q7）")
    void sessionWithoutHostReceivesNoTools() {
        UUID bare = newConversation(null);
        StubChatModelProvider provider = new StubChatModelProvider(
                ScriptedChatModel.builder().answer("nginx 502 通常先看上游").build());

        agentFor(provider).runTurn(new TurnRequest(bare, null, "nginx 502 一般怎么排查"));

        assertThat(provider.everReceivedEmptyToolCallbacks())
                .as("没有目标机器就没有可执行的工具，MUST 传空工具表")
                .isTrue();
        String systemPrompt = systemPromptOf(provider, 0);
        assertThat(systemPrompt).contains("没有绑定目标服务器");
        assertThat(systemPrompt).doesNotContain("当前目标服务器");
        assertThat(emitter.only(AiStreamFrame.Type.FINAL).conversationId()).isEqualTo(bare);
    }

    @Test
    @DisplayName("模型在未绑定服务器的会话里仍索要工具时被拦住，绝不去连任何机器")
    void toolCallWithoutHostIsRefusedInsteadOfExecuted() {
        UUID bare = newConversation(null);
        int execBefore = fake.execCommands().size();
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "system_info", "{}")
                .answer("我无法取证")
                .build();

        agent(model).runTurn(new TurnRequest(bare, null, "看看这台机器怎么样"));

        ToolCallEventFrame result = emitter.only(AiStreamFrame.Type.TOOL_RESULT).toolCall();
        assertThat(result.resultStatus()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.result()).contains("未绑定目标服务器");
        assertThat(fake.execCommands()).as("一条命令都不该发出去").hasSize(execBefore);
    }

    // ==================================================================
    // 7.3 思考模式
    // ==================================================================

    @Test
    @DisplayName("思考模式：reasoning_content 归 thinking_delta/segment=thinking，content 归 answer_delta/segment=answer")
    void thinkingModeSplitsReasoningFromAnswer() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thinkingAnswer(new String[] {"先看日志，", "再看上游。"},
                        new String[] {"建议执行 ", "tail -n 100。"})
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "502 怎么查"));

        List<AiStreamFrame> thinking = emitter.ofType(AiStreamFrame.Type.THINKING_DELTA);
        assertThat(thinking).as("思考增量必须分片到达，而不是聚合成一坨").hasSize(2);
        assertThat(thinking).allSatisfy(frame ->
                assertThat(frame.segment()).isEqualTo(AiStreamFrame.Segment.THINKING));
        assertThat(emitter.joinedContent(AiStreamFrame.Type.THINKING_DELTA)).isEqualTo("先看日志，再看上游。");

        List<AiStreamFrame> answers = emitter.ofType(AiStreamFrame.Type.ANSWER_DELTA);
        assertThat(answers).hasSize(2);
        assertThat(answers).allSatisfy(frame ->
                assertThat(frame.segment()).isEqualTo(AiStreamFrame.Segment.ANSWER));
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA)).isEqualTo("建议执行 tail -n 100。");
    }

    @Test
    @DisplayName("思考过程与最终回答分别落到 reasoning_content / content 两列")
    void thinkingContentIsPersistedSeparatelyFromAnswer() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .thinkingAnswer(new String[] {"推理过程"}, new String[] {"最终回答"})
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        Message assistant = lastAssistantMessage(conversationId);
        assertThat(assistant.getContent()).isEqualTo("最终回答");
        assertThat(assistant.getThinkingContent()).as("契约 Message.thinking_content 必须承载思考过程")
                .isEqualTo("推理过程");
    }

    @Test
    @DisplayName("内联 think 标签（Qwen3 类自托管模型）：标签内归 thinking、标签外归 answer，标签本身不展示")
    void inlineThinkTagsAreSplitIntoThinkingSegments() {
        // 真实形状：MindIE/vLLM 部署的 Qwen3 不走 reasoning_content 字段，
        // 而是把思考以 think 开闭标签内联在 content 里逐字流出（桌面壳实测截图实证）
        ScriptedChatModel model = ScriptedChatModel.builder()
                .round(ScriptedChatModel.content(THINK_OPEN + "先看端口再看内存"),
                        ScriptedChatModel.content(THINK_CLOSE + "结论：重启即可"))
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "服务挂了怎么办"));

        assertThat(emitter.joinedContent(AiStreamFrame.Type.THINKING_DELTA))
                .as("标签内文本必须作为思考增量到达").isEqualTo("先看端口再看内存");
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA))
                .as("标签外是回答，且标签本身不得漏给用户").isEqualTo("结论：重启即可");

        Message assistant = lastAssistantMessage(conversationId);
        assertThat(assistant.getContent()).isEqualTo("结论：重启即可");
        assertThat(assistant.getThinkingContent())
                .as("落库要与 reasoning_content 列同构，历史面板才分得开").isEqualTo("先看端口再看内存");
    }

    @Test
    @DisplayName("内联标签被 SSE 分片拦腰截断：半截标签不得泄漏到任何一帧")
    void inlineThinkTagSplitAcrossChunksDoesNotLeak() {
        // 分片边界正好落在标签中间，解析器必须缓存待定前缀
        String openHead = THINK_OPEN.substring(0, THINK_OPEN.length() - 1);
        String openTail = THINK_OPEN.substring(THINK_OPEN.length() - 1);
        String closeHead = THINK_CLOSE.substring(0, THINK_CLOSE.length() - 3);
        String closeTail = THINK_CLOSE.substring(THINK_CLOSE.length() - 3);
        ScriptedChatModel model = ScriptedChatModel.builder()
                .round(ScriptedChatModel.content("答复A" + openHead),
                        ScriptedChatModel.content(openTail + "首段"),
                        ScriptedChatModel.content("中段" + closeHead),
                        ScriptedChatModel.content(closeTail + "答复B"))
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        String answer = emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA);
        assertThat(answer).as("标签碎片既不是回答也不该显示").doesNotContain(openHead)
                .doesNotContain(closeHead).isEqualTo("答复A答复B");
        assertThat(emitter.joinedContent(AiStreamFrame.Type.THINKING_DELTA)).isEqualTo("首段中段");
    }

    @Test
    @DisplayName("内联标签未闭合（回合结束时仍在思考中）：余文归思考，不凭空丢字")
    void unclosedInlineThinkTagKeepsRemainderAsThinking() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .round(ScriptedChatModel.content("回答前半"),
                        ScriptedChatModel.content(THINK_OPEN + "还没想完"))
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA)).isEqualTo("回答前半");
        assertThat(emitter.joinedContent(AiStreamFrame.Type.THINKING_DELTA)).isEqualTo("还没想完");
    }

    @Test
    @DisplayName("非思考模式：内联 think 标签仍然分流到 thinking_delta（模型实际输出优先于配置）")
    void nonThinkingModeStillParsesInlineThinkTags() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .round(ScriptedChatModel.content(
                        "正文" + THINK_OPEN + "思考内容" + THINK_CLOSE + "回答"))
                .build();

        agentFor(new StubChatModelProvider(model, ThinkingMode.NON_THINKING))
                .runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        // WHY 非思考模式也解析内联标签：thinkingMode 只控制请求侧 enable_thinking
        // 与 reasoning_content 元数据读取，但模型在 content 流内嵌的 <think> 标签
        // 是实际输出格式，不解析就会作为原文透传给用户（BUG 实证：内网电脑看到裸标签）
        assertThat(emitter.joinedContent(AiStreamFrame.Type.THINKING_DELTA))
                .as("内联 think 标签内容应分流到 thinking_delta")
                .isEqualTo("思考内容");
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA))
                .as("标签外的正文和回答应走 answer_delta")
                .isEqualTo("正文回答");
    }

    @Test
    @DisplayName("非思考模式：即使端点回显了 reasoningContent 也不得推给前端")
    void nonThinkingModeIgnoresReasoningMetadata() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .round(ScriptedChatModel.thinking("这是端点回显的调试字段", "正式回答"))
                .build();

        agentFor(new StubChatModelProvider(model, ThinkingMode.NON_THINKING))
                .runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        assertThat(emitter.ofType(AiStreamFrame.Type.THINKING_DELTA))
                .as("非思考模式下解析思考字段，会把调试信息当成思考过程推给用户")
                .isEmpty();
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA)).isEqualTo("正式回答");
        assertThat(emitter.only(AiStreamFrame.Type.FINAL).thinkingMode())
                .as("final 帧要如实标注本回合的模式").isFalse();
    }

    // ==================================================================
    // 7.4 流式响应与错误分类
    // ==================================================================

    @Test
    @DisplayName("回答以多个增量到达，且增量帧全部先于 final 帧")
    void answerArrivesAsIncrementalDeltasBeforeFinal() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .answer("第一段", "第二段", "第三段")
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "讲三段"));

        assertThat(emitter.countOf(AiStreamFrame.Type.ANSWER_DELTA)).isEqualTo(3);
        assertThat(emitter.typeSequence())
                .containsExactly("answer_delta", "answer_delta", "answer_delta", "final");
        AiStreamFrame finalFrame = emitter.only(AiStreamFrame.Type.FINAL);
        assertThat(finalFrame.finishReason()).isEqualTo("stop");
        assertThat(finalFrame.thinkingMode()).isTrue();
        assertThat(finalFrame.hostId()).as("回合级帧要带上目标服务器，前端才知道说的是哪台机器")
                .isEqualTo(hostId);
    }

    @Test
    @DisplayName("final 帧的 message_id 与库里落下的 assistant 行一致")
    void finalFrameCarriesThePersistedMessageId() {
        ScriptedChatModel model = ScriptedChatModel.builder().answer("落库的回答").build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        UUID frameMessageId = emitter.only(AiStreamFrame.Type.FINAL).messageId();
        assertThat(frameMessageId).isNotNull();
        assertThat(lastAssistantMessage(conversationId).getId())
                .as("前端要靠这个 id 把增量与历史消息对上")
                .isEqualTo(frameMessageId);
    }

    @Test
    @DisplayName("未配置 api key：回 api_key_missing 帧与 spec 规定的原文提示，而不是 500")
    void missingApiKeyEmitsSpecMessageFrame() {
        agentFor(StubChatModelProvider.withoutApiKey())
                .runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        AiStreamFrame error = emitter.only(AiStreamFrame.Type.ERROR);
        assertThat(error.errorCode()).isEqualTo(ErrorCode.API_KEY_MISSING);
        assertThat(error.message()).isEqualTo(MissingModelApiKeyException.MESSAGE);
        assertThat(emitter.ofType(AiStreamFrame.Type.FINAL)).as("回合没有成功，不该发 final").isEmpty();
        assertThat(userMessages(conversationId))
                .as("提问已经落下，配置好 key 后用户不必重打一遍").hasSize(1);
    }

    @Test
    @DisplayName("端点不可达：沿 cause 链识别为 model_endpoint_unreachable")
    void unreachableEndpointIsClassifiedAsUnreachable() {
        agentFor(StubChatModelProvider.failingWith(new IllegalStateException("包装层",
                new ResourceAccessException("I/O error on POST",
                        new java.net.ConnectException("connection refused")))))
                .runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        AiStreamFrame error = emitter.only(AiStreamFrame.Type.ERROR);
        assertThat(error.errorCode())
                .as("只看最外层异常会把'连不上'误判成'模型服务返回错误'，用户于是去查一个没问题的配置")
                .isEqualTo(ErrorCode.MODEL_ENDPOINT_UNREACHABLE);
        assertThat(error.message()).contains("base_url");
    }

    @Test
    @DisplayName("端点其它错误：回 model_endpoint_error，且对外文案不含异常原文")
    void endpointFailureDoesNotLeakRawExceptionText() {
        agentFor(StubChatModelProvider.failingWith(new IllegalStateException(
                "HTTP 500 from https://mindie.internal/v1 body=sk-SECRET-VALUE")))
                .runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        AiStreamFrame error = emitter.only(AiStreamFrame.Type.ERROR);
        assertThat(error.errorCode()).isEqualTo(ErrorCode.MODEL_ENDPOINT_ERROR);
        assertThat(error.message())
                .as("异常原文可能含内网地址与请求体片段，MUST NOT 送到前端")
                .doesNotContain("sk-SECRET-VALUE")
                .doesNotContain("mindie.internal")
                .doesNotContain("HTTP 500");
    }

    @Test
    @DisplayName("模型流阻塞读被用户打断：包成 RuntimeException 的 InterruptedException 按停止注记收尾，不是端点错误")
    void interruptWrappedInRuntimeExceptionFinishesWithStopNoteNotError() {
        // WHY：run8 浏览器实测第四层断链——stop() 中断 worker 后，langchain4j 阻塞流式读
        // 把 InterruptedException 包成 ReactiveException（RuntimeException 子类）抛出；
        // runTurn 若不识 cause 链就把用户主动打断误判成 model_endpoint_error，
        // Ctrl+C 瞬间屏幕变红字报错，与「受控停止」语义完全相反
        agentFor(StubChatModelProvider.failingWith(new RuntimeException(
                "ReactiveException 同形包装", new InterruptedException("回合被用户停止"))))
                .runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        assertThat(emitter.ofType(AiStreamFrame.Type.ERROR))
                .as("打断不是失败，不得发错误帧")
                .isEmpty();
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA))
                .as("应像既有停止路径一样写停止注记")
                .contains(AiAgentService.STOPPED_NOTE);
        assertThat(emitter.only(AiStreamFrame.Type.FINAL).finishReason())
                .isEqualTo("stopped");
    }

    @Test
    @DisplayName("停止收尾必须在干净中断标志下发帧：残留标志会让阻塞式 WS 发送失败并污染线程池")
    void stopCleanupRunsOnInterruptFreeThread() {
        // WHY：run9 浏览器实测第五层断链——reactor 阻塞读被 interrupt() 打断时会恢复线程
        // 中断标志再抛 ReactiveException；而发帧（Tomcat WS blocking send）就跑在同一个
        // worker 线程上：标志在 → 第一帧发送即失败、连接 1006 半关闭，停止注记永远发不出去；
        // 标志泄漏还会污染池内后续任务。收尾前必须清标志。
        RuntimeException wrapped = new RuntimeException("ReactiveException 同形包装",
                new InterruptedException("回合被用户停止"));
        // 模拟 reactor 行为：回合被停止时线程中断标志已置位；
        // 泄漏断言必须在 runTurn 返回后、兼底清理前捕获，否则 finally 清标志会掉证据
        boolean leaked;
        Thread.currentThread().interrupt();
        try {
            agentFor(StubChatModelProvider.failingWith(wrapped))
                    .runTurn(new TurnRequest(conversationId, hostId, "问一句"));
            leaked = Thread.currentThread().isInterrupted();
        } finally {
            // 用例退出前兼底清理，防止标志泄漏到后续用例（JUnit 同线程串行）
            Thread.interrupted();
        }

        assertThat(leaked)
                .as("收尾后不得把中断标志泄漏给调用线程/线程池后续任务")
                .isFalse();
        assertThat(emitter.ofType(AiStreamFrame.Type.ERROR))
                .as("停止不得发错误帧")
                .isEmpty();
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA))
                .as("停止注记必须在标志已清、发送链健康时发出——带标志发帧会被传输层拒绝")
                .contains(AiAgentService.STOPPED_NOTE);
    }

    @Test
    @DisplayName("错误分类：走完整条 cause 链，且不被自引用 cause 拖死")
    void classifyWalksCauseChainAndSurvivesSelfReference() {
        assertThat(AiAgentService.classify(new MissingModelApiKeyException()))
                .isEqualTo(ErrorCode.API_KEY_MISSING);
        assertThat(AiAgentService.classify(new RuntimeException("外层",
                new RuntimeException("中层", new java.net.UnknownHostException("no-such-host")))))
                .as("网络异常常被包上两三层").isEqualTo(ErrorCode.MODEL_ENDPOINT_UNREACHABLE);
        assertThat(AiAgentService.classify(new RuntimeException("业务错误")))
                .isEqualTo(ErrorCode.MODEL_ENDPOINT_ERROR);

        // 自引用 cause：getCause() == this，朴素的 while 循环会永远转下去
        RuntimeException selfReferencing = new RuntimeException("自引用") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(AiAgentService.classify(selfReferencing)).isEqualTo(ErrorCode.MODEL_ENDPOINT_ERROR);
    }

    @Test
    @DisplayName("出站发送失败不中断回合：帧丢了，但消息仍然落库")
    void outboundFailureDoesNotAbortTheTurn() {
        ScriptedChatModel model = ScriptedChatModel.builder().answer("照样落库").build();
        AiAgentService agent = agent(model);
        emitter.failWith(new IllegalStateException("前端已断开"));

        agent.runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        assertThat(lastAssistantMessage(conversationId).getContent())
                .as("前端断开不等于对话没发生，用户重连后应能在历史面板看到全过程")
                .isEqualTo("照样落库");
    }

    @Test
    @DisplayName("会话不存在：回 not_found 帧，且不去打扰模型端点")
    void unknownConversationEmitsNotFound() {
        UUID missing = UUID.randomUUID();
        StubChatModelProvider provider = new StubChatModelProvider(
                ScriptedChatModel.builder().answer("不该出现").build());

        agentFor(provider).runTurn(new TurnRequest(missing, hostId, "问一句"));

        AiStreamFrame error = emitter.only(AiStreamFrame.Type.ERROR);
        assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.conversationId()).isEqualTo(missing);
        assertThat(provider.prepareCount()).as("会话都不存在，不该去装配模型").isZero();
    }

    // ==================================================================
    // 9.4 多轮上下文
    // ==================================================================

    @Test
    @DisplayName("工具结果被回喂进下一轮 Prompt（assistant 提议 + tool 响应成对出现）")
    void toolResultIsFedBackIntoTheNextRoundPrompt() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_42", "system_info", "{}")
                .answer("机器负载正常")
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "这台机器怎么样"));

        assertThat(model.streamCallCount()).as("一次工具调用应产生两轮模型往返").isEqualTo(2);
        Prompt second = model.prompts().get(1);
        AssistantMessage proposal = findLast(second, AssistantMessage.class);
        assertThat(proposal.hasToolCalls()).isTrue();
        assertThat(proposal.getToolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call_42");
            assertThat(call.name()).isEqualTo("system_info");
        });
        ToolResponseMessage response = findLast(second, ToolResponseMessage.class);
        assertThat(response.getResponses()).singleElement().satisfies(item -> {
            assertThat(item.id()).as("tool 响应必须与提议的 call id 配对，否则端点回 400")
                    .isEqualTo("call_42");
            assertThat(item.responseData()).startsWith("exit=0");
        });
    }

    @Test
    @DisplayName("工具调用被拆成多个流式分片时仍能聚合成一次完整调用")
    void streamedToolCallFragmentsAreAggregated() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCallFragments("call_7", "read_file",
                        "{\"path\":\"/etc/hosts\"", ",\"start_line\":1}")
                .answer("读完了")
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "看看 hosts"));

        ToolCallEventFrame call = emitter.only(AiStreamFrame.Type.TOOL_CALL).toolCall();
        assertThat(call.toolName()).isEqualTo(ToolName.READ_FILE);
        assertThat(call.toolParams())
                .as("分片必须被拼成合法 JSON 再解析，否则模型每次调工具都会报参数错误")
                .containsEntry("path", "/etc/hosts")
                .containsEntry("start_line", 1);
        assertThat(emitter.only(AiStreamFrame.Type.TOOL_RESULT).toolCall().resultStatus())
                .isEqualTo(ToolResultStatus.SUCCESS);
    }

    @Test
    @DisplayName("跨回合：上一轮的用户提问与回答被重建进新一轮上下文")
    void historyIsRebuiltAcrossTurns() {
        agent(ScriptedChatModel.builder().answer("第一轮的回答").build())
                .runTurn(new TurnRequest(conversationId, hostId, "第一个问题"));

        StubChatModelProvider provider = new StubChatModelProvider(
                ScriptedChatModel.builder().answer("第二轮的回答").build());
        agentFor(provider).runTurn(new TurnRequest(conversationId, hostId, "第二个问题"));

        // 第二次回合只调用一次模型，因此剧本模型里只有一条 Prompt
        List<org.springframework.ai.chat.messages.Message> instructions =
                provider.lastScriptedPrompts().get(0).getInstructions();
        assertThat(textsOf(instructions, UserMessage.class))
                .as("多轮对话的上下文延续")
                .contains("第一个问题", "第二个问题");
        assertThat(textsOf(instructions, AssistantMessage.class)).contains("第一轮的回答");
        assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
    }

    @Test
    @DisplayName("达到工具轮次上限时明确收尾，而不是悄悄结束")
    void toolRoundLimitStopsWithAnExplicitNote() {
        UUID bare = newConversation(null);
        // 剧本只有一轮"永远要求调工具"，用尽后重复最后一轮 → 必然撞到上限
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "system_info", "{}")
                .build();

        agent(model).runTurn(new TurnRequest(bare, null, "无限循环"));

        assertThat(model.streamCallCount()).isEqualTo(AiAgentService.MAX_TOOL_ROUNDS);
        AiStreamFrame finalFrame = emitter.only(AiStreamFrame.Type.FINAL);
        assertThat(finalFrame.finishReason())
                .as("契约没有为轮次上限定义取值，只能借 finish_reason 的自由字符串表达（见契约缺口记录）")
                .isEqualTo("tool_round_limit");
        assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA))
                .contains(AiAgentService.ROUND_LIMIT_NOTE);
        assertThat(lastAssistantMessage(bare).getContent())
                .as("说明必须落库，用户刷新后仍知道为什么停了")
                .contains(AiAgentService.ROUND_LIMIT_NOTE);
    }

    @Test
    @DisplayName("同一会话已有回合在飞时，第二次提问被拒并回 conflict 帧")
    void secondTurnForSameConversationIsRejectedWithConflict() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "run_command", "{\"command\":\"echo wait\",\"ai_analysis\":\"等待裁决\"}")
                .answer("已取消")
                .build();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AiAgentService asyncAgent = newAgent(new StubChatModelProvider(model, ThinkingMode.NON_THINKING), workers);
        try {
            TurnRequest request = new TurnRequest(conversationId, hostId, "跑一条命令");
            assertThat(asyncAgent.submit(request)).isTrue();
            until("审批提案已挂起（tool_call 帧带出了 approval_id）",
                    () -> emitter.latestApprovalId() != null);

            assertThat(asyncAgent.submit(request))
                    .as("并发回合会算出同一个 seq 并撞唯一约束，MUST 直接拒绝")
                    .isFalse();

            gate.respond(emitter.latestApprovalId(), ApprovalResponseFrame.Decision.CANCEL);
            until("回合已结束", () -> asyncAgent.inFlightCount() == 0);

            assertThat(emitter.ofType(AiStreamFrame.Type.ERROR)).anySatisfy(frame -> {
                assertThat(frame.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(frame.message()).contains("正在处理上一条提问");
            });
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("修改后批准：落到远端执行的是修改后命令，而非提案原命令（D5 端到端）")
    void modifiedCommandIsExecutedAfterApproval() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "run_command",
                        "{\"command\":\"echo original-e2e\",\"ai_analysis\":\"验证修改链路\"}")
                .answer("已完成")
                .build();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AiAgentService asyncAgent = newAgent(new StubChatModelProvider(model, ThinkingMode.NON_THINKING), workers);
        try {
            int execBefore = fake.execCommands().size();
            assertThat(asyncAgent.submit(new TurnRequest(conversationId, hostId, "跑一条命令"))).isTrue();
            until("审批提案已挂起", () -> emitter.latestApprovalId() != null);
            UUID approvalId = emitter.latestApprovalId();

            // 用户在弹窗上改命令→版本 2，再用新版本批准（与前端 modify→approve 序列一致）
            assertThat(gate.modify(approvalId, "echo modified-e2e", 1)).isTrue();
            assertThat(gate.respondWithVersion(approvalId, ApprovalResponseFrame.Decision.APPROVE, 2))
                    .isTrue();

            until("回合结束（命令已在同步链路里执行）", () -> asyncAgent.inFlightCount() == 0);
            assertThat(fake.execCommands().subList(execBefore, fake.execCommands().size()))
                    .as("批准后执行的 MUST 是修改后命令——若仍是原命令说明执行链路没消费 modify")
                    .containsExactly("echo modified-e2e");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("用户取消审批：回合立即终结，不回喂模型、不发起下一轮（用户实测追加）")
    void userCancellationEndsTurnWithoutAnotherModelCall() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "run_command",
                        "{\"command\":\"echo nope\",\"ai_analysis\":\"取消终结验证\"}")
                // 旧行为会把「用户已拒绝」回喂，模型拿到第二轮剧本继续吐“不该出现”的回答
                .answer("不该出现")
                .build();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AiAgentService asyncAgent = newAgent(new StubChatModelProvider(model, ThinkingMode.NON_THINKING), workers);
        try {
            assertThat(asyncAgent.submit(new TurnRequest(conversationId, hostId, "跑一条命令"))).isTrue();
            until("审批提案已挂起", () -> emitter.latestApprovalId() != null);

            gate.respond(emitter.latestApprovalId(), ApprovalResponseFrame.Decision.CANCEL);
            until("回合已结束", () -> asyncAgent.inFlightCount() == 0);

            assertThat(model.streamCallCount())
                    .as("取消后 MUST NOT 再次调用模型——回喂拒绝事实会诱导模型另想办法继续推进")
                    .isEqualTo(1);
            AiStreamFrame finalFrame = emitter.only(AiStreamFrame.Type.FINAL);
            assertThat(finalFrame.finishReason()).isEqualTo("approval_cancelled");
            assertThat(emitter.joinedContent(AiStreamFrame.Type.ANSWER_DELTA))
                    .contains(AiAgentService.CANCEL_END_NOTE)
                    .doesNotContain("不该出现");
            assertThat(lastAssistantMessage(conversationId).getContent())
                    .as("结束注记落库，用户刷新后仍知道回合为何终止")
                    .contains(AiAgentService.CANCEL_END_NOTE);
        } finally {
            workers.shutdownNow();
        }
    }

    // ==================================================================
    // 15.1 Shell → Agent 状态交接：获准命令与只读工具都 MUST 走共享 PTY
    // ==================================================================

    @Test
    @DisplayName("携连接实例的回合：获准命令经共享 PTY 执行而非另起 exec（cd 状态继承）")
    void approvedCommandIsRoutedThroughSharedPty() {
        PtyCommandGateway gateway = Mockito.mock(PtyCommandGateway.class);
        Mockito.when(gateway.submit(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new PtyCommandScheduler.CommandResult(0, "/var/log\r\n", false, false, "/var/log")));
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "run_command", "{\"command\":\"pwd\",\"ai_analysis\":\"确认工作目录\"}")
                .answer("当前目录是 /var/log")
                .build();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AiAgentService asyncAgent =
                newAgent(new StubChatModelProvider(model, ThinkingMode.NON_THINKING), workers, gateway);
        UUID sessionId = UUID.randomUUID();
        try {
            int execBefore = fake.execCommands().size();
            assertThat(asyncAgent.submit(new TurnRequest(conversationId, hostId, "我现在在哪", sessionId)))
                    .isTrue();
            until("审批提案已挂起", () -> emitter.latestApprovalId() != null);

            gate.respond(emitter.latestApprovalId(), ApprovalResponseFrame.Decision.APPROVE);
            until("回合结束", () -> asyncAgent.inFlightCount() == 0);

            Mockito.verify(gateway).submit(sessionId.toString(), "pwd");
            assertThat(fake.execCommands().subList(execBefore, fake.execCommands().size()))
                    .as("有连接实例可用时回落 exec 会丢失 Shell 的 cwd/env，MUST 禁止")
                    .isEmpty();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("只读工具同样经共享 PTY：toolContext 必须注入连接实例 id")
    void readOnlyToolIsRoutedThroughSharedPty() {
        PtyCommandGateway gateway = Mockito.mock(PtyCommandGateway.class);
        Mockito.when(gateway.submit(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new PtyCommandScheduler.CommandResult(0, "Linux testbox\r\n", false, false, "/var/log")));
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "system_info", "{}")
                .answer("看完了")
                .build();
        UUID sessionId = UUID.randomUUID();
        AiAgentService service = newAgent(
                new StubChatModelProvider(model), AgentTestWiring.directExecutor(), gateway);

        int execBefore = fake.execCommands().size();
        service.runTurn(new TurnRequest(conversationId, hostId, "看下系统信息", sessionId));

        Mockito.verify(gateway).submit(Mockito.eq(sessionId.toString()), Mockito.anyString());
        assertThat(fake.execCommands().subList(execBefore, fake.execCommands().size()))
                .as("只读工具同样不该在 sessionId 存在时回落 exec")
                .isEmpty();
    }

    // ==================================================================
    // BUG-B：工具阶段被用户停止——中断不得被吞、不得回落 exec 重跑、必须打断远端命令
    // ==================================================================

    @Test
    @DisplayName("只读工具等待 PTY 结果被中断：MUST NOT 回落 exec 重跑，按停止收尾（BUG-B）")
    void stopDuringReadOnlyToolDoesNotFallBackToExec() {
        // 浏览器实测症状：用户 Ctrl+C 停止后 Agent「像没听见一样继续执行命令」——
        // AgentTools 把 InterruptedException 吞成普通失败并回落 exec 重跑了一遍工具命令
        PtyCommandGateway gateway = Mockito.mock(PtyCommandGateway.class);
        Mockito.when(gateway.submit(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> {
                    // 模拟 stop() 命中正在 future.get 上阻塞的工作线程
                    Thread.currentThread().interrupt();
                    return new CompletableFuture<PtyCommandScheduler.CommandResult>();
                });
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "system_info", "{}")
                .answer("不该走到这")
                .build();
        AiAgentService service = newAgent(
                new StubChatModelProvider(model), AgentTestWiring.directExecutor(), gateway);
        UUID sessionId = UUID.randomUUID();
        int execBefore = fake.execCommands().size();

        try {
            service.runTurn(new TurnRequest(conversationId, hostId, "看下系统信息", sessionId));
        } finally {
            // directExecutor 在主线程同步跑回合，兜底清标志防泄漏到后续用例
            Thread.interrupted();
        }

        assertThat(fake.execCommands().subList(execBefore, fake.execCommands().size()))
                .as("停止后回落 exec 重跑命令 = 用户怎么打断都停不下来，MUST 禁止")
                .isEmpty();
        // 被吞的真痕迹不在命令重跑（测试替身下 exec 回落在发送前就被标志拦住），
        // 而在「中断被伪装成工具失败回喂给模型」：模型收到 ERROR 就会继续分析另想办法，
        // 这正是用户看到的「停不下来、按上一步输出继续执行」
        assertThat(emitter.ofType(AiStreamFrame.Type.TOOL_RESULT))
                .as("停止不是工具失败，MUST NOT 把 ERROR 回喂给模型驱动它继续推进")
                .isEmpty();
        assertThat(emitter.ofType(AiStreamFrame.Type.ERROR))
                .as("打断不是失败，不得发错误帧")
                .isEmpty();
        assertThat(emitter.only(AiStreamFrame.Type.FINAL).finishReason())
                .isEqualTo("stopped");
    }

    @Test
    @DisplayName("获准命令等待 PTY 结果被中断：MUST NOT 回落 exec 重跑已批准命令（BUG-B）")
    void stopDuringApprovedCommandDoesNotFallBackToExec() {
        PtyCommandGateway gateway = Mockito.mock(PtyCommandGateway.class);
        Mockito.when(gateway.submit(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return new CompletableFuture<PtyCommandScheduler.CommandResult>();
                });
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "run_command",
                        "{\"command\":\"pwd\",\"ai_analysis\":\"确认目录\"}")
                .answer("不该走到这")
                .build();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AiAgentService asyncAgent = newAgent(new StubChatModelProvider(model), workers, gateway);
        UUID sessionId = UUID.randomUUID();
        try {
            int execBefore = fake.execCommands().size();
            assertThat(asyncAgent.submit(new TurnRequest(conversationId, hostId, "我在哪", sessionId)))
                    .isTrue();
            until("审批提案已挂起", () -> emitter.latestApprovalId() != null);
            gate.respond(emitter.latestApprovalId(), ApprovalResponseFrame.Decision.APPROVE);
            until("回合结束", () -> asyncAgent.inFlightCount() == 0);

            assertThat(fake.execCommands().subList(execBefore, fake.execCommands().size()))
                    .as("已批准命令被打断后经 exec 重跑一遍：用户停不掉任务的最直接观感（BUG-B）")
                    .isEmpty();
            assertThat(emitter.ofType(AiStreamFrame.Type.TOOL_RESULT))
                    .as("停止不得伪装成工具执行失败回喂模型——它会让模型接着分析错误另想办法")
                    .isEmpty();
            assertThat(emitter.only(AiStreamFrame.Type.FINAL).finishReason())
                    .isEqualTo("stopped");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("stop() 必须经调度器 interruptCurrent() 打断在飞远端命令（BUG-B）")
    void stopInterruptsInFlightRemoteCommand() {
        // 仅中断本地等待线程不够：远端 PTY 上的命令（如正在跑的 JDK 安装）还在继续，
        // 必须对调度器发 interruptCurrent() 让 Ctrl-C 到达远端
        PtyCommandScheduler scheduler = Mockito.mock(PtyCommandScheduler.class);
        PtyCommandGateway gateway = Mockito.mock(PtyCommandGateway.class);
        AtomicBoolean submitted = new AtomicBoolean();
        Mockito.when(gateway.submit(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> {
                    submitted.set(true);
                    // 不完成：回合阻塞在 future.get，直到 stop() 的 interrupt 到达
                    return new CompletableFuture<PtyCommandScheduler.CommandResult>();
                });
        Mockito.when(gateway.findScheduler(Mockito.anyString())).thenReturn(scheduler);
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "run_command",
                        "{\"command\":\"yum install -y java-1.8.0-openjdk\",\"ai_analysis\":\"安装 JDK\"}")
                .answer("不该走到这")
                .build();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AiAgentService asyncAgent = newAgent(new StubChatModelProvider(model), workers, gateway);
        UUID sessionId = UUID.randomUUID();
        try {
            assertThat(asyncAgent.submit(new TurnRequest(conversationId, hostId, "装个 JDK", sessionId)))
                    .isTrue();
            until("审批提案已挂起", () -> emitter.latestApprovalId() != null);
            gate.respond(emitter.latestApprovalId(), ApprovalResponseFrame.Decision.APPROVE);
            until("命令已进入 PTY 通道", submitted::get);

            assertThat(asyncAgent.stop(conversationId))
                    .as("回合正在飞，停止必须命中")
                    .isTrue();
            until("回合停止收尾", () -> asyncAgent.inFlightCount() == 0);

            Mockito.verify(scheduler).interruptCurrent();
            assertThat(emitter.only(AiStreamFrame.Type.FINAL).finishReason())
                    .isEqualTo("stopped");
        } finally {
            workers.shutdownNow();
        }
    }

    // ==================================================================
    // 9.5 操作透明性的边界情形
    // ==================================================================

    @Test
    @DisplayName("模型幻觉出的未知工具名：不发帧，把错误回喂给模型让它自纠")
    void unknownToolNameIsFedBackAsToolError() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "drop_database", "{}")
                .answer("我改用别的办法")
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "删库"));

        // 契约 ToolName 只有四个取值，为未知工具造一帧就是给冻结契约添一个它没有的形状
        assertThat(emitter.ofType(AiStreamFrame.Type.TOOL_CALL)).isEmpty();
        assertThat(emitter.ofType(AiStreamFrame.Type.TOOL_RESULT)).isEmpty();
        ToolResponseMessage response = findLast(model.prompts().get(1), ToolResponseMessage.class);
        assertThat(response.getResponses()).singleElement()
                .satisfies(item -> assertThat(item.responseData()).contains("drop_database"));
    }

    @Test
    @DisplayName("工具参数不是合法 JSON：不硬造一帧非法的 tool_call，只发 tool_result 报错")
    void malformedToolArgumentsProduceToolErrorWithoutIllegalFrame() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "run_command", "这不是 JSON")
                .answer("我重新组织参数")
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "跑一条命令"));

        assertThat(emitter.ofType(AiStreamFrame.Type.TOOL_CALL))
                .as("参数非法时尚未受理审批、没有 approval_id；而 auto=true 又违反 run_command 的分级，"
                        + "两种形状都违约，只能不发")
                .isEmpty();
        ToolCallEventFrame result = emitter.only(AiStreamFrame.Type.TOOL_RESULT).toolCall();
        assertThat(result.resultStatus()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.result()).contains("不是合法的 JSON");
        assertThat(approvalRowCount(conversationId)).as("没解析出 command，就不该受理审批").isZero();
    }

    @Test
    @DisplayName("run_command 缺 command 参数：本地拦住，不受理审批")
    void runCommandWithoutCommandIsRejectedLocally() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "run_command", "{\"ai_analysis\":\"忘了给命令\"}")
                .answer("我补上命令")
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "跑一条命令"));

        assertThat(emitter.ofType(AiStreamFrame.Type.TOOL_CALL))
                .as("没有 approval_id 的 auto=false 事件不符合契约").isEmpty();
        ToolCallEventFrame result = emitter.only(AiStreamFrame.Type.TOOL_RESULT).toolCall();
        assertThat(result.resultStatus()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.result()).contains("缺少 command 参数");
        assertThat(approvalRowCount(conversationId)).isZero();
    }

    @Test
    @DisplayName("系统提示带上目标服务器展示名与工具分级纪律")
    void systemPromptCarriesHostLabelAndToolDiscipline() {
        StubChatModelProvider provider = new StubChatModelProvider(
                ScriptedChatModel.builder().answer("好").build());

        agentFor(provider).runTurn(new TurnRequest(conversationId, hostId, "问一句"));

        String prompt = systemPromptOf(provider, 0);
        assertThat(prompt).contains(HOST_LABEL);
        assertThat(prompt).as("副作用工具的审批纪律必须写进提示").contains("run_command 有副作用");
        assertThat(prompt).as("强制非交互").contains("非交互");
        assertThat(prompt).as("禁止编造输出").contains("绝不编造工具输出");
        assertThat(prompt).as("思考模式要额外说明输出结构").contains("思考模式");
        assertThat(prompt).as("禁止在命令里内联口令").contains("绝对不要在命令里内联任何口令");
    }

    @Test
    @DisplayName("每次被路由的调用恰好产生一对 tool_call / tool_result 帧（操作透明性）")
    void everyRoutedCallProducesExactlyOneCallAndOneResultFrame() {
        ScriptedChatModel model = ScriptedChatModel.builder()
                .toolCall("call_1", "system_info", "{}")
                .toolCall("call_2", "list_dir", "{\"path\":\"/etc\"}")
                .answer("看完了")
                .build();

        agent(model).runTurn(new TurnRequest(conversationId, hostId, "先看看系统信息"));

        assertThat(emitter.typeSequence())
                .as("前端那个工具条目若收不到 result 帧，就会永远停在转圈状态")
                .containsExactly("tool_call", "tool_result", "tool_call", "tool_result",
                        "answer_delta", "final");
        List<Message> toolRows = messagesOf(conversationId).stream()
                .filter(message -> message.getRole() == MessageRole.TOOL)
                .toList();
        assertThat(toolRows).hasSize(2);
        assertThat(toolRows).allSatisfy(row -> assertThat(row.getToolCalls()).hasSize(1));
    }

    // ==================================================================
    // 装配与断言辅助
    // ==================================================================

    /** 用同步执行器 + 思考模式装配一个被测服务（多数用例直接调 {@code runTurn}）。 */
    private AiAgentService agent(ScriptedChatModel model) {
        return agentFor(new StubChatModelProvider(model));
    }

    private AiAgentService agentFor(StubChatModelProvider modelProvider) {
        return newAgent(modelProvider, AgentTestWiring.directExecutor());
    }

    private AiAgentService newAgent(StubChatModelProvider modelProvider, ExecutorService workers) {
        return newAgent(modelProvider, workers, null);
    }

    /**
     * 带 PTY 网关的装配变体：同时注入 AgentTools 与 ApprovedCommandRunner，
     * 验证 sessionId 存在时两条执行路径都路由到共享 PTY（design D3）。
     */
    private AiAgentService newAgent(StubChatModelProvider modelProvider, ExecutorService workers,
                                    @Nullable PtyCommandGateway gateway) {
        return new AiAgentService(modelProvider,
                new AgentTools(exec, settings, gateway),
                gate,
                new ApprovedCommandRunner(exec, settings, audit, gateway),
                conversations,
                StaticObjectProvider.of(emitter),
                objectMapper,
                workers);
    }

    private UUID newConversation(@Nullable UUID boundHostId) {
        return conversations.create(new ConversationCreate().hostId(boundHostId)).getId();
    }

    private List<Message> messagesOf(UUID id) {
        return conversations.listMessages(id);
    }

    private Message lastAssistantMessage(UUID id) {
        List<Message> matched = messagesOf(id).stream()
                .filter(message -> message.getRole() == MessageRole.ASSISTANT)
                .toList();
        assertThat(matched).as("应至少有一条 assistant 消息").isNotEmpty();
        return matched.get(matched.size() - 1);
    }

    private List<Message> userMessages(UUID id) {
        return messagesOf(id).stream()
                .filter(message -> message.getRole() == MessageRole.USER)
                .toList();
    }

    private static String systemPromptOf(StubChatModelProvider provider, int promptIndex) {
        return provider.lastScriptedPrompts().get(promptIndex).getInstructions().get(0).getText();
    }

    private static <T> T findLast(Prompt prompt, Class<T> type) {
        List<T> matched = new ArrayList<>();
        for (org.springframework.ai.chat.messages.Message message : prompt.getInstructions()) {
            if (type.isInstance(message)) {
                matched.add(type.cast(message));
            }
        }
        assertThat(matched).as("Prompt 里应至少有一条 %s", type.getSimpleName()).isNotEmpty();
        return matched.get(matched.size() - 1);
    }

    private static List<String> textsOf(List<org.springframework.ai.chat.messages.Message> messages,
                                        Class<?> type) {
        List<String> texts = new ArrayList<>();
        for (org.springframework.ai.chat.messages.Message message : messages) {
            if (type.isInstance(message)) {
                texts.add(message.getText());
            }
        }
        return texts;
    }

    /** @return 指定会话在 {@code approvals} 表里的行数；用原生 JDBC 查，避免用被测代码验证被测代码 */
    private int approvalRowCount(UUID id) {
        String sql = "SELECT COUNT(*) FROM approvals WHERE conversation_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 approvals 失败", e);
        }
    }
}

package com.ananoesis.shell.ai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import com.ananoesis.shell.ai.ChatModelProvider.PreparedChatModel;
import com.ananoesis.shell.approval.ApprovalAuditService;
import com.ananoesis.shell.approval.ApprovalGate;
import com.ananoesis.shell.approval.ApprovalOutcome;
import com.ananoesis.shell.approval.ApprovalProposal;
import com.ananoesis.shell.approval.ApprovedCommandRunner;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.ananoesis.shell.entity.AiConversation;
import com.ananoesis.shell.entity.AiMessage;
import com.ananoesis.shell.security.MissingModelApiKeyException;
import com.ananoesis.shell.service.AgentRunService;
import com.ananoesis.shell.service.ConversationNotFoundException;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.support.TurnCancelledException;
import com.ananoesis.shell.ws.AiStreamFrame;
import com.ananoesis.shell.ws.ToolCallEventFrame;
import com.ananoesis.shell.ws.ToolName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * 智能体回合循环（tasks 9.1 / 9.3 / 9.4 / 9.5 / 6.6，design D3）。
 *
 * <h2>一次回合的形状</h2>
 * <pre>
 *   用户提问 → 落 user 行 → 装配 ChatModel → [流式取增量 → 有 tool_calls?]
 *                                                  ├─ 无：落 assistant 行 → final 帧 → 结束
 *                                                  └─ 有：分级路由 → 回喂 → 回到"流式取增量"
 * </pre>
 * <p>最多 {@value #MAX_TOOL_ROUNDS} 轮工具循环。WHY 要有上限：模型可能陷入
 * "读文件 → 觉得不够 → 再读同一个文件"的循环，而没有上限就意味着一个工作线程
 * 被永久占用、一条 WebSocket 连接一直吐增量。上限触发时会落一条明确说明的
 * assistant 消息，用户知道"为什么停了"，而不是看到一个悄悄结束的回合。</p>
 *
 * <h2>上下文超限恢复（design D7 "单次恢复"）</h2>
 * <p>当模型返回上下文超限错误时，本类执行一次受控恢复：</p>
 * <ol>
 *   <li>检查恢复额度（每个 run 最多一次）</li>
 *   <li>固定历史水位，将输入预算降到 50%</li>
 *   <li>重建 prompt，下一轮循环自然重试模型调用</li>
 *   <li>发出 attempt_reset 帧通知前端撤回失败尝试</li>
 * </ol>
 * <p>第二次超限直接停止 run，不再恢复。</p>
 *
 * <h2>WHY 手动接管工具执行（tasks 9.1）</h2>
 * <p>{@code ChatModelProvider} 装配时设了 {@code internalToolExecutionEnabled=false}，
 * Spring AI 因此<b>不会</b>自己调 {@code ToolCallback}，而是把 {@code tool_calls}
 * 原样透出。这是审批闸门能成立的<b>前提</b>：一旦框架自动执行，
 * {@code run_command} 就已经落到远端了，再去问用户"要不要批准"毫无意义。
 * 本类拿到 {@code tool_calls} 后按 {@link ToolName#autoExecuted()} 分流——
 * 只读的直接调 callback，{@code run_command} 交给 {@link ApprovalGate}，
 * <b>绝不</b>调它的 callback（{@code AgentTools#runCommand} 的方法体会抛异常，
 * 那是最后一道防线）。</p>
 *
 * <h2>WHY 回合跑在工作线程池上</h2>
 * <p>一轮对话可能因等待人工审批而阻塞最长 {@code approval.timeout.seconds}（默认 120 秒）。
 * 若跑在 WebSocket 的 IO 线程上，这段时间里<b>整条连接</b>都无法处理任何帧——
 * 包括用户在 {@code /ws/approval} 上的批准动作（前端可能复用同一条 TCP 连接的姊妹通道），
 * 结果是"批准了但没人接"。design D3 的「备选」里提到过异步 HTTP/WS 模型，
 * 但那会把一个简单的顺序循环拆成状态机；单机桌面应用用几个阻塞线程换代码可读性更划算。</p>
 *
 * <h2>WHY 同一会话同时只允许一个在飞回合</h2>
 * <p>{@code ai_messages.seq} 由"当前最大值 + 1"计算，两个并发回合会算出同一个 seq，
 * 撞 {@code (conversation_id, seq)} 唯一约束。更根本的是：两条交错的流式回答
 * 在前端会渲染成一团乱麻，用户也无法判断哪句是哪次提问的回答。
 * 直接拒绝第二次提问并给出 {@code conflict} 错误帧，比让它排队更诚实——
 * 排队会让用户以为"卡住了"。</p>
 *
 * <h2>安全</h2>
 * <ul>
 *   <li>本类不接触 api key：装配全在 {@code ChatModelProvider} 内完成，
 *       {@code SecretText} 出作用域即擦除。</li>
 *   <li>端点异常的<b>原文</b>只进 DEBUG 日志；WARN 只记 {@code error_code} 与异常类名。
 *       WHY：错误帧的文案会送到前端，异常原文可能含请求 URL、请求体片段
 *       （里面有用户的对话内容），把它们混进面向用户的文本既无用又扩大暴露面。</li>
 *   <li>工具结果原样回喂给模型，因此 {@code AgentTools} 与
 *       {@code ApprovedCommandRunner} 都只透出脱敏后的文案。</li>
 * </ul>
 */
@Service
public class AiAgentService {

    private static final Logger LOG = LoggerFactory.getLogger(AiAgentService.class);

    /**
     * 单回合的工具循环上限。
     *
     * <p>WHY 从 8 调到 30（用户反馈）：真实排障任务（收集信息→定位→处置→验证）
     * 动辄十几步工具调用，8 轮频繁把正常任务拦腰截断；上限只防死循环占住
     * 工作线程，30 轮仍是有界安全网。引用方（提示文案/测试断言）均由本常量拼接。</p>
     */
    static final int MAX_TOOL_ROUNDS = 30;

    /**
     * 重建上下文时保留的历史行数上限。
     *
     * <p>WHY 需要裁剪：模型有上下文长度上限，而一次排障对话可能积累上百条工具输出。
     * 超限时端点回 400，用户看到的是"模型服务返回错误"——完全无法自助。
     * 60 行足够覆盖十几轮工具调用，同时把最坏情况的 prompt 体积控制在可预测的量级。</p>
     */
    static final int MAX_HISTORY_ROWS = 60;

    /**
     * 恢复时的历史行数上限（正常水位的 50%）。
     *
     * <p>WHY 减半：design D7 要求"把输入预算降到原值的 50%"。
     * 行数减半是预算减半的近似实现——每条消息的 token 消耗大致均匀，
     * 行数减半 ≈ 体积减半。</p>
     */
    static final int RECOVERY_HISTORY_ROWS = MAX_HISTORY_ROWS / 2;

    /** 默认的输入预算（UTF-8 字节数），用于恢复时减半计算。 */
    static final int DEFAULT_INPUT_BUDGET = 120_000;

    /** {@code OpenAiChatModel} 把思考增量放进 {@code AssistantMessage} 元数据时用的键。 */
    static final String REASONING_METADATA_KEY = "reasoningContent";

    /** 达到轮次上限时写给用户的说明（会被落库，因此必须是明确"系统所写"的口吻）。 */
    static final String ROUND_LIMIT_NOTE =
            "（已达到单回合工具调用轮次上限 " + MAX_TOOL_ROUNDS + " 轮，本轮停止推进。"
                    + "请把问题拆小一些，或告诉我要继续哪一步。）";

    /** 用户取消审批终结回合时写给用户的说明（落库，口吻同 ROUND_LIMIT_NOTE）。 */
    static final String CANCEL_END_NOTE =
            "（已取消本次操作，本轮对话结束。请告诉我下一步。）";

    /** 回合被中断时写给用户的说明。 */
    static final String INTERRUPTED_NOTE = "（本轮处理被中断，命令未执行或结果未取回。请重新提问。）";

    /** 回合被用户停止时写给用户的说明。 */
    static final String STOPPED_NOTE = "（本轮处理已被用户停止。）";

    /** 上下文预算耗尽时写给用户的说明。 */
    static final String CONTEXT_BUDGET_EXCEEDED_NOTE =
            "（上下文预算已耗尽，无法继续处理。请缩短对话历史或开始新的对话。）";

    private static final String ERR_MISSING_API_KEY = MissingModelApiKeyException.MESSAGE;
    private static final String ERR_CONVERSATION_GONE = "会话不存在或已被删除";
    private static final String ERR_BUSY = "本会话正在处理上一条提问，请等待它完成后再发送";
    private static final String ERR_UNREACHABLE = "无法连接模型服务端点，请检查模型配置里的 base_url 与网络连通性";
    private static final String ERR_ENDPOINT = "模型服务返回错误，请稍后重试或查看后端日志获取详情";
    private static final String ERR_INTERNAL = "服务器内部错误，请查看后端日志获取详情";
    private static final String ERR_CONTEXT_BUDGET = "上下文预算已耗尽，请缩短对话历史或开始新对话";

    private static final TypeReference<List<Map<String, Object>>> PROPOSAL_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> ARGS_MAP = new TypeReference<>() {
    };

    /** 工作线程数：一个回合可能阻塞两分钟，留出并行余量给多个会话。 */
    private static final int WORKER_COUNT = 4;

    private final ChatModelProvider models;
    private final AgentTools agentTools;
    private final ApprovalGate gate;
    private final ApprovedCommandRunner runner;
    private final ConversationService conversations;
    private final ObjectProvider<AiStreamEmitter> emitters;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> toolCallbacks;
    private final Map<String, ToolCallback> callbacksByName;
    private final ExecutorService workers;
    private final ContextLimitClassifier contextLimitClassifier;

    /**
     * 运行账本服务（可空）。
     *
     * <p>WHY 可空：现有的多数测试不需要 run 跟踪。为恢复额度功能引入一个可选依赖，
     * 避免改动所有测试的装配代码。生产环境由 Spring 注入真实 bean。</p>
     */
    @Nullable
    private final AgentRunService runService;

    /**
     * 当前回合关联的 run id（可空）。
     *
     * <p>WHY 不在构造器固定而在 runTurn 传入：每次 runTurn 可能对应不同的 run。
     * 但为简化实现，当前版本在构造时传入（测试用），生产环境后续迭代改为 runTurn 参数。</p>
     */
    @Nullable
    private final String currentRunId;

    /**
     * 在飞回合表：键 = 会话 id，值 = 执行该回合的工作线程。
     *
     * <p>WHY 用 {@code ConcurrentHashMap} 而非 {@code Set}：
     * 停止机制（6.6）需要找到正在执行特定会话回合的线程并中断它。
     * {@code putIfAbsent} 返回 null 表示成功领取，返回非 null 表示已有回合在飞。</p>
     */
    private final ConcurrentHashMap<UUID, Thread> inFlight = new ConcurrentHashMap<>();

    /**
     * 权威停止标志：{@code stop()} 命中在飞回合时置位，回合结束（submit 的 finally）清除。
     *
     * <p>WHY 除 interrupt 外还要显式标志（BUG-B）：中断依赖每一层等待点都把
     * {@code InterruptedException} 透传上去，而历史上的工具回退/审批回落会把它吞成
     * 普通失败；只要有一层吞了，用户就停不下来。集合成员判断不依赖线程标志，
     * 在回合推进的每个检查点（轮首/工具间/流式增量）都能可靠终止。</p>
     */
    private final Set<UUID> stopRequested = ConcurrentHashMap.newKeySet();

    /** 在飞回合→终端会话 id：{@code stop()} 据此找到调度器打断远端在飞命令（BUG-B）。 */
    private final ConcurrentHashMap<UUID, UUID> inFlightSession = new ConcurrentHashMap<>();

    /**
     * 容器使用的构造器。
     *
     * <p>WHY 必须显式标 {@code @Autowired}：本类有多个构造器。Spring 面对多于一个候选构造器、
     * 且没有任何一个被标注时，会退回去找无参构造器——找不到就抛 {@code NoSuchMethodException}，
     * 症状是整个 ApplicationContext 加载失败、全部集成测试一起红。
     * 这个标注就是"哪一个才是装配入口"的显式声明。</p>
     */
    @Autowired
    public AiAgentService(ChatModelProvider models, AgentTools agentTools, ApprovalGate gate,
                          ApprovedCommandRunner runner, ConversationService conversations,
                          ObjectProvider<AiStreamEmitter> emitters, ObjectMapper objectMapper,
                          @Nullable AgentRunService runService) {
        this(models, agentTools, gate, runner, conversations, emitters, objectMapper,
                new ContextLimitClassifier(), runService, null,
                Executors.newFixedThreadPool(WORKER_COUNT, new WorkerThreadFactory()));
    }

    /** 测试用构造器（不含 run 跟踪）。 */
    AiAgentService(ChatModelProvider models, AgentTools agentTools, ApprovalGate gate,
                   ApprovedCommandRunner runner, ConversationService conversations,
                   ObjectProvider<AiStreamEmitter> emitters, ObjectMapper objectMapper,
                   ExecutorService workers) {
        this(models, agentTools, gate, runner, conversations, emitters, objectMapper,
                new ContextLimitClassifier(), null, null, workers);
    }

    /** 测试用构造器（含 run 跟踪，供恢复测试使用）。 */
    AiAgentService(ChatModelProvider models, AgentTools agentTools, ApprovalGate gate,
                   ApprovedCommandRunner runner, ConversationService conversations,
                   ObjectProvider<AiStreamEmitter> emitters, ObjectMapper objectMapper,
                   @Nullable AgentRunService runService, @Nullable String runId,
                   ExecutorService workers) {
        this(models, agentTools, gate, runner, conversations, emitters, objectMapper,
                new ContextLimitClassifier(), runService, runId, workers);
    }

    /** 全参构造，供测试注入同步执行器和自定义分类器。 */
    AiAgentService(ChatModelProvider models, AgentTools agentTools, ApprovalGate gate,
                   ApprovedCommandRunner runner, ConversationService conversations,
                   ObjectProvider<AiStreamEmitter> emitters, ObjectMapper objectMapper,
                   ContextLimitClassifier contextLimitClassifier,
                   @Nullable AgentRunService runService, @Nullable String runId,
                   ExecutorService workers) {
        this.models = Objects.requireNonNull(models, "models 不得为 null");
        this.gate = Objects.requireNonNull(gate, "gate 不得为 null");
        this.runner = Objects.requireNonNull(runner, "runner 不得为 null");
        this.conversations = Objects.requireNonNull(conversations, "conversations 不得为 null");
        this.emitters = Objects.requireNonNull(emitters, "emitters 不得为 null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不得为 null");
        this.workers = Objects.requireNonNull(workers, "workers 不得为 null");
        this.contextLimitClassifier = Objects.requireNonNull(contextLimitClassifier, "classifier 不得为 null");
        this.runService = runService;
        this.currentRunId = runId;
        // WHY 存字段：除反射工具回调外，系统提示词构建还要经它查会话 cwd（sessionCwdOf）
        this.agentTools = Objects.requireNonNull(agentTools, "agentTools 不得为 null");

        // WHY 在构造时就生成工具回调：@Tool 声明与 JSON Schema 是<b>静态</b>的
        // （目标服务器经 ToolContext 传入，不是方法参数），每回合重新反射一遍纯属浪费；
        // 而且提前生成能在启动期就暴露"工具名重复/参数无法建模"这类配置错误
        this.toolCallbacks = List.of(
                MethodToolCallbackProvider.builder().toolObjects(agentTools).build().getToolCallbacks());
        Map<String, ToolCallback> index = new LinkedHashMap<>();
        for (ToolCallback callback : this.toolCallbacks) {
            index.put(callback.getToolDefinition().name(), callback);
        }
        this.callbacksByName = Map.copyOf(index);
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 提交一个回合（异步）。
     *
     * @return 是否真的并开始执行。{@code false} 表示本会话已有回合在飞；
     *         此时已经发出一帧 {@code error(conflict)}，调用方不需要再通知用户
     */
    public boolean submit(TurnRequest request) {
        Objects.requireNonNull(request, "request 不得为 null");
        Thread currentThread = Thread.currentThread();
        Thread existing = inFlight.putIfAbsent(request.conversationId(), currentThread);
        if (existing != null) {
            LOG.info("会话已有回合在执行，忽略本次提问: conversationId={}", request.conversationId());
            emit(AiStreamFrame.error(request.conversationId(), ErrorCode.CONFLICT, ERR_BUSY));
            return false;
        }
        try {
            workers.execute(() -> {
                inFlight.put(request.conversationId(), Thread.currentThread());
                try {
                    runTurn(request);
                } catch (RuntimeException | Error e) {
                    LOG.error("智能体回合出现未捕获异常: conversationId={}", request.conversationId(), e);
                    emit(AiStreamFrame.error(request.conversationId(), ErrorCode.INTERNAL_ERROR, ERR_INTERNAL));
                } finally {
                    inFlight.remove(request.conversationId());
                    // 停止标志与回合同生命周期：回合已结束，残留会让下一回合开局即被停
                    stopRequested.remove(request.conversationId());
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(request.conversationId());
            LOG.error("智能体工作线程池已关闭，无法受理提问: conversationId={}", request.conversationId());
            emit(AiStreamFrame.error(request.conversationId(), ErrorCode.INTERNAL_ERROR, ERR_INTERNAL));
            return false;
        }
        return true;
    }

    /**
     * 停止指定会话的在飞回合（design D5 / 6.6）。
     *
     * @param conversationId 要停止的会话
     * @return 是否真的停止了某个回合。{@code false} 表示该会话没有回合在飞
     */
    public boolean stop(UUID conversationId) {
        if (conversationId == null) {
            return false;
        }
        Thread worker = inFlight.get(conversationId);
        if (worker == null) {
            LOG.info("停止请求未命中在飞回合: conversationId={}", conversationId);
            return false;
        }
        LOG.info("正在停止会话回合: conversationId={}", conversationId);
        // 权威标志先行：即使 interrupt 被某层吞掉，回合推进的检查点也能终止它（BUG-B）
        stopRequested.add(conversationId);
        // 仅中断本地线程不够：远端 PTY 上的命令（如安装中的 JDK）还在跑，
        // 必须由调度器对在飞命令发 Ctrl-C（BUG-B）
        UUID sessionId = inFlightSession.get(conversationId);
        if (sessionId != null) {
            agentTools.interruptInFlightCommand(sessionId.toString());
        }
        worker.interrupt();
        return true;
    }

    /**
     * 同步跑完一个回合。
     *
     * <p>WHY 单独暴露（包级）：这是 Wave 3 最需要被精确测试的一段——
     * 增量分流、思考模式、工具分级、审批挂起、错误分类，每一条都值得一个断言。
     * 让测试直接调它，就不必去等工作线程、不必猜时序。</p>
     */
    void runTurn(TurnRequest request) {
        UUID conversationId = request.conversationId();
        boolean thinking = false;
        UUID hostId = request.hostId();
        // 登记回合→会话映射，供 stop() 定位调度器打断远端命令（BUG-B）；
        // 放在 runTurn 而非 submit：包级 runTurn 也是测试/恢复路径的入口
        if (request.sessionId() != null) {
            inFlightSession.put(conversationId, request.sessionId());
        }
        try {
            AiConversation conversation = conversations.requireRow(conversationId);
            if (hostId == null) {
                hostId = conversations.hostIdOf(conversation);
            }
            String hostLabel = conversations.hostLabelOf(hostId);

            conversations.saveUserMessage(conversationId, request.userText());

            boolean toolsAvailable = hostId != null;
            List<ToolCallback> callbacks = toolsAvailable ? toolCallbacks : List.of();

            PreparedChatModel prepared = models.prepare(callbacks);
            thinking = prepared.thinkingMode() == ThinkingMode.THINKING;

            // WHY 注入 CTX_SESSION_ID：AgentTools 的只读工具据此把 list_dir/read_file/system_info
            // 路由到用户当前连接的持久 PTY（design D3）；缺失时 AgentTools 回落 exec，
            // 看不到用户在 Shell 里 cd 过的工作目录
            Map<String, Object> toolContext = buildToolContext(hostId, conversationId, request.sessionId());

            List<Message> prompt = new ArrayList<>();
            // 注入会话 cwd：用户 cd 后问“当前目录”，模型不告知就只能猜 /（浏览器验收实证）
            prompt.add(new SystemMessage(AgentSystemPrompt.build(hostLabel, toolsAvailable, thinking,
                    agentTools.sessionCwdOf(request.sessionId() == null ? null : request.sessionId().toString()))));
            prompt.addAll(history(conversationId, MAX_HISTORY_ROWS));

            loopRounds(prepared.chatModel(), prompt, conversationId, hostId, hostLabel,
                    toolsAvailable, thinking, toolContext);

        } catch (MissingModelApiKeyException e) {
            LOG.warn("模型未配置，回合终止: conversationId={}", conversationId);
            emitError(conversationId, ErrorCode.API_KEY_MISSING, ERR_MISSING_API_KEY, hostId, thinking);
        } catch (ConversationNotFoundException e) {
            LOG.warn("会话不存在，回合终止: conversationId={}", conversationId);
            emitError(conversationId, ErrorCode.NOT_FOUND, ERR_CONVERSATION_GONE, hostId, thinking);
        } catch (InterruptedException e) {
            // WHY 不恢复中断标志（反常规做法）：收尾帧要发回前端，而发帧（Tomcat WS
            // blocking send）就跑在本线程上——标志残留会让第一帧发送即失败、连接 1006
            // 半关闭，停止注记永远发不出去（run9 浏览器实测第五层断链）；本任务已结束，
            // 标志继续向上传播只会污染线程池后续任务
            Thread.interrupted();
            LOG.info("回合被中断（可能是用户停止）: conversationId={}", conversationId);
            finishWithNote(conversationId, STOPPED_NOTE, "stopped", hostId, thinking);
        } catch (RuntimeException e) {
            if (causedByInterrupt(e)) {
                // 用户打断在飞回合：langchain4j 阻塞流式读被 interrupt() 打断时，抛的是
                // 包住 InterruptedException 的 ReactiveException（RuntimeException 子类），
                // 永远到不了上面的 bare InterruptedException 分支（run8 浏览器实测第四层断链）；
                // 不识 cause 链就会把主动停止误判成端点错误，与「受控停止」语义相反。
                // 清标志同理由：reactor 抛异常时会恢复标志，收尾发帧必须用干净线程（#第五层）
                Thread.interrupted();
                LOG.info("回合被用户停止（阻塞读包装中断收尾）: conversationId={}", conversationId);
                finishWithNote(conversationId, STOPPED_NOTE, "stopped", hostId, thinking);
                return;
            }
            ErrorCode code = classify(e);
            LOG.warn("回合失败: conversationId={} error={} cause={}",
                    conversationId, code.getValue(), e.getClass().getSimpleName());
            LOG.debug("回合失败详情", e);
            emitError(conversationId, code, code == ErrorCode.MODEL_ENDPOINT_UNREACHABLE
                    ? ERR_UNREACHABLE : code == ErrorCode.MODEL_ENDPOINT_ERROR ? ERR_ENDPOINT : ERR_INTERNAL,
                    hostId, thinking);
        } finally {
            inFlightSession.remove(conversationId);
        }
    }

    /**
     * 沿 cause 链识别 InterruptedException（与 {@link #classify} 同风格的自引用/深度守卫）：
     * 阻塞式流读被 interrupt() 打断时，异常往往被框架包一至两层才到本层 catch。
     *
     * <p>{@link TurnCancelledException} 本身即「用户停止」标记，直接命中：
     * 权威停止标志检查点抛出的实例没有 InterruptedException 源（cause 为 null），
     * 沿链识别不到，必须按类型放行（BUG-B）。</p>
     */
    static boolean causedByInterrupt(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof InterruptedException || cursor instanceof TurnCancelledException) {
                return true;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return false;
    }

    // ==================================================================
    // 工具循环
    // ==================================================================

    private void loopRounds(ChatModel model, List<Message> prompt, UUID conversationId,
                            @Nullable UUID hostId, @Nullable String hostLabel, boolean toolsAvailable,
                            boolean thinking, Map<String, Object> toolContext) throws InterruptedException {
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            if (stopRequested.contains(conversationId) || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("回合被用户停止");
            }

            RoundResult result;
            try {
                result = streamRound(model, prompt, conversationId, thinking);
            } catch (RuntimeException e) {
                // WHY 在这里捕获而非在 runTurn：恢复需要重建 prompt 并让下一轮自然重试，
                // 而不是直接结束回合
                RecoveryOutcome outcome = tryRecoverFromContextLimit(e, prompt, conversationId,
                        hostId, toolsAvailable, thinking, sessionCwdOfContext(toolContext));
                switch (outcome) {
                    case RECOVERED -> {
                        // WHY 不在此处重试模型调用：让循环的下一轮自然调用模型，
                        // 工具调用也走正常的路由与审批流程，避免帧重复与逻辑分叉
                        continue;
                    }
                    case BUDGET_EXHAUSTED -> {
                        // WHY 直接 return 而非抛异常：错误帧（context_budget_exceeded）
                        // 已由 tryRecoverFromContextLimit 发出，再抛异常会让 runTurn 重复发帧
                        return;
                    }
                    case NOT_APPLICABLE -> throw e;
                }
                throw new AssertionError("unreachable");
            }

            if (result.calls.isEmpty()) {
                UUID messageId = conversations.saveAssistantMessage(
                        conversationId, result.text.toString(), result.reasoning.toString(), null);
                emit(decorate(AiStreamFrame.finished(conversationId, messageId, result.finishReason),
                        hostId, thinking));
                return;
            }

            LOG.info("模型请求工具调用: conversationId={} round={} calls={}",
                    conversationId, round, result.calls.size());

            List<ToolOutcome> outcomes = new ArrayList<>(result.calls.size());
            boolean userCancelled = false;
            for (MutableToolCall call : result.calls.values()) {
                // 工具间检查点：某个工具内部把停止吞成普通失败时，也不得继续
                // 执行后续工具/回喂模型——这就是用户看到的「停不下来」（BUG-B）
                if (stopRequested.contains(conversationId)) {
                    throw new InterruptedException("回合被用户停止");
                }
                ToolOutcome outcome = routeAndReport(call, conversationId, hostId, hostLabel,
                        toolsAvailable, toolContext);
                outcomes.add(outcome);
                if (outcome.userRejected()) {
                    // BUG-G：用户在审批弹窗点了「取消」——本轮终结信号，
                    // MUST NOT 再执行后续工具，也 MUST NOT 回喂模型发起下一轮
                    userCancelled = true;
                    break;
                }
            }

            // outcomes 可能因取消提前 break 而短于 calls：只落已处置的部分，
            // 未执行的提案被截断丢弃（本轮不会执行它们了）
            conversations.saveAssistantMessage(conversationId, result.text.toString(),
                    result.reasoning.toString(), proposals(result.calls, outcomes));

            List<AssistantMessage.ToolCall> springToolCalls = new ArrayList<>(outcomes.size());
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(outcomes.size());
            int index = 0;
            for (MutableToolCall call : result.calls.values()) {
                if (index >= outcomes.size()) {
                    break;
                }
                ToolOutcome outcome = outcomes.get(index++);
                conversations.saveToolMessage(conversationId, call.id, call.name,
                        outcome.params(), outcome.status(), outcome.text(), outcome.approvalId(),
                        outcome.status() == ToolResultStatus.REJECTED);
                String callId = call.id == null || call.id.isBlank() ? "call_" + index : call.id;
                springToolCalls.add(new AssistantMessage.ToolCall(callId, "function", call.name,
                        call.arguments.toString()));
                responses.add(new ToolResponseMessage.ToolResponse(callId, call.name, outcome.text()));
            }

            if (userCancelled) {
                // 拒绝事实不回喂：模型收不到 tool_result，也就没有「下一轮」可推理
                finishWithNote(conversationId, CANCEL_END_NOTE, "approval_cancelled",
                        hostId, thinking);
                return;
            }

            prompt.add(AssistantMessage.builder()
                    .content(result.text.toString())
                    .toolCalls(springToolCalls)
                    .build());
            prompt.add(ToolResponseMessage.builder().responses(responses).build());
        }

        LOG.warn("回合达到工具轮次上限: conversationId={} maxRounds={}", conversationId, MAX_TOOL_ROUNDS);
        finishWithNote(conversationId, ROUND_LIMIT_NOTE, "tool_round_limit", hostId, thinking);
    }

    // ==================================================================
    // 上下文超限恢复（design D7 "单次恢复"）
    // ==================================================================

    /**
     * 尝试从上下文超限中恢复。
     *
     * <p>WHY 不在恢复时重试模型调用：让循环的下一轮自然调用模型，
     * 工具调用会走正常的路由与审批流程（routeAndReport），
     * 避免帧重复与审批逻辑分叉。</p>
     *
     * @return {@link RecoveryOutcome} 指示调用方下一步动作
     */
    private RecoveryOutcome tryRecoverFromContextLimit(RuntimeException exception, List<Message> currentPrompt,
                                                        UUID conversationId, @Nullable UUID hostId,
                                                        boolean toolsAvailable, boolean thinking,
                                                        @Nullable String sessionCwd) {
        // 步骤 1：分类异常
        if (contextLimitClassifier.classify(exception) != ContextLimitClassifier.Verdict.CONTEXT_LIMIT) {
            return RecoveryOutcome.NOT_APPLICABLE;
        }

        LOG.info("检测到上下文超限，尝试恢复: conversationId={}", conversationId);

        // 步骤 2：检查恢复额度
        if (runService == null || currentRunId == null) {
            // WHY 没有 run 跟踪时不恢复：无法保证"只恢复一次"的约束
            LOG.warn("无 run 跟踪，无法执行上下文恢复: conversationId={}", conversationId);
            return RecoveryOutcome.NOT_APPLICABLE;
        }

        int recoveryCount = runService.getRecoveryCount(currentRunId);
        if (recoveryCount >= 1) {
            // WHY 只允许一次恢复：design D7 明确要求"每个用户 run 共享一次恢复额度"。
            // 多次恢复会导致无限重试循环，浪费计算资源且用户体验差
            LOG.info("恢复额度已用完，停止 run: conversationId={} recoveryCount={}",
                    conversationId, recoveryCount);
            emitError(conversationId, ErrorCode.CONTEXT_BUDGET_EXCEEDED, ERR_CONTEXT_BUDGET, hostId, thinking);
            return RecoveryOutcome.BUDGET_EXHAUSTED;
        }

        // 步骤 3：递增恢复计数
        runService.incrementRecoveryCount(currentRunId);

        // 步骤 4：发送 attempt_reset 帧
        UUID attemptId = UUID.randomUUID();
        emit(AiStreamFrame.attemptReset(conversationId, attemptId, 2));

        // 步骤 5：重建 prompt（使用减半的历史窗口）
        // WHY 不在此处重试模型调用：让 loopRounds 的下一轮自然调用模型。
        // 这样工具调用走正常的 routeAndReport 流程（含审批），
        // 帧只发一次，审批规则与正常轮次完全一致
        currentPrompt.clear();
        currentPrompt.add(new SystemMessage(AgentSystemPrompt.build(
                conversations.hostLabelOf(hostId), toolsAvailable, thinking, sessionCwd)));
        currentPrompt.addAll(history(conversationId, RECOVERY_HISTORY_ROWS));

        return RecoveryOutcome.RECOVERED;
    }

    // ==================================================================
    // 流式响应消费
    // ==================================================================

    /**
     * 消费一次流式响应，边收边把增量推给前端。
     */
    private RoundResult streamRound(ChatModel model, List<Message> prompt,
                                    UUID conversationId, boolean thinking) {
        RoundResult result = new RoundResult();
        // WHY 始终创建内联标签解析器：Qwen3 类模型在 content 流内嵌 <think>...</think> 标签，
        // 无论 thinkingMode 是否启用（thinkingMode 只控制请求侧 enable_thinking 与
        // reasoning_content 元数据读取），模型实际输出的内联标签都需要分流到 thinking_delta，
        // 否则 <think> 原文会作为 answer_delta 透传给用户——这是用户在内网电脑看到的症状。
        // 若模型不输出 <think> 标签，解析器零开销直通，不影响正常回答。
        InlineThinkTagParser inlineThink = new InlineThinkTagParser();
        for (ChatResponse chunk : model.stream(new Prompt(prompt)).toIterable()) {
            if (stopRequested.contains(conversationId)) {
                // 流式读期间停止：立即中断消费，不把半截回答继续推给前端（BUG-B）
                throw new TurnCancelledException("流式输出期间被用户停止", null);
            }
            if (chunk == null) {
                continue;
            }
            List<Generation> generations = chunk.getResults();
            if (generations == null || generations.isEmpty()) {
                continue;
            }
            Generation generation = generations.get(0);
            AssistantMessage output = generation.getOutput();
            if (output != null) {
                String delta = output.getText();
                if (delta != null && !delta.isEmpty()) {
                    if (inlineThink == null) {
                        result.text.append(delta);
                        emit(AiStreamFrame.answerDelta(conversationId, delta));
                    } else {
                        for (InlineThinkTagParser.Segment segment : inlineThink.feed(delta)) {
                            emitSegment(segment, conversationId, result);
                        }
                    }
                }
                if (thinking) {
                    Object reasoning = output.getMetadata() == null
                            ? null : output.getMetadata().get(REASONING_METADATA_KEY);
                    if (reasoning instanceof String text && !text.isEmpty()) {
                        result.reasoning.append(text);
                        emit(AiStreamFrame.thinkingDelta(conversationId, text));
                    }
                }
                for (AssistantMessage.ToolCall call : output.getToolCalls()) {
                    result.absorb(call);
                }
            }
            ChatGenerationMetadata metadata = generation.getMetadata();
            if (metadata != null) {
                String finishReason = metadata.getFinishReason();
                if (finishReason != null && !finishReason.isEmpty()) {
                    result.finishReason = finishReason;
                }
            }
        }
        // 回合结束先吐待定尾：未闭合标签的余文归思考，不凭空丢字
        if (inlineThink != null) {
            for (InlineThinkTagParser.Segment segment : inlineThink.flush()) {
                emitSegment(segment, conversationId, result);
            }
        }
        return result;
    }

    /** 把一个内联标签分流段路由到对应帧型与累积器（思考走 thinking_delta，其余走 answer_delta）。 */
    private void emitSegment(InlineThinkTagParser.Segment segment, UUID conversationId,
                             RoundResult result) {
        if (segment.thinking()) {
            result.reasoning.append(segment.text());
            emit(AiStreamFrame.thinkingDelta(conversationId, segment.text()));
        } else {
            result.text.append(segment.text());
            emit(AiStreamFrame.answerDelta(conversationId, segment.text()));
        }
    }

    // ==================================================================
    // 工具分级路由（tasks 9.2 / 9.3）
    // ==================================================================

    private ToolOutcome routeAndReport(MutableToolCall call, UUID conversationId, @Nullable UUID hostId,
                                       @Nullable String hostLabel, boolean toolsAvailable,
                                       Map<String, Object> toolContext) {
        ToolName toolName = ToolName.fromValue(call.name);
        ToolOutcome outcome = route(call, toolName, conversationId, hostId, hostLabel,
                toolsAvailable, toolContext);
        if (toolName != null) {
            emitToolResult(conversationId, toolName, outcome, toolName.autoExecuted());
        }
        return outcome;
    }

    private ToolOutcome route(MutableToolCall call, @Nullable ToolName toolName, UUID conversationId,
                              @Nullable UUID hostId, @Nullable String hostLabel, boolean toolsAvailable,
                              Map<String, Object> toolContext) {
        if (toolName == null) {
            LOG.warn("模型请求了未知工具: conversationId={} tool={}", conversationId, call.name);
            Map<String, Object> params = parseArguments(call);
            return new ToolOutcome(ToolResultStatus.ERROR, params, null,
                    "错误：不存在名为 " + call.name + " 的工具。可用工具为 list_dir、read_file、"
                            + "system_info、run_command。", false);
        }

        Map<String, Object> params = parseArguments(call);
        if (params == null) {
            emitTool(conversationId, toolName, Map.of(), true, null);
            return new ToolOutcome(ToolResultStatus.ERROR, Map.of(), null,
                    "错误：工具参数不是合法的 JSON 对象，请检查后重试。", false);
        }

        if (!toolsAvailable || hostId == null) {
            emitTool(conversationId, toolName, params, toolName.autoExecuted(), null);
            return new ToolOutcome(ToolResultStatus.ERROR, params, null,
                    "错误：本会话未绑定目标服务器，无法执行任何运维工具。", false);
        }

        if (toolName.autoExecuted()) {
            return runReadOnly(toolName, params, call, conversationId, toolContext);
        }
        return runGated(toolName, params, conversationId, hostId, hostLabel, toolContext);
    }

    /**
     * 构造工具上下文：只读工具与审批后执行共用的路由信息。
     *
     * <p>WHY 条件式而非 {@code Map.of}：{@code Map.of} 拒绝 null 值，而
     * {@code sessionId} 恰好合法可空（未建连接的纯问答回合）；可空字段直接不放入，
     * 下游的 {@code instanceof String} 判断自然得到 null。</p>
     */
    private static Map<String, Object> buildToolContext(@Nullable UUID hostId, UUID conversationId,
                                                         @Nullable UUID sessionId) {
        if (hostId == null) {
            return Map.of();
        }
        if (sessionId == null) {
            return Map.of(AgentTools.CTX_HOST_ID, hostId, AgentTools.CTX_CONVERSATION_ID, conversationId);
        }
        return Map.of(AgentTools.CTX_HOST_ID, hostId, AgentTools.CTX_CONVERSATION_ID, conversationId,
                AgentTools.CTX_SESSION_ID, sessionId.toString());
    }

    /**
     * 从工具上下文取会话 cwd：恢复重建提示词时与首轮构建共用同一个 sessionId 来源，
     * 避免恢复后的轮次反而丢失用户当前目录上下文。
     */
    private String sessionCwdOfContext(Map<String, Object> toolContext) {
        Object value = toolContext.get(AgentTools.CTX_SESSION_ID);
        return value instanceof String s ? agentTools.sessionCwdOf(s) : null;
    }

    private ToolOutcome runReadOnly(ToolName toolName, Map<String, Object> params, MutableToolCall call,
                                    UUID conversationId, Map<String, Object> toolContext) {
        emitTool(conversationId, toolName, params, true, null);
        ToolCallback callback = callbacksByName.get(toolName.getValue());
        if (callback == null) {
            LOG.error("只读工具没有对应的回调: tool={}", toolName.getValue());
            return new ToolOutcome(ToolResultStatus.ERROR, params, null,
                    "错误：工具 " + toolName.getValue() + " 当前不可用。", false);
        }
        try {
            String result = callback.call(call.arguments.toString(), new ToolContext(toolContext));
            return new ToolOutcome(ToolResultStatus.SUCCESS, params, null,
                    result == null ? "" : result, false);
        } catch (RuntimeException e) {
            if (causedByInterrupt(e)) {
                // BUG-B：工具 callback 层会把 TurnCancelledException 包成框架异常；
                // 识别后 MUST 继续上抛而不是伪装成 tool_result(ERROR) 回喂模型——
                // 模型收到「执行失败」就会接着分析另想办法，表现为停不下来
                throw new TurnCancelledException("只读工具执行中被用户停止", e);
            }
            LOG.warn("只读工具执行失败: tool={} cause={}", toolName.getValue(), e.getClass().getSimpleName());
            LOG.debug("只读工具失败详情", e);
            return new ToolOutcome(ToolResultStatus.ERROR, params, null,
                    "错误：工具 " + toolName.getValue() + " 执行失败，请检查参数后重试。", false);
        }
    }

    private ToolOutcome runGated(ToolName toolName, Map<String, Object> params,
                                 UUID conversationId, UUID hostId, @Nullable String hostLabel,
                                 Map<String, Object> toolContext) {
        String command = textOf(params.get("command"));
        String analysis = textOf(params.get("ai_analysis"));
        if (command == null || command.isBlank()) {
            emitTool(conversationId, toolName, params, false, null);
            return new ToolOutcome(ToolResultStatus.ERROR, params, null,
                    "错误：run_command 缺少 command 参数，请补全后重试。", false);
        }

        ApprovalProposal proposal = new ApprovalProposal(conversationId, hostId, hostLabel,
                toolName, params, command, analysis == null ? "" : analysis, null);
        ApprovalGate.Ticket ticket = gate.submit(proposal);
        emitTool(conversationId, toolName, params, false, ticket.approvalId());

        ApprovalOutcome outcome;
        try {
            outcome = gate.await(ticket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("等待审批时被中断: approvalId={}", ticket.approvalId());
            // 中断属于停止流程而非用户主动取消裁决，不触发终结回合
            return rejected(params, ticket.approvalId(), INTERRUPTED_NOTE, false);
        }

        if (!outcome.approved()) {
            String note = outcome == ApprovalOutcome.TIMED_OUT
                    ? ApprovalAuditService.TIMED_OUT_NOTE
                    : ApprovalAuditService.REJECTED_NOTE;
            LOG.info("命令未获批准: approvalId={} outcome={}", ticket.approvalId(), outcome);
            // 只有用户点「取消」（CANCELLED）才是终结回合的信号；
            // 超时（TIMED_OUT）维持回喂现状，让模型知道等不到裁决
            return rejected(params, ticket.approvalId(), note,
                    outcome == ApprovalOutcome.CANCELLED);
        }

        // WHY 回读生效命令：用户在弹窗上修改命令后再批准（design D5），落到远端的
        // 必须是新版本命令——局部变量 command 是提案原文，直接用会让修改形同虚设
        String approvedCommand = gate.effectiveCommand(ticket.approvalId(), command);
        // WHY 经共享 PTY 执行：用户先在 Shell 里 cd，再切 Agent 模式提问时，
        // 获准命令必须在同一持久 Shell 里跑（继承 cwd/env）且输出回流到终端；
        // sessionId 缺失（未建连接的纯问答）时才回落 exec 通道
        Object sessionId = toolContext.get(AgentTools.CTX_SESSION_ID);
        ApprovedCommandRunner.Result executed = runner.run(ticket.approvalId(), hostId, approvedCommand,
                sessionId instanceof String s ? s : null);
        return new ToolOutcome(executed.status(), params, ticket.approvalId(), executed.feedback(), false);
    }

    private static ToolOutcome rejected(Map<String, Object> params, UUID approvalId, String note,
                                        boolean userRejected) {
        return new ToolOutcome(ToolResultStatus.REJECTED, params, approvalId, note, userRejected);
    }

    // ==================================================================
    // 上下文重建
    // ==================================================================

    private List<Message> history(UUID conversationId, int maxRows) {
        List<AiMessage> rows = conversations.loadHistory(conversationId);
        int from = Math.max(0, rows.size() - maxRows);
        List<Message> result = new ArrayList<>(rows.size() - from);
        for (AiMessage row : rows.subList(from, rows.size())) {
            appendHistoryRow(result, row);
        }
        return sanitize(result);
    }

    private void appendHistoryRow(List<Message> result, AiMessage row) {
        String role = row.getRole();
        if (ConversationService.ROLE_USER.equals(role)) {
            result.add(new UserMessage(nullToEmpty(row.getContent())));
        } else if (ConversationService.ROLE_ASSISTANT.equals(role)) {
            AssistantMessage.Builder builder = AssistantMessage.builder()
                    .content(nullToEmpty(row.getContent()));
            List<AssistantMessage.ToolCall> calls = parseProposals(row.getToolCalls());
            if (!calls.isEmpty()) {
                builder.toolCalls(calls);
            }
            result.add(builder.build());
        } else if (ConversationService.ROLE_TOOL.equals(role)) {
            result.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            nullToEmpty(row.getToolCallId()), nullToEmpty(row.getToolName()),
                            nullToEmpty(row.getToolResult()))))
                    .build());
        }
    }

    private static List<Message> sanitize(List<Message> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage) {
                Message previous = out.isEmpty() ? null : out.get(out.size() - 1);
                boolean paired = (previous instanceof AssistantMessage assistant && assistant.hasToolCalls())
                        || previous instanceof ToolResponseMessage;
                if (!paired) {
                    continue;
                }
            } else if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                boolean followed = i + 1 < messages.size()
                        && messages.get(i + 1) instanceof ToolResponseMessage;
                if (!followed) {
                    continue;
                }
            }
            out.add(message);
        }
        return out;
    }

    private List<AssistantMessage.ToolCall> parseProposals(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, PROPOSAL_LIST);
            List<AssistantMessage.ToolCall> calls = new ArrayList<>(raw.size());
            for (Map<String, Object> item : raw) {
                String id = textOf(item.get("id"));
                String name = textOf(item.get("name"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                calls.add(new AssistantMessage.ToolCall(
                        id == null || id.isBlank() ? "call_" + calls.size() + 1 : id,
                        "function", name, nullToEmpty(textOf(item.get("arguments")))));
            }
            return calls;
        } catch (IOException | RuntimeException e) {
            LOG.warn("assistant 行的 tool_calls 无法解析: cause={}", String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    private List<Map<String, Object>> proposals(Map<String, MutableToolCall> calls,
                                                List<ToolOutcome> outcomes) {
        List<Map<String, Object>> proposals = new ArrayList<>(outcomes.size());
        int index = 0;
        for (MutableToolCall call : calls.values()) {
            if (index >= outcomes.size()) {
                // 取消提前 break：未处置的提案不配对、不落库
                break;
            }
            ToolOutcome outcome = outcomes.get(index++);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.id);
            item.put("name", call.name);
            item.put("arguments", call.arguments.toString());
            if (outcome.approvalId() != null) {
                item.put("approval_id", outcome.approvalId().toString());
            }
            proposals.add(item);
        }
        return proposals;
    }

    @Nullable
    private Map<String, Object> parseArguments(MutableToolCall call) {
        String raw = call.arguments.toString();
        if (raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, ARGS_MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (IOException | RuntimeException e) {
            LOG.warn("工具入参不是合法 JSON: tool={} cause={}", call.name, String.valueOf(e.getMessage()));
            return null;
        }
    }

    // ==================================================================
    // 出站
    // ==================================================================

    private void emitTool(UUID conversationId, ToolName toolName, Map<String, Object> params,
                          boolean auto, @Nullable UUID approvalId) {
        if (auto != toolName.autoExecuted() || (!auto && approvalId == null)) {
            LOG.warn("无法构造合法的 tool_call 帧，已跳过: conversationId={} tool={}",
                    conversationId, toolName.getValue());
            return;
        }
        emit(AiStreamFrame.toolCall(conversationId, auto
                ? ToolCallEventFrame.autoExecuted(toolName, params)
                : ToolCallEventFrame.pendingApproval(toolName, params, approvalId)));
    }

    private void emitToolResult(UUID conversationId, ToolName toolName, ToolOutcome outcome, boolean auto) {
        emit(AiStreamFrame.toolResult(conversationId, ToolCallEventFrame.result(
                toolName, outcome.params(), auto, outcome.approvalId(), outcome.status(), outcome.text())));
    }

    private void emitError(UUID conversationId, ErrorCode code, String message,
                           @Nullable UUID hostId, boolean thinking) {
        AiStreamFrame frame = AiStreamFrame.error(conversationId, code, message);
        if (hostId != null) {
            frame = frame.withHostId(hostId).withThinkingMode(thinking);
        }
        emit(frame);
    }

    private void finishWithNote(UUID conversationId, String note, String finishReason,
                                @Nullable UUID hostId, boolean thinking) {
        emit(AiStreamFrame.answerDelta(conversationId, note));
        UUID messageId = conversations.saveAssistantMessage(conversationId, note, null, null);
        emit(decorate(AiStreamFrame.finished(conversationId, messageId, finishReason), hostId, thinking));
    }

    private static AiStreamFrame decorate(AiStreamFrame frame, @Nullable UUID hostId, boolean thinking) {
        AiStreamFrame result = frame.withThinkingMode(thinking);
        return hostId == null ? result : result.withHostId(hostId);
    }

    private void emit(AiStreamFrame frame) {
        try {
            AiStreamEmitter emitter = emitters.getIfAvailable();
            if (emitter == null) {
                LOG.debug("无 ai_stream 出口，帧被丢弃: type={}", frame.type());
                return;
            }
            emitter.emit(frame);
        } catch (RuntimeException e) {
            LOG.warn("发送 ai_stream 帧失败（已忽略）: type={} cause={}",
                    frame.type(), String.valueOf(e.getMessage()));
        }
    }

    // ==================================================================
    // 错误分类
    // ==================================================================

    static ErrorCode classify(Throwable error) {
        if (error instanceof MissingModelApiKeyException) {
            return ErrorCode.API_KEY_MISSING;
        }
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof ResourceAccessException
                    || cursor instanceof java.net.ConnectException
                    || cursor instanceof java.net.UnknownHostException
                    || cursor instanceof java.net.NoRouteToHostException
                    || cursor instanceof java.net.SocketTimeoutException
                    || cursor instanceof java.net.UnknownServiceException) {
                return ErrorCode.MODEL_ENDPOINT_UNREACHABLE;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return ErrorCode.MODEL_ENDPOINT_ERROR;
    }

    // ==================================================================
    // 生命周期与可观测性
    // ==================================================================

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        LOG.info("智能体工作线程池已关闭: 在飞回合={}", inFlight.size());
    }

    int inFlightCount() {
        return inFlight.size();
    }

    Set<String> registeredToolNames() {
        return callbacksByName.keySet();
    }

    // ==================================================================
    // 内部数据类型
    // ==================================================================

    /**
     * 恢复操作的处置结果。
     *
     * <p>WHY 用枚举而非 boolean：三种结果需要不同的后续处理，
     * boolean 只能表达两种，第三种要靠副作用推断，可读性差且容易出错。</p>
     */
    private enum RecoveryOutcome {
        /** prompt 已重建，调用方应继续循环的下一轮。 */
        RECOVERED,
        /** 恢复额度已用完，错误帧已发出，调用方应静默结束回合。 */
        BUDGET_EXHAUSTED,
        /** 不是上下文超限错误，调用方应传播原始异常。 */
        NOT_APPLICABLE
    }

    /** 一次工具调用的处置结果。userRejected = 用户在弹窗点了取消（回合终结信号，BUG-G）。 */
    private record ToolOutcome(ToolResultStatus status, Map<String, Object> params,
                               @Nullable UUID approvalId, String text, boolean userRejected) {
    }

    /** 一次流式响应的累积结果。 */
    private static final class RoundResult {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final LinkedHashMap<String, MutableToolCall> calls = new LinkedHashMap<>();
        @Nullable private String lastKey;
        @Nullable private String finishReason;

        void absorb(AssistantMessage.ToolCall call) {
            if (call == null) return;
            boolean hasId = call.id() != null && !call.id().isBlank();
            String key;
            if (hasId) {
                key = call.id();
            } else if (lastKey != null) {
                key = lastKey;
            } else {
                key = "#" + calls.size();
            }
            lastKey = key;
            MutableToolCall aggregated = calls.computeIfAbsent(key, k -> new MutableToolCall());
            if (hasId) aggregated.id = call.id();
            if (call.name() != null && !call.name().isBlank()) aggregated.name = call.name();
            aggregated.appendArguments(call.arguments());
        }
    }

    /** 一个正在聚合中的工具调用。 */
    private static final class MutableToolCall {
        @Nullable private String id;
        @Nullable private String name;
        private final StringBuilder arguments = new StringBuilder();

        void appendArguments(@Nullable String fragment) {
            if (fragment == null || fragment.isEmpty()) return;
            String soFar = arguments.toString();
            if (soFar.isEmpty()) { arguments.append(fragment); return; }
            if (fragment.equals(soFar)) return;
            if (fragment.length() > soFar.length() && fragment.startsWith(soFar)) {
                arguments.setLength(0);
                arguments.append(fragment);
                return;
            }
            arguments.append(fragment);
        }
    }

    /** 命名 + 守护线程。 */
    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ai-agent-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    @Nullable
    private static String textOf(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        return String.valueOf(value);
    }
}

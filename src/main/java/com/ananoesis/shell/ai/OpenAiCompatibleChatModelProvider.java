package com.ananoesis.shell.ai;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import com.ananoesis.shell.ai.ModelEndpointResolver.ResolvedEndpoint;
import com.ananoesis.shell.contract.model.ThinkingRequestFormat;
import com.ananoesis.shell.security.SecretText;

import io.micrometer.observation.ObservationRegistry;

/**
 * OpenAI 兼容端点的 {@link ChatModel} 装配器（design D2/D5，tasks 7.2）。
 *
 * <h2>WHY 一种实现就够覆盖 provider 切换</h2>
 * <p>MindIE、OpenAI、Ollama、DashScope 兼容模式暴露的都是同一套
 * {@code POST {base}/chat/completions} 协议，差别只在 base_url、模型名与少量方言字段。
 * 因此"provider 切换"在本阶段等价于"换一组坐标重新装配"，不需要按 provider 造分支；
 * 唯一需要分流的是思考模式的请求格式（{@link ThinkingRequestFormat}）。</p>
 *
 * <h2>WHY 手动装配而不用 Spring AI 的自动配置</h2>
 * <p>见 {@link ChatModelProvider} 的类注释：端点坐标是运行期可改的用户数据，
 * 而自动配置的单例只在启动时读一次 {@code application.yml}。</p>
 *
 * <h2>安全底线</h2>
 * <ul>
 *   <li>api key 只在 try-with-resources 作用域内以 {@link SecretText} 存在，
 *       装配一结束立即擦除；日志只记 provider/model/工具数等非敏感坐标。</li>
 *   <li>{@code OpenAiApi} 会把 api key 放进 {@code Authorization} 头。
 *       {@code application.yml} 已把 {@code org.springframework.web.client} 与
 *       {@code HttpLogging} 压到 INFO，正是为了不让 RestClient 的 DEBUG 日志打印请求头。</li>
 * </ul>
 */
@Component
public class OpenAiCompatibleChatModelProvider implements ChatModelProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleChatModelProvider.class);

    /** OpenAI 兼容协议里对话补全的固定尾段。 */
    private static final String COMPLETIONS_SUFFIX = "/chat/completions";
    /** base_url 未带任何路径时使用的默认补全路径（与 Spring AI 的默认值一致）。 */
    private static final String DEFAULT_COMPLETIONS_PATH = "/v1" + COMPLETIONS_SUFFIX;

    /**
     * 思考模式开关在 {@code extra_body} 里的两个键名。
     *
     * <p>WHY 两个都写：Qwen3 经 vLLM/MindIE 部署时读 {@code chat_template_kwargs.enable_thinking}，
     * DashScope 兼容模式读顶层 {@code enable_thinking}。同一份用户配置在两种部署下都要生效，
     * 与其让用户判断自己那套属于哪种，不如两个都给——多余的那个会被服务端忽略。</p>
     */
    private static final String KEY_ENABLE_THINKING = "enable_thinking";
    private static final String KEY_CHAT_TEMPLATE_KWARGS = "chat_template_kwargs";

    private final ModelEndpointResolver endpoints;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;

    /**
     * 容器使用的构造器。
     *
     * <p>WHY 必须显式标 {@code @Autowired}：本类有<b>两个</b>构造器（下面那个包级的供测试
     * 注入替身）。经字节码核实，Spring 6.x 的 {@code BeanUtils.findPrimaryConstructor}
     * 对<b>非 Kotlin 类恒返回 null</b>，于是"只有一个 public 构造器"这条直觉并<b>不</b>成立——
     * 容器在多候选构造器且无一被标注时，会退回去找无参构造器，
     * 找不到就抛 {@code NoSuchMethodException: <init>()}，
     * 症状是整个 ApplicationContext 加载失败、全部集成测试一起红。
     * 这个标注就是"哪一个才是装配入口"的显式声明。</p>
     */
    @Autowired
    public OpenAiCompatibleChatModelProvider(ModelEndpointResolver endpoints) {
        // WHY 显式给出这三个依赖而不用 Builder 的默认值：默认值随 Spring AI 小版本变动
        // （1.0.x 的 Builder 字段甚至可能为 null 直到 build() 才补），显式赋值让
        // "重试策略是什么、观测是否开启"成为本类可读的事实，而不是要去翻依赖源码的猜测。
        this(endpoints, ToolCallingManager.builder().build(), noRetry(), ObservationRegistry.NOOP);
    }

    /**
     * 全参构造，供测试注入替身。
     *
     * <p>WHY {@code noRetry()}：这是单机桌面应用，端点不可达时用户就坐在屏幕前。
     * Spring AI 的默认重试模板会做多次指数退避，结果是"点了发送后界面转圈几十秒，
     * 最后才报端点不可达"——重试在这里不带来任何可用性收益，只是把失败拖长。
     * tasks 7.4 要求端点错误<b>不静默失败</b>，立即报告正是它的一部分。</p>
     */
    OpenAiCompatibleChatModelProvider(ModelEndpointResolver endpoints,
                                      ToolCallingManager toolCallingManager,
                                      RetryTemplate retryTemplate,
                                      ObservationRegistry observationRegistry) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints 不得为 null");
        this.toolCallingManager = Objects.requireNonNull(toolCallingManager, "toolCallingManager 不得为 null");
        this.retryTemplate = Objects.requireNonNull(retryTemplate, "retryTemplate 不得为 null");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry 不得为 null");
    }

    private static RetryTemplate noRetry() {
        return RetryTemplate.builder().maxAttempts(1).noBackoff().build();
    }

    @Override
    public PreparedChatModel prepare(List<ToolCallback> toolCallbacks) {
        Objects.requireNonNull(toolCallbacks, "toolCallbacks 不得为 null；'没有工具'请用空列表表达");
        ResolvedEndpoint endpoint = endpoints.resolve();

        // try-with-resources：SecretText 出作用域即擦除，明文驻留时间被压到装配的一瞬间
        try (SecretText apiKey = endpoint.apiKey()) {
            OpenAiApi api = OpenAiApi.builder()
                    // WHY 只传 origin：Spring AI 用 baseUrl + completionsPath 拼接最终地址。
                    // 若把带 /v1 的完整 base_url 交给 baseUrl，就会得到 /v1/v1/chat/completions
                    .baseUrl(originOf(endpoint.baseUrl()))
                    .apiKey(apiKey.revealAsString())
                    .completionsPath(completionsPathOf(endpoint.baseUrl()))
                    .build();

            // 构建 extra_body：思考模式开关 + 输出上限字段（design D7/D8）
            Map<String, Object> extraBody = thinkingExtraBody(endpoint.thinkingRequestFormat());
            // 输出上限写入配置的字段名（design D7 步骤 8）
            extraBody.put(endpoint.outputLimitField().getValue(), endpoint.maxOutputTokens());

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(endpoint.model())
                    .toolCallbacks(List.copyOf(toolCallbacks))
                    // tasks 9.1：工具执行权收归应用，run_command 才有机会被审批闸门拦下
                    // WHY 关掉并行工具调用：流式下发时，多个调用的 arguments 片段会交错，
                    // 而片段只在首个包里带 id，聚合就只能靠"最近一个在聚合的调用"来归属，
                    // 交错必然张冠李戴。关掉之后每轮至多一个调用，聚合是确定性的；
                    // 附带的好处是任何时刻至多弹出一个审批框，用户不会面对一排弹框无从下手。
                    // 代价是模型每轮只能提一个动作、往返次数变多——对运维排障这种
                    // "看一眼再决定下一步"的场景，本来就是顺序的，代价可忽略。
                    .parallelToolCalls(false)
                    .internalToolExecutionEnabled(false)
                    .extraBody(extraBody)
                    .build();

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .toolCallingManager(toolCallingManager)
                    .retryTemplate(retryTemplate)
                    .observationRegistry(observationRegistry)
                    .build();

            // MUST NOT 记录 endpoint（其 apiKey 字段虽是 SecretText、toString 恒为掩码，
            // 但"日志里绝不出现凭据载体"这条纪律不该依赖某个类型的 toString 恰好安全）
            LOG.info("已装配模型客户端: provider={} model={} thinking={} format={} outputLimit={} tools={}",
                    endpoint.provider(), endpoint.model(), endpoint.thinkingMode().getValue(),
                    endpoint.thinkingRequestFormat(), endpoint.outputLimitField(),
                    toolCallbacks.size());
            return new PreparedChatModel(chatModel, endpoint.provider(), endpoint.model(),
                    endpoint.thinkingMode());
        }
    }

    // ======================================================================
    // 端点坐标归一化
    // ======================================================================

    /**
     * base_url 的 origin 部分（{@code scheme://host[:port]}）。
     *
     * <p>WHY 自己拼而不用 {@code URI.resolve}：用户填的 base_url 可能带路径、可能带尾斜杠、
     * 可能省略默认端口。把这些形态统一成"origin + 路径"两段，拼接结果才可预测。</p>
     */
    static String originOf(URI baseUrl) {
        StringBuilder origin = new StringBuilder(baseUrl.getScheme()).append("://").append(baseUrl.getHost());
        int port = baseUrl.getPort();
        if (port > 0) {
            origin.append(':').append(port);
        }
        return origin.toString();
    }

    /**
     * 由 base_url 推导对话补全的相对路径。
     *
     * <p>规则：把 base_url 的路径整体当作补全地址的前缀，追加 {@code /chat/completions}；
     * 路径为空时用默认的 {@code /v1/chat/completions}；用户已经填到完整补全地址时原样使用。</p>
     *
     * <p>WHY 需要这层归一化：MindIE 与 DashScope 的文档给出的 base_url <b>已经</b>带 {@code /v1}，
     * 而 Spring AI 的默认补全路径也带 {@code /v1}。直接拼接会得到
     * {@code /v1/v1/chat/completions}，症状是一个不解释任何原因的 404——
     * 用户完全无法从"404"联想到"我多写了一段路径"。</p>
     */
    static String completionsPathOf(URI baseUrl) {
        String path = baseUrl.getPath() == null ? "" : baseUrl.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return DEFAULT_COMPLETIONS_PATH;
        }
        if (path.endsWith(COMPLETIONS_SUFFIX)) {
            return path;
        }
        return path + COMPLETIONS_SUFFIX;
    }

    /**
     * 思考模式的请求侧开关（design D8）。
     *
     * <p>WHY 不再按 provider 判断：V2 引入 {@link ThinkingRequestFormat} 后，
     * 是否发送思考开关由用户显式配置决定，不再隐式依赖 provider 名称。
     * 旧 provider=openai 配置迁移为 {@code NONE}，其他迁移为 {@code QWEN_COMPATIBLE}，
     * 保持旧请求行为不变（见 {@code ModelConfigService#migrateIfNeeded}）。</p>
     *
     * @return 可变且非 null 的 Map（可能为空），便于调用方直接交给 {@code extraBody(...)}
     */
    static Map<String, Object> thinkingExtraBody(ThinkingRequestFormat format) {
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (format == ThinkingRequestFormat.NONE) {
            // WHY NONE 时返回空 Map：不发送任何思考开关字段，
            // 适用于 OpenAI 官方 API（会以 400 拒绝未建模的请求字段）
            // 以及任何不需要思考开关的端点
            return extraBody;
        }
        // QWEN_COMPATIBLE：发送两组思考开关，兼容 Qwen3 经 vLLM/MindIE 和 DashScope 两种部署
        boolean enabled = true;
        extraBody.put(KEY_ENABLE_THINKING, enabled);
        extraBody.put(KEY_CHAT_TEMPLATE_KWARGS, Map.of(KEY_ENABLE_THINKING, enabled));
        return extraBody;
    }
}

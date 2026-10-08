package com.ananoesis.shell.ai;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.ananoesis.shell.contract.model.OutputLimitField;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.contract.model.ThinkingRequestFormat;
import com.ananoesis.shell.security.MissingModelApiKeyException;
import com.ananoesis.shell.security.SecretText;

/**
 * tasks 7.2 的验收：运行时**手动**装配 OpenAI 兼容的 {@link ChatModel}。
 *
 * <p>WHY 是纯单元测试而不启 Spring 上下文：本类的全部价值都在"装配参数是否正确"上，
 * 而 {@code application.yml} 里 {@code spring.ai.model.chat=none} 已经把自动装配关掉了——
 * 启上下文既不能证明什么，还会把断言与一堆无关的 bean 绑在一起。
 * 依赖通过 {@link ModelEndpointResolver} 这个窄接口注入，测试用 lambda 直接给假配置，
 * <b>不发任何网络请求</b>。</p>
 *
 * <p>WHY 逐个参数都要断言（而不是只断"能构造出来"）：
 * 这些参数错一个，症状都是"对话发出去就没反应/报 404/工具不生效"，
 * 而且要到用户真的点了发送才暴露。装配错误必须在编译期之后最早的时机被抓到。</p>
 */
class OpenAiCompatibleChatModelProviderTest {

    /** 刻意选一个绝不会偶然出现在数据里的字符串，便于日志断言复用。 */
    private static final String API_KEY = "sk-mindie-TEST-ONLY-7Qx2ZtR9vBnM4wKp-not-a-real-key";
    private static final String CONFIG_ID = "11111111-1111-1111-1111-111111111111";
    private static final URI BASE_URL = URI.create("http://10.95.1.11:8080/v1");

    // ======================================================================
    // 装配
    // ======================================================================

    @Test
    @DisplayName("用生效配置的 base_url/model 装配出 OpenAI 兼容 ChatModel（不走 auto-config 单例）")
    void prepareAssemblesOpenAiCompatibleChatModel() {
        OpenAiCompatibleChatModelProvider provider = providerWith("mindie", BASE_URL, "Qwen3-30B",
                ThinkingMode.NON_THINKING, ThinkingRequestFormat.QWEN_COMPATIBLE);

        ChatModelProvider.PreparedChatModel prepared = provider.prepare(List.of());

        assertThat(prepared.chatModel())
                .as("WHY 必须是 OpenAiChatModel：MindIE 暴露的是 OpenAI 兼容协议，"
                        + "复用 Spring AI 的 OpenAI 客户端即可，无需自造 HTTP 层")
                .isInstanceOf(OpenAiChatModel.class);
        assertThat(prepared.model()).isEqualTo("Qwen3-30B");
        assertThat(prepared.provider()).isEqualTo("mindie");
        assertThat(prepared.thinkingMode()).isEqualTo(ThinkingMode.NON_THINKING);

        OpenAiChatOptions options = optionsOf(prepared.chatModel());
        assertThat(options.getModel()).as("模型名必须与配置一致，不得写死于代码").isEqualTo("Qwen3-30B");
    }

    @Test
    @DisplayName("9.1：内部工具执行恒被关闭——tool_calls 一律由应用接管")
    void internalToolExecutionIsAlwaysDisabled() {
        OpenAiCompatibleChatModelProvider provider = providerWith("mindie", BASE_URL, "Qwen3-30B",
                ThinkingMode.THINKING, ThinkingRequestFormat.QWEN_COMPATIBLE);

        // WHY 空工具集也要断言：手动接管是**结构约束**而非"有工具时才需要"。
        // 若某次改动只在工具非空时关掉内部执行，副作用工具 run_command 就可能
        // 在某个分支上被 Spring AI 直接执行掉——审批闸门被绕过且无任何报错。
        assertThat(optionsOf(provider.prepare(List.of()).chatModel()).getInternalToolExecutionEnabled())
                .isFalse();
        assertThat(optionsOf(provider.prepare(List.of(stubCallback("list_dir"))).chatModel())
                .getInternalToolExecutionEnabled())
                .as("带工具时同样必须关闭").isFalse();
    }

    @Test
    @DisplayName("工具回调被交给默认选项，使模型能在响应里返回 tool_calls")
    void toolCallbacksAreHandedToDefaultOptions() {
        OpenAiCompatibleChatModelProvider provider = providerWith("mindie", BASE_URL, "Qwen3-30B",
                ThinkingMode.NON_THINKING, ThinkingRequestFormat.QWEN_COMPATIBLE);

        OpenAiChatOptions options = optionsOf(provider
                .prepare(List.of(stubCallback("list_dir"), stubCallback("run_command")))
                .chatModel());

        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("list_dir", "run_command");
    }

    // ======================================================================
    // 思考模式的请求侧开关（design D8：按 thinkingRequestFormat 判断）
    // ======================================================================

    @Test
    @DisplayName("thinkingRequestFormat=qwen_compatible 时经 extra_body 下发思考开关")
    void thinkingModeIsSentAsExtraBody() {
        OpenAiCompatibleChatModelProvider provider = providerWith("mindie", BASE_URL, "Qwen3-30B",
                ThinkingMode.THINKING, ThinkingRequestFormat.QWEN_COMPATIBLE);

        Map<String, Object> extraBody = optionsOf(provider.prepare(List.of()).chatModel()).getExtraBody();

        // WHY 两个键都写：Qwen3 经 vLLM/MindIE 部署时读 chat_template_kwargs.enable_thinking，
        // 而 DashScope 兼容模式读顶层 enable_thinking。同一份配置在两种部署下都要生效，
        // 与其让用户去猜自己那套属于哪种，不如两个都给——多余的那个会被忽略。
        assertThat(extraBody).containsEntry("enable_thinking", true);
        assertThat(chatTemplateKwargsOf(extraBody)).containsEntry("enable_thinking", true);
    }

    @Test
    @DisplayName("thinkingRequestFormat=none 时不下发思考开关：官方 API 会以 400 拒绝未建模的请求字段")
    void thinkingSwitchIsOmittedForNoneFormat() {
        OpenAiCompatibleChatModelProvider provider = providerWith("openai",
                URI.create("https://api.openai.com"), "gpt-4o-mini", ThinkingMode.THINKING,
                ThinkingRequestFormat.NONE);

        ChatModelProvider.PreparedChatModel prepared = provider.prepare(List.of());

        Map<String, Object> extraBody = optionsOf(prepared.chatModel()).getExtraBody();
        // thinkingRequestFormat=none 时不应包含思考开关键
        assertThat(extraBody).doesNotContainKeys("enable_thinking", "chat_template_kwargs");
        assertThat(optionsOf(prepared.chatModel()).getModel()).isEqualTo("gpt-4o-mini");
        // 但模式本身仍要透出：它决定客户端是否解析 reasoning_content（7.3 的下半段）
        assertThat(prepared.thinkingMode()).isEqualTo(ThinkingMode.THINKING);
    }

    // ======================================================================
    // 输出上限字段（design D7 步骤 8，tasks 8.8）
    // ======================================================================

    @Test
    @DisplayName("输出上限按 output_limit_field 配置写入 extra_body（默认 max_tokens）")
    void outputLimitIsWrittenWithConfiguredFieldName() {
        OpenAiCompatibleChatModelProvider provider = providerWith("mindie", BASE_URL, "Qwen3-30B",
                ThinkingMode.NON_THINKING, ThinkingRequestFormat.QWEN_COMPATIBLE,
                OutputLimitField.MAX_TOKENS, 1024);

        Map<String, Object> extraBody = optionsOf(provider.prepare(List.of()).chatModel()).getExtraBody();

        assertThat(extraBody).containsEntry("max_tokens", 1024);
        assertThat(extraBody).doesNotContainKey("max_completion_tokens");
    }

    @Test
    @DisplayName("output_limit_field=max_completion_tokens 时使用替代字段名")
    void outputLimitUsesMaxCompletionTokensWhenConfigured() {
        OpenAiCompatibleChatModelProvider provider = providerWith("mindie", BASE_URL, "Qwen3-30B",
                ThinkingMode.NON_THINKING, ThinkingRequestFormat.QWEN_COMPATIBLE,
                OutputLimitField.MAX_COMPLETION_TOKENS, 2048);

        Map<String, Object> extraBody = optionsOf(provider.prepare(List.of()).chatModel()).getExtraBody();

        assertThat(extraBody).containsEntry("max_completion_tokens", 2048);
        assertThat(extraBody).doesNotContainKey("max_tokens");
    }

    @Test
    @DisplayName("8.8：扩展由显式配置决定而非供应商名称——provider=openai 配 qwen_compatible 仍发思考开关")
    void extensionIsDrivenByConfigNotVendorName() {
        // WHY 这个用例：旧代码按 provider 名称隐式分流（mindie → 发思考开关，openai → 不发）。
        // design D8 明确要求由用户显式配置 thinking_request_format 决定。
        // 这里用 provider="openai" 但 format=QWEN_COMPATIBLE，验证配置驱动行为。
        OpenAiCompatibleChatModelProvider provider = providerWith("openai",
                URI.create("https://api.openai.com"), "gpt-4o-mini", ThinkingMode.THINKING,
                ThinkingRequestFormat.QWEN_COMPATIBLE);

        Map<String, Object> extraBody = optionsOf(provider.prepare(List.of()).chatModel()).getExtraBody();

        // 即使 provider 是 "openai"，只要 format 是 QWEN_COMPATIBLE 就发思考开关
        assertThat(extraBody).containsEntry("enable_thinking", true);
    }

    @Test
    @DisplayName("8.8：max_tokens 与 max_completion_tokens 只发送一个，不会同时出现")
    void onlyOneOutputLimitFieldIsSent() {
        // 验证两种配置下都只有一个字段被发送
        OpenAiCompatibleChatModelProvider maxTokensProvider = providerWith("mindie", BASE_URL, "Qwen3-30B",
                ThinkingMode.NON_THINKING, ThinkingRequestFormat.QWEN_COMPATIBLE,
                OutputLimitField.MAX_TOKENS, 1024);
        OpenAiCompatibleChatModelProvider maxCompletionProvider = providerWith("mindie", BASE_URL, "Qwen3-30B",
                ThinkingMode.NON_THINKING, ThinkingRequestFormat.QWEN_COMPATIBLE,
                OutputLimitField.MAX_COMPLETION_TOKENS, 2048);

        Map<String, Object> extraBody1 = optionsOf(maxTokensProvider.prepare(List.of()).chatModel()).getExtraBody();
        Map<String, Object> extraBody2 = optionsOf(maxCompletionProvider.prepare(List.of()).chatModel()).getExtraBody();

        // max_tokens 配置下只有 max_tokens
        assertThat(extraBody1).containsKey("max_tokens").doesNotContainKey("max_completion_tokens");
        // max_completion_tokens 配置下只有 max_completion_tokens
        assertThat(extraBody2).containsKey("max_completion_tokens").doesNotContainKey("max_tokens");
    }

    // ======================================================================
    // 端点路径归一化
    // ======================================================================

    @Test
    @DisplayName("completions 路径按 base_url 归一化，避免出现 /v1/v1/chat/completions")
    void completionsPathIsDerivedFromBaseUrl() {
        // WHY 值得单列一个用例：Spring AI 的 OpenAiApi 默认补全路径是 /v1/chat/completions，
        // 而用户按 MindIE 文档填的 base_url 往往**已经**带 /v1。直接拼接会得到
        // /v1/v1/chat/completions —— 症状是 404，而 404 不会告诉用户"你多写了一段路径"。
        assertThat(OpenAiCompatibleChatModelProvider.completionsPathOf(URI.create("http://h:8080/v1")))
                .isEqualTo("/v1/chat/completions");
        assertThat(OpenAiCompatibleChatModelProvider.completionsPathOf(URI.create("http://h:8080/v1/")))
                .isEqualTo("/v1/chat/completions");
        assertThat(OpenAiCompatibleChatModelProvider.completionsPathOf(URI.create("http://h:8080")))
                .isEqualTo("/v1/chat/completions");
        assertThat(OpenAiCompatibleChatModelProvider.completionsPathOf(URI.create("http://h:8080/")))
                .isEqualTo("/v1/chat/completions");
        assertThat(OpenAiCompatibleChatModelProvider
                .completionsPathOf(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1")))
                .isEqualTo("/compatible-mode/v1/chat/completions");
        assertThat(OpenAiCompatibleChatModelProvider.completionsPathOf(URI.create("http://h:8080/api")))
                .isEqualTo("/api/chat/completions");
        // 用户直接填了完整补全地址时不再追加，避免 /chat/completions/chat/completions
        assertThat(OpenAiCompatibleChatModelProvider
                .completionsPathOf(URI.create("http://h:8080/v1/chat/completions")))
                .isEqualTo("/v1/chat/completions");
    }

    // ======================================================================
    // 凭据安全
    // ======================================================================

    @Test
    @DisplayName("api key 在装配完成后立即从内存擦除")
    void apiKeyIsWipedAfterAssembly() {
        SecretText apiKey = SecretText.of(API_KEY);
        OpenAiCompatibleChatModelProvider provider = new OpenAiCompatibleChatModelProvider(
                () -> new ModelEndpointResolver.ResolvedEndpoint(CONFIG_ID, "mindie", BASE_URL,
                        "Qwen3-30B", ThinkingMode.NON_THINKING,
                        8192, 1024, OutputLimitField.MAX_TOKENS, ThinkingRequestFormat.QWEN_COMPATIBLE,
                        apiKey));

        provider.prepare(List.of());

        // WHY 必须擦除：ChatModel 会被整个会话期持有，明文若一直躺在堆上，
        // 一次堆转储或一次意外的 toString 就会把它带出去。
        // Spring AI 只接受 String，无法做到全程无 String 副本（平台限制），
        // 但"用完即擦"把暴露窗口从"进程生命周期"压缩到"装配的一瞬间"。
        assertThat(apiKey.isWiped()).isTrue();
    }

    @Test
    @DisplayName("未配置 api key → 抛出 spec 规定的提示，且不吞异常")
    void missingApiKeySurfacesSpecMessage() {
        OpenAiCompatibleChatModelProvider provider = new OpenAiCompatibleChatModelProvider(() -> {
            throw new MissingModelApiKeyException();
        });

        assertThatThrownBy(() -> provider.prepare(List.of()))
                .isInstanceOf(MissingModelApiKeyException.class)
                .hasMessage(MissingModelApiKeyException.MESSAGE);
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    /**
     * WHY 每个参数都如实透传：早先的版本把 model/mode 写死在辅助方法里，
     * 结果"provider=openai 用 gpt-4o-mini"这条用例实际上在测 mindie + Qwen3-30B——
     * 断言全过，却什么都没证明。测试辅助方法里的硬编码值和被测代码里的硬编码值一样危险。
     */
    private static OpenAiCompatibleChatModelProvider providerWith(String provider, URI baseUrl,
                                                                 String model, ThinkingMode mode,
                                                                 ThinkingRequestFormat format) {
        return providerWith(provider, baseUrl, model, mode, format,
                OutputLimitField.MAX_TOKENS, 1024);
    }

    private static OpenAiCompatibleChatModelProvider providerWith(String provider, URI baseUrl,
                                                                 String model, ThinkingMode mode,
                                                                 ThinkingRequestFormat format,
                                                                 OutputLimitField outputLimitField,
                                                                 int maxOutputTokens) {
        return new OpenAiCompatibleChatModelProvider(
                () -> new ModelEndpointResolver.ResolvedEndpoint(CONFIG_ID, provider, baseUrl, model, mode,
                        8192, maxOutputTokens, outputLimitField, format,
                        SecretText.of(API_KEY)));
    }

    private static OpenAiChatOptions optionsOf(ChatModel chatModel) {
        assertThat(chatModel.getDefaultOptions()).isInstanceOf(OpenAiChatOptions.class);
        return (OpenAiChatOptions) chatModel.getDefaultOptions();
    }

    private static Map<String, Object> chatTemplateKwargsOf(Map<String, Object> extraBody) {
        return assertThat(extraBody).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsKey("chat_template_kwargs")
                .extractingByKey("chat_template_kwargs", InstanceOfAssertFactories.map(String.class, Object.class))
                .actual();
    }

    private static ToolCallback stubCallback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("测试替身").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("测试替身不应被执行");
            }
        };
    }
}

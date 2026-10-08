package com.ananoesis.shell.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import com.ananoesis.shell.ai.ChatModelProvider;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.security.MissingModelApiKeyException;

/**
 * {@link ChatModelProvider} 的替身：交出 {@link ScriptedChatModel}，并记录被要走了哪些工具。
 *
 * <p>WHY 需要它而不只是直接把 {@code ScriptedChatModel} 塞进被测对象：
 * tasks 7.2 的一条关键性质是"装配<b>每次</b>都重新做，且工具集由调用方按会话上下文决定"。
 * 这条性质只有在 provider 这一层才看得见——{@code AiAgentService} 在会话没绑定服务器时
 * MUST 传<b>空</b>工具表。断言"provider 收到了什么"，比断言"模型收到了什么"更贴近缺陷本身。</p>
 *
 * <p>WHY 能一键切换成抛 {@link MissingModelApiKeyException}：tasks 7.2 要求
 * 未配置 api key 时前端收到 {@code error_code=api_key_missing} 的帧，而不是一个 500。
 * 这条路径在真实环境里是"用户还没填 key"，在测试里必须由替身主动抛出才能走到。</p>
 */
public final class StubChatModelProvider implements ChatModelProvider {

    private static final String PROVIDER_NAME = "mindie";
    private static final String MODEL_NAME = "qwen3-8b";

    @Nullable
    private final ScriptedChatModel model;

    private final ThinkingMode thinkingMode;

    /** 非 null 时 {@link #prepare} 直接抛它，不返回模型。 */
    @Nullable
    private volatile RuntimeException failure;

    private final List<List<ToolCallback>> requestedToolCallbacks = new CopyOnWriteArrayList<>();

    /** 思考模式的替身（默认，覆盖 tasks 7.3 的思考分支）。 */
    public StubChatModelProvider(ScriptedChatModel model) {
        this(model, ThinkingMode.THINKING);
    }

    public StubChatModelProvider(ScriptedChatModel model, ThinkingMode thinkingMode) {
        this.model = model;
        this.thinkingMode = thinkingMode;
    }

    /**
     * @return 一个恒定抛 {@link MissingModelApiKeyException} 的替身
     *
     * <p>WHY 用静态工厂而不是让调用方传 {@code null} 模型：
     * "没有模型"与"模型会失败"是两件事，前者是<b>配置缺失</b>，
     * 在类型上就该区别于后者。</p>
     */
    public static StubChatModelProvider withoutApiKey() {
        StubChatModelProvider provider = new StubChatModelProvider(null, ThinkingMode.NON_THINKING);
        provider.failure = new MissingModelApiKeyException();
        return provider;
    }

    /** @return 一个在装配阶段就失败的替身，用于测端点不可达/端点错误的错误分类 */
    public static StubChatModelProvider failingWith(RuntimeException failure) {
        StubChatModelProvider provider = new StubChatModelProvider(null, ThinkingMode.NON_THINKING);
        provider.failure = failure;
        return provider;
    }

    @Override
    public PreparedChatModel prepare(List<ToolCallback> toolCallbacks) {
        requestedToolCallbacks.add(List.copyOf(toolCallbacks));
        RuntimeException current = failure;
        if (current != null) {
            throw current;
        }
        if (model == null) {
            throw new IllegalStateException("替身没有模型也没有失败：请用 withoutApiKey()/failingWith() 之一");
        }
        return new PreparedChatModel(model, PROVIDER_NAME, MODEL_NAME, thinkingMode);
    }

    /** @return prepare 被调用的次数；用于断言"每轮都重新装配" */
    public int prepareCount() {
        return requestedToolCallbacks.size();
    }

    /** @return 最近一次 prepare 收到的工具回调名；没有工具时为空表 */
    public List<String> lastToolNames() {
        List<List<ToolCallback>> snapshot = List.copyOf(requestedToolCallbacks);
        if (snapshot.isEmpty()) {
            return List.of();
        }
        return snapshot.get(snapshot.size() - 1).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    /** @return 是否曾经收到过<b>空</b>工具表（会话未绑定服务器时的形状） */
    public boolean everReceivedEmptyToolCallbacks() {
        return requestedToolCallbacks.stream().anyMatch(List::isEmpty);
    }

    /**
     * @return 底层剧本模型收到的全部 {@link Prompt}，按时间顺序；
     *         失败替身没有模型时为空表
     *
     * <p>WHY 由 provider 转交、而不是让测试自己同时握住模型与 provider：
     * 多数用例只关心帧与库，少数几个要断言"发给模型的上下文究竟长什么样"
     * （系统提示、历史重建、工具结果回喂）。让 provider 做这层转交，
     * 测试就只有一个入口，不会出现"断言的模型不是实际被装配的那个"这类错位。</p>
     */
    public List<Prompt> lastScriptedPrompts() {
        return model == null ? List.of() : model.prompts();
    }
}

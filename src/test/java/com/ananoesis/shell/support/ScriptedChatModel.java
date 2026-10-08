package com.ananoesis.shell.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.lang.Nullable;

import reactor.core.publisher.Flux;

/**
 * 可编程的 {@link ChatModel} 替身：按<b>剧本</b>逐轮吐出流式分片。
 *
 * <p>WHY 自建而不用真实端点：指挥官明确要求"测试不发真实网络请求"。
 * 而智能体回合循环里最容易出错的四件事——思考增量与回答增量的分流、
 * {@code tool_calls} 片段的聚合、轮次上限、端点异常的错误分类——
 * 全都是<b>纯时序</b>行为，只有在能精确控制"第几个分片带什么"时才测得准。
 * 真实模型每次输出都不一样，测了也复现不了缺陷。</p>
 *
 * <p>WHY 剧本按<b>轮</b>组织而不是一条长流：智能体的工具循环会多次调用
 * {@code stream(prompt)}——第一次模型说"我要调工具"，回喂之后第二次模型才给答案。
 * 一轮 = 一次 {@code stream} 调用 = 剧本里的一项。</p>
 *
 * <p>WHY 剧本用尽后<b>重复最后一轮</b>而不是抛异常：{@code AiAgentService} 有
 * {@value com.ananoesis.shell.ai.AiAgentService#MAX_TOOL_ROUNDS} 轮上限，
 * 测"上限触发"需要模型<b>一直</b>要求调工具。让剧本停在"永远要求调工具"的那一轮，
 * 比在测试里手写九份几乎相同的剧本更能表达意图。</p>
 *
 * <h2>线程安全</h2>
 * <p>{@code stream} 可能从智能体的工作线程上被调用，而断言在测试线程上做，
 * 因此所有可变状态都用并发容器；{@code received} 与 {@code served} 是
 * "被测代码做了什么"的唯一证据，绝不能丢。</p>
 */
public final class ScriptedChatModel implements ChatModel {

    /** 剧本的一轮：要么是一串分片，要么是一个失败。 */
    private record Round(List<ChatResponse> chunks, @Nullable RuntimeException failure) {
    }

    private final List<Round> rounds;
    private final List<Prompt> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger served = new AtomicInteger();

    private ScriptedChatModel(List<Round> rounds) {
        this.rounds = List.copyOf(rounds);
    }

    // ==================================================================
    // ChatModel
    // ==================================================================

    @Override
    public ChatResponse call(Prompt prompt) {
        // AiAgentService 只用 stream；但 ChatModel 要求实现 call。
        // 返回该轮的最后一个分片即可，语义与"非流式一次拿全"一致。
        received.add(prompt);
        Round round = nextRound();
        if (round.failure() != null) {
            throw round.failure();
        }
        return round.chunks().isEmpty()
                ? new ChatResponse(List.of(new Generation(new AssistantMessage(""))))
                : round.chunks().get(round.chunks().size() - 1);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        received.add(prompt);
        Round round = nextRound();
        if (round.failure() != null) {
            // WHY 用 Flux.error 而不是直接抛：真实的 SSE 客户端是在<b>订阅之后</b>
            // 才发现连接失败的，抛在装配阶段会让被测代码走到一条现实中不存在的分支
            return Flux.error(round.failure());
        }
        return Flux.fromIterable(round.chunks());
    }

    /** @return 已经消费到第几轮（1 起）；用于断言"轮次上限"确实停在了预期的那一轮 */
    public int streamCallCount() {
        return served.get();
    }

    /** @return 每次调用收到的 {@code Prompt}，按时间顺序 */
    public List<Prompt> prompts() {
        return List.copyOf(received);
    }

    /** @return 最近一次收到的 Prompt；没有时为 null */
    @Nullable
    public Prompt lastPrompt() {
        return received.isEmpty() ? null : received.get(received.size() - 1);
    }

    private Round nextRound() {
        if (rounds.isEmpty()) {
            throw new IllegalStateException("剧本为空：ScriptedChatModel 至少需要一轮");
        }
        int index = served.getAndIncrement();
        // 用尽后停在最后一轮，见类注释
        return rounds.get(Math.min(index, rounds.size() - 1));
    }

    // ==================================================================
    // 分片工厂
    // ==================================================================

    /**
     * 一个只带回答文本增量的分片。
     *
     * @param delta 增量文本；空串会被 {@code AiAgentService} 跳过（与真实 SSE 的心跳包一致）
     */
    public static ChatResponse content(String delta) {
        return response(assistant(delta, null, List.of()), null);
    }

    /**
     * 一个带思考增量的分片（tasks 7.3）。
     *
     * <p>WHY 走 {@code AssistantMessage} 的元数据而不是自造字段：
     * {@code OpenAiChatModel} 把 SSE 里的 {@code reasoning_content} 正是放进
     * {@code AssistantMessage.getMetadata()} 的 {@code "reasoningContent"} 键下
     * （键名由 {@code javap -v OpenAiChatModel} 的常量池核实）。
     * 替身必须用<b>同一个</b>键，测试才是在验证真实的解析路径，
     * 而不是验证"我们自己的假字段能被我们自己读出来"。</p>
     *
     * @param reasoning 思考增量，可为 null（该分片只有回答）
     * @param delta     回答增量，可为 null（该分片只有思考）
     */
    public static ChatResponse thinking(@Nullable String reasoning, @Nullable String delta) {
        if (reasoning == null) {
            return content(delta == null ? "" : delta);
        }
        return response(assistant(delta == null ? "" : delta,
                Map.of("reasoningContent", reasoning), List.of()), null);
    }

    /**
     * 一个 {@code tool_calls} 分片。
     *
     * @param id       调用 id；OpenAI 只在<b>首个</b>分片给出，后续分片传 null
     * @param name     工具名；同样只在首个分片给出
     * @param argument 参数片段（增量或累计两种语义都被 {@code AiAgentService} 支持）
     */
    public static ChatResponse toolCallFragment(@Nullable String id, @Nullable String name,
                                                @Nullable String argument) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                id == null ? "" : id, "function", name == null ? "" : name,
                argument == null ? "" : argument);
        return response(assistant("", null, List.of(call)), null);
    }

    /** 一个只带 {@code finish_reason} 的分片（真实 SSE 的最后一包通常就是这样）。 */
    public static ChatResponse finish(String finishReason) {
        return response(assistant("", null, List.of()), finishReason);
    }

    private static ChatResponse response(AssistantMessage message, @Nullable String finishReason) {
        ChatGenerationMetadata metadata = finishReason == null
                ? ChatGenerationMetadata.NULL
                : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(message, metadata)));
    }

    private static AssistantMessage assistant(String content, @Nullable Map<String, Object> properties,
                                              List<AssistantMessage.ToolCall> toolCalls) {
        return AssistantMessage.builder()
                .content(content)
                .properties(properties == null ? Map.of() : properties)
                .toolCalls(toolCalls)
                .build();
    }

    // ==================================================================
    // 剧本构造器
    // ==================================================================

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 剧本构造器。
     *
     * <p>WHY 提供若干<b>命名</b>的轮次工厂而不是让测试自己拼分片：
     * "模型要求调一次 run_command"是一个语义单元，
     * 在九个测试里各拼一遍 {@code toolCallFragment(...)+finish("tool_calls")}
     * 会让测试的主体淹没在协议细节里，读的人也看不出这是在测什么。</p>
     */
    public static final class Builder {

        private final List<Round> rounds = new ArrayList<>();

        private Builder() {
        }

        /** 一轮纯文本回答：把 {@code deltas} 逐个作为增量下发，最后带 {@code finish_reason=stop}。 */
        public Builder answer(String... deltas) {
            List<ChatResponse> chunks = new ArrayList<>(deltas.length + 1);
            for (String delta : deltas) {
                chunks.add(content(delta));
            }
            chunks.add(finish("stop"));
            return round(chunks.toArray(ChatResponse[]::new));
        }

        /**
         * 一轮"先思考再回答"：思考增量与回答增量<b>交替</b>下发。
         *
         * <p>WHY 交替而不是"先全部思考、再全部回答"：真实的推理模型是边想边写的，
         * 两类增量交错到达。若只测"分块到达"，就漏掉了"两类增量必须各自归到
         * 正确的 segment"这条最容易写错的性质。</p>
         */
        public Builder thinkingAnswer(String[] reasoningDeltas, String[] answerDeltas) {
            List<ChatResponse> chunks = new ArrayList<>();
            int longest = Math.max(reasoningDeltas.length, answerDeltas.length);
            for (int i = 0; i < longest; i++) {
                chunks.add(thinking(i < reasoningDeltas.length ? reasoningDeltas[i] : null,
                        i < answerDeltas.length ? answerDeltas[i] : null));
            }
            chunks.add(finish("stop"));
            return round(chunks.toArray(ChatResponse[]::new));
        }

        /** 一轮"要求调一个工具"：完整参数一次给全，末尾 {@code finish_reason=tool_calls}。 */
        public Builder toolCall(String id, String toolName, String argumentsJson) {
            return round(toolCallFragment(id, toolName, argumentsJson), finish("tool_calls"));
        }

        /**
         * 一轮"要求调一个工具"，但参数被<b>拆成多个分片</b>下发。
         *
         * <p>这是 OpenAI 流式协议的真实形状，也是聚合逻辑存在的理由：
         * 只有首片带 id/name，后续片段只带参数的一部分。</p>
         */
        public Builder toolCallFragments(String id, String toolName, String... argumentFragments) {
            List<ChatResponse> chunks = new ArrayList<>(argumentFragments.length + 2);
            for (int i = 0; i < argumentFragments.length; i++) {
                chunks.add(toolCallFragment(i == 0 ? id : null, i == 0 ? toolName : null,
                        argumentFragments[i]));
            }
            chunks.add(finish("tool_calls"));
            return round(chunks.toArray(ChatResponse[]::new));
        }

        /** 一轮"端点失败"：订阅后立刻以 {@code failure} 结束流。 */
        public Builder error(RuntimeException failure) {
            rounds.add(new Round(List.of(), failure));
            return this;
        }

        /** 逃生舱：直接给一轮的原始分片序列。 */
        public Builder round(ChatResponse... chunks) {
            rounds.add(new Round(List.of(chunks), null));
            return this;
        }

        public ScriptedChatModel build() {
            if (rounds.isEmpty()) {
                throw new IllegalStateException("剧本至少要有一轮");
            }
            return new ScriptedChatModel(rounds);
        }
    }
}

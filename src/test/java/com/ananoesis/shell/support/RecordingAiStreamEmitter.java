package com.ananoesis.shell.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.lang.Nullable;

import com.ananoesis.shell.ai.AiStreamEmitter;
import com.ananoesis.shell.ws.AiStreamFrame;
import com.ananoesis.shell.ws.ToolCallEventFrame;

/**
 * 收集 {@code ai_stream} 出站帧的替身（tasks 7.3 / 7.4 / 9.5 的断言基座）。
 *
 * <p>WHY 记录<b>完整帧序列</b>而不只是"最后一次的内容"：本项目的核心可观测性承诺是
 * "操作透明"——每一次工具调用都要有一对 {@code tool_call}/{@code tool_result} 帧，
 * 思考增量必须归到 {@code segment=thinking}、回答增量归到 {@code segment=answer}。
 * 这些都是<b>序列</b>性质：只看最终文本，"两类增量被合并成一类"这种缺陷完全测不出来。</p>
 *
 * <p>WHY 提供 {@link #failWith}：{@code AiWebSocketHandler} 发送失败时，
 * 智能体的回合 MUST NOT 中断（产物还要落库）。要验证这条，就得让出口真的抛一次异常。</p>
 *
 * <p>WHY 用并发容器：回合可能跑在智能体的工作线程上，断言在测试线程上做。</p>
 */
public final class RecordingAiStreamEmitter implements AiStreamEmitter {

    private final List<AiStreamFrame> frames = new CopyOnWriteArrayList<>();

    @Nullable
    private volatile RuntimeException failure;

    @Override
    public void emit(AiStreamFrame frame) {
        frames.add(frame);
        RuntimeException current = failure;
        if (current != null) {
            // 先记录再抛：测试要断言的正是"帧已经产出，只是没送出去"
            throw current;
        }
    }

    /** @return 已发出的全部帧，按时间顺序 */
    public List<AiStreamFrame> frames() {
        return List.copyOf(frames);
    }

    /** @return 帧类型的线性序列，用于失败时一眼看清整段流（例如 {@code [answer_delta, final]}） */
    public List<String> typeSequence() {
        List<String> types = new ArrayList<>(frames.size());
        for (AiStreamFrame frame : frames) {
            types.add(frame.type() == null ? "null" : frame.type().name().toLowerCase());
        }
        return List.copyOf(types);
    }

    /** @return 指定类型的帧 */
    public List<AiStreamFrame> ofType(AiStreamFrame.Type type) {
        List<AiStreamFrame> matched = new ArrayList<>();
        for (AiStreamFrame frame : frames) {
            if (frame.type() == type) {
                matched.add(frame);
            }
        }
        return List.copyOf(matched);
    }

    /** @return 指定类型的帧数量 */
    public int countOf(AiStreamFrame.Type type) {
        return ofType(type).size();
    }

    /**
     * @return 指定类型的<b>唯一</b>一帧
     * @throws AssertionError 不是恰好一帧时——多数断言（final/error/tool_call）都要求唯一，
     *                          多发一帧与少发一帧同样是缺陷，不该被 {@code last()} 掩盖
     */
    public AiStreamFrame only(AiStreamFrame.Type type) {
        List<AiStreamFrame> matched = ofType(type);
        if (matched.size() != 1) {
            throw new AssertionError("期望恰好一帧 " + type + "，实际 " + matched.size()
                    + " 帧；完整序列=" + typeSequence());
        }
        return matched.get(0);
    }

    /** @return 指定类型的最后一帧；没有时为 null */
    @Nullable
    public AiStreamFrame last(AiStreamFrame.Type type) {
        List<AiStreamFrame> matched = ofType(type);
        return matched.isEmpty() ? null : matched.get(matched.size() - 1);
    }

    /** @return 把指定类型的所有 {@code content} 按到达顺序拼起来 */
    public String joinedContent(AiStreamFrame.Type type) {
        StringBuilder text = new StringBuilder();
        for (AiStreamFrame frame : ofType(type)) {
            if (frame.content() != null) {
                text.append(frame.content());
            }
        }
        return text.toString();
    }

    /** @return {@code tool_call} 帧里出现的 {@code approval_id}（副作用工具转审批的证据） */
    public List<UUID> approvalIds() {
        List<UUID> ids = new ArrayList<>();
        for (AiStreamFrame frame : ofType(AiStreamFrame.Type.TOOL_CALL)) {
            ToolCallEventFrame event = frame.toolCall();
            if (event != null && event.approvalId() != null) {
                ids.add(event.approvalId());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * @return 最近一个 {@code tool_call} 帧携带的 {@code approval_id}；还没有时为 null
     *
     * <p>WHY 需要它：审批测试要在<b>另一个线程</b>上等待"提案已挂起"，
     * 而这个帧是测试线程唯一能观察到的挂起信号。</p>
     */
    @Nullable
    public UUID latestApprovalId() {
        List<AiStreamFrame> calls = ofType(AiStreamFrame.Type.TOOL_CALL);
        for (int i = calls.size() - 1; i >= 0; i--) {
            ToolCallEventFrame event = calls.get(i).toolCall();
            if (event != null && event.approvalId() != null) {
                return event.approvalId();
            }
        }
        return null;
    }

    /** 让后续 {@link #emit} 抛异常，模拟"前端已断开"。 */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void clear() {
        frames.clear();
        failure = null;
    }
}

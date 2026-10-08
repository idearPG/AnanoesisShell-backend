package com.ananoesis.shell.ai;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import com.ananoesis.shell.contract.model.ThinkingMode;

/**
 * 模型接入抽象（design D5 / tasks 7.2）：把"当前生效配置"变成可用的 {@link ChatModel}。
 *
 * <h2>WHY 每次调用都重新装配，而不是在启动时造一个单例 bean</h2>
 * <p>Spring AI 的自动装配会按 {@code spring.ai.openai.*} 造一个进程级 {@code ChatModel} 单例。
 * 但本产品的模型端点是<b>用户在界面上随时可改</b>的：换 base_url、轮换 api key、
 * 切换生效配置、切思考模式，都要求下一次对话立刻用新值，不能等重启。
 * {@code application.yml} 里 {@code spring.ai.model.chat=none} 已经关掉了自动装配，
 * 装配责任因此落在本接口的实现上——每轮对话装配一次，开销是几个对象构造，
 * 而收益是"配置改完立即生效"这条用户能直接感知的行为。</p>
 *
 * <h2>WHY 工具回调由调用方传入</h2>
 * <p>工具集与<b>会话上下文</b>绑定（例如 host_id 决定了工具在哪台机器上执行），
 * 而 {@code ChatModel} 只与<b>模型端点</b>绑定。把两者揉进一个单例，
 * 就会出现"A 会话的工具被 B 会话用上"这类跨会话串数据的缺陷。</p>
 *
 * <h2>WHY 透出 {@code thinkingMode}</h2>
 * <p>tasks 7.3 要求区分 {@code reasoning_content} 与 {@code content}。
 * 非思考模式下模型不产出前者，此时若仍去解析，会把某些端点回显的调试字段
 * 当成思考过程推给前端。因此"是否解析思考增量"必须由装配时确定的模式说了算，
 * 而不是在流处理里靠"有没有这个字段"猜。</p>
 */
public interface ChatModelProvider {

    /**
     * 装配一个 {@code ChatModel}。
     *
     * <p>实现 MUST 关闭 Spring AI 的内部工具执行
     * （{@code internalToolExecutionEnabled=false}）：副作用工具 {@code run_command}
     * 必须经审批闸门，一旦被框架自动执行，审批就成了摆设（tasks 9.1）。</p>
     *
     * @param toolCallbacks 本轮可用的工具；可为空列表
     * @throws com.ananoesis.shell.security.MissingModelApiKeyException 未配置生效模型或 api key
     */
    PreparedChatModel prepare(List<ToolCallback> toolCallbacks);

    /**
     * 装配结果。
     *
     * @param chatModel    可直接用于 {@code call}/{@code stream} 的模型客户端
     * @param provider     provider 名称，用于日志与将来的方言分流
     * @param model        实际使用的模型名
     * @param thinkingMode 实际使用的思考模式（已按 Q3 解析）
     */
    record PreparedChatModel(ChatModel chatModel, String provider, String model, ThinkingMode thinkingMode) {
    }
}

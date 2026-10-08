package com.ananoesis.shell.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import com.ananoesis.shell.ws.AiWebSocketHandler;
import com.ananoesis.shell.ws.ApprovalWebSocketHandler;
import com.ananoesis.shell.ws.TerminalWebSocketHandler;

/**
 * WebSocket 端点注册（tasks 6.3 / 8.1 / 9.4；契约 {@code asyncapi.yaml} 的
 * {@code servers.ws} + {@code /terminal}、{@code /approval}、{@code /ai} 三个 channel）。
 *
 * <p>WHY 用原生 {@link WebSocketConfigurer} 而不是 STOMP：契约规定一个文本帧就是一条完整的
 * {@code terminal_input}/{@code terminal_output} JSON（{@code messages.*.payload} 直接
 * {@code $ref} 载荷 schema，<b>没有</b>信封层）。STOMP 会强加一个 {@code COMMAND/SEND}
 * 帧头与 {@code /topic} 目的地寻址，前端要么改契约要么在两种协议之间来回翻译。
 * 原生端点让线上字节与契约逐字一致。</p>
 *
 * <h2>三个端点共用同一套连接策略</h2>
 * <p>WHY 抽 {@link #register} 而不是三段复制：同源限定与"允许列表为空时不调用
 * {@code setAllowedOrigins}"这两条规则必须对三个端点<b>一致</b>成立。
 * 复制三份的话，日后给其中一个加白名单时很容易只改一处——
 * 结果是终端能连、AI 通道连不上（或反过来，审批通道对任意源敞开，那才是安全事故）。
 * 收进一个方法后，三个端点的安全姿态由编译器保证相同。</p>
 *
 * <h2>同源限制（CSWSH 防线）</h2>
 * <p>WHY 默认<b>不</b>调用 {@code setAllowedOrigins}：经字节码确认，Spring 6.2 的
 * {@code AbstractWebSocketHandlerRegistration#getInterceptors()} 会<b>无条件</b>注册一个
 * {@code OriginHandshakeInterceptor(allowedOrigins)}，其内部持有 {@code CorsConfiguration}。
 * 在允许列表为空时它的语义是"同源、或请求不带 {@code Origin} 头才放行"——这正是我们要的默认。</p>
 *
 * <p>为什么这条必须守住：终端端点能驱动用户配置好的 SSH 会话在远端执行任意命令，
 * 审批端点能对<b>智能体提议的命令</b>点头放行，AI 端点能直接触发一轮带工具的对话。
 * 若放开成 {@code setAllowedOrigins("*")}，用户浏览器里任何一个恶意页面都能
 * {@code new WebSocket("ws://localhost:18080/ws/ai")}，凭浏览器自动附带的同源身份
 * 驱动智能体去操作生产机（Cross-Site WebSocket Hijacking）。REST 侧靠 CSRF/凭据保护，
 * WebSocket 握手不走那条路，唯一的闸门就是 Origin 校验。</p>
 *
 * <p>WHY 仍然提供 {@code ananoesis.ws.allowed-origins} 开关：前端在 Vite dev server 上跑时
 * 源是 {@code http://localhost:5173}，与后端的 18080 跨源，必须显式放行；
 * 阶段 2 套上桌面壳后由壳以 {@code file://} 或自定义 scheme 加载页面，届时也在这里加白名单。
 * 配置为空即保持最严格的同源限定。</p>
 *
 * <h2>契约偏差（已上报，勿在本类"修正"）</h2>
 * <p>{@code asyncapi.yaml} 的 {@code servers.ws.url} 写作 {@code ws://localhost:8080/ws}，
 * 而 {@code application.yml} 把端口定为 18080（开发机 8080 被占，指挥官裁定）。
 * 端口属部署参数、不属消息契约，故此处只对齐<b>路径</b>部分，由
 * {@code WsContractAlignmentTest#*EndpointMatchesContractServerAndChannel} 钉住。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    /**
     * 终端端点绝对路径。
     *
     * <p>WHY 提成常量而不是内联字符串：{@code WsContractAlignmentTest} 要拿它与
     * 契约的 {@code servers.ws.url} 路径 + channel 名拼接结果比对。内联的话，
     * 测试就只能自己再写一遍 {@code "/ws/terminal"}，两边同时写错也照样绿。</p>
     */
    public static final String TERMINAL_ENDPOINT = "/ws/terminal";

    /**
     * 审批端点绝对路径（tasks 8.1；契约 channel {@code /approval}）。
     *
     * <p>下行 {@code approval_request}、上行 {@code approval_response}，
     * 方向与终端<b>相反</b>（请求由后端发起）。该方向由
     * {@code WsContractAlignmentTest#approvalChannelWiringMatchesContract} 钉住。</p>
     */
    public static final String APPROVAL_ENDPOINT = "/ws/approval";

    /**
     * AI 端点绝对路径（tasks 7.4 / 9.4；契约 channel {@code /ai}）。
     *
     * <p>双向复用 {@code ai_stream}（TRACEABILITY Q2）：上行 {@code type=user_message}
     * 触发一轮对话，下行六种增量/事件帧。</p>
     */
    public static final String AI_ENDPOINT = "/ws/ai";

    /** 单条入站文本帧上限。WHY 64KiB：见 {@link #webSocketContainer()}。 */
    private static final int MAX_TEXT_MESSAGE_BUFFER_SIZE = 64 * 1024;

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final ApprovalWebSocketHandler approvalWebSocketHandler;
    private final AiWebSocketHandler aiWebSocketHandler;
    private final List<String> allowedOrigins;

    public WebSocketConfiguration(
            TerminalWebSocketHandler terminalWebSocketHandler,
            ApprovalWebSocketHandler approvalWebSocketHandler,
            AiWebSocketHandler aiWebSocketHandler,
            @Value("${ananoesis.ws.allowed-origins:}") String allowedOrigins) {
        this.terminalWebSocketHandler =
                Objects.requireNonNull(terminalWebSocketHandler, "terminalWebSocketHandler 不得为 null");
        this.approvalWebSocketHandler =
                Objects.requireNonNull(approvalWebSocketHandler, "approvalWebSocketHandler 不得为 null");
        this.aiWebSocketHandler = Objects.requireNonNull(aiWebSocketHandler, "aiWebSocketHandler 不得为 null");
        this.allowedOrigins = parseOrigins(allowedOrigins);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        register(registry, terminalWebSocketHandler, TERMINAL_ENDPOINT);
        register(registry, approvalWebSocketHandler, APPROVAL_ENDPOINT);
        register(registry, aiWebSocketHandler, AI_ENDPOINT);
    }

    /**
     * 按统一策略挂载一个端点。
     *
     * <p>WHY 条件式调用 {@code setAllowedOrigins}：传空数组会让 {@code CorsConfiguration}
     * 进入"已配置但无允许项"的状态，语义与"未配置"并不等价；保持不调用才是同源限定。</p>
     */
    private void register(WebSocketHandlerRegistry registry,
                          org.springframework.web.socket.WebSocketHandler handler, String path) {
        var registration = registry.addHandler(handler, path);
        if (!allowedOrigins.isEmpty()) {
            registration.setAllowedOrigins(allowedOrigins.toArray(new String[0]));
        }
    }

    /**
     * 调整底层 JSR-356 容器的缓冲区与空闲策略。
     *
     * <p>WHY 必须抬高文本缓冲区：Tomcat 的默认入站文本缓冲是 8KiB。用户在终端里粘贴一段
     * 安装脚本（很常见的 MVP 用法）时，前端会把整段作为<b>一个</b> {@code terminal_input}
     * 帧发出，超过上限后容器直接以 1009 (message too big) 关闭会话——症状是
     * "粘贴大段内容后终端断线"，且后端日志里看不到任何异常。
     * AI 通道同理：用户可能把整段日志贴进提问里。</p>
     *
     * <p>WHY 把空闲超时设为 0（永不）：空闲回收是 {@code TerminalIdleReaper} 的职责，
     * 它会写 {@code sessions.close_reason=timeout} 并向前端发一个带 {@code end_reason} 的
     * {@code closed} 帧。若容器也自行回收，那条路径<b>绕过</b>了 handler，结果是
     * 审计行永远停在 {@code open}、前端只收到一个裸 TCP 关闭而没有任何结束原因。
     * 让容器不管空闲，回收语义才有唯一实现。
     * 审批/AI 端点更不能用容器空闲回收：一轮对话可能因为等待人工审批而静默两分钟，
     * 那是<b>正常</b>状态，不是空闲故障。</p>
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_SIZE);
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }

    /**
     * 解析逗号分隔的允许源列表，忽略空白项。
     *
     * @return 不可变列表；未配置时为空列表（= 同源限定）
     */
    private static List<String> parseOrigins(String raw) {
        List<String> origins = new ArrayList<>();
        if (raw == null) {
            return List.of();
        }
        for (String candidate : raw.split(",")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed);
            }
        }
        return List.copyOf(origins);
    }

    /** @return 生效的允许源列表；供测试断言"默认严格"。 */
    List<String> allowedOrigins() {
        return allowedOrigins;
    }

}

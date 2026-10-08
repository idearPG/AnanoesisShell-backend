package com.ananoesis.shell.ws;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.lang.Nullable;
import org.yaml.snakeyaml.Yaml;

import com.ananoesis.shell.config.WebSocketConfiguration;
import com.ananoesis.shell.contract.model.EndReason;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

/**
 * 把 {@code contract/asyncapi.yaml} 当作<b>数据</b>读进来，钉住 ws 层与冻结契约的一致。
 *
 * <p>WHY 需要这个测试（而不是"人工对照着写一遍"）：REST 侧有 openapi-generator，
 * 契约一改生成物就改，实现类跟不上就<b>编译不过</b>——漂移在编译期被拦住。
 * 而 asyncapi 的 Spring 生成器生态不成熟（见 {@link TerminalInput} 类注释），
 * 终端/审批/AI 三类消息的 Java 形态只能手写转录。手写转录的唯一防线就是运行时比对：
 * 契约加了字段、改了枚举取值、把 {@code required} 挪了位置，这个测试立刻变红。
 * 否则漂移会以"前端按新契约发字段，后端默默丢弃"的形式存在很久。</p>
 *
 * <p>WHY 用 Jackson 内省取"线上字段名"而不是自己算 snake_case：
 * 真正决定线上形态的是 {@code @JsonNaming} + {@code @JsonProperty} 注解链，
 * 自己再实现一遍下划线换算等于用第二份逻辑验证第一份逻辑——
 * 两份同时写错（例如都漏掉 {@code error_code} 里的 {@code errorCode → error_code}）时测试照样绿。
 * 走 {@link BeanDescription#findProperties()} 拿到的是序列化器<b>实际</b>使用的名字。</p>
 *
 * <p>WHY 判断可选性时读<b>访问器方法</b>上的注解，而不是 {@link RecordComponent#getAnnotations()}：
 * 后者恒为空。经 javap 核实，{@code org.springframework.lang.Nullable} 的 {@code @Target} 是
 * {@code {METHOD, PARAMETER, FIELD}}，<b>不含 RECORD_COMPONENT</b>；按 JLS 8.10.1，
 * record 组件上的注解只会传播到其 {@code @Target} 允许的位置。用 {@code getAnnotations()}
 * 会把每个组件都判成必填，于是"字段漏标 @Nullable"这种真实缺陷反而测不出来。</p>
 *
 * <p>WHY 端点路径只断言常量、不在这里启动 Spring 验证注册：
 * "常量对得上契约"与"Spring 真的把 handler 挂到了这个路径"是两个不同的命题，
 * 后者由 {@code TerminalWebSocketIntegrationTest} / {@code ApprovalWebSocketIntegrationTest}
 * 用真实 WebSocket 客户端连一次来证明。
 * 把两件事混在一个测试里，失败时就分不清是路径写错还是注册没生效。</p>
 */
class WsContractAlignmentTest {

    /** 仓库根下的契约目录。surefire 的工作目录是 {@code backend/}，故向上一级。 */
    private static final Path ASYNCAPI = Paths.get("..", "contract", "asyncapi.yaml").toAbsolutePath().normalize();

    private static Map<String, Object> root;
    private static Map<String, Object> schemas;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void loadContract() throws IOException {
        assertThat(Files.isRegularFile(ASYNCAPI))
                .as("契约文件必须存在：%s（codegen 与手写转录都以它为唯一真源）", ASYNCAPI)
                .isTrue();
        try (InputStream in = Files.newInputStream(ASYNCAPI)) {
            root = castMap(new Yaml().load(in), "asyncapi.yaml 根节点");
        }
        schemas = castMap(section(root, "components").get("schemas"), "components.schemas");
    }

    // ======================================================================
    // 载荷字段名
    // ======================================================================

    @Test
    @DisplayName("TerminalInput 的线上字段名集合 == 契约 properties 键集合")
    void terminalInputFieldsMatchContract() {
        assertThat(wireNamesOf(TerminalInput.class))
                .containsExactlyInAnyOrderElementsOf(contractPropertyNames("TerminalInput"));
    }

    @Test
    @DisplayName("TerminalOutput 的线上字段名集合 == 契约 properties 键集合")
    void terminalOutputFieldsMatchContract() {
        assertThat(wireNamesOf(TerminalOutput.class))
                .containsExactlyInAnyOrderElementsOf(contractPropertyNames("TerminalOutput"));
    }

    @Test
    @DisplayName("AiStreamFrame 的线上字段名集合 == 契约 AiStream.properties 键集合")
    void aiStreamFieldsMatchContract() {
        assertThat(wireNamesOf(AiStreamFrame.class))
                .containsExactlyInAnyOrderElementsOf(contractPropertyNames("AiStream"));
    }

    @Test
    @DisplayName("ToolCallEventFrame 的线上字段名集合 == 契约 ToolCallEvent.properties 键集合")
    void toolCallEventFieldsMatchContract() {
        assertThat(wireNamesOf(ToolCallEventFrame.class))
                .containsExactlyInAnyOrderElementsOf(contractPropertyNames("ToolCallEvent"));
    }

    @Test
    @DisplayName("ApprovalRequestFrame 的线上字段名集合 == 契约 ApprovalRequest.properties 键集合")
    void approvalRequestFieldsMatchContract() {
        assertThat(wireNamesOf(ApprovalRequestFrame.class))
                .containsExactlyInAnyOrderElementsOf(contractPropertyNames("ApprovalRequest"));
    }

    @Test
    @DisplayName("ApprovalResponseFrame 的线上字段名集合 == 契约 ApprovalResponse.properties 键集合")
    void approvalResponseFieldsMatchContract() {
        assertThat(wireNamesOf(ApprovalResponseFrame.class))
                .containsExactlyInAnyOrderElementsOf(contractPropertyNames("ApprovalResponse"));
    }

    // ======================================================================
    // required 语义（入站与出站方向不同，刻意分开断言）
    // ======================================================================

    /**
     * 入站方向：契约的 {@code required} 是<b>生产者义务</b>——客户端 MUST 发 {@code action}。
     * 但后端是<b>消费者</b>，它收到什么帧并不受自己控制。
     *
     * <p>WHY 因此要求 {@link TerminalInput} 的每个组件都可缺省：若把 {@code action}
     * 声明成非空，Jackson 会在反序列化阶段就失败，handler 根本没机会把它归类成
     * {@code validation_error}——而契约规定这条通道上的字段缺失要用
     * {@code terminal_output(type=error, error_code=validation_error)} 回应。
     * 换言之，Java 侧的"可空"不是放松契约，而是<b>实现</b>契约的错误处理条款；
     * 具体的校验行为由 {@code TerminalWebSocketHandlerTest} 覆盖。</p>
     *
     * <p>本用例能抓住的漂移是：契约新增了一个 required 字段而后端根本没建模——
     * 那意味着前端按新契约发出的必填项会被 {@code @JsonIgnoreProperties} 静默丢弃。</p>
     */
    @Test
    @DisplayName("TerminalInput：契约 required 的字段都已建模，且每个组件都可缺省")
    void terminalInputToleratesMissingFields() {
        assertThat(wireNamesOf(TerminalInput.class))
                .as("契约的 required 字段必须被后端建模，否则会被静默丢弃")
                .containsAll(contractRequired("TerminalInput"));
        assertThat(requiredComponentNames(TerminalInput.class))
                .as("入站载荷不得有非空组件，否则字段缺失会在反序列化阶段炸掉，而不是回 validation_error")
                .isEmpty();
    }

    /**
     * {@link AiStreamFrame} 是<b>双向</b>载荷（Q2 裁定：用户提问复用同一 schema），
     * 因此按入站标准处理——全组件可空。
     *
     * <p>出站侧的 required 义务由静态工厂承担，验证在 {@code AiStreamFrameTest}：
     * 那边断言的是"序列化出来的 JSON 一定含 type 与 conversation_id"，
     * 比在这里断言 record 结构更贴近真实的违约形态。</p>
     */
    @Test
    @DisplayName("AiStreamFrame：契约 required 已建模，且入站方向全组件可缺省")
    void aiStreamToleratesMissingFields() {
        assertThat(wireNamesOf(AiStreamFrame.class))
                .containsAll(contractRequired("AiStream"));
        assertThat(requiredComponentNames(AiStreamFrame.class))
                .as("ai_stream 双向复用同一 schema，入站容忍优先（见类注释）")
                .isEmpty();
    }

    @Test
    @DisplayName("ApprovalResponseFrame / ToolCallEventFrame：入站容忍，全组件可缺省")
    void approvalResponseToleratesMissingFields() {
        assertThat(wireNamesOf(ApprovalResponseFrame.class))
                .containsAll(contractRequired("ApprovalResponse"));
        assertThat(requiredComponentNames(ApprovalResponseFrame.class)).isEmpty();
        // ToolCallEvent 契约本身没有 required，但它会被嵌进入站帧的反序列化路径，
        // 同样不能有非空组件
        assertThat(requiredComponentNames(ToolCallEventFrame.class)).isEmpty();
        assertThat(contractRequiredOfSchema("ToolCallEvent"))
                .as("契约确实未给 ToolCallEvent 声明 required").isEmpty();
    }

    /**
     * 出站方向：后端是<b>生产者</b>，契约的 {@code required} 就是它自己的义务。
     *
     * <p>WHY 这里用严格相等：{@code type} 若在 Java 侧可空，就意味着存在一条能发出
     * {@code type=null} 帧的代码路径——那是明确违约，前端会收到一个既不是 {@code data}
     * 也不是 {@code error}/{@code closed} 的帧而无从分派。非空声明让这类路径在编译期消失。</p>
     */
    @Test
    @DisplayName("TerminalOutput：契约 required 恰为无 @Nullable 的 record 组件")
    void terminalOutputRequiredMatchesContract() {
        assertThat(requiredComponentNames(TerminalOutput.class))
                .as("required=[type]（asyncapi.yaml）")
                .containsExactlyInAnyOrderElementsOf(contractRequired("TerminalOutput"));
    }

    /**
     * {@link ApprovalRequestFrame} 只下行，故能把契约 required 直接表达成非空组件。
     *
     * <p>WHY 值得单列：审批帧缺 {@code approval_id} 或 {@code host_id}，
     * 前端弹框能显示但"批准"按钮发回的响应无从关联，命令会一直挂到超时——
     * 用户看到的是"点了批准却没反应"。把必填做成编译期约束，这条路径根本不存在。</p>
     */
    @Test
    @DisplayName("ApprovalRequestFrame：契约 required 恰为无 @Nullable 的 record 组件")
    void approvalRequestRequiredMatchesContract() {
        assertThat(requiredComponentNames(ApprovalRequestFrame.class))
                .containsExactlyInAnyOrderElementsOf(contractRequired("ApprovalRequest"));
    }

    // ======================================================================
    // 枚举线上取值
    // ======================================================================

    @Test
    @DisplayName("TerminalInput.action 的线上取值 == 契约 enum [open, input, close]")
    void actionEnumMatchesContract() {
        assertThat(serializedValuesOf(TerminalInput.Action.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("TerminalInput", "action"));
    }

    @Test
    @DisplayName("TerminalOutput.type 的线上取值 == 契约 enum [data, error, closed]")
    void typeEnumMatchesContract() {
        assertThat(serializedValuesOf(TerminalOutput.Type.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("TerminalOutput", "type"));
    }

    @Test
    @DisplayName("TerminalOutput.stream 的线上取值 == 契约 enum [stdout, stderr]")
    void streamEnumMatchesContract() {
        assertThat(serializedValuesOf(TerminalOutput.Stream.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("TerminalOutput", "stream"));
    }

    @Test
    @DisplayName("AiStreamFrame.Type 的线上取值 == 契约 AiStreamType 七种事件")
    void aiStreamTypeEnumMatchesContract() {
        assertThat(serializedValuesOf(AiStreamFrame.Type.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("AiStreamType", null));
    }

    @Test
    @DisplayName("AiStreamFrame.Segment 的线上取值 == 契约 AiStream.segment 内联 enum")
    void aiStreamSegmentEnumMatchesContract() {
        assertThat(serializedValuesOf(AiStreamFrame.Segment.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("AiStream", "segment"));
    }

    /**
     * {@link ToolName} 同时承担"契约转录"与"工具分级"两个职责，
     * 后者是安全边界（副作用工具必须审批），所以它的取值集合必须与契约严格一致——
     * 少一个常量，那个工具就无法被识别，模型调用它时会走进"未知工具"分支；
     * 多一个常量，就是一个契约里没有、前端不认识的工具。
     */
    @Test
    @DisplayName("ToolName 的线上取值 == 契约 ToolName enum（四个工具，一个不多一个不少）")
    void toolNameEnumMatchesContract() {
        assertThat(serializedValuesOf(ToolName.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("ToolName", null));
    }

    /**
     * asyncapi 的 {@code ApprovalDecision} 是 {@code [approve, cancel]}（用户的动作），
     * 与 openapi 的 {@code [approved, cancelled, timed_out]}（审计里的最终决定）<b>取值不同</b>。
     *
     * <p>WHY 要断言这个"看起来显然"的事实：它是最容易被"好心重构"掉的地方——
     * 有人看到两个同名枚举会想"复用一下更 DRY"，一改就让前端发的 {@code approve}
     * 在后端解析失败，命令既不执行也不取消，一直挂到超时。
     * 本用例把"两者不可互换"变成一条会红的断言。</p>
     */
    @Test
    @DisplayName("ws 侧 ApprovalDecision == asyncapi 的 [approve, cancel]，且与 REST 侧取值不同")
    void wsApprovalDecisionMatchesAsyncapiAndDiffersFromRest() {
        assertThat(serializedValuesOf(ApprovalResponseFrame.Decision.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("ApprovalDecision", null));
        assertThat(serializedValuesOf(ApprovalResponseFrame.Decision.class))
                .as("REST 侧的 ApprovalDecision 是审计视角（含 timed_out），不可与 ws 侧互换")
                .doesNotContainAnyElementsOf(
                        serializedValuesOf(com.ananoesis.shell.contract.model.ApprovalDecision.class));
    }

    /**
     * 跨文件漂移守卫：{@code ErrorCode} 与 {@code EndReason} 在 openapi.yaml 与
     * asyncapi.yaml 里<b>各写了一份</b>（asyncapi 的注释自称"与 REST 契约同源"，
     * 但那是人写的注释，机器不会去核对）。ws 层的错误帧复用的是 openapi 生成物，
     * 所以真正会出事的是"asyncapi 悄悄改了取值，前端按它解析，后端发的是旧的"。
     */
    @Test
    @DisplayName("生成物 ErrorCode 的线上取值 == asyncapi 自己声明的 ErrorCode 枚举")
    void generatedErrorCodeMatchesAsyncapi() {
        assertThat(serializedValuesOf(ErrorCode.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("ErrorCode", null));
    }

    @Test
    @DisplayName("生成物 EndReason 的线上取值 == asyncapi 自己声明的 EndReason 枚举")
    void generatedEndReasonMatchesAsyncapi() {
        assertThat(serializedValuesOf(EndReason.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("EndReason", null));
    }

    /**
     * 同上，针对 {@code ToolResultStatus}：ws 的 {@code tool_result} 帧复用的是 openapi 生成物，
     * 而 asyncapi 又抄了一份。工具结果状态直接决定前端把这次调用渲染成"成功/被拒绝/超时/截断"，
     * 取值对不上就会显示成一个未知状态。
     */
    @Test
    @DisplayName("生成物 ToolResultStatus 的线上取值 == asyncapi 自己声明的 ToolResultStatus 枚举")
    void generatedToolResultStatusMatchesAsyncapi() {
        assertThat(serializedValuesOf(ToolResultStatus.class))
                .containsExactlyInAnyOrderElementsOf(contractEnum("ToolResultStatus", null));
    }

    // ======================================================================
    // 通道与端点
    // ======================================================================

    @Test
    @DisplayName("终端端点 == 契约 servers.ws.url 的 path 拼上 channel /terminal")
    void terminalEndpointMatchesContractServerAndChannel() {
        URI base = wsServerUrl();
        Map<String, Object> channels = section(root, "channels");
        assertThat(channels).as("契约必须声明终端通道").containsKey("/terminal");

        // WHY 用 getPath() 而不是硬编码 "/ws"：契约若把 base 改成 /api/ws 或加前缀，
        // 硬编码的断言会照样绿，而真实的端点已经对不上前端了
        assertThat(WebSocketConfiguration.TERMINAL_ENDPOINT)
                .isEqualTo(base.getPath() + "/terminal");
    }

    @Test
    @DisplayName("审批端点 == 契约 servers.ws.url 的 path 拼上 channel /approval")
    void approvalEndpointMatchesContractServerAndChannel() {
        URI base = wsServerUrl();
        assertThat(section(root, "channels")).as("契约必须声明审批通道").containsKey("/approval");
        assertThat(WebSocketConfiguration.APPROVAL_ENDPOINT)
                .isEqualTo(base.getPath() + "/approval");
    }

    @Test
    @DisplayName("AI 端点 == 契约 servers.ws.url 的 path 拼上 channel /ai")
    void aiEndpointMatchesContractServerAndChannel() {
        URI base = wsServerUrl();
        assertThat(section(root, "channels")).as("契约必须声明 AI 通道").containsKey("/ai");
        assertThat(WebSocketConfiguration.AI_ENDPOINT)
                .isEqualTo(base.getPath() + "/ai");
    }

    @Test
    @DisplayName("/terminal 通道：subscribe 收 terminal_input、publish 发 terminal_output")
    void terminalChannelWiringMatchesContract() {
        Map<String, Object> channel = channel("/terminal");

        // WHY 连方向一起断言：asyncapi 的方向语义是"以后端为记录主体"
        // （subscribe = 后端接收 = 客户端发送）。把两个 operation 调换不会让任何
        // 单元测试变红，但会让前端把输入发到收不到的通道上——症状是"敲键盘毫无反应"。
        assertThat(messageNameOf(channel, "subscribe")).isEqualTo("terminal_input");
        assertThat(messageNameOf(channel, "publish")).isEqualTo("terminal_output");
    }

    /**
     * 审批通道的方向与终端<b>相反</b>：请求由后端发起（publish），响应由客户端回送（subscribe）。
     *
     * <p>WHY 单独钉住：把这两个 operation 记反，后端就会去"接收"一个永远不会有人发的
     * {@code approval_request}，而把审批弹框发到客户端的发送通道上。
     * 编译、启动、连接全都不会报错，唯一症状是"AI 提议执行命令后界面毫无反应"。</p>
     */
    @Test
    @DisplayName("/approval 通道：publish 发 approval_request、subscribe 收 approval_response")
    void approvalChannelWiringMatchesContract() {
        Map<String, Object> channel = channel("/approval");

        assertThat(messageNameOf(channel, "publish")).isEqualTo("approval_request");
        assertThat(messageNameOf(channel, "subscribe")).isEqualTo("approval_response");
        assertThat(operationIdOf(channel, "publish")).isEqualTo("sendApprovalRequest");
        assertThat(operationIdOf(channel, "subscribe")).isEqualTo("receiveApprovalResponse");
    }

    /**
     * AI 通道<b>双向复用同一个</b> {@code ai_stream} 消息（TRACEABILITY Q2）。
     *
     * <p>WHY 连 operationId 一起断言：两个方向的 message 名字相同，
     * 只看名字无法发现"方向被记反"。operationId（{@code receiveUserMessage} /
     * {@code sendAiStream}）才是区分上下行的唯一线索。</p>
     */
    @Test
    @DisplayName("/ai 通道：subscribe 收 user_message、publish 发 ai_stream，二者复用同一 payload")
    void aiChannelWiringMatchesContract() {
        Map<String, Object> channel = channel("/ai");

        assertThat(messageNameOf(channel, "subscribe")).isEqualTo("ai_stream");
        assertThat(messageNameOf(channel, "publish")).isEqualTo("ai_stream");
        assertThat(operationIdOf(channel, "subscribe")).isEqualTo("receiveUserMessage");
        assertThat(operationIdOf(channel, "publish")).isEqualTo("sendAiStream");
    }

    @Test
    @DisplayName("terminal_input/terminal_output 的 payload 直接 $ref 载荷 schema（无信封）")
    void terminalMessagesHaveNoEnvelope() {
        Map<String, Object> messages = messages();

        assertThat(payloadRefOf(messages, "terminal_input"))
                .isEqualTo("#/components/schemas/TerminalInput");
        assertThat(payloadRefOf(messages, "terminal_output"))
                .isEqualTo("#/components/schemas/TerminalOutput");
    }

    /**
     * WHY 断言"无信封"：若契约给审批/AI 消息套了一层 {@code {header, payload}}，
     * 后端按裸 schema 解析就会全帧失败；反过来，后端自作主张加信封而同步忘了改契约，
     * 前端会收到解不开的帧。两种漂移都表现为"连上了但什么都没发生"。
     */
    @Test
    @DisplayName("approval_request/approval_response/ai_stream 的 payload 同样直接 $ref（无信封）")
    void approvalAndAiMessagesHaveNoEnvelope() {
        Map<String, Object> messages = messages();

        assertThat(payloadRefOf(messages, "approval_request"))
                .isEqualTo("#/components/schemas/ApprovalRequest");
        assertThat(payloadRefOf(messages, "approval_response"))
                .isEqualTo("#/components/schemas/ApprovalResponse");
        assertThat(payloadRefOf(messages, "ai_stream"))
                .isEqualTo("#/components/schemas/AiStream");
    }

    // ======================================================================
    // 反射/契约读取辅助
    // ======================================================================

    /** @return 序列化器实际使用的线上字段名（已应用 {@code @JsonNaming}/{@code @JsonProperty}） */
    private Set<String> wireNamesOf(Class<?> type) {
        return new LinkedHashSet<>(wireNameByInternalName(type).values());
    }

    /**
     * @return Java 组件名 → 线上字段名。
     *
     * <p>WHY 用 {@code getInternalName()}：它给的是<b>Java 侧</b>的名字，
     * 而 {@code getName()} 给的是<b>线上</b>的名字；两者配对才构成一张翻译表。
     * 翻译交给 Jackson 内省，等于让"决定线上形态的那套注解链"自己作证，
     * 测试里不重写第二份下划线换算逻辑。</p>
     */
    private Map<String, String> wireNameByInternalName(Class<?> type) {
        BeanDescription description = mapper.getSerializationConfig()
                .introspect(mapper.getTypeFactory().constructType(type));
        Map<String, String> names = new LinkedHashMap<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            // WHY 跳过不可序列化的属性：record 的组件都有访问器，这里主要是防御性地
            // 排除"只有 getter 没有对应字段"的合成属性，避免断言被无关项干扰
            if (!property.couldSerialize()) {
                continue;
            }
            names.put(property.getInternalName(), property.getName());
        }
        return names;
    }

    /**
     * @return record 中<b>没有</b> {@code @Nullable} 的组件，翻译成<b>线上字段名</b>
     *         （即契约意义上的必填项）
     *
     * <p>WHY 必须翻译、不能直接用 {@code component.getName()}：Java 侧是 camelCase，
     * 契约侧是 snake_case，两者只在"单个单词"时碰巧相同。这个缺陷最初是<b>隐形</b>的——
     * {@code TerminalOutput} 的必填项恰好只有 {@code type} 一个单词，测试照样绿；
     * 直到 {@code ApprovalRequestFrame}（六个必填项全是多单词）才暴露。
     * 换句话说：原先那个绿灯证明的东西比它看上去少得多。</p>
     */
    private Set<String> requiredComponentNames(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        assertThat(components).as("%s 必须是 record", type.getSimpleName()).isNotNull();
        Map<String, String> wireNames = wireNameByInternalName(type);
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : components) {
            // WHY 读访问器而不是组件本身：见类注释。访问器上确有 @Nullable（javap 可见）
            boolean nullable = Arrays.stream(component.getAccessor().getAnnotations())
                    .anyMatch(a -> Nullable.class.equals(a.annotationType()));
            if (nullable) {
                continue;
            }
            String javaName = component.getName();
            String wireName = wireNames.get(javaName);
            assertThat(wireName)
                    .as("%s.%s 必须可序列化，否则无从与契约 required 对齐", type.getSimpleName(), javaName)
                    .isNotNull();
            names.add(wireName);
        }
        return names;
    }

    /**
     * @return 枚举常量的线上取值（经 Jackson 序列化，故 {@code @JsonValue}/{@code @JsonProperty} 生效）
     */
    private Set<String> serializedValuesOf(Class<? extends Enum<?>> type) {
        Set<String> values = new LinkedHashSet<>();
        for (Enum<?> constant : type.getEnumConstants()) {
            try {
                values.add(mapper.writeValueAsString(constant).replace("\"", ""));
            } catch (IOException e) {
                fail("序列化枚举常量失败: " + constant, e);
            }
        }
        return values;
    }

    private Set<String> contractPropertyNames(String schemaName) {
        return new TreeSet<>(castMap(schema(schemaName).get("properties"), schemaName + ".properties").keySet());
    }

    private Set<String> contractRequired(String schemaName) {
        Object required = schema(schemaName).get("required");
        assertThat(required).as("%s.required 必须声明", schemaName).isInstanceOf(List.class);
        return toNameSet(required);
    }

    /** 同 {@link #contractRequired}，但 schema 未声明 required 时返回空集而非失败。 */
    private Set<String> contractRequiredOfSchema(String schemaName) {
        Object required = schema(schemaName).get("required");
        return required instanceof List ? toNameSet(required) : Set.of();
    }

    private static Set<String> toNameSet(Object required) {
        Set<String> names = new TreeSet<>();
        for (Object item : (List<?>) required) {
            names.add(String.valueOf(item));
        }
        return names;
    }

    /**
     * @param propertyName 该 schema 下的属性名；为 null 时把 schema 自身当作枚举读取
     */
    private Set<String> contractEnum(String schemaName, String propertyName) {
        Map<String, Object> node = schema(schemaName);
        if (propertyName != null) {
            node = castMap(castMap(node.get("properties"), schemaName + ".properties").get(propertyName),
                    schemaName + "." + propertyName);
        }
        Object values = node.get("enum");
        assertThat(values).as("%s.%s.enum 必须声明", schemaName, propertyName).isInstanceOf(List.class);
        return toNameSet(values);
    }

    private Map<String, Object> schema(String schemaName) {
        return castMap(schemas.get(schemaName), "components.schemas." + schemaName);
    }

    private static URI wsServerUrl() {
        Map<String, Object> ws = castMap(section(root, "servers").get("ws"), "servers.ws");
        assertThat(ws.get("protocol")).as("协议必须是 ws（非 wss）").isEqualTo("ws");
        return URI.create(String.valueOf(ws.get("url")));
    }

    private static Map<String, Object> channel(String path) {
        return castMap(section(root, "channels").get(path), "channels." + path);
    }

    private static Map<String, Object> messages() {
        return castMap(section(root, "components").get("messages"), "components.messages");
    }

    private static String messageNameOf(Map<String, Object> channel, String operation) {
        String ref = String.valueOf(operationNode(channel, operation).get("message") == null
                ? null
                : castMap(operationNode(channel, operation).get("message"), operation + ".message").get("$ref"));
        assertThat(ref).as("%s.message.$ref 必须声明", operation).isNotNull();
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private static String operationIdOf(Map<String, Object> channel, String operation) {
        return String.valueOf(operationNode(channel, operation).get("operationId"));
    }

    private static Map<String, Object> operationNode(Map<String, Object> channel, String operation) {
        return castMap(channel.get(operation), "channel." + operation);
    }

    private static String payloadRefOf(Map<String, Object> messages, String messageName) {
        Map<String, Object> message = castMap(messages.get(messageName), "components.messages." + messageName);
        Map<String, Object> payload = castMap(message.get("payload"), messageName + ".payload");
        return String.valueOf(payload.get("$ref"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value, String what) {
        if (!(value instanceof Map)) {
            fail("%s 应为映射，实际为 %s", what, value == null ? "null" : value.getClass().getSimpleName());
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> section(Map<String, Object> source, String key) {
        return castMap(source.get(key), key);
    }

}

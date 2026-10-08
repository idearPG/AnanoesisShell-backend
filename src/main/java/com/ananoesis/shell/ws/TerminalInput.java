package com.ananoesis.shell.ws;

import java.util.UUID;

import org.springframework.lang.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * {@code terminal_input} 消息载荷（asyncapi.yaml {@code components/schemas/TerminalInput}）。
 *
 * <p>WHY 手写而不是生成：codegen 只覆盖 {@code contract/openapi.yaml}（REST）。
 * asyncapi 的 Spring 生成器生态不成熟，硬接会引入一个与 openapi-generator 平行的
 * 第二套代码生成机制。因此这里改为**逐字段对照契约转录**，并做两件降漂移的事：</p>
 * <ol>
 *   <li>{@code error_code}/{@code end_reason} 直接复用生成的
 *       {@link com.ananoesis.shell.contract.model.ErrorCode} 与
 *       {@link com.ananoesis.shell.contract.model.EndReason} 枚举——契约改了取值，
 *       这里跟着编译期出错，而不是静默发出对端不认识的字符串；</li>
 *   <li>{@link WsContractAlignmentTest} 把 asyncapi.yaml 当作**数据**读进来，
 *       断言字段名与枚举取值和本类一致。契约漂移会让那个测试变红。</li>
 * </ol>
 *
 * <p>WHY 用 record 而不是可变 POJO：消息是一次性的值对象，不可变性让"处理途中
 * 被人改了字段"这类竞态无从发生。</p>
 *
 * <p>WHY {@code @JsonIgnoreProperties(ignoreUnknown = true)}：契约允许未来新增字段
 * （0.1.0 之后的小版本）。默认行为是抛异常，那会让"前端升级、后端未升级"
 * 直接表现为终端打不开；忽略未知字段才能向前兼容。</p>
 *
 * <p>WHY <b>连 {@code action} 都标</b> {@link Nullable}，尽管契约把它列为 {@code required}：
 * 契约的 required 约束的是<b>发送方</b>，而本类是接收方的反序列化目标。若在此声明非空，
 * 缺了 {@code action} 的帧会在 Jackson 阶段直接抛异常，处理器就没机会按契约回一个
 * {@code error_code=validation_error} 的 {@code terminal_output} 帧——前端只会看到连接莫名断开。
 * "组件可空 + 处理器显式校验"才能把违约翻译成契约规定的错误。
 * 这条不变量由 {@code WsContractAlignmentTest#terminalInputToleratesMissingFields} 钉住。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TerminalInput(
        @Nullable Action action,
        @Nullable UUID hostId,
        @Nullable UUID sessionId,
        @Nullable String data,
        @Nullable String controlToken,
        @Nullable Integer cols,
        @Nullable Integer rows) {

    /**
     * 输入动作。
     *
     * <p>WHY 每个常量都写 {@code @JsonProperty}：Jackson 默认按枚举**常量名**匹配，
     * 而契约的线上取值是小写 {@code open}/{@code input}/{@code close}。
     * 不显式标注就只能依赖"把常量名写成小写"这种既违反 Java 命名规范、
     * 又会在 IDE 里被高亮成警告的做法。</p>
     */
    public enum Action {
        /** 请求建立 SSH 会话；{@code host_id} 必填（Q1 裁定）。 */
        @JsonProperty("open") OPEN,
        /** 转发按键/命令原文，可含控制字节（如 Ctrl-C = {@code \u0003}）。 */
        @JsonProperty("input") INPUT,
        /** 主动断开。 */
        @JsonProperty("close") CLOSE,
        /** V2：提交 session_id + control_token，连接后首帧，10 秒未绑定即关闭。 */
        @JsonProperty("bind") BIND,
        /** V2：传递 cols/rows 变化（ssh-connection「远端终端尺寸同步」）。 */
        @JsonProperty("resize") RESIZE
    }
}

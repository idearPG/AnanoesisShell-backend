package com.ananoesis.shell.ws;

import java.util.UUID;

import org.springframework.lang.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * {@code approval_response} 消息载荷（asyncapi.yaml {@code components/schemas/ApprovalResponse}）。
 *
 * <p>对应 command-approval spec「用户批准执行」/「用户取消执行」：
 * {@code approve} → 经 exec channel 执行命令并把输出回喂给模型；
 * {@code cancel} → 回喂「用户已拒绝」。</p>
 *
 * <h2>WHY 本帧的 {@code Decision} 不能复用 REST 侧的 {@code ApprovalDecision}</h2>
 * <p>两个契约文件里各有一个叫 {@code ApprovalDecision} 的枚举，但<b>取值不同</b>：
 * asyncapi 的是 {@code [approve, cancel, modify]}（用户在弹框上能做的动作，V2 新增 modify），
 * openapi 的是 {@code [approved, cancelled, timed_out]}（审计记录里的三种最终决定，
 * 多出"超时"因为它不是用户做的动作）。二者是同一件事的两个视角，
 * 复用任何一个都会让另一端收到对不上的字符串——症状是前端点了批准、后端判成未知决定、
 * 命令既不执行也不取消，一直挂到超时。</p>
 *
 * <h2>WHY 全组件可空</h2>
 * <p>同 {@link TerminalInput}：入站帧的字段缺失必须走到处理器的校验分支，
 * 才能按契约回 {@code validation_error}，而不是在 Jackson 阶段炸掉连接。</p>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalResponseFrame(
        @Nullable UUID approvalId,
        @Nullable Decision decision,
        @Nullable String comment,
        @Nullable String modifiedCommand,
        @Nullable Integer expectedVersion) {

    /** 用户决定（契约 asyncapi {@code ApprovalDecision}）。 */
    public enum Decision {
        /** 批准：执行命令并回喂输出。 */
        @JsonProperty("approve") APPROVE,
        /** 取消：回喂「用户已拒绝」。 */
        @JsonProperty("cancel") CANCEL,
        /** V2：修改后重新审批（design D5）。 */
        @JsonProperty("modify") MODIFY
    }
}

package com.ananoesis.shell.ai;

/**
 * 嵌套 Shell 环境信息。
 *
 * <p>供 {@link AgentSystemPrompt} 注入系统提示词使用。独立于 {@code AgentSystemPrompt}
 * 以避免包可见性限制跨包引用。</p>
 *
 * @param nested                 是否处于嵌套 Shell（如 Docker 容器内）
 * @param integrationAvailable   Shell 集成是否在嵌套环境中可用
 */
public record NestedEnvInfo(boolean nested, boolean integrationAvailable) {
}

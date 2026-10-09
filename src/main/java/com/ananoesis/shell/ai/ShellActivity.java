package com.ananoesis.shell.ai;

/**
 * 最近 Shell 活动记录（人工命令摘要）。
 *
 * <p>供 {@link AgentSystemPrompt} 注入系统提示词、{@link com.ananoesis.shell.service.CommandExecutionService}
 * 查询结果返回使用。独立于 {@code AgentSystemPrompt} 以避免包可见性限制跨包引用。</p>
 *
 * @param command  命令原文
 * @param exitCode 退出码（-1 表示未知）
 */
public record ShellActivity(String command, int exitCode) {
}

package com.ananoesis.shell.ws;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ToolResultStatus;

/**
 * WS 帧类的分支覆盖补测。
 *
 * <p>WHY 集中在此文件：ToolCallEventFrame / ApprovalRequestFrame 的分支缺口均为
 * 静态工厂的参数校验路径（null 检查、工具分级校验），逻辑独立且无外部依赖。</p>
 */
@DisplayName("WS 帧类分支覆盖")
class WsFrameBranchTest {

    private static final UUID CONV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HOST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID APPROVAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // ==================================================================
    // ToolCallEventFrame (6 missed)
    // ==================================================================

    @Nested
    @DisplayName("ToolCallEventFrame")
    class ToolCallEventFrameTests {

        @Test
        @DisplayName("autoExecuted: 只读工具正常创建")
        void autoExecutedWithReadOnlyTool() {
            ToolCallEventFrame frame = ToolCallEventFrame.autoExecuted(
                    ToolName.LIST_DIR, Map.of("path", "/tmp"));
            assertThat(frame.auto()).isTrue();
            assertThat(frame.toolName()).isEqualTo(ToolName.LIST_DIR);
        }

        @Test
        @DisplayName("autoExecuted: 副作用工具抛异常")
        void autoExecutedWithGatedToolThrows() {
            assertThatThrownBy(() -> ToolCallEventFrame.autoExecuted(
                    ToolName.RUN_COMMAND, Map.of("command", "ls")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("有副作用");
        }

        @Test
        @DisplayName("autoExecuted: toolName 为 null 时抛异常")
        void autoExecutedWithNullToolNameThrows() {
            assertThatThrownBy(() -> ToolCallEventFrame.autoExecuted(null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("toolName");
        }

        @Test
        @DisplayName("pendingApproval: 正常创建")
        void pendingApprovalNormal() {
            ToolCallEventFrame frame = ToolCallEventFrame.pendingApproval(
                    ToolName.RUN_COMMAND, Map.of("command", "rm"), APPROVAL_ID);
            assertThat(frame.auto()).isFalse();
            assertThat(frame.approvalId()).isEqualTo(APPROVAL_ID);
        }

        @Test
        @DisplayName("pendingApproval: approvalId 为 null 时抛异常")
        void pendingApprovalNullIdThrows() {
            assertThatThrownBy(() -> ToolCallEventFrame.pendingApproval(
                    ToolName.RUN_COMMAND, Map.of(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("approvalId");
        }

        @Test
        @DisplayName("pendingApproval: 只读工具抛异常")
        void pendingApprovalWithReadOnlyToolThrows() {
            assertThatThrownBy(() -> ToolCallEventFrame.pendingApproval(
                    ToolName.LIST_DIR, Map.of(), APPROVAL_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不经审批");
        }

        @Test
        @DisplayName("result: 正常创建")
        void resultNormal() {
            ToolCallEventFrame frame = ToolCallEventFrame.result(
                    ToolName.LIST_DIR, Map.of(), true, null,
                    ToolResultStatus.SUCCESS, "ok");
            assertThat(frame.resultStatus()).isEqualTo(ToolResultStatus.SUCCESS);
        }

        @Test
        @DisplayName("result: status 为 null 时抛异常")
        void resultNullStatusThrows() {
            assertThatThrownBy(() -> ToolCallEventFrame.result(
                    ToolName.LIST_DIR, Map.of(), true, null, null, "ok"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("result_status");
        }

        @Test
        @DisplayName("params: null 归一为空表")
        void nullParamsNormalizedToEmptyMap() {
            ToolCallEventFrame frame = ToolCallEventFrame.autoExecuted(ToolName.LIST_DIR, null);
            assertThat(frame.toolParams()).isEmpty();
        }
    }

    // ==================================================================
    // ApprovalRequestFrame (8 missed)
    // ==================================================================

    @Nested
    @DisplayName("ApprovalRequestFrame")
    class ApprovalRequestFrameTests {

        @Test
        @DisplayName("of: 正常创建")
        void ofNormal() {
            ApprovalRequestFrame frame = ApprovalRequestFrame.of(
                    APPROVAL_ID, CONV_ID, HOST_ID, "myhost",
                    ToolName.RUN_COMMAND, Map.of("command", "rm"),
                    "rm -rf /", "危险操作", 1, 60, null);
            assertThat(frame.approvalId()).isEqualTo(APPROVAL_ID);
            assertThat(frame.toolName()).isEqualTo(ToolName.RUN_COMMAND);
        }

        @Test
        @DisplayName("of: approvalId 为 null 时抛异常")
        void ofNullApprovalIdThrows() {
            assertThatThrownBy(() -> ApprovalRequestFrame.of(
                    null, CONV_ID, HOST_ID, "myhost",
                    ToolName.RUN_COMMAND, Map.of(), "cmd", "analysis", 1, 60, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("approvalId");
        }

        @Test
        @DisplayName("of: conversationId 为 null 时抛异常")
        void ofNullConversationIdThrows() {
            assertThatThrownBy(() -> ApprovalRequestFrame.of(
                    APPROVAL_ID, null, HOST_ID, "myhost",
                    ToolName.RUN_COMMAND, Map.of(), "cmd", "analysis", 1, 60, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("conversationId");
        }

        @Test
        @DisplayName("of: hostId 为 null 时抛异常")
        void ofNullHostIdThrows() {
            assertThatThrownBy(() -> ApprovalRequestFrame.of(
                    APPROVAL_ID, CONV_ID, null, "myhost",
                    ToolName.RUN_COMMAND, Map.of(), "cmd", "analysis", 1, 60, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hostId");
        }

        @Test
        @DisplayName("of: toolName 为 null 时抛异常")
        void ofNullToolNameThrows() {
            assertThatThrownBy(() -> ApprovalRequestFrame.of(
                    APPROVAL_ID, CONV_ID, HOST_ID, "myhost",
                    null, Map.of(), "cmd", "analysis", 1, 60, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("toolName");
        }

        @Test
        @DisplayName("of: 只读工具抛异常")
        void ofReadOnlyToolThrows() {
            assertThatThrownBy(() -> ApprovalRequestFrame.of(
                    APPROVAL_ID, CONV_ID, HOST_ID, "myhost",
                    ToolName.LIST_DIR, Map.of(), "cmd", "analysis", 1, 60, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不应产生审批请求");
        }

        @Test
        @DisplayName("of: aiAnalysis 为 null 时抛异常")
        void ofNullAiAnalysisThrows() {
            assertThatThrownBy(() -> ApprovalRequestFrame.of(
                    APPROVAL_ID, CONV_ID, HOST_ID, "myhost",
                    ToolName.RUN_COMMAND, Map.of(), "cmd", null, 1, 60, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("aiAnalysis");
        }

        @Test
        @DisplayName("of: version 为 null 时抛异常")
        void ofNullVersionThrows() {
            assertThatThrownBy(() -> ApprovalRequestFrame.of(
                    APPROVAL_ID, CONV_ID, HOST_ID, "myhost",
                    ToolName.RUN_COMMAND, Map.of(), "cmd", "analysis", null, 60, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version");
        }

        @Test
        @DisplayName("of: toolParams 为 null 时归一为空表")
        void ofNullToolParamsNormalizedToEmptyMap() {
            ApprovalRequestFrame frame = ApprovalRequestFrame.of(
                    APPROVAL_ID, CONV_ID, HOST_ID, "myhost",
                    ToolName.RUN_COMMAND, null, "cmd", "analysis", 1, 60, null);
            assertThat(frame.toolParams()).isEmpty();
        }
    }
}

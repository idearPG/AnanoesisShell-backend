package com.ananoesis.shell.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ShellFrameDecoder 测试（task 5.1）。
 *
 * <p>验证 OSC 1337 控制帧的解析、分片处理、非法帧拒绝、nonce 校验、
 * UTF-8 边界处理以及内部帧从显示流中剥离。</p>
 */
@DisplayName("ShellFrameDecoder")
class ShellFrameDecoderTest {

    private static final String NONCE = "test-nonce-123";

    private ShellFrameDecoder decoder;

    /** 记录解码器回调的所有帧。 */
    private java.util.List<ShellFrame> frames;

    @BeforeEach
    void setUp() {
        frames = new java.util.ArrayList<>();
        decoder = new ShellFrameDecoder(NONCE, frames::add);
    }

    // ==================================================================
    // 完整帧解析
    // ==================================================================

    @Nested
    @DisplayName("完整帧解析")
    class CompleteFrameParsing {

        @Test
        @DisplayName("解析经 ST (ESC \\) 终止的 cmd_start 帧")
        void parsesCmdStartFrameTerminatedWithST() {
            String data = "\u001B]1337;cmd_start;" + NONCE + ";cmd-1;\u001B\\";
            String display = decoder.decode(data);

            assertThat(display).isEmpty();
            assertThat(frames).hasSize(1);
            ShellFrame frame = frames.get(0);
            assertThat(frame.type()).isEqualTo(ShellFrameType.CMD_START);
            assertThat(frame.nonce()).isEqualTo(NONCE);
            assertThat(frame.commandId()).isEqualTo("cmd-1");
            assertThat(frame.payload()).isEmpty();
        }

        @Test
        @DisplayName("解析经 BEL 终止的 cmd_end 帧，payload 为退出码")
        void parsesCmdEndFrameTerminatedWithBEL() {
            String data = "\u001B]1337;cmd_end;" + NONCE + ";cmd-1;0\u0007";
            String display = decoder.decode(data);

            assertThat(display).isEmpty();
            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).type()).isEqualTo(ShellFrameType.CMD_END);
            assertThat(frames.get(0).payload()).isEqualTo("0");
        }

        @Test
        @DisplayName("解析 cwd 帧，payload 含工作目录路径")
        void parsesCwdFrame() {
            String data = "\u001B]1337;cwd;" + NONCE + ";cmd-1;/home/user\u0007";
            decoder.decode(data);

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).type()).isEqualTo(ShellFrameType.CWD);
            assertThat(frames.get(0).payload()).isEqualTo("/home/user");
        }

        @Test
        @DisplayName("解析 prompt 帧")
        void parsesPromptFrame() {
            String data = "\u001B]1337;prompt;" + NONCE + ";cmd-1;\u0007";
            decoder.decode(data);

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).type()).isEqualTo(ShellFrameType.PROMPT);
        }

        @Test
        @DisplayName("payload 含分号不被错误拆分")
        void payloadWithSemicolonIsNotSplitIncorrectly() {
            // payload "a;b;c" 应整体保留
            String data = "\u001B]1337;cwd;" + NONCE + ";cmd-1;/path;a;b;c\u0007";
            decoder.decode(data);

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).payload()).isEqualTo("/path;a;b;c");
        }
    }

    // ==================================================================
    // 分片处理
    // ==================================================================

    @Nested
    @DisplayName("分片处理")
    class FragmentHandling {

        @Test
        @DisplayName("OSC 起始与帧体分两次到达")
        void oscStartAndBodyArriveSeparately() {
            String part1 = "\u001B]1337;cmd_start;";
            String part2 = NONCE + ";cmd-1;\u0007";

            assertThat(decoder.decode(part1)).isEmpty();
            assertThat(frames).isEmpty();

            assertThat(decoder.decode(part2)).isEmpty();
            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).type()).isEqualTo(ShellFrameType.CMD_START);
        }

        @Test
        @DisplayName("ESC 与 ] 分两次到达")
        void escAndBracketArriveSeparately() {
            String part1 = "\u001B";
            String part2 = "]1337;prompt;" + NONCE + ";cmd-1;\u0007";

            assertThat(decoder.decode(part1)).isEmpty();
            assertThat(decoder.decode(part2)).isEmpty();
            assertThat(frames).hasSize(1);
        }

        @Test
        @DisplayName("逐字符喂入仍能正确解析帧")
        void characterByCharacterFeedingStillParses() {
            String full = "\u001B]1337;cwd;" + NONCE + ";cmd-1;/tmp\u0007";
            StringBuilder display = new StringBuilder();

            for (int i = 0; i < full.length(); i++) {
                display.append(decoder.decode(String.valueOf(full.charAt(i))));
            }

            assertThat(display.toString()).isEmpty();
            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).payload()).isEqualTo("/tmp");
        }

        @Test
        @DisplayName("ST 终止符分片：ESC 与 \\ 分开到达")
        void stTerminatorSplitAcrossChunks() {
            String part1 = "\u001B]1337;prompt;" + NONCE + ";c1;\u001B";
            String part2 = "\\";

            assertThat(decoder.decode(part1)).isEmpty();
            assertThat(decoder.decode(part2)).isEmpty();
            assertThat(frames).hasSize(1);
        }
    }

    // ==================================================================
    // 连续帧
    // ==================================================================

    @Nested
    @DisplayName("连续帧")
    class ConsecutiveFrames {

        @Test
        @DisplayName("两条完整帧在同一次 decode 中全部解析")
        void twoFramesInSingleDecode() {
            String data = "\u001B]1337;cmd_start;" + NONCE + ";c1;\u0007"
                    + "\u001B]1337;cmd_end;" + NONCE + ";c1;0\u0007";
            decoder.decode(data);

            assertThat(frames).hasSize(2);
            assertThat(frames.get(0).type()).isEqualTo(ShellFrameType.CMD_START);
            assertThat(frames.get(1).type()).isEqualTo(ShellFrameType.CMD_END);
            assertThat(frames.get(1).payload()).isEqualTo("0");
        }

        @Test
        @DisplayName("帧间含普通文本，文本保留而帧被剥离")
        void textBetweenFramesIsPreserved() {
            String data = "hello\u001B]1337;cwd;" + NONCE + ";c1;/x\u0007world";
            String display = decoder.decode(data);

            assertThat(display).isEqualTo("helloworld");
            assertThat(frames).hasSize(1);
        }
    }

    // ==================================================================
    // 非法帧拒绝
    // ==================================================================

    @Nested
    @DisplayName("非法帧拒绝")
    class IllegalFrameRejection {

        @Test
        @DisplayName("超过最大帧长度的数据被放弃，原始字节作为显示文本输出")
        void oversizedFrameIsAbandonedAndOutputAsDisplayText() {
            // 构造一段超过默认 4096 上限的 OSC 数据
            StringBuilder sb = new StringBuilder("\u001B]1337;cwd;" + NONCE + ";c1;");
            while (sb.length() < 5000) {
                sb.append('x');
            }
            sb.append("\u0007");

            String display = decoder.decode(sb.toString());

            assertThat(frames).isEmpty();
            // 非法帧的全部内容应作为显示文本输出
            assertThat(display.length()).isGreaterThan(4000);
        }

        @Test
        @DisplayName("未知帧类型被忽略，不产生帧事件")
        void unknownFrameTypeIsIgnored() {
            String data = "\u001B]1337;bogus_type;" + NONCE + ";c1;\u0007";
            decoder.decode(data);

            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("格式不完整的帧（缺少字段）被忽略")
        void malformedFrameIsIgnored() {
            // 只有 type 和 nonce，缺少 command_id
            String data = "\u001B]1337;cmd_start;" + NONCE + "\u0007";
            decoder.decode(data);

            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("空 OSC 内容被忽略")
        void emptyOscContentIsIgnored() {
            decoder.decode("\u001B]\u0007");

            assertThat(frames).isEmpty();
        }
    }

    // ==================================================================
    // Nonce 校验
    // ==================================================================

    @Nested
    @DisplayName("Nonce 校验")
    class NonceValidation {

        @Test
        @DisplayName("错误 nonce 的帧被静默丢弃")
        void frameWithWrongNonceIsSilentlyDropped() {
            String data = "\u001B]1337;cmd_start;wrong-nonce;c1;\u0007";
            decoder.decode(data);

            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("正确 nonce 的帧正常通过")
        void frameWithCorrectNoncePasses() {
            String data = "\u001B]1337;cmd_start;" + NONCE + ";c1;\u0007";
            decoder.decode(data);

            assertThat(frames).hasSize(1);
        }

        @Test
        @DisplayName("错误 nonce 的帧被剥离，不出现在显示文本中")
        void wrongNonceFrameIsStrippedFromDisplay() {
            String data = "\u001B]1337;cmd_start;wrong;c1;\u0007";
            String display = decoder.decode(data);

            assertThat(display).isEmpty();
            assertThat(frames).isEmpty();
        }
    }

    // ==================================================================
    // UTF-8 边界处理
    // ==================================================================

    @Nested
    @DisplayName("UTF-8 边界处理")
    class Utf8BoundaryHandling {

        @Test
        @DisplayName("中文文本与帧混合，中文完整保留")
        void chineseTextPreservedAroundFrames() {
            String data = "你好\u001B]1337;cwd;" + NONCE + ";c1;/tmp\u0007世界";
            String display = decoder.decode(data);

            assertThat(display).isEqualTo("你好世界");
            assertThat(frames).hasSize(1);
        }

        @Test
        @DisplayName("中文 payload 完整保留")
        void chinesePayloadPreserved() {
            String data = "\u001B]1337;cwd;" + NONCE + ";c1;/路径/中文\u0007";
            decoder.decode(data);

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).payload()).isEqualTo("/路径/中文");
        }

        @Test
        @DisplayName("多字节字符跨帧边界不损坏")
        void multiByteCharNotCorruptedAcrossFrameBoundary() {
            // "你好" 后紧跟 OSC 帧，"世界" 在帧后
            String data = "你好\u001B]1337;prompt;" + NONCE + ";c1;\u0007世界";
            String display = decoder.decode(data);

            assertThat(display).isEqualTo("你好世界");
        }
    }

    // ==================================================================
    // 显示流剥离
    // ==================================================================

    @Nested
    @DisplayName("显示流剥离")
    class DisplayStreamStripping {

        @Test
        @DisplayName("内部帧不出现在显示文本中")
        void internalFramesAbsentFromDisplayText() {
            String data = "before\u001B]1337;cmd_start;" + NONCE + ";c1;\u0007after";
            String display = decoder.decode(data);

            assertThat(display).isEqualTo("beforeafter");
            assertThat(display).doesNotContain("1337");
            assertThat(display).doesNotContain("cmd_start");
        }

        @Test
        @DisplayName("无帧时文本原样通过")
        void plainTextPassesThroughUnchanged() {
            String data = "just normal text\nwith newlines\r\nand escapes\u001B[31m";
            String display = decoder.decode(data);

            assertThat(display).isEqualTo(data);
        }

        @Test
        @DisplayName("ANSI 颜色转义序列不被误识别为 OSC 帧起始")
        void ansiColorEscapesNotMistakenForOsc() {
            // ESC[31m 是红色 ANSI 序列，不应触发 OSC 解析
            String data = "\u001B[31mred text\u001B[0m";
            String display = decoder.decode(data);

            assertThat(display).isEqualTo(data);
            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("孤立 ESC 字符不影响后续文本")
        void loneEscCharacterDoesNotAffectSubsequentText() {
            // ESC 后跟非 ] 字符，应作为普通文本输出
            String data = "\u001B[31m";
            String display = decoder.decode(data);

            assertThat(display).isEqualTo(data);
        }

        @Test
        @DisplayName("多次 decode 调用间状态一致")
        void stateConsistentAcrossMultipleDecodes() {
            String d1 = decoder.decode("hello ");
            String d2 = decoder.decode("\u001B]1337;cwd;" + NONCE + ";c1;/x\u0007");
            String d3 = decoder.decode(" world");

            assertThat(d1).isEqualTo("hello ");
            assertThat(d2).isEmpty();
            assertThat(d3).isEqualTo(" world");
            assertThat(frames).hasSize(1);
        }
    }

    // ==================================================================
    // decodeCollecting（帧暂存 API）
    // ==================================================================

    @Nested
    @DisplayName("decodeCollecting")
    class DecodeCollecting {

        @Test
        @DisplayName("帧暂存进返回值，不回调 consumer，且与文本顺序一致")
        void framesCollectedNotDispatched() {
            ShellFrameDecoder.Decoded decoded = decoder.decodeCollecting(
                    "ls-out\r\n"
                            + "\u001B]1337;cmd_end;" + NONCE + ";1;0\u0007"
                            + "\u001B]1337;prompt;" + NONCE + ";1;\u0007"
                            + "[root@localhost ~]# ");

            assertThat(decoded.text()).isEqualTo("ls-out\r\n[root@localhost ~]# ");
            assertThat(decoded.frames()).hasSize(2);
            assertThat(decoded.frames().get(0).frame().type()).isEqualTo(ShellFrameType.CMD_END);
            assertThat(decoded.frames().get(1).frame().type()).isEqualTo(ShellFrameType.PROMPT);
            // WHY 暂存路径不得回调 consumer：帧处理顺序由调用方决定（先采集后帧）
            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("帧携带 textOffset：帧在干净文本流中的插入点（保序处理的依据）")
        void frameSliceCarriesTextOffset() {
            ShellFrameDecoder.Decoded decoded = decoder.decodeCollecting(
                    "hello\u001B]1337;cwd;" + NONCE + ";c1;/tmp\u0007world");

            assertThat(decoded.text()).isEqualTo("helloworld");
            assertThat(decoded.frames()).hasSize(1);
            assertThat(decoded.frames().get(0).frame().type()).isEqualTo(ShellFrameType.CWD);
            // 帧完成时干净文本缓冲长 5（"hello"）——帧位于 hello 与 world 之间
            assertThat(decoded.frames().get(0).textOffset()).isEqualTo(5);
        }

        @Test
        @DisplayName("批首帧 offset=0：同批帧后接输出时可先开采集闸门再采文本")
        void frameAtBatchStartHasZeroOffset() {
            ShellFrameDecoder.Decoded decoded = decoder.decodeCollecting(
                    "\u001B]1337;cmd_start;" + NONCE + ";1;\u0007"
                            + "root:x:0:0:root:/root:/bin/bash\r\n");

            assertThat(decoded.frames()).hasSize(1);
            assertThat(decoded.frames().get(0).frame().type()).isEqualTo(ShellFrameType.CMD_START);
            assertThat(decoded.frames().get(0).textOffset()).isZero();
            assertThat(decoded.text()).startsWith("root:x:0:0");
        }

        @Test
        @DisplayName("decode 与 decodeCollecting 互不干扰，共享同一解析状态机")
        void decodeAndCollectingShareParserState() {
            // 前半段帧经暂存路径取出，后半段帧经回调路径分发：
            // 两者共用同一状态机，混合调用不丢帧不串文本
            ShellFrameDecoder.Decoded decoded = decoder.decodeCollecting(
                    "a\u001B]1337;cmd_start;" + NONCE + ";1;\u0007");
            assertThat(decoded.text()).isEqualTo("a");
            assertThat(decoded.frames()).hasSize(1);
            assertThat(frames).isEmpty();

            String display = decoder.decode("b\u001B]1337;cwd;" + NONCE + ";1;/tmp\u0007");
            assertThat(display).isEqualTo("b");
            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).type()).isEqualTo(ShellFrameType.CWD);
        }

        @Test
        @DisplayName("null/空输入返回空文本与空帧列表")
        void nullOrEmptyInputReturnsEmpty() {
            assertThat(decoder.decodeCollecting(null).text()).isEmpty();
            assertThat(decoder.decodeCollecting(null).frames()).isEmpty();
            assertThat(decoder.decodeCollecting("").text()).isEmpty();
            assertThat(decoder.decodeCollecting("").frames()).isEmpty();
        }
    }

    // ==================================================================
    // 退化输入与边界分支
    // ==================================================================

    @Nested
    @DisplayName("退化输入与边界分支")
    class DegenerateInputAndEdgeBranches {

        @Test
        @DisplayName("null consumer 不抛异常，帧静默丢弃（防御性构造）")
        void nullConsumerDoesNotThrow() {
            ShellFrameDecoder silent = new ShellFrameDecoder(NONCE, null);

            assertThat(silent.decode("\u001B]1337;cwd;" + NONCE + ";c1;/tmp\u0007")).isEmpty();
        }

        @Test
        @DisplayName("decode 接受 null/空输入并返回空文本，不产生帧")
        void decodeHandlesNullOrEmptyInput() {
            assertThat(decoder.decode(null)).isEmpty();
            assertThat(decoder.decode("")).isEmpty();
            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("OSC 内 ESC 后跟 ] 视为帧内容一部分，继续收集")
        void escBracketInsideOscContinuesCollection() {
            String data = "\u001B]1337;cwd;" + NONCE + ";c1;\u001B]tail\u0007";
            String display = decoder.decode(data);

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).type()).isEqualTo(ShellFrameType.CWD);
            assertThat(frames.get(0).payload()).isEqualTo("\u001B]tail");
            assertThat(display).isEmpty();
        }

        @Test
        @DisplayName("OSC 内 ESC 后跟普通字符同样继续收集")
        void escPlainCharInsideOscContinuesCollection() {
            String data = "\u001B]1337;cwd;" + NONCE + ";c1;\u001Bxtail\u0007";
            String display = decoder.decode(data);

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).payload()).isEqualTo("\u001Bxtail");
            assertThat(display).isEmpty();
        }

        @Test
        @DisplayName("非 1337 的 OSC 序列被整体忽略并剥出显示流")
        void non1337OscSequenceIsIgnored() {
            String data = "\u001B]0;cmd_start;" + NONCE + ";c1\u0007";
            String display = decoder.decode(data);

            assertThat(frames).isEmpty();
            assertThat(display).isEmpty();
        }
    }
}

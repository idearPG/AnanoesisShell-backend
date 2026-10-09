package com.ananoesis.shell.ssh;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link ShellFrameDecoder} 分支覆盖补测。
 *
 * <p>WHY 独立测试：原测试覆盖基本帧解析，但构造器边界、空帧、
 * 超短帧、未知帧类型、无 payload 帧等分支未被覆盖。</p>
 */
@DisplayName("ShellFrameDecoder 分支覆盖")
class ShellFrameDecoderBranchTest {

    @Nested
    @DisplayName("构造器")
    class Constructor {
        @Test
        @DisplayName("null nonce 抛 IllegalArgumentException")
        void nullNonceThrows() {
            assertThatThrownBy(() -> new ShellFrameDecoder(null, f -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("空 nonce 抛 IllegalArgumentException")
        void emptyNonceThrows() {
            assertThatThrownBy(() -> new ShellFrameDecoder("", f -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null frameConsumer 使用空操作")
        void nullConsumerDefaultsToNoop() {
            ShellFrameDecoder decoder = new ShellFrameDecoder("nonce1", null);
            // 不抛异常即成功
            String result = decoder.decode("hello");
            assertThat(result).isEqualTo("hello");
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {
        @Test
        @DisplayName("null 返回空串")
        void nullReturnsEmpty() {
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", f -> {});
            assertThat(decoder.decode(null)).isEmpty();
        }

        @Test
        @DisplayName("空串返回空串")
        void emptyReturnsEmpty() {
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", f -> {});
            assertThat(decoder.decode("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("parseFrame 边界（通过 decode 间接测试）")
    class ParseFrameBoundary {
        @Test
        @DisplayName("太少的字段被忽略")
        void tooFewFieldsIgnored() {
            List<ShellFrame> frames = new ArrayList<>();
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", frames::add);
            // OSC 帧只有 2 个字段
            String data = "\u001b]1337;n1\u0007";
            decoder.decode(data);
            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("非 1337 帧被忽略")
        void non1337FrameIgnored() {
            List<ShellFrame> frames = new ArrayList<>();
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", frames::add);
            // 第一字段不是 1337
            String data = "\u001b]9999;cmd_start;n1;cid\u0007";
            decoder.decode(data);
            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("未知帧类型被忽略")
        void unknownFrameTypeIgnored() {
            List<ShellFrame> frames = new ArrayList<>();
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", frames::add);
            String data = "\u001b]1337;unknown_type;n1;cid\u0007";
            decoder.decode(data);
            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("无 payload 的帧正常解析")
        void noPayloadFrameParsed() {
            List<ShellFrame> frames = new ArrayList<>();
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", frames::add);
            String data = "\u001b]1337;cmd_start;n1;cid\u0007";
            decoder.decode(data);
            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).payload()).isEmpty();
        }

        @Test
        @DisplayName("带 payload 的帧正常解析")
        void withPayloadFrameParsed() {
            List<ShellFrame> frames = new ArrayList<>();
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", frames::add);
            String data = "\u001b]1337;cmd_end;n1;cid;exit=0\u0007";
            decoder.decode(data);
            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).payload()).isEqualTo("exit=0");
        }

        @Test
        @DisplayName("nonce 不匹配时帧被忽略")
        void nonceMismatchIgnored() {
            List<ShellFrame> frames = new ArrayList<>();
            ShellFrameDecoder decoder = new ShellFrameDecoder("expected", frames::add);
            String data = "\u001b]1337;cmd_start;actual;cid\u0007";
            decoder.decode(data);
            assertThat(frames).isEmpty();
        }

        @Test
        @DisplayName("ESC \\ 终止的帧正常解析")
        void escBackslashTerminatedFrame() {
            List<ShellFrame> frames = new ArrayList<>();
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", frames::add);
            String data = "\u001b]1337;prompt;n1;cid\u001b\\";
            decoder.decode(data);
            assertThat(frames).hasSize(1);
        }
    }

    @Nested
    @DisplayName("OSC 缓冲溢出")
    class OscBufferOverflow {
        @Test
        @DisplayName("超长 OSC 帧被放弃并输出为普通文本")
        void oversizedOscAbandoned() {
            List<ShellFrame> frames = new ArrayList<>();
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", frames::add);
            // 构造超过 4096 字节的 OSC 内容
            StringBuilder sb = new StringBuilder("\u001b]");
            sb.append("x".repeat(4100));
            sb.append("\u0007");
            String result = decoder.decode(sb.toString());
            // 帧被放弃后内容变为普通文本
            assertThat(result).contains("x");
            assertThat(frames).isEmpty();
        }
    }

    @Nested
    @DisplayName("ESC 后非 ] 字符")
    class EscNonBracket {
        @Test
        @DisplayName("ESC 后跟非 ] 字符时两者都输出")
        void escFollowedByNonBracket() {
            ShellFrameDecoder decoder = new ShellFrameDecoder("n1", f -> {});
            String result = decoder.decode("\u001bX");
            assertThat(result).isEqualTo("\u001bX");
        }
    }
}

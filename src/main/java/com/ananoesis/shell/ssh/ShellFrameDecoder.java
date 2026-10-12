package com.ananoesis.shell.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSC 1337 控制帧流式解析器（task 5.1）。
 *
 * <p>从终端输出流中识别并剥离 OSC 1337 控制帧，回调通知上层，
 * 同时返回干净的显示文本。</p>
 *
 * <h2>帧格式</h2>
 * <pre>
 * OSC 1337 ; type ; nonce ; command_id ; payload ST
 * OSC = ESC ] (0x1B 0x5D)
 * ST  = ESC \ (0x1B 0x5C) 或 BEL (0x07)
 * </pre>
 */
public class ShellFrameDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(ShellFrameDecoder.class);

    /** 最大帧长度。design.md D3：有界解析。 */
    private static final int MAX_FRAME_LENGTH = 4096;

    private final String expectedNonce;
    private final Consumer<ShellFrame> frameConsumer;

    private enum State { NORMAL, ESC_PENDING, IN_OSC, OSC_ESC_SEEN }

    private State state = State.NORMAL;
    private final StringBuilder oscBuffer = new StringBuilder();
    private final StringBuilder textBuffer = new StringBuilder();

    public ShellFrameDecoder(String expectedNonce, Consumer<ShellFrame> frameConsumer) {
        if (expectedNonce == null || expectedNonce.isEmpty()) {
            throw new IllegalArgumentException("expectedNonce must not be empty");
        }
        this.expectedNonce = expectedNonce;
        this.frameConsumer = frameConsumer != null ? frameConsumer : f -> { };
    }

    /**
     * 一段终端输出的解码结果：干净显示文本 + 解析出的完整控制帧（按出现顺序，
     * 已按 nonce 过滤）。
     *
     * <p>WHY 帧与文本分离返回：SSH 分块是随机的，快命令的输出常与
     * CMD_END/PROMPT 帧落在<b>同一批</b>数据里。若像 {@link #decode(String)}
     * 那样在解析途中同步回调帧，PROMPT 帧会提前完成命令，随后到达的输出在
     * 采集方眼里已无归属、被丢弃——工具拿到 exit=0 空 stdout，模型幻觉文件
     * 列表（内网实测根因）。调用方用本方法可先让整批文本完成归属、再逐帧
     * 驱动状态机。</p>
     *
     * <p>WHY 每个帧携带 textOffset：快命令的 CMD_START 帧常与命令输出同批，
     * 「整批文本先于帧」会把输出先于 CMD_START 处理，采集闸门（cmdStartSeen）
     * 未开即拒采（内网实测 sed 返回 exit=0 空 stdout 根因）。调用方按 offset
     * 交替处理帧与文本，即可还原帧在输出流中的真实位置。</p>
     *
     * @param text   剥离控制帧后的干净显示文本
     * @param frames 本批解析出的完整帧（有序，各带流内文本偏移）
     */
    public record Decoded(String text, List<FrameSlice> frames) {
    }

    /**
     * 控制帧及其在干净文本流中的位置。
     *
     * @param frame      解析出的控制帧
     * @param textOffset 帧完成时干净文本缓冲的当前长度——即该帧在 {@code text}
     *                   中的插入点，调用方据此把文本切成帧前/帧后两段
     */
    public record FrameSlice(ShellFrame frame, int textOffset) {
    }

    /**
     * 处理一段终端输出，返回干净的显示文本。
     *
     * <p>解析途中遇到完整帧即<b>同步回调</b> frameConsumer。仅在帧回调
     * 必须先于文本消费的场景使用；输出采集场景请改用
     * {@link #decodeCollecting(String)} 避免同批数据里帧先行完成命令。</p>
     */
    public String decode(String data) {
        return decodeInternal(data, null).text;
    }

    /**
     * 处理一段终端输出，返回干净文本与解析出的完整帧（不回调 frameConsumer）。
     *
     * <p>与 {@link #decode(String)} 的差别只在帧的投递时机：本方法把帧暂存
     * 进返回值，由调用方决定处理顺序。两者共享同一解析状态机与 nonce 过滤。</p>
     *
     * @param data 一段终端输出；null 或空串时返回空文本与空帧列表
     * @return 解码结果，永不为 null
     */
    public Decoded decodeCollecting(String data) {
        if (data == null || data.isEmpty()) {
            return new Decoded("", List.of());
        }
        List<FrameSlice> collected = new ArrayList<>();
        return new Decoded(decodeInternal(data, collected).text(), collected);
    }

    private Decoded decodeInternal(String data, List<FrameSlice> frameSink) {
        if (data == null || data.isEmpty()) {
            return new Decoded("", frameSink == null ? List.of() : frameSink);
        }
        textBuffer.setLength(0);
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            processChar(c, frameSink);
        }
        return new Decoded(textBuffer.toString(), frameSink == null ? List.of() : frameSink);
    }

    private void processChar(char c, List<FrameSlice> frameSink) {
        switch (state) {
            case NORMAL:
                if (c == 0x1B) {
                    state = State.ESC_PENDING;
                } else {
                    textBuffer.append(c);
                }
                break;
            case ESC_PENDING:
                if (c == ']') {
                    oscBuffer.setLength(0);
                    state = State.IN_OSC;
                } else {
                    textBuffer.append((char) 0x1B);
                    textBuffer.append(c);
                    state = State.NORMAL;
                }
                break;
            case IN_OSC:
                if (c == 0x07) {
                    completeFrame(frameSink);
                    state = State.NORMAL;
                } else if (c == 0x1B) {
                    state = State.OSC_ESC_SEEN;
                } else {
                    oscBuffer.append(c);
                    if (oscBuffer.length() > MAX_FRAME_LENGTH) {
                        LOG.debug("OSC frame exceeded max length {}, abandoning", MAX_FRAME_LENGTH);
                        textBuffer.append(oscBuffer);
                        oscBuffer.setLength(0);
                        state = State.NORMAL;
                    }
                }
                break;
            case OSC_ESC_SEEN:
                if (c == '\\') {
                    completeFrame(frameSink);
                    state = State.NORMAL;
                } else if (c == ']') {
                    oscBuffer.append((char) 0x1B);
                    oscBuffer.append(c);
                    state = State.IN_OSC;
                } else {
                    oscBuffer.append((char) 0x1B);
                    oscBuffer.append(c);
                    state = State.IN_OSC;
                }
                break;
            default:
                break;
        }
    }

    /**
     * 帧收尾处理。
     *
     * @param frameSink 非 null 时把帧暂存进列表（decodeCollecting 路径，
     *                  顺序与批内出现顺序一致，textOffset 为帧在干净文本
     *                  流中的插入点）；null 时同步回调 frameConsumer
     *                  （decode 路径的既有行为）
     */
    private void completeFrame(List<FrameSlice> frameSink) {
        String content = oscBuffer.toString();
        oscBuffer.setLength(0);
        ShellFrame frame = parseFrame(content);
        if (frame == null) {
            return;
        }
        if (!expectedNonce.equals(frame.nonce())) {
            LOG.debug("Ignoring frame with wrong nonce: expected={} actual={}", expectedNonce, frame.nonce());
            return;
        }
        if (frameSink != null) {
            frameSink.add(new FrameSlice(frame, textBuffer.length()));
        } else {
            frameConsumer.accept(frame);
        }
    }

    private ShellFrame parseFrame(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] parts = content.split(";", 5);
        if (parts.length < 4) {
            LOG.debug("Frame has too few fields: {}", content);
            return null;
        }
        if (!"1337".equals(parts[0])) {
            LOG.debug("Not a 1337 frame, ignoring: {}", parts[0]);
            return null;
        }
        ShellFrameType type = parseFrameType(parts[1]);
        if (type == null) {
            LOG.debug("Unknown frame type, ignoring: {}", parts[1]);
            return null;
        }
        String nonce = parts[2];
        String commandId = parts[3];
        String payload = parts.length >= 5 ? parts[4] : "";
        return new ShellFrame(type, nonce, commandId, payload);
    }

    private static ShellFrameType parseFrameType(String typeStr) {
        if (typeStr == null) {
            return null;
        }
        switch (typeStr) {
            case "cmd_start": return ShellFrameType.CMD_START;
            case "cmd_end": return ShellFrameType.CMD_END;
            case "prompt": return ShellFrameType.PROMPT;
            case "cwd": return ShellFrameType.CWD;
            default: return null;
        }
    }
}

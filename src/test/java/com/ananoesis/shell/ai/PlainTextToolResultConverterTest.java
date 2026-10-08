package com.ananoesis.shell.ai;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

/**
 * {@link PlainTextToolResultConverter} 的行为钉（tasks 9.2 / 9.5）。
 *
 * <p>WHY 值得为"一个只有一行的类"单开一个测试：它修的是一条<b>已经发生过</b>的生产缺陷。
 * {@code AiAgentServiceTest} 首次运行时，两个用例因为工具结果被多包了一层 JSON 引号、
 * 换行变成字面 {@code \n} 而失败——症状出现在智能体循环里，根因却在框架的默认转换器上。
 * 本类把"转换器必须原样透出文本"这条契约独立钉住，将来若有人删掉
 * {@code @Tool(resultConverter = ...)}，失败会直接指向这里而不是绕一大圈。</p>
 */
class PlainTextToolResultConverterTest {

    private final PlainTextToolResultConverter converter = new PlainTextToolResultConverter();

    /** 一段真实形状的只读工具输出：多行、含空格对齐、以换行结尾。 */
    private static final String MULTILINE = """
            exit=0
            total 12
            -rw-r--r-- 1 root root 4096 2026-09-21 10:00 access.log
            """;

    @Test
    @DisplayName("多行命令输出原样透出：不加外层引号、不把换行转义成字面 \\n")
    void multiLineTextIsPassedThroughVerbatim() {
        assertThat(converter.convert(MULTILINE, String.class)).isEqualTo(MULTILINE);

        // WHY 还要显式断言这两个"没有"：它们是缺陷的真实症状。
        // AssertJ 的 isEqualTo 失败时会打印期望/实际，但阅读者未必一眼看出
        // 差别是"多了一对引号"；把症状写成断言，失败信息就是自解释的。
        String converted = converter.convert(MULTILINE, String.class);
        assertThat(converted).doesNotStartWith("\"").doesNotEndWith("\"");
        assertThat(converted).doesNotContain("\\n");
        assertThat(converted.split("\n")).hasSize(3);
        assertThat(converted).endsWith("\n");
    }

    @Test
    @DisplayName("null 返回值归一为空串，而不是字面量 \"null\"")
    void nullBecomesEmptyString() {
        // WHY 在意这四个字符：工具没有返回值时，模型看到 "null"
        // 会以为远端真的输出了这四个字符，进而把它当成一条线索继续推理
        assertThat(converter.convert(null, String.class)).isEmpty();
    }

    @Test
    @DisplayName("非字符串返回值走 toString，不做 JSON 序列化")
    void nonStringValuesUseToString() {
        assertThat(converter.convert(42, Integer.class)).isEqualTo("42");
        assertThat(converter.convert(List.of("a", "b"), null)).isEqualTo("[a, b]");
        assertThat(converter.convert(Map.of("k", "v"), null)).isEqualTo("{k=v}");
    }

    @Test
    @DisplayName("targetType 被忽略：转换结果只取决于返回值本身")
    void targetTypeIsIgnored() {
        // WHY 断言这个：接口签名要求带 Type，若哪天有人"按类型分支"，
        // 同一段工具输出会因为模型声明的返回类型不同而变形——
        // 而工具的返回类型是框架推断的，不该影响用户看到的文本
        assertThat(converter.convert(MULTILINE, String.class))
                .isEqualTo(converter.convert(MULTILINE, Object.class))
                .isEqualTo(converter.convert(MULTILINE, null));
    }

    @Test
    @DisplayName("回归钉：框架默认转换器确实会把 String 再 JSON 化一遍")
    void frameworkDefaultConverterIsTheReasonThisClassExists() throws Exception {
        DefaultToolCallResultConverter frameworkDefault = new DefaultToolCallResultConverter();

        String byDefault = frameworkDefault.convert(MULTILINE, String.class);

        // 这条断言是在描述**框架的行为**，不是在描述我们想要的行为。
        // WHY 仍然要写下来：一旦某个 Spring AI 版本修好了默认转换器，
        // 本用例会失败，提醒维护者"PlainTextToolResultConverter 已无必要，可以删掉"——
        // 比留一个无人知晓是否还需要的类要好。
        assertThat(byDefault)
                .as("默认转换器把纯文本包成了一个 JSON 字符串字面量")
                .startsWith("\"")
                .endsWith("\"")
                .contains("\\n")
                .isNotEqualTo(MULTILINE);

        assertThat(converter.convert(MULTILINE, String.class))
                .as("自定义转换器必须避开这个行为")
                .isNotEqualTo(byDefault)
                .isEqualTo(MULTILINE);
    }
}

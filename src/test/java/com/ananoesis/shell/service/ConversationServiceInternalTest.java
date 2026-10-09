package com.ananoesis.shell.service;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link ConversationService} 静态/包私有方法的分支覆盖。
 *
 * <p>WHY 独立测试：原测试覆盖 CRUD 闭环，但 parseUuid/summarize/encodeCursor/
 * decodeCursor/roleOf/sourceOf/normalizeTitle 等方法的分支未被覆盖。</p>
 */
@DisplayName("ConversationService 内部方法分支覆盖")
class ConversationServiceInternalTest {

    @Nested
    @DisplayName("parseUuid")
    class ParseUuid {
        @Test
        @DisplayName("null 返回 null")
        void nullReturnsNull() {
            assertThat(ConversationService.parseUuid(null)).isNull();
        }

        @Test
        @DisplayName("空串返回 null")
        void blankReturnsNull() {
            assertThat(ConversationService.parseUuid("  ")).isNull();
        }

        @Test
        @DisplayName("合法 UUID 返回对应 UUID")
        void validUuid() {
            UUID expected = UUID.randomUUID();
            assertThat(ConversationService.parseUuid(expected.toString())).isEqualTo(expected);
        }

        @Test
        @DisplayName("带前后空白的 UUID 可解析")
        void uuidWithWhitespace() {
            UUID expected = UUID.randomUUID();
            assertThat(ConversationService.parseUuid("  " + expected + "  ")).isEqualTo(expected);
        }

        @Test
        @DisplayName("非法格式返回 null")
        void invalidFormatReturnsNull() {
            assertThat(ConversationService.parseUuid("not-a-uuid")).isNull();
        }
    }

    @Nested
    @DisplayName("summarize")
    class Summarize {
        @Test
        @DisplayName("短文本原样返回")
        void shortTextUnchanged() {
            assertThat(ConversationService.summarize("hello world")).isEqualTo("hello world");
        }

        @Test
        @DisplayName("超过 40 字符截断并加省略号")
        void longTextTruncated() {
            String longText = "a".repeat(50);
            String result = ConversationService.summarize(longText);
            assertThat(result).hasSize(41); // 40 chars + ellipsis
            assertThat(result).endsWith("\u2026");
        }

        @Test
        @DisplayName("恰好 40 字符不截断")
        void exactlyFortyChars() {
            String text = "a".repeat(40);
            assertThat(ConversationService.summarize(text)).hasSize(40);
        }

        @Test
        @DisplayName("连续空白被压缩为单空格")
        void whitespaceCollapsed() {
            assertThat(ConversationService.summarize("hello   world")).isEqualTo("hello world");
        }

        @Test
        @DisplayName("前后空白被去除")
        void leadingTrailingStripped() {
            assertThat(ConversationService.summarize("  hello  ")).isEqualTo("hello");
        }
    }

    @Nested
    @DisplayName("encodeCursor / decodeCursor")
    class CursorEncoding {
        @Test
        @DisplayName("编码后解码得到原始值")
        void roundTrip() {
            ConversationService.CursorPair pair = new ConversationService.CursorPair("123", "abc-def");
            String encoded = ConversationService.encodeCursor(pair.first(), pair.second());
            ConversationService.CursorPair decoded = ConversationService.decodeCursor(encoded);
            assertThat(decoded.first()).isEqualTo("123");
            assertThat(decoded.second()).isEqualTo("abc-def");
        }

        @Test
        @DisplayName("非法 cursor 格式抛 InvalidRequestException")
        void invalidCursorThrows() {
            String bad = Base64.getUrlEncoder().withoutPadding().encodeToString("nocomma".getBytes());
            assertThatThrownBy(() -> ConversationService.decodeCursor(bad))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        @DisplayName("非 base64 内容抛 InvalidRequestException")
        void nonBase64Throws() {
            assertThatThrownBy(() -> ConversationService.decodeCursor("!!!invalid!!!"))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    @Nested
    @DisplayName("roleOf（通过 Message DTO 间接验证）")
    class RoleOf {
        @Test
        @DisplayName("常量值正确")
        void constantsCorrect() {
            assertThat(ConversationService.ROLE_USER).isEqualTo("user");
            assertThat(ConversationService.ROLE_ASSISTANT).isEqualTo("assistant");
            assertThat(ConversationService.ROLE_TOOL).isEqualTo("tool");
            assertThat(ConversationService.SOURCE_AI).isEqualTo("ai");
            assertThat(ConversationService.SOURCE_SHELL_EVENT).isEqualTo("shell_event");
        }
    }

    @Nested
    @DisplayName("isEmptyPayload（反射）")
    class IsEmptyPayload {
        private boolean invoke(Object value) throws Exception {
            Method m = ConversationService.class.getDeclaredMethod("isEmptyPayload", Object.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, value);
        }

        @Test
        @DisplayName("null 返回 true")
        void nullIsTrue() throws Exception {
            assertThat(invoke(null)).isTrue();
        }

        @Test
        @DisplayName("空 Collection 返回 true")
        void emptyCollectionIsTrue() throws Exception {
            assertThat(invoke(List.of())).isTrue();
        }

        @Test
        @DisplayName("空 Map 返回 true")
        void emptyMapIsTrue() throws Exception {
            assertThat(invoke(Map.of())).isTrue();
        }

        @Test
        @DisplayName("非空 Collection 返回 false")
        void nonEmptyCollectionIsFalse() throws Exception {
            assertThat(invoke(List.of("x"))).isFalse();
        }

        @Test
        @DisplayName("非空 Map 返回 false")
        void nonEmptyMapIsFalse() throws Exception {
            assertThat(invoke(Map.of("k", "v"))).isFalse();
        }

        @Test
        @DisplayName("非 Collection/Map 返回 false")
        void nonCollectionIsFalse() throws Exception {
            assertThat(invoke("hello")).isFalse();
        }
    }

    @Nested
    @DisplayName("normalizeTitle（反射）")
    class NormalizeTitle {
        private String invoke(String title) throws Exception {
            Method m = ConversationService.class.getDeclaredMethod("normalizeTitle", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, title);
        }

        @Test
        @DisplayName("null 返回 null")
        void nullReturnsNull() throws Exception {
            assertThat(invoke(null)).isNull();
        }

        @Test
        @DisplayName("空白串返回 null")
        void blankReturnsNull() throws Exception {
            assertThat(invoke("   ")).isNull();
        }

        @Test
        @DisplayName("短文本原样返回")
        void shortTextReturned() throws Exception {
            assertThat(invoke("hello")).isEqualTo("hello");
        }

        @Test
        @DisplayName("长文本截断")
        void longTextTruncated() throws Exception {
            String result = invoke("a".repeat(50));
            assertThat(result).endsWith("\u2026");
        }
    }
}

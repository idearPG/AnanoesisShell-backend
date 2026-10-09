package com.ananoesis.shell.security;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.config.TransferProperties;

/**
 * {@link DownloadTicketService} 分支覆盖补测。
 *
 * <p>WHY 独立测试：原测试覆盖签发/消费闭环，但 null 校验、过期票据、
 * 非法 hex、重放检测、revokeTicket null 等分支未被覆盖。</p>
 */
@DisplayName("DownloadTicketService 分支覆盖")
class DownloadTicketServiceBranchTest {

    private TransferProperties properties;
    private DownloadTicketService service;

    @BeforeEach
    void setUp() {
        properties = new TransferProperties();
        properties.setDownloadTicketTimeoutSeconds(30);
        service = new DownloadTicketService(properties);
    }

    @Nested
    @DisplayName("构造器")
    class Constructor {
        @Test
        @DisplayName("properties 为 null 时抛异常")
        void nullPropertiesThrows() {
            assertThatThrownBy(() -> new DownloadTicketService(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("properties");
        }
    }

    @Nested
    @DisplayName("issueTicket")
    class IssueTicket {
        @Test
        @DisplayName("transferId 为 null 时抛异常")
        void nullTransferIdThrows() {
            assertThatThrownBy(() -> service.issueTicket(null, LocalDateTime.now().plusSeconds(10)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("readyDeadline 为 null 时抛异常")
        void nullDeadlineThrows() {
            assertThatThrownBy(() -> service.issueTicket("t1", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("正常签发返回 hex 字符串")
        void normalIssue() {
            String ticket = service.issueTicket("t1", LocalDateTime.now().plusSeconds(10));
            assertThat(ticket).hasSize(64); // 32 bytes = 64 hex chars
        }

        @Test
        @DisplayName("再次签发使旧票失效")
        void reissueInvalidatesOld() {
            String ticket1 = service.issueTicket("t1", LocalDateTime.now().plusSeconds(30));
            String ticket2 = service.issueTicket("t1", LocalDateTime.now().plusSeconds(30));
            // 旧票应该无法消费
            assertThat(service.consumeTicket("t1", ticket1)).isFalse();
            // 新票可以消费
            assertThat(service.consumeTicket("t1", ticket2)).isTrue();
        }
    }

    @Nested
    @DisplayName("consumeTicket")
    class ConsumeTicket {
        @Test
        @DisplayName("null transferId 返回 false")
        void nullTransferIdReturnsFalse() {
            assertThat(service.consumeTicket(null, "abc")).isFalse();
        }

        @Test
        @DisplayName("null ticket 返回 false")
        void nullTicketReturnsFalse() {
            assertThat(service.consumeTicket("t1", null)).isFalse();
        }

        @Test
        @DisplayName("非法 hex 返回 false")
        void invalidHexReturnsFalse() {
            assertThat(service.consumeTicket("t1", "xyz")).isFalse();
        }

        @Test
        @DisplayName("不存在的 transfer 返回 false")
        void nonExistentTransferReturnsFalse() {
            assertThat(service.consumeTicket("non-existent", "aa")).isFalse();
        }

        @Test
        @DisplayName("过期票据返回 false")
        void expiredTicketReturnsFalse() {
            // 签发一个已过期的票据（deadline 在过去）
            service.issueTicket("t1", LocalDateTime.now().minusSeconds(60));
            String ticket = service.issueTicket("t1", LocalDateTime.now().minusSeconds(60));
            // 等待一小段时间确保过期
            assertThat(service.consumeTicket("t1", ticket)).isFalse();
        }

        @Test
        @DisplayName("不匹配的票据返回 false")
        void mismatchedTicketReturnsFalse() {
            service.issueTicket("t1", LocalDateTime.now().plusSeconds(30));
            // 用另一个 transfer 的票据
            String otherTicket = service.issueTicket("t2", LocalDateTime.now().plusSeconds(30));
            assertThat(service.consumeTicket("t1", otherTicket)).isFalse();
        }

        @Test
        @DisplayName("重放已消费票据返回 false")
        void replayConsumedTicketReturnsFalse() {
            String ticket = service.issueTicket("t1", LocalDateTime.now().plusSeconds(30));
            assertThat(service.consumeTicket("t1", ticket)).isTrue();
            // 再次消费同一票据
            assertThat(service.consumeTicket("t1", ticket)).isFalse();
        }

        @Test
        @DisplayName("正常消费返回 true")
        void normalConsumeReturnsTrue() {
            String ticket = service.issueTicket("t1", LocalDateTime.now().plusSeconds(30));
            assertThat(service.consumeTicket("t1", ticket)).isTrue();
        }
    }

    @Nested
    @DisplayName("revokeTicket")
    class RevokeTicket {
        @Test
        @DisplayName("null transferId 不抛异常")
        void nullTransferIdSafe() {
            service.revokeTicket(null);
        }

        @Test
        @DisplayName("撤销后票据不可用")
        void revokeInvalidates() {
            String ticket = service.issueTicket("t1", LocalDateTime.now().plusSeconds(30));
            service.revokeTicket("t1");
            assertThat(service.consumeTicket("t1", ticket)).isFalse();
        }
    }

    @Nested
    @DisplayName("hasValidTicket")
    class HasValidTicket {
        @Test
        @DisplayName("无票据时返回 false")
        void noTicketReturnsFalse() {
            assertThat(service.hasValidTicket("non-existent")).isFalse();
        }

        @Test
        @DisplayName("有效票据返回 true")
        void validTicketReturnsTrue() {
            service.issueTicket("t1", LocalDateTime.now().plusSeconds(30));
            assertThat(service.hasValidTicket("t1")).isTrue();
        }

        @Test
        @DisplayName("过期票据返回 false")
        void expiredTicketReturnsFalse() {
            service.issueTicket("t1", LocalDateTime.now().minusSeconds(60));
            assertThat(service.hasValidTicket("t1")).isFalse();
        }
    }

    @Nested
    @DisplayName("hexToBytes（反射）")
    class HexToBytes {
        @Test
        @DisplayName("奇数长度抛异常")
        void oddLengthThrows() throws Exception {
            Method m = DownloadTicketService.class.getDeclaredMethod("hexToBytes", String.class);
            m.setAccessible(true);
            try {
                m.invoke(null, "abc");
                org.assertj.core.api.Assertions.fail("应抛异常");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("非法字符抛异常")
        void invalidCharThrows() throws Exception {
            Method m = DownloadTicketService.class.getDeclaredMethod("hexToBytes", String.class);
            m.setAccessible(true);
            try {
                m.invoke(null, "zz");
                org.assertj.core.api.Assertions.fail("应抛异常");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("合法 hex 正确转换")
        void validHex() throws Exception {
            Method m = DownloadTicketService.class.getDeclaredMethod("hexToBytes", String.class);
            m.setAccessible(true);
            byte[] result = (byte[]) m.invoke(null, "ff00");
            assertThat(result).containsExactly((byte) 0xff, (byte) 0x00);
        }
    }
}

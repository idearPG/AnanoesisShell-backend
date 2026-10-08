package com.ananoesis.shell.security;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.config.TransferProperties;

/**
 * 下载票据服务的安全测试（design.md D9）。
 *
 * <p>验证票据的签发、验证、消费、过期、撤销等安全约束。</p>
 */
class DownloadTicketServiceTest {

    private DownloadTicketService service;

    @BeforeEach
    void setUp() {
        TransferProperties properties = new TransferProperties();
        properties.setDownloadTicketTimeoutSeconds(30);
        service = new DownloadTicketService(properties);
    }

    @Nested
    @DisplayName("票据签发")
    class TicketIssuance {

        @Test
        @DisplayName("签发返回 64 字符 hex 票据")
        void issuedTicketIs64CharHex() {
            String ticket = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));

            assertThat(ticket).hasSize(64);
            assertThat(ticket).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("每次签发产生不同票据")
        void eachIssuanceIsUnique() {
            String ticket1 = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));
            String ticket2 = service.issueTicket("transfer-2", LocalDateTime.now().plusSeconds(60));

            assertThat(ticket1).isNotEqualTo(ticket2);
        }

        @Test
        @DisplayName("同一任务再次领票使旧票失效")
        void reissueInvalidatesOldTicket() {
            String oldTicket = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));
            String newTicket = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));

            // 旧票应失效
            assertThat(service.consumeTicket("transfer-1", oldTicket)).isFalse();
            // 新票应有效
            assertThat(service.consumeTicket("transfer-1", newTicket)).isTrue();
        }

        @Test
        @DisplayName("transferId 为 null 时抛出异常")
        void nullTransferIdRejected() {
            assertThatThrownBy(() -> service.issueTicket(null, LocalDateTime.now().plusSeconds(60)))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("票据消费")
    class TicketConsumption {

        @Test
        @DisplayName("正确票据消费成功")
        void validTicketConsumed() {
            String ticket = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));

            assertThat(service.consumeTicket("transfer-1", ticket)).isTrue();
        }

        @Test
        @DisplayName("消费后票据不可再用（单次有效）")
        void ticketIsSingleUse() {
            String ticket = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));

            assertThat(service.consumeTicket("transfer-1", ticket)).isTrue();
            // 再次消费同一票据应失败
            assertThat(service.consumeTicket("transfer-1", ticket)).isFalse();
        }

        @Test
        @DisplayName("错误票据被拒绝")
        void wrongTicketRejected() {
            service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));
            String wrongTicket = "a".repeat(64);

            assertThat(service.consumeTicket("transfer-1", wrongTicket)).isFalse();
        }

        @Test
        @DisplayName("不存在的任务票据被拒绝")
        void nonExistentTransferRejected() {
            assertThat(service.consumeTicket("nonexistent", "a".repeat(64))).isFalse();
        }

        @Test
        @DisplayName("null 参数返回 false")
        void nullParametersReturnFalse() {
            assertThat(service.consumeTicket(null, "ticket")).isFalse();
            assertThat(service.consumeTicket("id", null)).isFalse();
        }

        @Test
        @DisplayName("非法 hex 格式被拒绝")
        void invalidHexRejected() {
            service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));

            assertThat(service.consumeTicket("transfer-1", "not-hex")).isFalse();
        }
    }

    @Nested
    @DisplayName("票据过期")
    class TicketExpiry {

        @Test
        @DisplayName("过期票据被拒绝")
        void expiredTicketRejected() {
            // 签发一个已过期（deadline 在过去）的票据
            String ticket = service.issueTicket("transfer-1", LocalDateTime.now().minusSeconds(1));

            assertThat(service.consumeTicket("transfer-1", ticket)).isFalse();
        }

        @Test
        @DisplayName("票据过期时间不超过 readyDeadline")
        void ticketExpiryCappedByDeadline() {
            // readyDeadline 在 5 秒后
            String ticket = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(5));

            // 票据应该有效（还没过期）
            assertThat(service.hasValidTicket("transfer-1")).isTrue();
        }
    }

    @Nested
    @DisplayName("票据撤销")
    class TicketRevocation {

        @Test
        @DisplayName("撤销后票据不可用")
        void revokedTicketInvalid() {
            String ticket = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));

            service.revokeTicket("transfer-1");

            assertThat(service.consumeTicket("transfer-1", ticket)).isFalse();
        }

        @Test
        @DisplayName("撤销不存在的任务不报错")
        void revokeNonExistentIsNoop() {
            service.revokeTicket("nonexistent");
            // 不应抛异常
        }

        @Test
        @DisplayName("撤销 null 不报错")
        void revokeNullIsNoop() {
            service.revokeTicket(null);
            // 不应抛异常
        }
    }

    @Nested
    @DisplayName("票据存在性检查")
    class TicketPresence {

        @Test
        @DisplayName("签发后 hasValidTicket 返回 true")
        void hasValidTicketAfterIssuance() {
            service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));

            assertThat(service.hasValidTicket("transfer-1")).isTrue();
        }

        @Test
        @DisplayName("未签发时 hasValidTicket 返回 false")
        void noValidTicketBeforeIssuance() {
            assertThat(service.hasValidTicket("transfer-1")).isFalse();
        }

        @Test
        @DisplayName("消费后 hasValidTicket 返回 false")
        void noValidTicketAfterConsumption() {
            String ticket = service.issueTicket("transfer-1", LocalDateTime.now().plusSeconds(60));
            service.consumeTicket("transfer-1", ticket);

            assertThat(service.hasValidTicket("transfer-1")).isFalse();
        }
    }
}

package com.ananoesis.shell.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.config.TransferProperties;
import com.ananoesis.shell.contract.model.TransferStatus;
import com.ananoesis.shell.mapper.FileTransferMapper;
import com.ananoesis.shell.security.DownloadTicketService;
import com.ananoesis.shell.ssh.SshTerminalService;

/**
 * {@link TransferService} 内部方法的分支覆盖。
 *
 * <p>WHY 独立测试：toInt/isTerminalState/isNotFound/describeFailureForDisplay/
 * serializeExpectedTarget 等方法的分支未被既有测试覆盖。</p>
 */
@DisplayName("TransferService 内部方法分支覆盖")
class TransferServiceInternalTest {

    // ==================================================================
    // toInt
    // ==================================================================

    @Nested
    @DisplayName("toInt（反射）")
    class ToInt {
        private int invoke(Long count) throws Exception {
            Method m = TransferService.class.getDeclaredMethod("toInt", Long.class);
            m.setAccessible(true);
            return (int) m.invoke(null, count);
        }

        @Test
        @DisplayName("null → 0")
        void nullReturnsZero() throws Exception {
            assertThat(invoke(null)).isEqualTo(0);
        }

        @Test
        @DisplayName("正常值可转换")
        void normalValueConverted() throws Exception {
            assertThat(invoke(42L)).isEqualTo(42);
        }

        @Test
        @DisplayName("0 → 0")
        void zeroReturnsZero() throws Exception {
            assertThat(invoke(0L)).isEqualTo(0);
        }
    }

    // ==================================================================
    // isTerminalState
    // ==================================================================

    @Nested
    @DisplayName("isTerminalState（反射）")
    class IsTerminalState {
        private boolean invoke(String status) throws Exception {
            Method m = TransferService.class.getDeclaredMethod("isTerminalState", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(
                    new TransferService(mock(FileTransferMapper.class),
                            new TransferProperties(),
                            mock(SshTerminalService.class),
                            mock(DownloadTicketService.class)),
                    status);
        }

        @Test
        @DisplayName("PUBLISHED → true")
        void publishedIsTerminal() throws Exception {
            assertThat(invoke(TransferStatus.PUBLISHED.getValue())).isTrue();
        }

        @Test
        @DisplayName("DELIVERED → true")
        void deliveredIsTerminal() throws Exception {
            assertThat(invoke(TransferStatus.DELIVERED.getValue())).isTrue();
        }

        @Test
        @DisplayName("FAILED → true")
        void failedIsTerminal() throws Exception {
            assertThat(invoke(TransferStatus.FAILED.getValue())).isTrue();
        }

        @Test
        @DisplayName("CANCELLED → true")
        void cancelledIsTerminal() throws Exception {
            assertThat(invoke(TransferStatus.CANCELLED.getValue())).isTrue();
        }

        @Test
        @DisplayName("EXPIRED → true")
        void expiredIsTerminal() throws Exception {
            assertThat(invoke(TransferStatus.EXPIRED.getValue())).isTrue();
        }

        @Test
        @DisplayName("QUEUED → false")
        void queuedIsNotTerminal() throws Exception {
            assertThat(invoke(TransferStatus.QUEUED.getValue())).isFalse();
        }

        @Test
        @DisplayName("READY → false")
        void readyIsNotTerminal() throws Exception {
            assertThat(invoke(TransferStatus.READY.getValue())).isFalse();
        }

        @Test
        @DisplayName("TRANSFERRING → false")
        void transferringIsNotTerminal() throws Exception {
            assertThat(invoke(TransferStatus.TRANSFERRING.getValue())).isFalse();
        }

        @Test
        @DisplayName("PUBLISHING → false")
        void publishingIsNotTerminal() throws Exception {
            assertThat(invoke(TransferStatus.PUBLISHING.getValue())).isFalse();
        }
    }

    // ==================================================================
    // isNotFound
    // ==================================================================

    @Nested
    @DisplayName("isNotFound（反射）")
    class IsNotFound {
        private boolean invoke(java.io.IOException e) throws Exception {
            Method m = TransferService.class.getDeclaredMethod("isNotFound", java.io.IOException.class);
            m.setAccessible(true);
            return (boolean) m.invoke(
                    new TransferService(mock(FileTransferMapper.class),
                            new TransferProperties(),
                            mock(SshTerminalService.class),
                            mock(DownloadTicketService.class)),
                    e);
        }

        @Test
        @DisplayName("含 'No such file' → true")
        void noSuchFileReturnsTrue() throws Exception {
            assertThat(invoke(new java.io.IOException("No such file"))).isTrue();
        }

        @Test
        @DisplayName("含 'no such file'（小写）→ true")
        void lowercaseNoSuchFileReturnsTrue() throws Exception {
            assertThat(invoke(new java.io.IOException("no such file"))).isTrue();
        }

        @Test
        @DisplayName("其他消息 → false")
        void otherMessageReturnsFalse() throws Exception {
            assertThat(invoke(new java.io.IOException("connection refused"))).isFalse();
        }

        @Test
        @DisplayName("null 消息 → false")
        void nullMessageReturnsFalse() throws Exception {
            assertThat(invoke(new java.io.IOException((String) null))).isFalse();
        }
    }

    // ==================================================================
    // describeFailureForDisplay
    // ==================================================================

    @Nested
    @DisplayName("describeFailureForDisplay（反射）")
    class DescribeFailureForDisplay {
        private String invoke(String failureCode) throws Exception {
            Method m = TransferService.class.getDeclaredMethod("describeFailureForDisplay", String.class);
            m.setAccessible(true);
            var props = new com.ananoesis.shell.config.TransferProperties();
            props.setProgressTimeoutSeconds(120);
            return (String) m.invoke(
                    new TransferService(mock(FileTransferMapper.class),
                            props,
                            mock(SshTerminalService.class),
                            mock(DownloadTicketService.class)),
                    failureCode);
        }

        @Test
        @DisplayName("progress_timeout → 含秒数的文案")
        void progressTimeoutReturnsMessage() throws Exception {
            String result = invoke(TransferService.FAILURE_PROGRESS_TIMEOUT);
            assertThat(result).contains("120");
            assertThat(result).contains("无字节进展");
        }

        @Test
        @DisplayName("其他失败码 → null")
        void otherCodeReturnsNull() throws Exception {
            assertThat(invoke("io_error")).isNull();
        }

        @Test
        @DisplayName("null → null")
        void nullCodeReturnsNull() throws Exception {
            assertThat(invoke(null)).isNull();
        }
    }

    // ==================================================================
    // serializeExpectedTarget
    // ==================================================================

    @Nested
    @DisplayName("serializeExpectedTarget（反射）")
    class SerializeExpectedTarget {
        private String invoke(com.ananoesis.shell.contract.model.ExpectedTarget et) throws Exception {
            Method m = TransferService.class.getDeclaredMethod(
                    "serializeExpectedTarget",
                    com.ananoesis.shell.contract.model.ExpectedTarget.class);
            m.setAccessible(true);
            return (String) m.invoke(
                    new TransferService(mock(FileTransferMapper.class),
                            new TransferProperties(),
                            mock(SshTerminalService.class),
                            mock(DownloadTicketService.class)),
                    et);
        }

        @Test
        @DisplayName("null → null")
        void nullReturnsNull() throws Exception {
            assertThat(invoke(null)).isNull();
        }

        @Test
        @DisplayName("完整对象 → JSON")
        void fullObjectReturnsJson() throws Exception {
            var et = new com.ananoesis.shell.contract.model.ExpectedTarget();
            et.setSize(1024L);
            et.setMode("0644");
            et.setMtime(java.time.OffsetDateTime.parse("2025-01-01T00:00:00Z"));
            String result = invoke(et);
            assertThat(result).contains("1024");
            assertThat(result).contains("0644");
        }

        @Test
        @DisplayName("字段为 null 时使用默认值")
        void nullFieldsUseDefaults() throws Exception {
            var et = new com.ananoesis.shell.contract.model.ExpectedTarget();
            // size/mtime/mode 全部为 null
            String result = invoke(et);
            assertThat(result).contains("0"); // size 默认 0
            assertThat(result).contains("null"); // mtime 为 "null"
        }
    }
}

package com.ananoesis.shell.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.support.SshTestDoubles.RecordingTerminalListener;

/**
 * {@link PreInstallMuteListener} 的行为契约（known-issues #19 修复的组件级 RED）。
 *
 * <p>WHY 单独单测：真实首连的「登录 prompt 抢在闸门接入前泄漏」是毫秒级竞态，
 * 集成测试无法确定性复现；把静音语义下沉成独立组件后，其正确性可以完全
 * 确定性地验证，接线层再由 SshTerminalServiceTest 的 banner 用例做回归守护。</p>
 */
class PreInstallMuteListenerTest {

    @Test
    @DisplayName("静音窗口内的 stdout/stderr 一律吞掉，不转发下游")
    void swallowsOutputUntilSwitch() {
        RecordingTerminalListener delegate = new RecordingTerminalListener();
        PreInstallMuteListener mute = new PreInstallMuteListener(delegate);

        mute.onStdout("[root@localhost ~]# ");
        mute.onStderr("login noise");

        assertThat(mute.delegate()).as("delegate 原样保留，接线方随时可切换").isSameAs(delegate);
        assertThat(delegate.stdout()).as("登录 banner 不得泄漏给前端").isEmpty();
        assertThat(delegate.stderr()).isEmpty();
    }

    @Test
    @DisplayName("closed 事件必须透传：静音窗口内远端断开/认证失败，前端不能蒙在鼓里")
    void forwardsClosedImmediately() {
        RecordingTerminalListener delegate = new RecordingTerminalListener();
        PreInstallMuteListener mute = new PreInstallMuteListener(delegate);

        mute.onStdout("[root@localhost ~]# ");
        mute.onClosed(SshCloseReason.REMOTE_CLOSED);

        assertThat(delegate.closedReasons())
                .as("输出可以静音，但会话终结事件绝不静音")
                .containsExactly(SshCloseReason.REMOTE_CLOSED);
    }
}

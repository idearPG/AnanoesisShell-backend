package com.ananoesis.shell.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSH 运行时参数（design.md D4：双通道 + 输出约束）。
 *
 * <p>WHY 需要显式的超时与上限，而不是用库默认值：
 * sshj 的 socket 超时默认是 0（永不超时）。若不设置，"主机不可达"这个 spec 明确要求
 * 的场景会表现为界面永久转圈——用户既看不到错误，也无法取消。同理，exec 通道若没有
 * 输出上限，一条 {@code cat 大文件} 就能把 AI 的上下文与 JVM 堆一起撑爆。</p>
 *
 * <p><b>已知漂移风险（须在设置模块落地时消除）</b>：下面三个默认值刻意与
 * {@code V1__init_schema.sql} 预置的 settings 行一一对应
 * （{@code ssh.connect.timeout.seconds=15}、{@code run_command.timeout.seconds=1800}、
 * {@code run_command.max_output_bytes=65536}）。settings 表的 REST 管理属后续 Wave，
 * 届时本类应改为"启动读默认、运行期以 DB 值为准"，否则用户在设置界面改了超时却不生效。
 * 目前两份值靠本注释与 {@code SshPropertiesDefaultsTest} 保持一致。</p>
 */
@ConfigurationProperties(prefix = "ananoesis.ssh")
public class SshProperties {

    /** 建立 TCP 连接 + 完成 SSH 握手与认证的总时限。超时按「主机不可达」上报。 */
    private Duration connectTimeout = Duration.ofSeconds(15);

    /** exec 通道单条命令的执行时限（design.md D4：防止 {@code tail -f} 挂死）。run_command.timeout.seconds=1800 */
    private Duration execTimeout = Duration.ofSeconds(1800);

    /** exec 通道单条命令的 stdout/stderr 采集上限（字节），超限截断并标记。 */
    private int execMaxOutputBytes = 65_536;

    /**
     * 交互式终端的空闲回收时限。
     * WHY 需要它：用户直接关掉浏览器标签页时，前端来不及发 {@code action=close}，
     * 若没有空闲回收，SSH 连接与两条读线程会一直挂到进程退出。
     */
    private Duration terminalIdleTimeout = Duration.ofMinutes(30);

    /** 空闲会话扫描周期。 */
    private Duration terminalIdleCheckInterval = Duration.ofMinutes(1);

    /** PTY 终端类型；xterm-256color 让远端程序敢于输出彩色与光标控制序列。 */
    private String ptyTerm = "xterm-256color";

    /**
     * 远端登录 Shell 类型（design D3：第一阶段仅支持 Linux Bash 4+）。
     * WHY 是配置而非探测：安装前的探测需要往交互式 PTY 写命令并等回显，
     * 会与用户首键竞态；目标机约定为 bash，非 bash 用户改配置后
     * 集成自动禁用（保留人工终端/SFTP），不会写不兼容的钩子代码。
     */
    private String shellType = "bash";

    private int ptyColumns = 80;

    private int ptyRows = 24;

    /**
     * SSH keep-alive 间隔。
     * WHY 需要它：NAT/防火墙会静默丢弃长时间无流量的连接，用户回来敲键盘时
     * 才发现"终端死了"却没有任何提示。定期心跳既保活，也能让断线被尽早察觉。
     */
    private Duration keepAliveInterval = Duration.ofSeconds(30);

    /**
     * 嵌套 Shell 帧超时检测阈值（秒）：MANUAL_IDLE 超过此时间无帧活动则触发探测。
     * 0 表示禁用嵌套检测（保持旧行为）。
     *
     * <p>WHY 默认 8 秒：覆盖正常嵌套 Shell 启动时间（bash 启动 ~200ms + PROMPT_COMMAND
     * 报错 ~50ms），又不至于让用户等太久。用户离开座位场景由探测命令的"无回显则重置"
     * 逻辑覆盖，不会误判。</p>
     */
    private int nestedDetectTimeout = 8;

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getExecTimeout() {
        return execTimeout;
    }

    public void setExecTimeout(Duration execTimeout) {
        this.execTimeout = execTimeout;
    }

    public int getExecMaxOutputBytes() {
        return execMaxOutputBytes;
    }

    public void setExecMaxOutputBytes(int execMaxOutputBytes) {
        this.execMaxOutputBytes = execMaxOutputBytes;
    }

    public Duration getTerminalIdleTimeout() {
        return terminalIdleTimeout;
    }

    public void setTerminalIdleTimeout(Duration terminalIdleTimeout) {
        this.terminalIdleTimeout = terminalIdleTimeout;
    }

    public Duration getTerminalIdleCheckInterval() {
        return terminalIdleCheckInterval;
    }

    public void setTerminalIdleCheckInterval(Duration terminalIdleCheckInterval) {
        this.terminalIdleCheckInterval = terminalIdleCheckInterval;
    }

    public String getPtyTerm() {
        return ptyTerm;
    }

    public void setPtyTerm(String ptyTerm) {
        this.ptyTerm = ptyTerm;
    }

    public String getShellType() {
        return shellType;
    }

    public void setShellType(String shellType) {
        this.shellType = shellType;
    }

    public int getPtyColumns() {
        return ptyColumns;
    }

    public void setPtyColumns(int ptyColumns) {
        this.ptyColumns = ptyColumns;
    }

    public int getPtyRows() {
        return ptyRows;
    }

    public void setPtyRows(int ptyRows) {
        this.ptyRows = ptyRows;
    }

    public Duration getKeepAliveInterval() {
        return keepAliveInterval;
    }

    public void setKeepAliveInterval(Duration keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
    }

    public int getNestedDetectTimeout() {
        return nestedDetectTimeout;
    }

    public void setNestedDetectTimeout(int nestedDetectTimeout) {
        this.nestedDetectTimeout = nestedDetectTimeout;
    }
}

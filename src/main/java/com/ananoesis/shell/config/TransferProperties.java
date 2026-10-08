package com.ananoesis.shell.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件传输配置（design.md D9「SFTP 传输」）。
 *
 * <p>WHY 独立配置类而非硬编码：文件大小上限、配额等参数在不同部署环境
 * （开发/测试/生产）下需要差异化调整，做成配置项避免改代码重新编译。</p>
 */
@ConfigurationProperties(prefix = "ananoesis.transfer")
public class TransferProperties {

    /**
     * 单文件最大字节数，默认 10 GiB。
     * WHY 10 GiB 而非 10 GB：存储行业惯例以 GiB 计量，10 GiB = 10 × 1024³ 字节。
     */
    private long maxFileSize = 10L * 1024 * 1024 * 1024;

    /** 每个 session 同时处于 ready + transferring 的传输数上限。 */
    private int maxPerSession = 2;

    /** 全局同时处于 ready + transferring 的传输数上限。 */
    private int maxGlobal = 4;

    /** 全应用 queued 状态传输数上限。 */
    private int maxQueued = 100;

    /** ready 状态租期（秒）：前端必须在此时间内开始上传/下载，否则过期。 */
    private int readyTimeoutSeconds = 30;

    /** transferring 状态无进度超时（秒）：连续此时间无字节进展则判定失败。 */
    private int progressTimeoutSeconds = 300;

    /** 上传缓冲区大小（字节），默认 64 KiB。 */
    private int uploadBufferSize = 64 * 1024;

    /** 下载票据有效期（秒），默认 30 秒。 */
    private int downloadTicketTimeoutSeconds = 30;

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public int getMaxPerSession() {
        return maxPerSession;
    }

    public void setMaxPerSession(int maxPerSession) {
        this.maxPerSession = maxPerSession;
    }

    public int getMaxGlobal() {
        return maxGlobal;
    }

    public void setMaxGlobal(int maxGlobal) {
        this.maxGlobal = maxGlobal;
    }

    public int getMaxQueued() {
        return maxQueued;
    }

    public void setMaxQueued(int maxQueued) {
        this.maxQueued = maxQueued;
    }

    public int getReadyTimeoutSeconds() {
        return readyTimeoutSeconds;
    }

    public void setReadyTimeoutSeconds(int readyTimeoutSeconds) {
        this.readyTimeoutSeconds = readyTimeoutSeconds;
    }

    public int getProgressTimeoutSeconds() {
        return progressTimeoutSeconds;
    }

    public void setProgressTimeoutSeconds(int progressTimeoutSeconds) {
        this.progressTimeoutSeconds = progressTimeoutSeconds;
    }

    public int getUploadBufferSize() {
        return uploadBufferSize;
    }

    public void setUploadBufferSize(int uploadBufferSize) {
        this.uploadBufferSize = uploadBufferSize;
    }

    public int getDownloadTicketTimeoutSeconds() {
        return downloadTicketTimeoutSeconds;
    }

    public void setDownloadTicketTimeoutSeconds(int downloadTicketTimeoutSeconds) {
        this.downloadTicketTimeoutSeconds = downloadTicketTimeoutSeconds;
    }
}

package com.ananoesis.shell.ssh;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ananoesis.shell.config.SshProperties;

/**
 * 终端会话的空闲回收定时器（task 6.6：超时释放资源）。
 *
 * <p>WHY 是服务端主动扫描，而不是靠 WebSocket 的 close 事件：
 * 用户直接关掉浏览器标签页或整机断电时，前端根本没有机会发 {@code action=close}；
 * 而 WebSocket 容器察觉到"对端没了"可能要好几分钟（取决于 TCP keep-alive 与操作系统）。
 * 只依赖 close 事件，SSH 连接与两条读线程就会在这段时间里一直占着资源。</p>
 *
 * <p>WHY 用 {@code fixedDelayString} 而不是 {@code fixedRate}：
 * 回收本身要关闭 socket、可能阻塞若干毫秒；{@code fixedRate} 在上一次未结束时会立刻补跑，
 * 形成"扫描套扫描"。{@code fixedDelay} 保证两次扫描之间一定有完整间隔。</p>
 *
 * <p>WHY 间隔写成占位符而不是 {@code SshProperties} 的字段读取：
 * {@code @Scheduled} 的间隔在容器启动时就固定了，读属性字段拿不到"运行期改配置"的能力，
 * 反而会让人误以为改了 {@code terminalIdleCheckInterval} 就能立即生效。
 * 用占位符把这层限制显式写在注解上。</p>
 */
@Component
public class TerminalIdleReaper {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalIdleReaper.class);

    private final TerminalSessionRegistry registry;
    private final SshProperties properties;

    public TerminalIdleReaper(TerminalSessionRegistry registry, SshProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry 不得为 null");
        this.properties = Objects.requireNonNull(properties, "properties 不得为 null");
    }

    @Scheduled(initialDelayString = "${ananoesis.ssh.terminal-idle-check-interval:1m}",
            fixedDelayString = "${ananoesis.ssh.terminal-idle-check-interval:1m}")
    public void reapIdleSessions() {
        try {
            int expired = registry.expireIdleSessions(properties.getTerminalIdleTimeout());
            if (expired > 0) {
                LOG.info("空闲终端回收完成: expired={} active={}", expired, registry.size());
            } else if (LOG.isTraceEnabled()) {
                LOG.trace("空闲终端回收: 无到期会话, active={}", registry.size());
            }
        } catch (RuntimeException e) {
            // WHY 吞掉而不让异常冒到调度器：Spring 的定时任务遇到未捕获异常会记录后继续，
            // 但一次数据库抖动不该在日志里留下一条看起来像"回收器坏了"的 ERROR。
            // 记 WARN 并把下一次扫描当作重试，是这个场景下最合适的语义。
            LOG.warn("空闲终端回收失败，将在下一次扫描时重试", e);
        }
    }
}

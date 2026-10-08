package com.ananoesis.shell.ssh;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ananoesis.shell.entity.SshSession;
import com.ananoesis.shell.mapper.SshSessionMapper;
import com.ananoesis.shell.support.EntityIds;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

/**
 * 生产实现：把会话生命周期写进 {@code sessions} 表（task 6.6）。
 *
 * <p>WHY 状态字面值集中在本类的常量里，而不是散在各处：
 * DDL 上有 {@code CHECK (status IN ('connecting','open','closed','error'))}，
 * 写错一个字母在 SQLite 上就是一条约束违例——症状是"连接成功但审计没记录"，
 * 而且只在运行到那一行时才暴露。集中成常量至少让拼写错误变成编译错误。</p>
 *
 * <p><b>线程模型</b>：{@link #recordEnd} 可能由终端读泵线程调用（远端 EOF 触发的关闭），
 * 因此本类的每个方法都必须是<b>单条语句 + 自身幂等</b>的，不能依赖调用方处于某个事务里。</p>
 */
@Component
public class DatabaseSessionRecorder implements SessionRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseSessionRecorder.class);

    private static final String STATUS_CONNECTING = "connecting";
    private static final String STATUS_OPEN = "open";
    private static final String STATUS_CLOSED = "closed";
    private static final String STATUS_ERROR = "error";

    /** {@code error_message} 的落库长度上限：它只用于排障，不该成为无限增长的文本。 */
    private static final int DETAIL_MAX_LENGTH = 500;

    private final SshSessionMapper sessions;

    public DatabaseSessionRecorder(SshSessionMapper sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions 不得为 null");
    }

    @Override
    public String recordStart(UUID hostId, SessionKind kind) {
        Objects.requireNonNull(hostId, "hostId 不得为 null");
        Objects.requireNonNull(kind, "kind 不得为 null");

        SshSession row = new SshSession();
        // WHY 显式赋 id：见 EntityIds 的类注释——ASSIGN_UUID 产出无连字符的 32 位串，
        // 而契约把 session_id 声明为 format: uuid，前端拿到后无法用 UUID.fromString 解析
        row.setId(EntityIds.newUuid());
        row.setHostId(hostId.toString());
        row.setSessionType(kind.columnValue());
        row.setStatus(STATUS_CONNECTING);
        row.setStartedAt(LocalDateTime.now());
        sessions.insert(row);

        LOG.debug("已记录会话开始: id={} hostId={} type={}", row.getId(), hostId, kind.columnValue());
        return row.getId();
    }

    @Override
    public void recordOpen(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId 不得为 null");
        // WHY 带上 status='connecting' 条件：会话可能在"连上"与"记录 open"之间就被远端关闭，
        // 此时 recordEnd 已经把状态改成 closed/error 了，无条件 update 会把一条已结束的会话
        // 改回 open，审计里于是出现"结束了又在运行"的矛盾记录
        int rows = sessions.update(null, new UpdateWrapper<SshSession>()
                .eq("id", sessionId)
                .eq("status", STATUS_CONNECTING)
                .set("status", STATUS_OPEN)
                .set("updated_at", LocalDateTime.now()));
        if (rows == 0) {
            LOG.debug("会话未转为 open（可能已结束或不存在）: id={}", sessionId);
            return;
        }
        LOG.debug("已记录会话建立: id={}", sessionId);
    }

    @Override
    public void recordEnd(String sessionId, SshCloseReason reason, String detail) {
        Objects.requireNonNull(sessionId, "sessionId 不得为 null");
        Objects.requireNonNull(reason, "reason 不得为 null");

        LocalDateTime now = LocalDateTime.now();
        // WHY 用 ended_at IS NULL 作为幂等闸门：主动断开、远端 EOF、空闲回收三者可能并发触发，
        // 而数据库的这条条件更新是唯一能让"只有一次真正生效"的地方——
        // 用内存标志位做不到，因为触发方可能在不同线程、甚至不同会话对象上
        int rows = sessions.update(null, new UpdateWrapper<SshSession>()
                .eq("id", sessionId)
                .isNull("ended_at")
                .set("status", statusOf(reason))
                .set("ended_at", now)
                .set("close_reason", reason.columnValue())
                .set("error_message", truncate(detail))
                .set("updated_at", now));

        if (rows == 0) {
            LOG.debug("会话已结束或不存在，跳过重复的 end 记录: id={} reason={}", sessionId, reason);
            return;
        }
        LOG.info("已记录会话结束: id={} closeReason={} status={}", sessionId, reason.columnValue(), statusOf(reason));
    }

    /**
     * 终止原因 → {@code sessions.status}。
     * WHY 连接期失败记为 {@code error} 而不是 {@code closed}：
     * "认证失败/主机不可达"的会话从未真正可用，把它记成正常关闭会让
     * "成功率"这个运维指标虚高。
     */
    private static String statusOf(SshCloseReason reason) {
        switch (reason) {
            case USER_DISCONNECT:
            case TIMEOUT:
            case REMOTE_CLOSED:
                return STATUS_CLOSED;
            case AUTH_FAILED:
            case UNREACHABLE:
            case ERROR:
            default:
                return STATUS_ERROR;
        }
    }

    private static String truncate(String detail) {
        if (detail == null || detail.length() <= DETAIL_MAX_LENGTH) {
            return detail;
        }
        return detail.substring(0, DETAIL_MAX_LENGTH) + "...(truncated)";
    }
}

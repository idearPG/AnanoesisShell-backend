package com.ananoesis.shell.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.contract.model.Session;
import com.ananoesis.shell.contract.model.SessionStatus;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.entity.SshSession;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.mapper.SshSessionMapper;
import com.ananoesis.shell.ssh.SshCloseReason;
import com.ananoesis.shell.support.Timestamps;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * 连接会话审计的只读查询（契约 {@code GET /api/sessions}；ssh-connection spec「连接会话生命周期」）。
 *
 * <p>本服务<b>只读</b>。写入由 {@code DatabaseSessionRecorder} 在 SSH 通道生命周期内完成——
 * 把读写分开是因为写入方运行在读泵线程上、且必须逐条幂等，而读取方是普通的请求线程；
 * 两者的约束完全不同，合在一个类里会让"谁能改状态"变得模糊。</p>
 *
 * <h2>状态映射是本类唯一有内容的业务规则</h2>
 * <p>DDL 有 4 个内部状态（{@code connecting}/{@code open}/{@code closed}/{@code error}），
 * 契约只对外暴露 2 个（{@code open}/{@code ended}）。这张映射表必须在<b>一处</b>定义：
 * 若查询与过滤各写一份，"列表里看得到但过滤不出来"这种自相矛盾的结果就会出现，
 * 而且只在特定状态组合下复现。</p>
 *
 * <p>WHY {@code connecting} 归入对外的 {@code open}：从客户端视角，一条正在握手的会话
 * 就是"已发起、尚未结束"。若把它排除，卡在握手中的会话会从列表里凭空消失，
 * 用户无法察觉后端仍在尝试连接——而此时恰恰是最需要看到它的时候。</p>
 */
@Service
public class SessionService {

    // DDL 的 CHECK (status IN (...)) 允许的全部字面值，集中在此避免拼写漂移
    private static final String STATUS_CONNECTING = "connecting";
    private static final String STATUS_OPEN = "open";
    private static final String STATUS_CLOSED = "closed";
    private static final String STATUS_ERROR = "error";

    private final SshSessionMapper sessions;
    private final HostMapper hosts;

    public SessionService(SshSessionMapper sessions, HostMapper hosts) {
        this.sessions = sessions;
        this.hosts = hosts;
    }

    /**
     * @param hostId 为空则不按主机过滤
     * @param status 为空则不按状态过滤；取值按上文的映射表反查为内部状态集合
     * @return 契约形态的会话列表，按开始时间<b>倒序</b>（最新在前）
     */
    public List<Session> list(@Nullable UUID hostId, @Nullable SessionStatus status) {
        QueryWrapper<SshSession> query = new QueryWrapper<>();
        if (hostId != null) {
            query.eq("host_id", hostId.toString());
        }
        if (status != null) {
            query.in("status", dbStatusesOf(status));
        }
        // WHY 追加 id 作为次级排序键：同一毫秒内建立的多条会话（AI 并发跑命令时很常见）
        // 否则顺序由 SQLite 的扫描计划决定，刷新一次就变一次
        query.orderByDesc("started_at", "id");

        List<SshSession> rows = sessions.selectList(query);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, String> labels = hostLabels();
        return rows.stream().map(row -> toDto(row, labels.get(row.getHostId()))).toList();
    }

    /**
     * 契约状态 → DDL 内部状态集合。
     *
     * <p>WHY 用穷尽的 switch 表达式而不写 {@code default}：这样契约若新增第三个状态，
     * 本方法会<b>编译不过</b>，逼着实现者回来决定它对应哪些内部状态。
     * 加了 {@code default} 就等于把这次决策静默地推迟到线上。</p>
     */
    private static List<String> dbStatusesOf(SessionStatus status) {
        return switch (status) {
            case OPEN -> List.of(STATUS_CONNECTING, STATUS_OPEN);
            case ENDED -> List.of(STATUS_CLOSED, STATUS_ERROR);
        };
    }

    /**
     * 内部状态 → 契约状态。
     *
     * <p>WHY 未知/为 null 时归入 {@code ENDED}：DDL 的 CHECK 约束已经把取值域限定为上述 4 个，
     * 走到 default 只可能是有人绕过约束写了脏数据。此时把它报成 {@code open} 更危险——
     * 那会让一条来路不明的记录永远挂在"进行中"列表里，且无法被关闭。</p>
     */
    private static SessionStatus toContractStatus(@Nullable String dbStatus) {
        if (STATUS_CONNECTING.equals(dbStatus) || STATUS_OPEN.equals(dbStatus)) {
            return SessionStatus.OPEN;
        }
        return SessionStatus.ENDED;
    }

    /**
     * @param hostLabel 展示名；主机行已不存在时为 null（契约把该字段声明为可选）
     */
    private static Session toDto(SshSession row, @Nullable String hostLabel) {
        // WHY 直接 UUID.fromString 而不做容错：id 由 EntityIds.newUuid() 写成带连字符的形式。
        // 若哪天有代码路径漏了这一步（IdType.ASSIGN_UUID 会填 32 位无连字符串），
        // 这里会抛异常、整个列表返回 500——这是**期望**的行为：
        // 静默跳过会让审计记录无声消失，而审计缺失比接口报错严重得多。
        Session dto = new Session(UUID.fromString(row.getId()), UUID.fromString(row.getHostId()),
                toContractStatus(row.getStatus()), Timestamps.toOffset(row.getStartedAt()));
        dto.setHostLabel(hostLabel);
        dto.setEndedAt(Timestamps.toOffset(row.getEndedAt()));
        dto.setEndReason(row.getCloseReason() == null
                ? null
                : SshCloseReason.fromColumnValue(row.getCloseReason()).toContractEndReason());
        // 刻意不映射 error_message：它含异常类名、主机与端口，属排障信息；
        // 契约的 Session 里也没有这个字段（见 SessionsApiIntegrationTest#errorMessageIsNeverSerialized）
        return dto;
    }

    /**
     * 一次性把 {@code hosts} 全表读成 {@code id -> name} 映射。
     *
     * <p>WHY 不用 JOIN：MyBatis-Plus 的 {@code BaseMapper} 不提供连接查询，
     * 要么写原生 SQL（于是放弃实体映射与类型安全），要么按行回查（N+1）。
     * 这是单机单用户桌面应用，{@code hosts} 是用户手工维护的服务器清单，量级是几十条，
     * 全表读一次的代价远低于为它引入一套自定义 SQL。</p>
     */
    private Map<String, String> hostLabels() {
        Map<String, String> labels = new HashMap<>();
        for (Host host : hosts.selectList(null)) {
            labels.put(host.getId(), host.getName());
        }
        return labels;
    }
}

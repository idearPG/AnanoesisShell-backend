package com.ananoesis.shell.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.EndReason;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.Session;
import com.ananoesis.shell.contract.model.SessionStatus;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.entity.SshSession;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.mapper.SshSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code GET /api/sessions} 的验收（ssh-connection spec「连接会话生命周期」）。
 *
 * <p>WHY 用 Mapper <b>播种</b>、用原生 JDBC <b>复核播种结果</b>、再用 REST 断言映射：
 * 播种走 Mapper 能保证时间戳的落库文本格式与生产完全一致（自己拼字符串很容易与
 * sqlite-jdbc 的写法差一个空格或时区后缀，那样测的就是格式而不是映射）。
 * 但"播种与读取共用同一套实体映射"会掩盖系统性错误——若实体把 {@code close_reason}
 * 写进了别的列，播种和读取会一起错、测试照样绿。所以额外用原生 JDBC 断言
 * {@code status}/{@code close_reason} 两列里躺着的<b>确实是</b> DDL 允许的字面值。</p>
 *
 * <p>WHY 每个用例播种自己独立的主机行：所有集成测试共享同一个 Spring 上下文与同一个
 * SQLite 文件（见 {@link AbstractSqliteIntegrationTest}），唯一化才能让断言与
 * JUnit 的方法执行顺序无关。</p>
 *
 * <p><b>状态映射是这一层唯一有内容的业务规则</b>：DDL 有 4 个内部状态
 * （{@code connecting}/{@code open}/{@code closed}/{@code error}），契约只对外暴露 2 个
 * （{@code open}/{@code ended}）。下面的 {@code statusOpen*}/{@code statusEnded*}
 * 两组用例就是钉住这张映射表的双向覆盖。</p>
 */
class SessionsApiIntegrationTest extends AbstractSqliteIntegrationTest {

    /** 刻意选一个绝不会偶然出现在数据里的字符串，使"响应里没有它"成为强断言。 */
    private static final String INTERNAL_DETAIL =
            "java.net.ConnectException: Connection refused: 127.0.0.1:39999 (internal-probe-token)";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private HostMapper hostMapper;
    @Autowired
    private SshSessionMapper sessionMapper;

    // ======================================================================
    // 字段映射与排序
    // ======================================================================

    @Test
    @DisplayName("GET /api/sessions：DB 行映射为契约 Session，按 started_at 倒序")
    void listSessionsMapsRowsToContract() {
        String hostId = seedHost("10.95.1.11", "生产-web-01");
        String oldest = seedSession(hostId, "open", "interactive_pty",
                LocalDateTime.now().minusMinutes(30), null, null, null);
        String newest = seedSession(hostId, "closed", "interactive_pty",
                LocalDateTime.now().minusMinutes(2), LocalDateTime.now().minusMinutes(1),
                "user_disconnect", null);

        List<Session> sessions = parseList(get("/api/sessions?host_id=" + hostId));

        assertThat(sessions).hasSize(2);
        // WHY 断言顺序：契约没有规定，但审计列表若顺序抖动，用户会以为"记录丢了又回来了"。
        // 倒序（最新在前）是运维界面的通行约定，也让人一眼看到刚断开的那条。
        assertThat(sessions.get(0).getId().toString()).isEqualTo(newest);
        assertThat(sessions.get(1).getId().toString()).isEqualTo(oldest);

        Session ended = sessions.get(0);
        assertThat(ended.getHostId().toString()).isEqualTo(hostId);
        assertThat(ended.getHostLabel()).as("host_label 取 hosts.name（展示名）")
                .isEqualTo("生产-web-01");
        assertThat(ended.getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(ended.getStartedAt()).isNotNull();
        assertThat(ended.getEndedAt()).isNotNull();
        assertThat(ended.getEndReason()).isEqualTo(EndReason.USER_DISCONNECT);

        Session open = sessions.get(1);
        assertThat(open.getStatus()).isEqualTo(SessionStatus.OPEN);
        assertThat(open.getEndedAt()).as("未结束的会话不得有 ended_at").isNull();
        assertThat(open.getEndReason()).as("未结束的会话不得有 end_reason").isNull();
    }

    @Test
    @DisplayName("GET /api/sessions：无记录时返回空数组而非 null")
    void emptyResultIsAnEmptyArray() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/sessions?host_id=" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // WHY 断原始文本：反序列化成 List 后，null 与 [] 都会得到一个"空"的对象，
        // 而前端 fetch 到字面量 null 时 .map() 会直接抛异常
        assertThat(response.getBody().trim()).isEqualTo("[]");
    }

    @Test
    @DisplayName("GET /api/sessions：host_label 来自 hosts.name；无展示名时回退到地址")
    void hostLabelFollowsHostsNameColumn() {
        String withName = seedHost("10.95.1.12", "跳板机-华东");
        String withoutName = seedHost("10.95.1.13", "10.95.1.13");
        seedSession(withName, "open", "interactive_pty", LocalDateTime.now(), null, null, null);
        seedSession(withoutName, "open", "exec", LocalDateTime.now(), null, null, null);

        assertThat(findByHost(parseList(get("/api/sessions?host_id=" + withName)), withName).getHostLabel())
                .isEqualTo("跳板机-华东");
        assertThat(findByHost(parseList(get("/api/sessions?host_id=" + withoutName)), withoutName).getHostLabel())
                .as("HostAssembler 在 note 缺省时把地址写进 name，这里只是如实呈现")
                .isEqualTo("10.95.1.13");
    }

    // ======================================================================
    // 过滤
    // ======================================================================

    @Test
    @DisplayName("GET /api/sessions?host_id=…：只返回该主机的会话")
    void filterByHostId() {
        String mine = seedHost("10.95.1.21", "主机甲");
        String theirs = seedHost("10.95.1.22", "主机乙");
        String kept = seedSession(mine, "open", "interactive_pty", LocalDateTime.now(), null, null, null);
        String dropped = seedSession(theirs, "open", "interactive_pty", LocalDateTime.now(), null, null, null);

        List<Session> sessions = parseList(get("/api/sessions?host_id=" + mine));

        assertThat(sessions).extracting(s -> s.getId().toString()).containsExactly(kept);
        assertThat(sessions).extracting(s -> s.getId().toString()).doesNotContain(dropped);
    }

    @Test
    @DisplayName("GET /api/sessions?status=open：包含 DDL 的 connecting 与 open 两种内部状态")
    void statusOpenFilterCoversConnectingAndOpen() {
        String hostId = seedHost("10.95.1.31", "主机丙");
        String connecting = seedSession(hostId, "connecting", "interactive_pty",
                LocalDateTime.now(), null, null, null);
        String open = seedSession(hostId, "open", "interactive_pty",
                LocalDateTime.now().minusMinutes(5), null, null, null);
        String closed = seedSession(hostId, "closed", "interactive_pty",
                LocalDateTime.now().minusMinutes(9), LocalDateTime.now().minusMinutes(8),
                "user_disconnect", null);

        List<Session> sessions = parseList(get("/api/sessions?host_id=" + hostId + "&status=open"));

        // WHY connecting 也算对外的 open：契约的 SessionStatus 只有 open/ended 两值，
        // "正在握手"从客户端视角就是"这条会话已经发起、尚未结束"。若把它排除，
        // 一个卡在握手中的会话会从列表里凭空消失，用户无法察觉后端还在尝试连接。
        assertThat(sessions).extracting(s -> s.getId().toString())
                .containsExactlyInAnyOrder(connecting, open);
        assertThat(sessions).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SessionStatus.OPEN));
        assertThat(sessions).extracting(s -> s.getId().toString()).doesNotContain(closed);
    }

    @Test
    @DisplayName("GET /api/sessions?status=ended：包含 DDL 的 closed 与 error 两种内部状态")
    void statusEndedFilterCoversClosedAndError() {
        String hostId = seedHost("10.95.1.32", "主机丁");
        LocalDateTime started = LocalDateTime.now().minusMinutes(20);
        String closed = seedSession(hostId, "closed", "interactive_pty",
                started, LocalDateTime.now().minusMinutes(19), "remote_closed", null);
        String errored = seedSession(hostId, "error", "exec",
                started, LocalDateTime.now().minusMinutes(18), "auth_failed", INTERNAL_DETAIL);
        String open = seedSession(hostId, "open", "interactive_pty", started, null, null, null);

        List<Session> sessions = parseList(get("/api/sessions?host_id=" + hostId + "&status=ended"));

        assertThat(sessions).extracting(s -> s.getId().toString()).containsExactlyInAnyOrder(closed, errored);
        assertThat(sessions).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SessionStatus.ENDED));
        assertThat(sessions).extracting(s -> s.getId().toString()).doesNotContain(open);
    }

    // ======================================================================
    // end_reason 映射
    // ======================================================================

    @Test
    @DisplayName("end_reason：user_disconnect / timeout / remote_closed 分别映射到契约三值")
    void endReasonMapsTerminationCauses() {
        String hostId = seedHost("10.95.1.41", "主机戊");
        LocalDateTime started = LocalDateTime.now().minusHours(1);
        LocalDateTime ended = LocalDateTime.now().minusMinutes(30);

        assertThat(endReasonOf(seedSession(hostId, "closed", "interactive_pty", started, ended,
                "user_disconnect", null))).isEqualTo(EndReason.USER_DISCONNECT);
        assertThat(endReasonOf(seedSession(hostId, "closed", "interactive_pty", started, ended,
                "timeout", null))).isEqualTo(EndReason.TIMEOUT);
        // WHY 这一条最值得钉：DDL 写的是 remote_closed（过去式，描述"远端已关闭"这个事实），
        // 契约写的是 remote_close（动词短语）。两个字符串差一个字母 d，
        // 抄错不会有任何编译或运行时报错，只会让前端把"远端断开"显示成"未知原因"。
        assertThat(endReasonOf(seedSession(hostId, "closed", "interactive_pty", started, ended,
                "remote_closed", null))).isEqualTo(EndReason.REMOTE_CLOSE);
    }

    @Test
    @DisplayName("end_reason：连接期失败（auth_failed/unreachable/error）映射为 null")
    void connectPhaseFailuresHaveNullEndReason() {
        String hostId = seedHost("10.95.1.42", "主机己");
        LocalDateTime started = LocalDateTime.now().minusMinutes(10);
        LocalDateTime ended = LocalDateTime.now().minusMinutes(9);

        for (String reason : List.of("auth_failed", "unreachable", "error")) {
            EndReason endReason = endReasonOf(seedSession(hostId, "error", "exec", started, ended,
                    reason, INTERNAL_DETAIL));
            // WHY 允许 null 而不是硬凑一个契约值：把"认证失败"报成"用户主动断开"
            // 会在审计里留下与事实相反的记录，比留空更糟（见 SshCloseReason 类注释）
            assertThat(endReason).as("close_reason=%s 不应有对外 end_reason", reason).isNull();
        }
    }

    // ======================================================================
    // 不泄露内部细节
    // ======================================================================

    @Test
    @DisplayName("GET /api/sessions：error_message（含主机端口与异常类名）绝不出现在响应里")
    void errorMessageIsNeverSerialized() {
        String hostId = seedHost("10.95.1.51", "主机庚");
        seedSession(hostId, "error", "exec", LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().minusMinutes(4), "unreachable", INTERNAL_DETAIL);

        // 复核播种：内部细节确实进了 error_message 列（否则下面的断言是空转）
        assertThat(rawSessionRow(hostId, "unreachable").errorMessage()).isEqualTo(INTERNAL_DETAIL);

        ResponseEntity<String> response = rest.getForEntity("/api/sessions?host_id=" + hostId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // WHY 断原始响应文本而不是解析后的 DTO：契约的 Session 里没有 error_message 字段，
        // 解析成 DTO 后它自然"不存在"，断言恒真。只有原始字节能证明它没被多序列化出来。
        assertThat(response.getBody()).doesNotContain("internal-probe-token");
        assertThat(response.getBody()).doesNotContain("ConnectException");
        assertThat(response.getBody()).doesNotContain("39999");
    }

    // ======================================================================
    // 查询参数校验
    // ======================================================================

    @Test
    @DisplayName("GET /api/sessions?status=非法取值 → 400 validation_error，且不回显该取值")
    void invalidStatusIsValidationError() {
        ResponseEntity<Error> response = rest.getForEntity("/api/sessions?status=bogus", Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        // WHY 不回显：把用户输入原样拼回响应体，等于提供一个"探测后端解析器"的回声端点
        assertThat(response.getBody().getMessage()).doesNotContain("bogus");
    }

    @Test
    @DisplayName("GET /api/sessions?host_id=非 UUID → 400 validation_error（而非 500）")
    void invalidHostIdIsValidationError() {
        ResponseEntity<Error> response = rest.getForEntity("/api/sessions?host_id=not-a-uuid", Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(response.getBody().getMessage()).doesNotContain("not-a-uuid");
    }

    // ======================================================================
    // 播种与断言辅助
    // ======================================================================

    private String seedHost(String address, String displayName) {
        Host host = new Host();
        host.setId(UUID.randomUUID().toString());
        host.setName(displayName);
        host.setHost(address);
        host.setPort(22);
        host.setUsername("ops");
        host.setAuthType("password");
        hostMapper.insert(host);
        return host.getId();
    }

    /**
     * @return 新会话行的 id（带连字符，与契约的 {@code format: uuid} 一致）
     */
    private String seedSession(String hostId, String status, String sessionType, LocalDateTime startedAt,
                              LocalDateTime endedAt, String closeReason, String errorMessage) {
        SshSession row = new SshSession();
        // WHY 显式赋 id：IdType.ASSIGN_UUID 产出的是 32 位无连字符串，
        // 契约把 session id 声明为 format: uuid，前端 UUID.fromString 会直接抛异常
        row.setId(UUID.randomUUID().toString());
        row.setHostId(hostId);
        row.setSessionType(sessionType);
        row.setStatus(status);
        row.setStartedAt(startedAt);
        row.setEndedAt(endedAt);
        row.setCloseReason(closeReason);
        row.setErrorMessage(errorMessage);
        sessionMapper.insert(row);

        // WHY 立刻用原生 JDBC 复核：播种若把状态写错了列，后面的 REST 断言会与之一起错，
        // 测试就成了自证。这一步把"库里躺着的字面值"变成独立事实。
        SessionRow raw = rawSessionRowById(row.getId());
        assertThat(raw.status()).as("播种的状态必须原样落库").isEqualTo(status);
        assertThat(raw.sessionType()).isEqualTo(sessionType);
        assertThat(raw.closeReason()).isEqualTo(closeReason);
        return row.getId();
    }

    private EndReason endReasonOf(String sessionId) {
        Session session = parseList(get("/api/sessions")).stream()
                .filter(s -> s.getId().toString().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("响应里找不到会话 " + sessionId));
        assertThat(session.getStatus()).isEqualTo(SessionStatus.ENDED);
        return session.getEndReason();
    }

    private Session findByHost(List<Session> sessions, String hostId) {
        return sessions.stream()
                .filter(s -> s.getHostId().toString().equals(hostId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("响应里找不到 host_id=" + hostId + " 的会话"));
    }

    private String get(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<Session> parseList(String body) {
        try {
            return objectMapper.readValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Session.class));
        } catch (Exception e) {
            throw new IllegalStateException("无法解析响应体: " + body, e);
        }
    }

    /** sessions 表的一行原始内容（用于复核播种，以及断言 error_message 确实存在于库中）。 */
    private record SessionRow(String id, String hostId, String sessionType, String status,
                              String closeReason, String errorMessage) {
    }

    private SessionRow rawSessionRowById(String id) {
        String sql = "SELECT id, host_id, session_type, status, close_reason, error_message "
                + "FROM sessions WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("sessions 表里没有 id=" + id + " 的行");
                }
                return new SessionRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return 指定主机下、指定 close_reason 的第一行（用于定位刚播种的那条） */
    private SessionRow rawSessionRow(String hostId, String closeReason) {
        String sql = "SELECT id, host_id, session_type, status, close_reason, error_message "
                + "FROM sessions WHERE host_id = ? AND close_reason = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hostId);
            statement.setString(2, closeReason);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("sessions 表里没有 host=" + hostId
                            + " close_reason=" + closeReason + " 的行");
                }
                return new SessionRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

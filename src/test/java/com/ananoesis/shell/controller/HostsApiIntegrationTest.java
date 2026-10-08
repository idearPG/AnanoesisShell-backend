package com.ananoesis.shell.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.AuthType;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.Host;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * tasks 5.1 的验收：{@code /api/hosts} 的 REST CRUD 与凭据密文化。
 *
 * <p>WHY 断言分三层（响应体 / hosts 表 / credentials 表）：
 * "接口返回正确"只证明了序列化层；spec 的 MUST 级要求是**明文不落库**与**响应不回显**，
 * 这两条分别只能靠查 {@code credentials.ciphertext} 与查原始响应字节来证明。</p>
 *
 * <p>WHY 每个用例使用唯一的 host 地址：所有集成测试共享同一个 Spring 上下文与同一个
 * SQLite 文件（见 {@link AbstractSqliteIntegrationTest}），用唯一值隔离才能让断言
 * 与 JUnit 的方法执行顺序无关。</p>
 *
 * <p>WHY 数据库断言一律走原生 JDBC 而不是复用 {@code HostMapper}：
 * 用被测代码去验证被测代码，等于让"实体映射漏了一列"这类 bug 自己给自己判无罪。</p>
 */
class HostsApiIntegrationTest extends AbstractSqliteIntegrationTest {

    /** 刻意选一个绝不会偶然出现在数据里的字符串，使"扫不到"成为强断言。 */
    private static final String PASSWORD = "S3cr3t-P@ssw0rd-XYZ-9f3a";
    private static final String PASSPHRASE = "k3y-P@ssphrase-QRS-7d2e";
    private static final String PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gt
            ZWRyMjU1MTkAAAgQFakeKeyMaterialForTestingOnlyDoNotUseAAA
            -----END OPENSSH PRIVATE KEY-----
            """;
    private static final String OTHER_PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gt
            ZWRyMjU1MTkAAAgQSecondFakeKeyForRotationTestsAAA
            -----END OPENSSH PRIVATE KEY-----
            """;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper objectMapper;

    // ======================================================================
    // 新增
    // ======================================================================

    @Test
    @DisplayName("POST /api/hosts（密码认证）→ 201，响应不回显明文，credentials 表仅存密文")
    void createPasswordHostStoresCiphertextAndMasksResponse() throws Exception {
        Host request = passwordHost("10.90.1.11", 2222, "ops", "生产", "web-01", PASSWORD);

        ResponseEntity<String> raw = rest.postForEntity("/api/hosts", jsonEntity(request), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // WHY 先断原始响应文本：把 DTO 反序列化后再查字段，会漏掉"多回显了一个未建模字段"的泄露
        assertThat(raw.getBody()).doesNotContain(PASSWORD);

        Host created = objectMapper.readValue(raw.getBody(), Host.class);
        assertThat(created.getId()).as("服务端应分配 id").isNotNull();
        assertThat(created.getHost()).isEqualTo("10.90.1.11");
        assertThat(created.getPort()).isEqualTo(2222);
        assertThat(created.getUsername()).isEqualTo("ops");
        assertThat(created.getAuthType()).isEqualTo(AuthType.PASSWORD);
        assertThat(created.getGroupName()).isEqualTo("生产");
        assertThat(created.getNote()).isEqualTo("web-01");
        assertThat(created.getCredentialSet()).as("以掩码布尔告知凭据已配置").isTrue();
        assertThat(created.getPassword()).as("writeOnly：不得回显").isNull();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();

        // hosts 表：非敏感配置落库，且**没有任何凭据列**
        HostRow row = selectHost(created.getId().toString());
        assertThat(row).isNotNull();
        assertThat(row.host()).isEqualTo("10.90.1.11");
        assertThat(row.port()).isEqualTo(2222);
        assertThat(row.username()).isEqualTo("ops");
        assertThat(row.authType()).isEqualTo("password");
        assertThat(row.groupName()).isEqualTo("生产");
        assertThat(row.remark()).isEqualTo("web-01");

        // credentials 表：只有密文
        String ciphertext = selectCiphertext(created.getId().toString(), "ssh_password");
        assertThat(ciphertext).as("密码应以密文落库").startsWith("v1.");
        assertThat(ciphertext).doesNotContain(PASSWORD);
        assertThat(allCredentialColumnsOf(created.getId().toString()))
                .as("该主机的任何凭据列都不得含明文")
                .noneMatch(value -> value.contains(PASSWORD));
    }

    @Test
    @DisplayName("POST /api/hosts（私钥 + passphrase）→ 两者都以密文存储")
    void createPrivateKeyHostEncryptsKeyAndPassphrase() throws Exception {
        Host request = new Host("10.90.1.12", 22, "deploy", AuthType.PRIVATE_KEY);
        request.setPrivateKey(PRIVATE_KEY);
        request.setPassphrase(PASSPHRASE);

        ResponseEntity<String> raw = rest.postForEntity("/api/hosts", jsonEntity(request), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(raw.getBody()).doesNotContain(PASSPHRASE).doesNotContain("BEGIN OPENSSH PRIVATE KEY");
        Host created = objectMapper.readValue(raw.getBody(), Host.class);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getPrivateKey()).as("writeOnly：不得回显").isNull();
        assertThat(created.getPassphrase()).as("writeOnly：不得回显").isNull();
        assertThat(created.getCredentialSet()).isTrue();

        String id = created.getId().toString();
        assertThat(selectCiphertext(id, "ssh_private_key"))
                .startsWith("v1.").doesNotContain("BEGIN OPENSSH PRIVATE KEY");
        assertThat(selectCiphertext(id, "ssh_passphrase")).startsWith("v1.").doesNotContain(PASSPHRASE);
    }

    @Test
    @DisplayName("POST /api/hosts 缺少必填字段 → 400 validation_error 且带字段明细")
    void createHostWithoutRequiredFieldsIsRejected() {
        // 缺 username / auth_type
        String body = """
                {"host":"10.90.1.13","port":22}
                """;

        ResponseEntity<Error> response = rest.postForEntity("/api/hosts", rawJson(body), Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error error = response.getBody();
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(error.getDetails()).extracting("field").contains("username", "auth_type");
    }

    @Test
    @DisplayName("POST /api/hosts：auth_type=password 却未带 password → 400 validation_error")
    void createPasswordHostWithoutPasswordIsRejected() {
        Host request = passwordHost("10.90.1.14", 22, "ops", null, null, null);

        ResponseEntity<Error> response = rest.postForEntity("/api/hosts", jsonEntity(request), Error.class);

        // WHY 这条约束必须由后端校验：契约的条件约束（auth_type=password 须带 password）
        // 无法用 OpenAPI 表达，若放过，用户会得到一台"永远连不上"的配置且无任何提示。
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(response.getBody().getDetails()).extracting("field").contains("password");
        assertThat(countHostRows("10.90.1.14")).as("校验失败不得留下半成品记录").isZero();
    }

    @Test
    @DisplayName("POST /api/hosts：auth_type=private_key 却未带 private_key → 400 validation_error")
    void createPrivateKeyHostWithoutKeyIsRejected() {
        Host request = new Host("10.90.1.15", 22, "ops", AuthType.PRIVATE_KEY);

        ResponseEntity<Error> response = rest.postForEntity("/api/hosts", jsonEntity(request), Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(response.getBody().getDetails()).extracting("field").contains("private_key");
    }

    @Test
    @DisplayName("POST /api/hosts：auth_type 取值不在契约枚举内 → 400 validation_error（非 500，且不回显非法取值）")
    void createHostWithUnknownAuthTypeIsRejected() {
        ResponseEntity<Error> response = rest.postForEntity("/api/hosts",
                rawJson("""
                        {"host":"10.90.1.16","port":22,"username":"ops","auth_type":"kerberos"}
                        """),
                Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(response.getBody().getMessage()).isNotBlank();
        // WHY：Jackson 的原文是 "Unexpected value 'kerberos'"，直接透传等于把用户输入
        // 原样拼回响应体，既可用于探测后端解析器，也让前端拿到一段无法本地化的英文
        assertThat(response.getBody().getMessage()).doesNotContain("kerberos");
    }

    @Test
    @DisplayName("POST /api/hosts：非法 id 形式的路径参数 → 400 validation_error（而非 500）")
    void pathVariableThatIsNotAUuidIsRejected() {
        ResponseEntity<Error> response = rest.getForEntity("/api/hosts/not-a-uuid", Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(response.getBody().getMessage()).doesNotContain("not-a-uuid");
    }

    @Test
    @DisplayName("未定义的 URL → 404 not_found（契约形状），而非 500")
    void unknownUrlYieldsContractNotFound() {
        ResponseEntity<Error> response = rest.getForEntity("/api/definitely-not-an-endpoint", Error.class);

        // WHY 值得一条用例：全局兜底 handler 捕获 Exception 后，Spring 对未知路径抛出的
        // NoResourceFoundException 会被误报成 500，从此"客户端拼错 URL"与"服务端故障"
        // 在日志和监控里再也分不开
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ======================================================================
    // 查询
    // ======================================================================

    @Test
    @DisplayName("GET /api/hosts 返回已保存配置，且任何一项都不含明文凭据")
    void listHostsReturnsSavedHostsWithoutCredentials() {
        Host created = create(passwordHost("10.90.2.21", 22, "ops", "测试", "list-01", PASSWORD));

        ResponseEntity<String> raw = rest.getForEntity("/api/hosts", String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain(PASSWORD);
        List<Host> hosts = parseList(raw.getBody());
        assertThat(hosts).extracting(Host::getId).contains(created.getId());
        assertThat(hosts).allSatisfy(host -> {
            assertThat(host.getPassword()).isNull();
            assertThat(host.getPrivateKey()).isNull();
            assertThat(host.getPassphrase()).isNull();
        });
    }

    @Test
    @DisplayName("GET /api/hosts/{id} 返回单个配置；id 可原样用于后续请求（契约 format=uuid 往返）")
    void getHostByIdRoundTripsContractUuid() {
        Host created = create(passwordHost("10.90.2.22", 22022, "root", null, null, PASSWORD));

        ResponseEntity<String> raw = rest.getForEntity("/api/hosts/{id}", String.class, created.getId());

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain(PASSWORD);
        Host fetched = parse(raw.getBody());
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getPort()).isEqualTo(22022);
        assertThat(fetched.getCredentialSet()).isTrue();
        // WHY 单独断言 id 形式：契约声明 format=uuid，SQLite 里存的是文本主键；
        // 若两者形式不一致（例如库里是 32 位无连字符），前端拿到的 id 将无法回传
        assertThat(UUID.fromString(fetched.getId().toString())).isNotNull();
        assertThat(selectHost(created.getId().toString()).id()).isEqualTo(created.getId().toString());
    }

    @Test
    @DisplayName("GET /api/hosts/{未知 id} → 404 not_found")
    void getUnknownHostYieldsNotFound() {
        ResponseEntity<Error> response =
                rest.getForEntity("/api/hosts/{id}", Error.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(response.getBody().getMessage()).isNotBlank();
    }

    // ======================================================================
    // 更新
    // ======================================================================

    @Test
    @DisplayName("PUT /api/hosts/{id} 更新非敏感字段后，后续读取使用新值")
    void updateHostPersistsChangedFields() {
        Host created = create(passwordHost("10.90.3.31", 22, "ops", "生产", "web-31", PASSWORD));

        Host update = passwordHost("10.90.3.31", 2222, "deployer", "预发", "web-31-renamed", null);
        ResponseEntity<Host> response = rest.exchange("/api/hosts/{id}", HttpMethod.PUT,
                jsonEntity(update), Host.class, created.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Host updated = response.getBody();
        assertThat(updated.getPort()).isEqualTo(2222);
        assertThat(updated.getUsername()).isEqualTo("deployer");
        assertThat(updated.getGroupName()).isEqualTo("预发");
        assertThat(updated.getNote()).isEqualTo("web-31-renamed");
        assertThat(updated.getCredentialSet()).as("未携带凭据时应保持已配置状态").isTrue();
        assertThat(updated.getCreatedAt()).as("创建时间不得被更新改写").isEqualTo(created.getCreatedAt());
        assertThat(updated.getUpdatedAt()).as("更新时间应刷新").isAfterOrEqualTo(created.getUpdatedAt());

        HostRow row = selectHost(created.getId().toString());
        assertThat(row.port()).isEqualTo(2222);
        assertThat(row.username()).isEqualTo("deployer");
        assertThat(row.groupName()).isEqualTo("预发");
        assertThat(row.remark()).isEqualTo("web-31-renamed");
        // 未携带凭据 => 原密文保持不变（契约：不回显、不覆盖为空）
        assertThat(selectCiphertext(created.getId().toString(), "ssh_password")).isNotBlank();
    }

    @Test
    @DisplayName("PUT /api/hosts/{id} 可以把可选字段清空（分组/备注置回 NULL）")
    void updateHostCanClearOptionalFields() {
        Host created = create(passwordHost("10.90.3.36", 22, "ops", "生产", "web-36", PASSWORD));

        Host update = passwordHost("10.90.3.36", 22, "ops", null, null, null);
        ResponseEntity<Host> response = rest.exchange("/api/hosts/{id}", HttpMethod.PUT,
                jsonEntity(update), Host.class, created.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getGroupName()).as("分组应被清空").isNull();
        assertThat(response.getBody().getNote()).as("备注应被清空").isNull();

        HostRow row = selectHost(created.getId().toString());
        // WHY 值得一条用例：MyBatis-Plus 全局 update-strategy=not_null 会**静默跳过**
        // 值为 null 的字段，于是"用户删掉了分组"表现为"界面刷回去还是老分组"，
        // 且没有任何报错。必须显式把可空列写进 UPDATE 语句。
        assertThat(row.groupName()).isNull();
        assertThat(row.remark()).isNull();
        assertThat(row.name()).as("name 为 NOT NULL，无备注时应回退为主机地址").isEqualTo("10.90.3.36");
    }

    @Test
    @DisplayName("PUT /api/hosts/{id} 携带新密码时替换旧密文，且不产生第二行凭据")
    void updateHostReplacesCredentialWhenProvided() {
        Host created = create(passwordHost("10.90.3.32", 22, "ops", null, null, PASSWORD));
        String oldCiphertext = selectCiphertext(created.getId().toString(), "ssh_password");

        Host update = passwordHost("10.90.3.32", 22, "ops", null, null, "N3w-P@ssw0rd-AbC-77");
        ResponseEntity<Host> response = rest.exchange("/api/hosts/{id}", HttpMethod.PUT,
                jsonEntity(update), Host.class, created.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newCiphertext = selectCiphertext(created.getId().toString(), "ssh_password");
        assertThat(newCiphertext).isNotEqualTo(oldCiphertext);
        assertThat(newCiphertext).doesNotContain("N3w-P@ssw0rd-AbC-77");
        assertThat(countCredentialRows(created.getId().toString())).as("upsert 不得留下重复行").isEqualTo(1);
        assertThat(response.getBody().getPassword()).isNull();
    }

    @Test
    @DisplayName("PUT /api/hosts/{id} 切换认证方式时清理不再适用的旧凭据")
    void updateHostSwitchingAuthTypeDropsStaleCredential() {
        Host created = create(passwordHost("10.90.3.33", 22, "ops", null, null, PASSWORD));
        assertThat(selectCiphertext(created.getId().toString(), "ssh_password")).isNotBlank();

        Host update = new Host("10.90.3.33", 22, "ops", AuthType.PRIVATE_KEY);
        update.setPrivateKey(PRIVATE_KEY);
        ResponseEntity<Host> response = rest.exchange("/api/hosts/{id}", HttpMethod.PUT,
                jsonEntity(update), Host.class, created.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getAuthType()).isEqualTo(AuthType.PRIVATE_KEY);
        // WHY 必须清理：残留的旧密码密文既不会再被使用，也会让"这台机器用什么认证"
        // 的审计问题出现两个互相矛盾的答案
        assertThat(countCredentialRows(created.getId().toString(), "ssh_password")).isZero();
        assertThat(countCredentialRows(created.getId().toString(), "ssh_private_key")).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /api/hosts/{id} 切换到另一种认证方式却不带新凭据 → 400，且旧凭据不被破坏")
    void updateHostSwitchingAuthTypeWithoutNewCredentialIsRejected() {
        Host created = create(passwordHost("10.90.3.35", 22, "ops", null, null, PASSWORD));
        String passwordCiphertext = selectCiphertext(created.getId().toString(), "ssh_password");

        Host update = new Host("10.90.3.35", 22, "ops", AuthType.PRIVATE_KEY);
        ResponseEntity<Error> response = rest.exchange("/api/hosts/{id}", HttpMethod.PUT,
                jsonEntity(update), Error.class, created.getId());

        // WHY 必须拒绝而不是照做：照做的结果是"清掉旧密码、又没有新私钥"，
        // 这台主机从此**无法连接且无法自愈**——用户看到的只是 credential_set=false，
        // 却得不到"你还没给我私钥"这句关键提示
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(response.getBody().getDetails()).extracting("field").contains("private_key");
        assertThat(selectHost(created.getId().toString()).authType()).as("校验失败不得改写既有配置")
                .isEqualTo("password");
        assertThat(selectCiphertext(created.getId().toString(), "ssh_password"))
                .as("校验失败不得清掉既有凭据")
                .isEqualTo(passwordCiphertext);
    }

    @Test
    @DisplayName("PUT /api/hosts/{id} 更换私钥但未给新 passphrase → 清理旧 passphrase")
    void replacingPrivateKeyDropsStalePassphrase() {
        Host created = create(privateKeyHost("10.90.3.37", 22, "ops"));
        assertThat(countCredentialRows(created.getId().toString(), "ssh_passphrase")).isEqualTo(1);

        Host update = new Host("10.90.3.37", 22, "ops", AuthType.PRIVATE_KEY);
        update.setPrivateKey(OTHER_PRIVATE_KEY);
        ResponseEntity<Host> response = rest.exchange("/api/hosts/{id}", HttpMethod.PUT,
                jsonEntity(update), Host.class, created.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCredentialSet()).isTrue();
        // WHY 必须清理：passphrase 是用来解密**某一把**私钥的。换上无口令的新私钥后，
        // 旧 passphrase 会让 sshj 拿着错误的口令去解新密钥，表现为一个
        // 与真实原因（用户没填新口令）毫无关系的认证失败
        assertThat(countCredentialRows(created.getId().toString(), "ssh_passphrase")).isZero();
        assertThat(countCredentialRows(created.getId().toString(), "ssh_private_key")).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /api/hosts/{未知 id} → 404 not_found")
    void updateUnknownHostYieldsNotFound() {
        Host update = passwordHost("10.90.3.34", 22, "ops", null, null, PASSWORD);

        ResponseEntity<Error> response = rest.exchange("/api/hosts/{id}", HttpMethod.PUT,
                jsonEntity(update), Error.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ======================================================================
    // 删除
    // ======================================================================

    @Test
    @DisplayName("DELETE /api/hosts/{id} → 204，配置与关联密文凭据一并移除")
    void deleteHostRemovesHostAndItsCredentials() {
        Host created = create(privateKeyHost("10.90.4.41", 22, "ops"));

        ResponseEntity<Void> response = rest.exchange("/api/hosts/{id}", HttpMethod.DELETE,
                null, Void.class, created.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(selectHost(created.getId().toString())).as("配置应已移除").isNull();
        assertThat(countCredentialRows(created.getId().toString())).as("关联凭据应一并删除").isZero();
        assertThat(rest.getForEntity("/api/hosts/{id}", Error.class, created.getId()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /api/hosts/{未知 id} → 404 not_found")
    void deleteUnknownHostYieldsNotFound() {
        ResponseEntity<Error> response = rest.exchange("/api/hosts/{id}", HttpMethod.DELETE,
                null, Error.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private Host create(Host request) {
        ResponseEntity<Host> response = rest.postForEntity("/api/hosts", jsonEntity(request), Host.class);
        assertThat(response.getStatusCode()).as("前置：创建应成功").isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static Host passwordHost(String host, int port, String username,
                                     String groupName, String note, String password) {
        Host request = new Host(host, port, username, AuthType.PASSWORD);
        request.setGroupName(groupName);
        request.setNote(note);
        request.setPassword(password);
        return request;
    }

    private static Host privateKeyHost(String host, int port, String username) {
        Host request = new Host(host, port, username, AuthType.PRIVATE_KEY);
        request.setPrivateKey(PRIVATE_KEY);
        request.setPassphrase(PASSPHRASE);
        return request;
    }

    private HttpEntity<String> jsonEntity(Host host) {
        return rawJson(toJson(host));
    }

    private HttpEntity<String> rawJson(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String toJson(Host host) {
        try {
            return objectMapper.writeValueAsString(host);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    private Host parse(String body) {
        try {
            return objectMapper.readValue(body, Host.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析响应体: " + body, e);
        }
    }

    private List<Host> parseList(String body) {
        try {
            return objectMapper.readValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Host.class));
        } catch (Exception e) {
            throw new IllegalStateException("无法解析响应体: " + body, e);
        }
    }

    /** hosts 表的一行原始内容（不含任何凭据列，因为 DDL 里根本没有）。 */
    private record HostRow(String id, String name, String host, int port, String username,
                           String authType, String groupName, String remark) {
    }

    private HostRow selectHost(String id) {
        String sql = "SELECT id, name, host, port, username, auth_type, group_name, remark "
                + "FROM hosts WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new HostRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String selectCiphertext(String ownerId, String credentialType) {
        String sql = "SELECT ciphertext FROM credentials WHERE owner_type = 'host' "
                + "AND owner_id = ? AND credential_type = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            statement.setString(2, credentialType);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int countCredentialRows(String ownerId) {
        String sql = "SELECT COUNT(*) FROM credentials WHERE owner_type = 'host' AND owner_id = ?";
        return count(sql, ownerId, null);
    }

    private int countCredentialRows(String ownerId, String credentialType) {
        String sql = "SELECT COUNT(*) FROM credentials WHERE owner_type = 'host' "
                + "AND owner_id = ? AND credential_type = ?";
        return count(sql, ownerId, credentialType);
    }

    private int countHostRows(String hostAddress) {
        String sql = "SELECT COUNT(*) FROM hosts WHERE host = ?";
        return count(sql, hostAddress, null);
    }

    private int count(String sql, String first, String second) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first);
            if (second != null) {
                statement.setString(2, second);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> allCredentialColumnsOf(String ownerId) {
        String sql = "SELECT * FROM credentials WHERE owner_type = 'host' AND owner_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            try (ResultSet rs = statement.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                List<String> values = new java.util.ArrayList<>();
                while (rs.next()) {
                    for (int i = 1; i <= columns; i++) {
                        String value = rs.getString(i);
                        if (value != null) {
                            values.add(value);
                        }
                    }
                }
                return values;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

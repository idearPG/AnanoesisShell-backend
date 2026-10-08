package com.ananoesis.shell.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.entity.Credential;
import com.ananoesis.shell.mapper.CredentialMapper;
import com.ananoesis.shell.service.CredentialStoreService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * tasks 4.2 的验收：凭据保存后 {@code credentials} 表仅存密文、无明文，解密可正确还原。
 *
 * <p>WHY 除了查表还要**逐字节扫描数据库文件**（含 WAL/SHM）：
 * "表里没有明文列"只证明了 schema 合规，证明不了数据合规。WAL 模式下未 checkpoint 的页、
 * 已删除行的残留、以及 freelist 页都可能留下旧内容。逐字节扫描是对
 * credential-store spec「数据库中 MUST NOT 存储明文凭据」最直接、
 * 且不依赖任何实现细节的验证方式。</p>
 *
 * <p>WHY 每个用例使用**独立的 owner id**：本类的所有用例共享同一个 Spring 上下文与同一个
 * SQLite 文件（刻意如此——共享上下文让集成测试跑得快）。若再共用一个固定的
 * {@code HOST_ID}，先跑的 {@code deleteByOwner...} 会删掉后跑用例的数据、
 * 后跑用例写入的行又会让先跑用例的计数断言失效，测试结果将取决于 JUnit 的方法排序。
 * 用方法名做 owner id 后缀，既保证隔离，又让数据库里的残留行可追溯到具体用例。</p>
 */
class CredentialStoreServiceTest extends AbstractSqliteIntegrationTest {

    /** 刻意选一个绝不会偶然出现在数据里的字符串，使"扫不到"成为强断言。 */
    private static final String SSH_PASSWORD = "S3cr3t-P@ssw0rd-XYZ-9f3a";
    private static final String SSH_PASSPHRASE = "k3y-P@ssphrase-QRS-7d2e";
    private static final String LLM_API_KEY = "sk-FAKE-aBcD0123456789xyz-DoNotUse";
    private static final String PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gt
            ZWRyMjU1MTkAAAgQFakeKeyMaterialForTestingOnlyDoNotUseAAA
            -----END OPENSSH PRIVATE KEY-----
            """;

    @Autowired
    private CredentialStoreService store;
    @Autowired
    private CredentialMapper credentialMapper;
    @Autowired
    private DataSource dataSource;

    /** 每个测试方法一个实例（JUnit 默认 PER_METHOD 生命周期），因此这两个字段天然是用例私有的。 */
    private String hostId;
    private String modelConfigId;

    @BeforeEach
    void scopeOwnerIdsToCurrentTest(TestInfo info) {
        String method = info.getTestMethod().orElseThrow().getName();
        hostId = "store-test-host-" + method;
        modelConfigId = "store-test-model-" + method;
    }

    @Test
    @DisplayName("保存 SSH 密码后，credentials 行只含密文，且密文不等于明文")
    void savedSshPasswordIsCiphertextOnly() {
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSWORD,
                SecretText.of(SSH_PASSWORD));

        String ciphertext = selectCiphertext("host", hostId, "ssh_password");
        assertThat(ciphertext).as("密文应已落库").isNotBlank();
        assertThat(ciphertext).doesNotContain(SSH_PASSWORD);
        assertThat(ciphertext).startsWith("v1.");

        // 表结构层面也不应存在任何明文列（与 SqliteSchemaMigrationTest 互为补充：
        // 那里查 schema，这里查真实数据）
        assertThat(allColumnValuesOf("credentials"))
                .as("credentials 表任何列都不得出现明文")
                .noneMatch(value -> value.contains(SSH_PASSWORD));
    }

    @Test
    @DisplayName("保存私钥与 passphrase，两者都以密文存储（spec：保存私钥）")
    void privateKeyAndPassphraseAreBothEncrypted() {
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PRIVATE_KEY,
                SecretText.of(PRIVATE_KEY));
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSPHRASE,
                SecretText.of(SSH_PASSPHRASE));

        String keyCiphertext = selectCiphertext("host", hostId, "ssh_private_key");
        String passphraseCiphertext = selectCiphertext("host", hostId, "ssh_passphrase");

        assertThat(keyCiphertext).doesNotContain("BEGIN OPENSSH PRIVATE KEY");
        assertThat(passphraseCiphertext).doesNotContain(SSH_PASSPHRASE);
        assertThat(keyCiphertext).isNotEqualTo(passphraseCiphertext);
    }

    @Test
    @DisplayName("整个数据库文件（含 WAL/SHM）中搜不到任何明文凭据")
    void noPlaintextAnywhereInDatabaseFiles() throws IOException {
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSWORD,
                SecretText.of(SSH_PASSWORD));
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PRIVATE_KEY,
                SecretText.of(PRIVATE_KEY));
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSPHRASE,
                SecretText.of(SSH_PASSPHRASE));
        store.save(CredentialOwnerType.MODEL_CONFIG, modelConfigId, CredentialType.LLM_API_KEY,
                SecretText.of(LLM_API_KEY));

        String haystack = readAllDatabaseFilesAsString();
        assertThat(haystack)
                .doesNotContain(SSH_PASSWORD)
                .doesNotContain(SSH_PASSPHRASE)
                .doesNotContain(LLM_API_KEY)
                .doesNotContain("BEGIN OPENSSH PRIVATE KEY");
    }

    @Test
    @DisplayName("解密可正确还原，包括多行 PEM 的换行")
    void decryptionRestoresOriginalPlaintext() {
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSWORD,
                SecretText.of(SSH_PASSWORD));
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PRIVATE_KEY,
                SecretText.of(PRIVATE_KEY));

        try (SecretText password = store.find(CredentialOwnerType.HOST, hostId,
                CredentialType.SSH_PASSWORD).orElseThrow();
             SecretText privateKey = store.find(CredentialOwnerType.HOST, hostId,
                CredentialType.SSH_PRIVATE_KEY).orElseThrow()) {
            assertThat(password.revealAsString()).isEqualTo(SSH_PASSWORD);
            assertThat(privateKey.revealAsString()).isEqualTo(PRIVATE_KEY);
        }
    }

    @Test
    @DisplayName("重复保存同一 (owner, type) 是覆盖更新，不产生第二行")
    void savingTwiceOverwritesInsteadOfDuplicating() {
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSWORD,
                SecretText.of("first-value-aaa"));
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSWORD,
                SecretText.of(SSH_PASSWORD));

        // WHY 必须覆盖：唯一索引 ux_credentials_owner 决定了同一宿主同一类型只能有一行；
        // 若服务层用 insert 而非 upsert，第二次保存会抛约束冲突，用户将无法更新密码。
        assertThat(countRows("host", hostId, "ssh_password")).isEqualTo(1);
        try (SecretText restored = store.find(CredentialOwnerType.HOST, hostId,
                CredentialType.SSH_PASSWORD).orElseThrow()) {
            assertThat(restored.revealAsString()).isEqualTo(SSH_PASSWORD);
        }
    }

    @Test
    @DisplayName("查询不存在的凭据返回 empty，而非抛异常或返回明文空串")
    void findingAbsentCredentialYieldsEmpty() {
        Optional<SecretText> found = store.find(CredentialOwnerType.HOST,
                "no-such-host", CredentialType.SSH_PASSWORD);

        assertThat(found).isEmpty();
        assertThat(store.exists(CredentialOwnerType.HOST, "no-such-host",
                CredentialType.SSH_PASSWORD)).isFalse();
    }

    @Test
    @DisplayName("api key 以 owner_type=model_config 密文保存（spec：API Key 不硬编码）")
    void llmApiKeyIsStoredEncryptedUnderModelConfig() {
        store.save(CredentialOwnerType.MODEL_CONFIG, modelConfigId,
                CredentialType.LLM_API_KEY, SecretText.of(LLM_API_KEY));

        assertThat(selectCiphertext("model_config", modelConfigId, "llm_api_key"))
                .doesNotContain(LLM_API_KEY);
        try (SecretText apiKey = store.requireLlmApiKey(modelConfigId)) {
            assertThat(apiKey.revealAsString()).isEqualTo(LLM_API_KEY);
        }
    }

    @Test
    @DisplayName("未配置 api key 时提示「请先在设置中配置模型 api key」")
    void missingLlmApiKeyProducesSpecifiedPrompt() {
        store.delete(CredentialOwnerType.MODEL_CONFIG, "never-configured", CredentialType.LLM_API_KEY);

        // spec R3 Scenario「未配置 api key 即使用 AI」：MUST NOT 退回任何内置默认值
        assertThatThrownBy(() -> store.requireLlmApiKey("never-configured"))
                .isInstanceOf(MissingModelApiKeyException.class)
                .hasMessageContaining("请先在设置中配置模型 api key");
    }

    @Test
    @DisplayName("按宿主删除可清理其全部凭据")
    void deleteByOwnerRemovesAllItsCredentials() {
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSWORD,
                SecretText.of(SSH_PASSWORD));
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSPHRASE,
                SecretText.of(SSH_PASSPHRASE));

        int deleted = store.deleteByOwner(CredentialOwnerType.HOST, hostId);

        // 断言精确等于 2 而非"至少 2"：多删意味着 deleteByOwner 的范围条件写错了
        // （例如漏掉 owner_type 限定，会连带删掉模型配置里的 api key）。
        assertThat(deleted).as("应只删掉本用例写入的两条凭据").isEqualTo(2);
        assertThat(store.exists(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSWORD)).isFalse();
        assertThat(store.exists(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSPHRASE)).isFalse();
        assertThat(countRows("host", hostId, "ssh_password")).isZero();
    }

    @Test
    @DisplayName("保存的凭据带自动填充的时间戳，可支撑审计")
    void savedCredentialCarriesAuditTimestamps() {
        store.save(CredentialOwnerType.HOST, hostId, CredentialType.SSH_PASSWORD,
                SecretText.of(SSH_PASSWORD));

        Credential row = credentialMapper.selectOne(new QueryWrapper<Credential>()
                .eq("owner_type", "host")
                .eq("owner_id", hostId)
                .eq("credential_type", "ssh_password"));

        assertThat(row).isNotNull();
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getUpdatedAt()).isNotNull();
        // toString 不得输出完整密文，避免密文进日志
        assertThat(row.toString()).contains("ciphertext=<len=").doesNotContain(row.getCiphertext());
    }

    // ======================================================================
    // 辅助：一律走原生 JDBC，避免"用被测代码验证被测代码"
    // ======================================================================

    /**
     * 取出指定凭据的密文列。
     *
     * <p>WHY 用 {@link PreparedStatement} 占位符而非 {@code String.formatted} 拼 SQL：
     * 拼串版本曾因为 {@code "A" + "B".formatted(x, y, z)} 的**运算符优先级**
     * （方法调用高于 {@code +}）只对最后一段字面量生效，导致前两个 {@code %s}
     * 原样留在 SQL 里、查询恒返回 0 行。占位符从根上消除了这一类错误，
     * 也顺带避免了值里含单引号时的语法问题。</p>
     */
    private String selectCiphertext(String ownerType, String ownerId, String credentialType) {
        String sql = "SELECT ciphertext FROM credentials "
                + "WHERE owner_type = ? AND owner_id = ? AND credential_type = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerType);
            statement.setString(2, ownerId);
            statement.setString(3, credentialType);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next())
                        .as("应存在凭据行: owner_type=%s, owner_id=%s, credential_type=%s",
                                ownerType, ownerId, credentialType)
                        .isTrue();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int countRows(String ownerType, String ownerId, String credentialType) {
        String sql = "SELECT COUNT(*) FROM credentials "
                + "WHERE owner_type = ? AND owner_id = ? AND credential_type = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerType);
            statement.setString(2, ownerId);
            statement.setString(3, credentialType);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> allColumnValuesOf(String table) {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table);
             ResultSet rs = statement.executeQuery()) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= columns; i++) {
                    String value = rs.getString(i);
                    if (value != null) {
                        values.add(value);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return values;
    }

    /**
     * 把 data.db 及其 -wal / -shm 旁文件全部读成字符串。
     *
     * <p>WHY 用 ISO-8859-1 解码：它是**字节保真**的（每个字节映射到一个 char），
     * 用 UTF-8 解码会因非法字节序列被替换成 U+FFFD，从而可能"洗掉"要搜索的明文。</p>
     */
    private String readAllDatabaseFilesAsString() throws IOException {
        Path database = databaseFile();
        StringBuilder all = new StringBuilder();
        for (Path candidate : List.of(database,
                database.resolveSibling(database.getFileName() + "-wal"),
                database.resolveSibling(database.getFileName() + "-shm"))) {
            if (Files.exists(candidate)) {
                all.append(new String(Files.readAllBytes(candidate), StandardCharsets.ISO_8859_1));
            }
        }
        assertThat(all.length()).as("至少应读到主库文件").isPositive();
        return all.toString();
    }
}

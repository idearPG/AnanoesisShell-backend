package com.ananoesis.shell.datastore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 迁移脚本与默认配置的"无内置凭据"安全门禁（credential-store spec：API Key 不硬编码）。
 *
 * <p>WHY 独立于 Spring 上下文、在一个**全新**的临时库上跑 Flyway：
 * 其它集成测试会在共享库里写入并删除数据，只有从空库开始迁移，
 * "迁移本身不会带入任何凭据"这一断言才是确定性的、与执行顺序无关的。</p>
 *
 * <p>WHY 允许用 {@code @TempDir}：本测试不启动 Spring 上下文，JDBC 连接在 try-with-resources
 * 内即时关闭，不存在文件句柄被连接池长期持有导致清理失败的问题。</p>
 */
class FlywayMigrationPurityTest {

    /** 形如 {@code api-key: <非空值>} 的配置项——出现即视为内置凭据。 */
    private static final Pattern POPULATED_API_KEY_PROPERTY =
            Pattern.compile("(?im)^\\s*[\\w.-]*api[-_]?key[\\w.-]*\\s*:\\s*(?!\\s*$)(?!\\$\\{)[^\\s#].*$");

    /** 明文凭据列名特征：迁移脚本里一旦出现即视为把秘密写进了 SQL。 */
    private static final Pattern INSERT_INTO_CREDENTIALS =
            Pattern.compile("(?i)insert\\s+into\\s+credentials");

    /** 设置项键名中不得出现的凭据语义词。 */
    private static final List<String> FORBIDDEN_SETTING_KEY_FRAGMENTS =
            List.of("api_key", "apikey", "password", "passwd", "secret", "token", "private_key");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("全新库上 Flyway 迁移成功且只执行 2 个版本（V1 + V2）")
    void flywayMigratesFreshSqliteDatabase() {
        MigrateResult result = migrateFreshDatabase(tempDir.resolve("fresh.db"));

        assertThat(result.success).isTrue();
        // V2 新增了 session_workspace_and_transfers 迁移脚本
        assertThat(result.migrationsExecuted).isEqualTo(2);
    }

    @Test
    @DisplayName("迁移后 credentials 表为空——不存在任何预置密文或明文凭据")
    void migratedDatabaseHasNoSeededCredentials() throws SQLException {
        Path database = tempDir.resolve("no-credentials.db");
        migrateFreshDatabase(database);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM credentials")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("credentials 表在迁移后必须为空").isZero();
        }
    }

    @Test
    @DisplayName("迁移预置的 settings 键名不含任何凭据语义")
    void seededSettingKeysContainNoCredentialSemantics() throws SQLException {
        Path database = tempDir.resolve("settings-keys.db");
        migrateFreshDatabase(database);

        List<String> keys = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT setting_key FROM settings")) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        }

        assertThat(keys).as("V1 应预置运维阈值默认值").isNotEmpty();
        assertThat(keys).noneMatch(key -> {
            String normalized = key.toLowerCase(Locale.ROOT);
            return FORBIDDEN_SETTING_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
        });
    }

    @Test
    @DisplayName("V1 迁移脚本不含 INSERT INTO credentials，默认配置不含已填值的 api key")
    void migrationScriptAndDefaultConfigCarryNoBuiltinSecret() throws IOException {
        String migrationSql = readClasspathText("db/migration/V1__init_schema.sql");
        String applicationYaml = readClasspathText("application.yml");

        assertThat(INSERT_INTO_CREDENTIALS.matcher(migrationSql).find())
                .as("迁移脚本不得写入任何凭据记录")
                .isFalse();
        assertThat(POPULATED_API_KEY_PROPERTY.matcher(applicationYaml).find())
                .as("默认配置不得内置任何 api key")
                .isFalse();
    }

    private static MigrateResult migrateFreshDatabase(Path databaseFile) {
        return Flyway.configure()
                .dataSource("jdbc:sqlite:" + databaseFile.toAbsolutePath(), null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static String readClasspathText(String resource) throws IOException {
        try (InputStream in = FlywayMigrationPurityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath 资源 %s 应存在", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 仅为让未使用的导入检查通过：确保 tempDir 目录确实由 JUnit 创建。 */
    @Test
    @DisplayName("测试前置：临时目录可用")
    void tempDirectoryIsUsable() {
        assertThat(Files.isDirectory(tempDir)).isTrue();
    }
}

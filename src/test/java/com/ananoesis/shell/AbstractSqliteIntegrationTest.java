package com.ananoesis.shell;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ananoesis.shell.security.TestSecurityConfiguration;

/**
 * SQLite 集成测试基类：把用户数据目录重定向到 {@code target/test-data/} 下的一次性目录，
 * 并把凭据主密钥替换为确定性的内存提供者。
 *
 * <p>WHY 不用 JUnit {@code @TempDir}：Spring 的上下文缓存会把 ApplicationContext
 * （含 HikariCP 连接池）保留到 JVM 结束，Windows 下被打开的 SQLite 文件句柄不会随测试类
 * 结束而释放，{@code @TempDir} 的 afterAll 清理会因文件占用而失败。放在 {@code target/} 下
 * 交由 {@code mvn clean} 回收，既隔离又不产生清理竞态。</p>
 *
 * <p>WHY 整个测试 JVM 共用一个目录、且基类固定 {@code webEnvironment=RANDOM_PORT}：
 * Spring 按「配置指纹」缓存上下文，所有子类配置一致时只会启动**一个**上下文、
 * 一个连接池，既显著加快测试，也避免多个上下文同时持有同一个 SQLite 文件带来的写争用。
 * 也正因如此，{@code @Import} 必须放在**基类**上：任何子类单独添加都会产生第二个上下文指纹。</p>
 *
 * <p>WHY 目录名带启动时间戳：保证每次 {@code mvn test} 都从空库开始，
 * 使 Flyway 迁移、WAL 建库等断言不会被上一次运行的残留数据"作弊"通过。</p>
 *
 * <p>WHY 关闭真实 OS 密钥库：集成测试若在开发者机器上真的往 Windows Credential Store /
 * macOS Keychain 写入条目，会留下测试残留，并在无头 CI 上直接失败。
 * 关闭开关后由 {@link TestSecurityConfiguration} 提供内存主密钥，
 * 真实后端的连通性另由 {@code JavaKeyringOsKeyringTest} 以假设门控方式验证。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfiguration.class)
public abstract class AbstractSqliteIntegrationTest {

    /** 本次测试运行独享的数据目录，对应生产环境的 {@code ${user.home}/.ananoesis}。 */
    protected static final Path DATA_DIR = newRunScopedDataDir();

    /** SQLite 数据库文件名，与 {@code ananoesis.storage.database-file-name} 默认值一致。 */
    protected static final String DATABASE_FILE_NAME = "data.db";

    /**
     * 覆盖存储目录与密钥库开关，确保被测应用绝不会读写开发者真实的
     * {@code ~/.ananoesis} 或系统密钥库。
     */
    @DynamicPropertySource
    static void isolateFromDeveloperEnvironment(DynamicPropertyRegistry registry) {
        registry.add("ananoesis.storage.data-dir", () -> DATA_DIR.toString());
        registry.add("ananoesis.security.os-keyring.enabled", () -> "false");
    }

    /** @return 本次运行专属的数据目录下的 SQLite 文件路径 */
    protected static Path databaseFile() {
        return DATA_DIR.resolve(DATABASE_FILE_NAME);
    }

    private static Path newRunScopedDataDir() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        try {
            Path dir = Paths.get("target", "test-data", "run-" + stamp).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            // 测试前置条件失败必须显式暴露，禁止吞异常后继续跑出误导性结果
            throw new UncheckedIOException("无法创建测试数据目录", e);
        }
    }
}

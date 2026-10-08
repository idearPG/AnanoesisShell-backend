package com.ananoesis.shell.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 把 SSH 运行时参数的<b>三个来源</b>钉在一起：Java 字段默认值、{@code application.yml}、
 * 以及迁移落库后的 {@code settings} 有效值（V1 种子被 V3 UPDATE 覆盖后的值）。
 *
 * <p>WHY 会有三个来源（这是 design.md D6 遗留的已知漂移风险）：
 * 运行期真正生效的是 {@code application.yml}；{@code SshProperties} 的字段默认值是
 * "yml 里漏写某项"时的兜底；而 {@code settings} 表里的行是将来"设置界面"要展示与覆盖的值
 * （settings 的 REST 管理属后续 Wave）。三份值现在语义重叠、却各写各的，
 * 任何一处单独改动都不会让编译或测试失败——症状是"用户在设置界面看到 15 秒、
 * 实际生效的是 30 秒"，这类缺陷几乎不可能靠肉眼发现。</p>
 *
 * <p>WHY 种子行要叠加 V3：已发布的 V1 不回写（校验和不变），后补的 V3 用 UPDATE 把
 * {@code run_command.timeout.seconds} 提升到 1800（ssh-connection spec）；全新库迁移后
 * {@code settings} 表里可见的<b>有效值</b>因此是「V1 种子被 V3 覆盖后的值」——
 * 解析时漏掉 V3 会把旧值 60 当成期望值反向钉死，漂移又多了一层 camouflage。</p>
 *
 * <p>WHY 用 Spring 自己的 {@link Binder} 解析 yml，而不是自己把 {@code "15s"} 转成
 * {@link Duration}：自己实现一遍时长换算，等于用第二份逻辑验证第一份逻辑，
 * 两份同时写错时测试照样绿。走 {@code YamlPropertySourceLoader} + {@code Binder}
 * 得到的就是应用启动后<b>真正注入</b>的那个对象。</p>
 *
 * <p>WHY 不启动 Spring 上下文：本测试比对的是"配置文件里写了什么"，
 * 与 bean 装配无关。不启动上下文使它保持在毫秒级，也避免与集成测试争用 SQLite 文件。</p>
 */
class SshPropertiesDefaultsTest {

    /** settings 种子行的形状：{@code ('key', 'value', 'type',}。 */
    private static final Pattern SETTINGS_ROW =
            Pattern.compile("^\\s*\\('([^']+)',\\s*'([^']+)',\\s*'([^']+)',", Pattern.MULTILINE);

    /** UPDATE 迁移的形状：{@code setting_value = '...' ... WHERE setting_key = '...'}。 */
    private static final Pattern SETTINGS_UPDATE =
            Pattern.compile("setting_value\\s*=\\s*'([^']+)'[\\s\\S]*?setting_key\\s*=\\s*'([^']+)'");

    /** 迁移里与 SshProperties 语义重叠的三个 settings 键。 */
    private static final String KEY_CONNECT_TIMEOUT = "ssh.connect.timeout.seconds";
    private static final String KEY_RUN_COMMAND_TIMEOUT = "run_command.timeout.seconds";
    private static final String KEY_MAX_OUTPUT_BYTES = "run_command.max_output_bytes";

    private static Map<String, String> settingsSeed;

    @BeforeAll
    static void loadSettingsSeed() throws IOException {
        Map<String, String> seed = parseSettingsSeed(readClasspath("db/migration/V1__init_schema.sql"));
        // WHY 叠加 V3 的 UPDATE：已发布的 V1 不回写，后补迁移才是当前有效值；
        // 全新库迁移后 settings 表的取值 = V1 种子被 V3 覆盖后的值
        applyUpdateOverrides(seed, readClasspath("db/migration/V3__update_run_command_timeout.sql"));
        settingsSeed = seed;
        // WHY 先断言解析结果非空：若某天迁移文件的书写格式变了、正则匹配不到任何行，
        // 后面所有比对都会在"空 map"上进行——取值全为 null，断言全部退化
        assertThat(settingsSeed)
                .as("必须从 V1 + V3 迁移里解析出 settings 有效值")
                .containsKeys(KEY_CONNECT_TIMEOUT, KEY_RUN_COMMAND_TIMEOUT, KEY_MAX_OUTPUT_BYTES);
    }

    // ======================================================================
    // Java 字段默认值 ↔ settings 有效值
    // ======================================================================

    @Test
    @DisplayName("SshProperties 的字段默认值 == 迁移落库后的 settings 有效值")
    void javaDefaultsMatchSettingsSeed() {
        SshProperties defaults = new SshProperties();

        assertThat(defaults.getConnectTimeout())
                .as("connectTimeout 应与 %s 一致", KEY_CONNECT_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(seedSeconds(KEY_CONNECT_TIMEOUT)));
        assertThat(defaults.getExecTimeout())
                .as("execTimeout 应与 %s 一致", KEY_RUN_COMMAND_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(seedSeconds(KEY_RUN_COMMAND_TIMEOUT)));
        assertThat(defaults.getExecMaxOutputBytes())
                .as("execMaxOutputBytes 应与 %s 一致", KEY_MAX_OUTPUT_BYTES)
                .isEqualTo(seedInt(KEY_MAX_OUTPUT_BYTES));
    }

    // ======================================================================
    // application.yml ↔ settings 有效值
    // ======================================================================

    @Test
    @DisplayName("application.yml 里 ananoesis.ssh.* 的生效值 == 迁移落库后的 settings 有效值")
    void applicationYamlMatchesSettingsSeed() {
        SshProperties bound = bindFromApplicationYaml();

        assertThat(bound.getConnectTimeout())
                .as("yml 的 connect-timeout 应与 %s 一致", KEY_CONNECT_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(seedSeconds(KEY_CONNECT_TIMEOUT)));
        assertThat(bound.getExecTimeout())
                .as("yml 的 exec-timeout 应与 %s 一致", KEY_RUN_COMMAND_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(seedSeconds(KEY_RUN_COMMAND_TIMEOUT)));
        assertThat(bound.getExecMaxOutputBytes())
                .as("yml 的 exec-max-output-bytes 应与 %s 一致", KEY_MAX_OUTPUT_BYTES)
                .isEqualTo(seedInt(KEY_MAX_OUTPUT_BYTES));
    }

    @Test
    @DisplayName("application.yml 与 Java 字段默认值一致（yml 没有悄悄改动兜底语义）")
    void applicationYamlMatchesJavaDefaults() {
        SshProperties bound = bindFromApplicationYaml();
        SshProperties defaults = new SshProperties();

        // WHY 逐项列出而不是反射比对所有字段：反射会把 ptyTerm 这类只有一个来源的字段
        // 也纳入，那些字段没有"第二份值"可比，写进断言只会产生噪声。
        // 这里只比对确实存在两份定义的字段。
        assertThat(bound.getConnectTimeout()).isEqualTo(defaults.getConnectTimeout());
        assertThat(bound.getExecTimeout()).isEqualTo(defaults.getExecTimeout());
        assertThat(bound.getExecMaxOutputBytes()).isEqualTo(defaults.getExecMaxOutputBytes());
        assertThat(bound.getTerminalIdleTimeout()).isEqualTo(defaults.getTerminalIdleTimeout());
        assertThat(bound.getTerminalIdleCheckInterval()).isEqualTo(defaults.getTerminalIdleCheckInterval());
        assertThat(bound.getPtyTerm()).isEqualTo(defaults.getPtyTerm());
        assertThat(bound.getPtyColumns()).isEqualTo(defaults.getPtyColumns());
        assertThat(bound.getPtyRows()).isEqualTo(defaults.getPtyRows());
        assertThat(bound.getKeepAliveInterval()).isEqualTo(defaults.getKeepAliveInterval());
    }

    // ======================================================================
    // 合理性下限（防"为了让两边相等而把超时调到 0"）
    // ======================================================================

    @Test
    @DisplayName("超时与上限都是正数：把两边同时改成 0 也能相等，故单独钉住下限")
    void timeoutsAndLimitsArePositive() {
        SshProperties bound = bindFromApplicationYaml();

        assertThat(bound.getConnectTimeout()).isPositive();
        assertThat(bound.getExecTimeout()).isPositive();
        assertThat(bound.getTerminalIdleTimeout()).isPositive();
        assertThat(bound.getTerminalIdleCheckInterval()).isPositive();
        assertThat(bound.getKeepAliveInterval()).isPositive();
        assertThat(bound.getExecMaxOutputBytes()).isPositive();
        assertThat(bound.getPtyColumns()).isPositive();
        assertThat(bound.getPtyRows()).isPositive();

        // WHY exec 超时不得短于连接超时：命令是在连上之后才开始计时的，
        // 若 execTimeout < connectTimeout，一条需要等待慢速握手的命令会在
        // "还没连上"时就被判超时，报出与事实不符的「执行超时」
        assertThat(bound.getExecTimeout())
                .as("execTimeout 应不短于 connectTimeout")
                .isGreaterThanOrEqualTo(bound.getConnectTimeout());
    }

    // ======================================================================
    // 解析辅助
    // ======================================================================

    /**
     * 用 Spring Boot 自己的加载器与绑定器还原 {@code ananoesis.ssh} 的生效值。
     *
     * <p>WHY 造一个只含 application.yml 的 {@link StandardEnvironment}：
     * 直接 {@code Binder.get(真实环境)} 会掺入系统属性、环境变量与测试用的
     * {@code @DynamicPropertySource} 覆盖值，那时测到的就不是"配置文件里写了什么"。</p>
     */
    private static SshProperties bindFromApplicationYaml() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            assertThat(sources).as("application.yml 应能被解析为属性源").isNotEmpty();

            StandardEnvironment environment = new StandardEnvironment();
            // WHY 先移除自带的 systemProperties/systemEnvironment：否则 ${user.home} 之外的
            // 任何同名系统属性都可能悄悄覆盖 yml 的值，让断言通过在一个并不成立的前提上
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.forEach(source -> environment.getPropertySources().addLast(source));

            return Binder.get(environment)
                    .bind("ananoesis.ssh", SshProperties.class)
                    .orElseThrow(() -> new AssertionError("application.yml 里没有 ananoesis.ssh 配置段"));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 application.yml", e);
        }
    }

    private static Map<String, String> parseSettingsSeed(String sql) {
        Map<String, String> seed = new HashMap<>();
        Matcher matcher = SETTINGS_ROW.matcher(sql);
        while (matcher.find()) {
            seed.put(matcher.group(1), matcher.group(2));
        }
        return seed;
    }

    /**
     * 把 UPDATE 型迁移叠加到种子 map 上：后写的迁移才是当前有效值。
     * WHY 与 Flyway 的执行顺序一致：V1 先 INSERT，V3 再 UPDATE 覆盖同键。
     */
    private static void applyUpdateOverrides(Map<String, String> seed, String updateSql) {
        Matcher matcher = SETTINGS_UPDATE.matcher(updateSql);
        while (matcher.find()) {
            seed.put(matcher.group(2), matcher.group(1));
        }
    }

    private static long seedSeconds(String key) {
        return Long.parseLong(seedValue(key));
    }

    private static int seedInt(String key) {
        return Integer.parseInt(seedValue(key));
    }

    private static String seedValue(String key) {
        String value = settingsSeed.get(key);
        assertThat(value).as("settings 有效值里应有 %s", key).isNotNull();
        return value;
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            // WHY 显式 UTF-8：迁移脚本里含大量中文注释，用平台默认编码在
            // 非 UTF-8 的区域设置下会读成乱码（正则仍能匹配 ASCII 部分，
            // 但断言消息会变成不可读的噪音）
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}

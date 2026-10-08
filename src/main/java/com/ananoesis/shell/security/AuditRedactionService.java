package com.ananoesis.shell.security;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.entity.CommandExecution;
import com.ananoesis.shell.mapper.CommandExecutionMapper;

/**
 * 审计脱敏服务（tasks 6.7）——查询最终命令/版本/目标快照及敏感值遮蔽。
 *
 * <h2>design.md D5 约束</h2>
 * <ul>
 *   <li>新增记录复用凭据保护与脱敏入口</li>
 *   <li>不得记录控制 token、SSH 登录交互或完整私钥</li>
 *   <li>手工命令/输出是敏感运维数据，剔除终端控制帧与已知秘密</li>
 * </ul>
 *
 * <h2>WHY 正则替换而非完整解析</h2>
 * <p>命令行参数的语法因工具而异（{@code -p password}、{@code --token=xxx}、
 * {@code key=value} 等），完整解析需要为每个工具写一个解析器。
 * 正则替换覆盖最常见的几种模式（密码参数、Bearer token、API key），
 * 漏网的敏感信息不会造成灾难性后果（审计日志只在本地 SQLite 中，
 * 不上传、不外传），但能挡住绝大多数"用户随手在命令行里带密码"的场景。</p>
 *
 * <h2>安全边界</h2>
 * <p>本类是脱敏的<b>唯一入口</b>。所有面向前端展示的审计查询都必须经过这里，
 * 不能绕过脱敏直接返回原始命令/输出。日志同样只打脱敏后的文本。</p>
 */
@Service
public class AuditRedactionService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditRedactionService.class);

    /** 脱敏替换文本。 */
    private static final String REDACTED = "****";

    // ==================================================================
    // 脱敏模式（按优先级排列）
    // ==================================================================

    /**
     * {@code -pPASSWORD} 或 {@code --password=PASSWORD} 形式的密码参数。
     *
     * <p>WHY 两组分别捕获前缀与值：
     * group 1 = {@code -p}（前缀），group 2 = 密码值；
     * group 3 = {@code --password=}（前缀），group 4 = 密码值。
     * {@link #redactPattern} 按前缀组号找到非 null 的前缀，只替换密码值部分。</p>
     */
    private static final Pattern PASSWORD_FLAG = Pattern.compile(
            "(?i)(-p)(?=\\S)(\\S+)|(--password=)(\\S+)");

    /**
     * {@code Authorization: Bearer TOKEN} 形式的认证头。
     * WHY 匹配 Bearer 后的非空白/非引号字符：curl 等工具的认证头
     * 可能被单引号或双引号包裹，也可能没有。
     */
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(Bearer\\s+)[^\\s'\"]+");

    /**
     * {@code key=APIKEY} 或 {@code api_key=APIKEY} 形式的 API 密钥参数。
     *
     * <p>WHY 包含 {@code key}：URL 查询参数最常见的就是 {@code key=xxx}，
     * 必须覆盖。同时覆盖 {@code api_key}、{@code token}、{@code secret}、
     * {@code access_token}、{@code apikey} 等常见变体。</p>
     */
    private static final Pattern API_KEY_PARAM = Pattern.compile(
            "(?i)((?:api[_-]?key|key|token|secret|access[_-]?token|apikey)=)[^\\s&'\"]+");

    /**
     * {@code Password: XXX} 形式的输出中的密码行。
     * WHY 覆盖命令输出中可能出现的密码提示回显。
     */
    private static final Pattern PASSWORD_IN_OUTPUT = Pattern.compile(
            "(?i)((?:password|passwd|pwd)\\s*[:=]\\s*)\\S+");

    private final CommandExecutionMapper executionMapper;

    public AuditRedactionService(CommandExecutionMapper executionMapper) {
        this.executionMapper = Objects.requireNonNull(executionMapper);
    }

    // ==================================================================
    // 脱敏
    // ==================================================================

    /**
     * 脱敏命令原文。
     *
     * <p>WHY null/空输入返回空串而非 null：调用方（审计查询）统一用空串展示，
     * 不需要额外判空。</p>
     *
     * @param command 原始命令
     * @return 脱敏后的命令
     */
    public String redactCommand(@Nullable String command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        String result = command;
        // WHY PASSWORD_FLAG 有两组前缀（1 和 3）：两个分支分别对应 -p 和 --password= 形式
        result = redactPattern(result, PASSWORD_FLAG, 1, 3);
        result = redactPattern(result, BEARER_TOKEN, 1);
        result = redactPattern(result, API_KEY_PARAM, 1);
        return result;
    }

    /**
     * 脱敏命令输出。
     *
     * @param output 原始输出
     * @return 脱敏后的输出
     */
    public String redactOutput(@Nullable String output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        String result = output;
        result = redactPattern(result, PASSWORD_IN_OUTPUT, 1);
        result = redactPattern(result, BEARER_TOKEN, 1);
        result = redactPattern(result, API_KEY_PARAM, 1);
        return result;
    }

    // ==================================================================
    // 审计快照
    // ==================================================================

    /**
     * 一次执行记录的审计快照。
     *
     * @param command         原始命令（未脱敏）
     * @param redactedCommand 脱敏后的命令
     * @param status          执行状态
     * @param exitCode        退出码
     */
    public record ExecutionSnapshot(
            String command,
            String redactedCommand,
            String status,
            @Nullable Integer exitCode) {
    }

    /**
     * 查询一条执行记录的审计快照（含脱敏）。
     *
     * <p>WHY 返回快照而非直接返回实体：实体含原始命令与输出，
     * 直接暴露给前端会造成敏感信息泄漏。快照在构造时就完成脱敏，
     * 调用方拿到的已经是安全数据。</p>
     *
     * @param executionId 执行记录 id
     * @return 审计快照；不存在时为 null
     */
    @Nullable
    public ExecutionSnapshot snapshotOf(String executionId) {
        if (executionId == null) {
            return null;
        }
        CommandExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            return null;
        }
        String command = execution.getCommand() == null ? "" : execution.getCommand();
        return new ExecutionSnapshot(
                command,
                redactCommand(command),
                execution.getClaimStatus(),
                execution.getExitCode());
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 用正则替换敏感值。
     *
     * <p>WHY 支持多个前缀组号：{@link #PASSWORD_FLAG} 有两个分支（{@code -p} 和
     * {@code --password=}），各自的前缀在不同的捕获组里。传入所有可能的前缀组号，
     * 方法内部取第一个非 null 的作为前缀。</p>
     *
     * <p>WHY 保留前缀（group 1）只替换后续部分：
     * 例如 {@code -pMyPassword} → {@code -p****}，而不是整个匹配都变成 {@code ****}。
     * 保留前缀让脱敏后的命令仍然可读、可调试——用户能看到"这里有个密码参数"，
     * 但看不到密码本身。</p>
     */
    private static String redactPattern(String input, Pattern pattern, int... prefixGroups) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String prefix = findPrefix(matcher, prefixGroups);
            matcher.appendReplacement(result, Matcher.quoteReplacement(prefix + REDACTED));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 从多个候选捕获组中找到第一个非 null 的前缀。
     *
     * <p>WHY 需要遍历：正则的分支（{@code |}）导致每次匹配只有一个分支参与，
     * 其他分支的捕获组为 null。遍历所有前缀组号，取命中的那个。</p>
     */
    private static String findPrefix(Matcher matcher, int[] prefixGroups) {
        for (int group : prefixGroups) {
            String value = matcher.group(group);
            if (value != null) {
                return value;
            }
        }
        return "";
    }
}

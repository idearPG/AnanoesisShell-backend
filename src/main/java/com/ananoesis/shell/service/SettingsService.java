package com.ananoesis.shell.service;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ananoesis.shell.contract.model.Settings;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.entity.Setting;
import com.ananoesis.shell.mapper.SettingMapper;

/**
 * {@code settings} 表的业务读写（tasks 7.1 的设置侧）。
 *
 * <h2>为什么契约的 Settings 只有一个字段，而本类却管着五个</h2>
 * <p>冻结契约（{@code openapi.yaml}）的 {@code Settings} schema 只声明了
 * {@code default_thinking_mode}；其 description 明确写着"审批等待时限/执行超时/输出上限等
 * 是否归属设置接口 spec 未明确"。TRACEABILITY 第 5 节 <b>Q5</b> 的裁定是：这些阈值
 * <b>走 settings 键值</b>，并由 {@code approval_request.timeout_seconds} 把<b>实值</b>
 * 下发给前端。</p>
 *
 * <p>因此阈值的读写收口在本类，但<b>不</b>经 REST 暴露——擅自给 {@code Settings} DTO
 * 加字段就是破坏冻结契约（生成物由契约驱动，加了也编译不过）。前端需要的数字，
 * 从审批帧里拿，那才是"本次审批实际用的值"，而不是"设置页上写着的值"。</p>
 *
 * <h2>WHY 读不到/读坏了都退回内置默认，而不是抛异常</h2>
 * <p>这些键由 V1 迁移种子化，正常情况下一定存在。但 {@code settings} 是一张用户可以
 * 直接编辑的键值表（阶段 2 的设置界面、乃至手工排障），一旦出现空行、拼错的数字或 0，
 * 让整条 AI 链路 500 是最坏的处置：用户既看不到原因，也无法从界面上自救。
 * 退回默认值并记一条 WARN，既保住了可用性，又在日志里留下了线索。</p>
 *
 * <p>特别地，<b>0 或负数必须视为非法</b>：{@code approval.timeout.seconds=0} 会让每一次
 * 审批在挂起的同一瞬间超时，用户看到的现象是"弹框一闪而过、命令永远被取消"，
 * 而这种"配置合法但语义致命"的坑不会有任何异常提示。</p>
 *
 * <h2>安全边界</h2>
 * <p>本类只处理<b>非敏感</b>的行为开关与阈值（credential-store spec / {@code Setting} 实体注释）。
 * api key 一律走 {@link CredentialStoreService} 密文保存，MUST NOT 出现在本类的任何读写路径上。</p>
 */
@Service
public class SettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    // ======================================================================
    // 设置键（与 V1__init_schema.sql 的种子行一一对应）
    // ======================================================================

    /** 全局默认思考模式；库里是 {@code boolean}，契约侧是 {@link ThinkingMode} 枚举。 */
    public static final String KEY_DEFAULT_THINKING_MODE = "model.default_thinking_mode";
    /** 审批等待时限（秒）；超时自动按"取消"处理并释放挂起资源。 */
    public static final String KEY_APPROVAL_TIMEOUT_SECONDS = "approval.timeout.seconds";
    /** 经审批执行的命令超时（秒）。 */
    public static final String KEY_RUN_COMMAND_TIMEOUT_SECONDS = "run_command.timeout.seconds";
    /** 命令输出长度上限（字节）；超限截断并标注"输出已截断"。 */
    public static final String KEY_RUN_COMMAND_MAX_OUTPUT_BYTES = "run_command.max_output_bytes";
    /** {@code read_file} 工具单次读取行数上限。 */
    public static final String KEY_READ_FILE_MAX_LINES = "read_file.max_lines";

    // ======================================================================
    // 内置默认（与 V1 种子值逐字一致；库不可用时才生效）
    // ======================================================================

    public static final boolean DEFAULT_THINKING_MODE_ENABLED = false;
    public static final int DEFAULT_APPROVAL_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS = 1800;
    public static final int DEFAULT_RUN_COMMAND_MAX_OUTPUT_BYTES = 65536;
    public static final int DEFAULT_READ_FILE_MAX_LINES = 500;

    private final SettingMapper settingMapper;

    public SettingsService(SettingMapper settingMapper) {
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper 不得为 null");
    }

    // ======================================================================
    // 契约暴露面：全局默认思考模式
    // ======================================================================

    /**
     * @return 契约形态的全局设置
     */
    public Settings toContract() {
        return new Settings(defaultThinkingMode());
    }

    /**
     * 更新全局设置。
     *
     * @return 更新后的设置（回显给客户端，使其无需再查一次）
     */
    @Transactional
    public Settings update(Settings request) {
        Objects.requireNonNull(request, "request 不得为 null");
        ThinkingMode mode = request.getDefaultThinkingMode();
        if (mode == null) {
            // Bean Validation 已在 REST 边界拦住 null；这里防的是服务层被其它代码直接调用
            throw new InvalidRequestException("default_thinking_mode", "默认思考模式不得为空");
        }
        writeBoolean(KEY_DEFAULT_THINKING_MODE, mode == ThinkingMode.THINKING, "boolean", null);
        LOG.info("已更新全局默认思考模式: mode={}", mode.getValue());
        return toContract();
    }

    /**
     * 全局默认思考模式。
     *
     * <p>WHY 库里存布尔而契约是枚举：V1 迁移（已冻结）把它种子化为
     * {@code value_type='boolean'} 的 {@code 'false'}，而契约的 {@code ThinkingMode}
     * 是 {@code thinking}/{@code non_thinking}。二者是同一个开关的两种表述，
     * 换算必须只有一处——就是本方法，否则"设置页显示思考模式、实际按非思考发请求"
     * 这类不一致会散落各处且无从定位。</p>
     */
    public ThinkingMode defaultThinkingMode() {
        return readBoolean(KEY_DEFAULT_THINKING_MODE, DEFAULT_THINKING_MODE_ENABLED)
                ? ThinkingMode.THINKING
                : ThinkingMode.NON_THINKING;
    }

    // ======================================================================
    // 阈值（TRACEABILITY Q5：实值取自 settings，不经 REST 暴露）
    // ======================================================================

    /** 审批等待时限（秒）。 */
    public int approvalTimeoutSeconds() {
        return readPositiveInt(KEY_APPROVAL_TIMEOUT_SECONDS, DEFAULT_APPROVAL_TIMEOUT_SECONDS);
    }

    /** {@code run_command} 的执行超时（秒）。 */
    public int runCommandTimeoutSeconds() {
        return readPositiveInt(KEY_RUN_COMMAND_TIMEOUT_SECONDS, DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS);
    }

    /** {@code run_command} 的输出字节上限。 */
    public int runCommandMaxOutputBytes() {
        return readPositiveInt(KEY_RUN_COMMAND_MAX_OUTPUT_BYTES, DEFAULT_RUN_COMMAND_MAX_OUTPUT_BYTES);
    }

    /** {@code read_file} 的行数上限。 */
    public int readFileMaxLines() {
        return readPositiveInt(KEY_READ_FILE_MAX_LINES, DEFAULT_READ_FILE_MAX_LINES);
    }

    // ======================================================================
    // 键值通道（供同包的服务层复用；MUST NOT 用于秘密）
    // ======================================================================

    /** @return 某个键的原始文本值；键不存在时为 empty */
    Optional<String> rawValue(String key) {
        Setting row = settingMapper.findByKey(key);
        return row == null ? Optional.empty() : Optional.ofNullable(row.getSettingValue());
    }

    /**
     * 写入（存在则覆盖）一个字符串键。
     *
     * @param description 为 null 时保留原有说明
     */
    void writeString(String key, String value, String valueType, String description) {
        Setting existing = settingMapper.findByKey(key);
        if (existing == null) {
            Setting row = new Setting();
            row.setSettingKey(key);
            row.setSettingValue(value);
            row.setValueType(valueType);
            row.setDescription(description);
            settingMapper.insert(row);
            LOG.debug("已新增设置项: key={} valueType={}", key, valueType);
            return;
        }
        existing.setSettingValue(value);
        existing.setValueType(valueType);
        if (description != null) {
            existing.setDescription(description);
        }
        settingMapper.updateById(existing);
        LOG.debug("已更新设置项: key={} valueType={}", key, valueType);
    }

    /** 删除一个键；用于清理已删除的模型配置。 */
    void deleteKey(String key) {
        settingMapper.deleteById(key);
    }

    // ======================================================================
    // 内部：带容错的读取
    // ======================================================================

    private void writeBoolean(String key, boolean value, String valueType, String description) {
        writeString(key, Boolean.toString(value), valueType, description);
    }

    private boolean readBoolean(String key, boolean fallback) {
        return rawValue(key)
                .map(value -> {
                    String text = value.trim();
                    if (text.equalsIgnoreCase("true") || text.equals("1")) {
                        return true;
                    }
                    if (text.equalsIgnoreCase("false") || text.equals("0")) {
                        return false;
                    }
                    LOG.warn("设置项 {} 的布尔取值无法识别，已退回默认值 {}", key, fallback);
                    return fallback;
                })
                .orElse(fallback);
    }

    private int readPositiveInt(String key, int fallback) {
        return rawValue(key)
                .map(value -> {
                    try {
                        int parsed = Integer.parseInt(value.trim());
                        if (parsed <= 0) {
                            LOG.warn("设置项 {} 必须为正数（实际 {}），已退回默认值 {}", key, parsed, fallback);
                            return fallback;
                        }
                        return parsed;
                    } catch (NumberFormatException e) {
                        LOG.warn("设置项 {} 不是合法整数，已退回默认值 {}", key, fallback);
                        return fallback;
                    }
                })
                .orElse(fallback);
    }
}

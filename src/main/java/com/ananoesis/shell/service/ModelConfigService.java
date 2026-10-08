package com.ananoesis.shell.service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ananoesis.shell.contract.model.ModelConfig;
import com.ananoesis.shell.contract.model.OutputLimitField;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.contract.model.ThinkingRequestFormat;
import com.ananoesis.shell.entity.Setting;
import com.ananoesis.shell.mapper.SettingMapper;
import com.ananoesis.shell.security.CredentialOwnerType;
import com.ananoesis.shell.security.CredentialType;
import com.ananoesis.shell.security.MissingModelApiKeyException;
import com.ananoesis.shell.security.SecretText;
import com.ananoesis.shell.service.InvalidRequestException.FieldViolation;
import com.ananoesis.shell.support.EntityIds;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 模型配置的业务规则与凭据编排（tasks 7.1 的配置侧、7.2 的取值来源）。
 *
 * <h2>WHY 存在 {@code settings} 表里，而不是新建一张 {@code model_configs} 表</h2>
 * <p>Wave 1 的 V1 迁移已冻结，其 {@code settings} 表注释就写明了这个方案：
 * 「模型配置（provider / base_url / model）以 {@code value_type='json'} 存于此表，
 * 其 api key 通过 {@code credentials(owner_type='model_config')} 单独密文保存」。
 * 新增表要改迁移脚本，而迁移脚本一旦发布就不能再改（Flyway 校验和），
 * 补一个 V2 只为放三列数据，代价远大于收益。配置条数是个位数，
 * 全表读进内存排序完全够用。</p>
 *
 * <p>把配置塞进一列 JSON 还带来一个<b>意外的好处</b>：全局的
 * {@code mybatis-plus.update-strategy=not_null} 会静默跳过实体上的 null 字段，
 * 导致"把已设置的 per-config 思考模式清空"变成 no-op（界面提交成功、刷新后旧值又回来）。
 * 而 JSON 载荷每次都是<b>整体重写</b>，null 自然表现为"键不存在"，清空语义天然正确。</p>
 *
 * <h2>安全边界（credential-store spec）</h2>
 * <ul>
 *   <li>api key 一律经 {@link CredentialStoreService} 密文保存，MUST NOT 进入 JSON 载荷。</li>
 *   <li>契约 DTO {@code ModelConfig} 的 {@code api_key} 字段由生成器产出，
 *       <b>没有</b> {@code @JsonProperty(access = WRITE_ONLY)}（生成器配置如此），
 *       意味着只要它非 null 就会被序列化回客户端。因此本类<b>永不在出站 DTO 上调用
 *       {@code setApiKey}</b>——这是掩码的唯一保证，与 Wave 2 处理 {@code Host.password} 的手法一致。</li>
 *   <li>同理，{@code ModelConfig#toString()} 会把 {@code apiKey} 原样打印，
 *       本类在任何级别都 MUST NOT 把请求 DTO 写进日志，只记 id/provider/model 这类非敏感坐标。
 *       该风险由 {@code ModelConfigsApiCredentialLoggingTest} 在 DEBUG 级别下回归。</li>
 * </ul>
 */
@Service
public class ModelConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(ModelConfigService.class);

    /** 单条模型配置在 {@code settings} 表中的键前缀；完整键为 {@code model.config.<uuid>}。 */
    static final String CONFIG_KEY_PREFIX = "model.config.";
    /** 当前生效配置 id 的键。 */
    static final String ACTIVE_CONFIG_KEY = "model.active_config_id";

    // 预算字段默认值（design D7）
    static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 8192;
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 1024;
    static final OutputLimitField DEFAULT_OUTPUT_LIMIT_FIELD = OutputLimitField.MAX_TOKENS;
    static final ThinkingRequestFormat DEFAULT_THINKING_REQUEST_FORMAT = ThinkingRequestFormat.NONE;

    private static final CredentialOwnerType OWNER = CredentialOwnerType.MODEL_CONFIG;

    private final SettingMapper settingMapper;
    private final SettingsService settingsService;
    private final CredentialStoreService credentials;
    private final ObjectMapper objectMapper;

    public ModelConfigService(SettingMapper settingMapper,
                              SettingsService settingsService,
                              CredentialStoreService credentials,
                              ObjectMapper objectMapper) {
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper 不得为 null");
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService 不得为 null");
        this.credentials = Objects.requireNonNull(credentials, "credentials 不得为 null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不得为 null");
    }

    /**
     * 配置在 {@code settings} 表中的键。
     *
     * <p>WHY 是 public static：集成测试要绕过服务层直接查库，
     * 才能证明"api key 确实不在 settings 表的任何一列里"——若断言依赖服务层自己算出的键，
     * 键名写错时测试会跟着一起错。</p>
     */
    public static String configKeyOf(String configId) {
        return CONFIG_KEY_PREFIX + configId;
    }

    // ======================================================================
    // 查询
    // ======================================================================

    public List<ModelConfig> list() {
        String activeId = activeConfigId().orElse(null);
        List<ModelConfig> items = new ArrayList<>();
        for (Setting row : settingMapper.findByKeyPrefix(CONFIG_KEY_PREFIX)) {
            StoredConfig stored = parse(row.getSettingValue(), row.getSettingKey());
            if (stored == null) {
                continue;
            }
            // 迁移旧配置（幂等）；migrateIfNeeded 内部调用 write(configId, ...)
            // 会再拼一次 CONFIG_KEY_PREFIX，因此这里必须传纯 configId，而非完整 setting key
            stored = migrateIfNeeded(stored, configIdOf(row.getSettingKey()));
            items.add(toDto(configIdOf(row.getSettingKey()), stored, activeId));
        }
        // WHY 在内存排序：settings 表只有 updated_at 一列时间，改个名字就会变；
        // 真创建时间在 JSON 载荷里。配置数量个位数，排序成本可以忽略。
        items.sort(Comparator
                .comparing((ModelConfig item) -> String.valueOf(item.getCreatedAt()))
                .thenComparing(item -> String.valueOf(item.getId())));
        return items;
    }

    public ModelConfig findById(UUID id) {
        Objects.requireNonNull(id, "id 不得为 null");
        StoredRecord existing = requireRecord(id.toString());
        StoredConfig stored = migrateIfNeeded(existing.stored(), existing.id());
        return toDto(existing.id(), stored, activeConfigId().orElse(null));
    }

    /**
     * 当前生效的模型配置（含已解析的思考模式和预算字段）。
     *
     * @return 未设置生效配置时为 empty
     */
    public Optional<ActiveModelConfig> findActive() {
        return activeConfigId()
                .flatMap(id -> {
                    StoredRecord existing = requireRecordRaw(id);
                    if (existing == null) {
                        return Optional.empty();
                    }
                    StoredConfig stored = migrateIfNeeded(existing.stored(), existing.id());
                    return Optional.of(toActive(existing.id(), stored));
                });
    }

    /**
     * 取回当前生效配置；不可用时抛 spec 指定的提示。
     *
     * <p>WHY「没有生效配置」也抛 {@link MissingModelApiKeyException}：
     * 冻结契约的 {@code ErrorCode} 里没有"未配置模型"这一项，
     * 而这两种情形对用户的处置完全相同——去设置页把模型配好。
     * 分成两个码只会让前端多写一个分支、却给出同一句引导。
     * 该契约空白已记入交付报告，交指挥官裁定。</p>
     *
     * @throws MissingModelApiKeyException 无生效配置
     */
    public ActiveModelConfig requireActive() {
        return findActive().orElseThrow(() -> new MissingModelApiKeyException("尚无生效的模型配置"));
    }

    /**
     * 解析某个配置<b>实际</b>使用的思考模式（TRACEABILITY Q3：per-config 覆盖全局）。
     *
     * @throws ModelConfigNotFoundException 配置不存在
     */
    public ThinkingMode resolveThinkingMode(String configId) {
        Objects.requireNonNull(configId, "configId 不得为 null");
        StoredRecord existing = requireRecord(configId);
        StoredConfig stored = migrateIfNeeded(existing.stored(), configId);
        return resolveThinkingMode(stored);
    }

    // ======================================================================
    // 写入
    // ======================================================================

    /**
     * 新建配置。
     *
     * <p>WHY「库中无生效配置时自动生效」：若不自动，用户建完第一条配置后 AI 仍然完全不可用，
     * 而界面上没有任何提示告诉他还要再点一次「设为生效」——他会以为产品坏了。
     * 仅在<b>无</b>生效项时才自动接管，已有生效项时绝不抢占，
     * 否则"新建一个备用配置"会静默地把正在用的模型换掉。</p>
     */
    @Transactional
    public ModelConfig create(ModelConfig request) {
        Objects.requireNonNull(request, "request 不得为 null");
        rejectIfAny(violationsOf(request));

        String id = EntityIds.newUuid();
        OffsetDateTime now = OffsetDateTime.now();
        StoredConfig stored = new StoredConfig(
                request.getProvider().trim(),
                request.getBaseUrl(),
                request.getModel().trim(),
                request.getDefaultThinkingMode(),
                // 新配置的预算字段取请求值（契约已有默认值）
                request.getContextWindowTokens(),
                request.getMaxOutputTokens(),
                request.getOutputLimitField(),
                // WHY 新配置默认 none（design D8）：不在"其他"名称上继续隐式分流思考开关
                request.getThinkingRequestFormat(),
                now.toString(),
                now.toString());
        write(id, stored);

        if (hasText(request.getApiKey())) {
            // SecretText.of 之后由加密服务负责擦除，明文不在本类驻留
            credentials.save(OWNER, id, CredentialType.LLM_API_KEY, SecretText.of(request.getApiKey()));
        }

        boolean autoActivated = activeConfigId().isEmpty();
        if (autoActivated) {
            writeActiveConfigId(id);
        }

        LOG.info("已创建模型配置: id={} provider={} model={} apiKeySet={} autoActivated={}",
                id, stored.provider(), stored.model(), hasText(request.getApiKey()), autoActivated);
        return toDto(id, stored, autoActivated ? id : activeConfigId().orElse(null));
    }

    /**
     * 更新配置；api key「给了就换、没给就留」。
     *
     * <p>WHY 没给就留：{@code api_key} 是 writeOnly，响应永不回显，前端无法"回填旧值再提交"。
     * 若把"请求里没带 key"理解成"清空 key"，用户改个模型名就会顺手把密钥弄丢，
     * 而现象是若干分钟后 AI 突然报"请先配置 api key"，与他的操作看起来毫无关系。</p>
     *
     * <p>per-config 思考模式则相反：请求里给 null 就是<b>清空</b>（回落到全局默认），
     * 因为该字段是可回显的，前端能如实提交"未设置"这一状态。</p>
     */
    @Transactional
    public ModelConfig update(UUID id, ModelConfig request) {
        Objects.requireNonNull(id, "id 不得为 null");
        Objects.requireNonNull(request, "request 不得为 null");
        StoredRecord existing = requireRecord(id.toString());
        rejectIfAny(violationsOf(request));

        StoredConfig stored = new StoredConfig(
                request.getProvider().trim(),
                request.getBaseUrl(),
                request.getModel().trim(),
                request.getDefaultThinkingMode(),
                request.getContextWindowTokens(),
                request.getMaxOutputTokens(),
                request.getOutputLimitField(),
                request.getThinkingRequestFormat(),
                existing.stored().createdAt(),
                OffsetDateTime.now().toString());
        write(id.toString(), stored);

        if (hasText(request.getApiKey())) {
            credentials.save(OWNER, id.toString(), CredentialType.LLM_API_KEY, SecretText.of(request.getApiKey()));
        }

        LOG.info("已更新模型配置: id={} provider={} model={} apiKeyRotated={}",
                id, stored.provider(), stored.model(), hasText(request.getApiKey()));
        return toDto(id.toString(), stored, activeConfigId().orElse(null));
    }

    /**
     * 删除配置及其凭据；目标是生效配置时，一并清除生效指针。
     *
     * <p>WHY 删除生效配置放行而不是拒绝（历史上的 409）：首条配置自动生效，
     * 「只剩一条」必然「它就是生效项」，拒绝删除生效配置意味着用户永远删不掉
     * 最后一条——而零配置本就是空库首启的合法初始态：{@link #requireActive()}
     * 对此报「尚无生效的模型配置」的可读引导，界面也有对应的缺配置提示，
     * AI 能力并不会静默失效。契约同步修订见 TRACEABILITY R11。</p>
     */
    @Transactional
    public void delete(UUID id) {
        Objects.requireNonNull(id, "id 不得为 null");
        String configId = id.toString();
        requireRecord(configId);

        Optional<String> activeId = activeConfigId();
        if (activeId.isPresent() && activeId.get().equals(configId)) {
            // 不留悬空指针：否则 findActive 会指向已不存在的配置，错误现场远比空态难查
            settingsService.deleteKey(ACTIVE_CONFIG_KEY);
            LOG.info("已删除生效模型配置，生效指针同步清空: id={}", configId);
        }

        // 孤立密文既占空间，又让"这个 key 属于谁"的审计问题出现无解的行
        credentials.deleteByOwner(OWNER, configId);
        settingsService.deleteKey(configKeyOf(configId));
        LOG.info("已删除模型配置: id={}", configId);
    }

    /**
     * 切换当前生效配置。
     *
     * @throws ModelConfigNotFoundException 目标配置不存在
     */
    @Transactional
    public ModelConfig setActive(UUID id) {
        Objects.requireNonNull(id, "id 不得为 null");
        StoredRecord record = requireRecord(id.toString());
        writeActiveConfigId(id.toString());
        LOG.info("已切换生效模型配置: id={} provider={} model={}",
                id, record.stored().provider(), record.stored().model());
        return toDto(record.id(), record.stored(), id.toString());
    }

    // ======================================================================
    // 内部：持久化载荷
    // ======================================================================

    /**
     * {@code settings.setting_value} 里的 JSON 载荷。
     *
     * <p>WHY 时间戳存成字符串而不是 {@code OffsetDateTime}：避免把序列化结果
     * 绑定到全局 {@code ObjectMapper} 的 {@code JavaTimeModule} 配置上
     * （{@code WRITE_DATES_AS_TIMESTAMPS} 一开就会写成数组，旧数据随即读不回来）。
     * ISO-8601 文本是自解释且与配置无关的。</p>
     *
     * <p>{@code NON_NULL} 使 per-config 思考模式为 null 时<b>整个键消失</b>，
     * 这正是"回落到全局默认"在存储层的表达。</p>
     *
     * <p>V2 新增预算字段与思考请求格式（design D7/D8）。旧配置缺少这些字段时，
     * 反序列化后为 null，由 {@link #migrateIfNeeded} 在读取时补全默认值并写回。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredConfig(
            @JsonProperty("provider") String provider,
            @JsonProperty("base_url") URI baseUrl,
            @JsonProperty("model") String model,
            @JsonProperty("default_thinking_mode") ThinkingMode defaultThinkingMode,
            // V2 新增预算字段（design D7）
            @JsonProperty("context_window_tokens") Integer contextWindowTokens,
            @JsonProperty("max_output_tokens") Integer maxOutputTokens,
            @JsonProperty("output_limit_field") OutputLimitField outputLimitField,
            // V2 新增思考请求格式（design D8）
            @JsonProperty("thinking_request_format") ThinkingRequestFormat thinkingRequestFormat,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {
    }

    /** 配置 id 与其载荷的绑定，避免调用方在两者间来回换算。 */
    private record StoredRecord(String id, StoredConfig stored) {
    }

    /**
     * 供 {@code ChatModelProvider}（tasks 7.2）使用的不可变快照。
     *
     * <p>WHY 不直接把 {@link ModelConfig} 交出去：契约 DTO 是可变的、带 {@code apiKey} 字段的
     * 传输对象，把它渗进运行时装配逻辑，就等于给"某处不小心 setApiKey 又打日志"开了口子。
     * 本记录<b>没有</b> api key 字段——密钥只在 {@code ChatModelProvider} 内部
     * 经 {@link CredentialStoreService#requireLlmApiKey} 以 {@link SecretText} 形态短暂取用。</p>
     *
     * @param id                    配置主键
     * @param provider              provider 名称（mindie/openai/ollama…），不写死于代码
     * @param baseUrl               OpenAI 兼容端点 base URL
     * @param model                 模型名
     * @param thinkingMode          已按 Q3 解析完毕的思考模式（per-config 优先，否则全局默认）
     * @param contextWindowTokens   上下文窗口 token 容量（design D7）
     * @param maxOutputTokens       最大输出预留 token（design D7）
     * @param outputLimitField      Chat Completions 输出上限字段名（design D7）
     * @param thinkingRequestFormat 思考模式请求扩展格式（design D8）
     */
    public record ActiveModelConfig(String id, String provider, URI baseUrl, String model,
                                    ThinkingMode thinkingMode,
                                    int contextWindowTokens, int maxOutputTokens,
                                    OutputLimitField outputLimitField,
                                    ThinkingRequestFormat thinkingRequestFormat) {
    }

    private ActiveModelConfig toActive(String id, StoredConfig stored) {
        return new ActiveModelConfig(id, stored.provider(), stored.baseUrl(), stored.model(),
                resolveThinkingMode(stored),
                stored.contextWindowTokens(), stored.maxOutputTokens(),
                stored.outputLimitField(), stored.thinkingRequestFormat());
    }

    private ThinkingMode resolveThinkingMode(StoredConfig stored) {
        return stored.defaultThinkingMode() != null
                ? stored.defaultThinkingMode()
                : settingsService.defaultThinkingMode();
    }

    // ======================================================================
    // 旧配置迁移（tasks 8.2 / design D8）
    // ======================================================================

    /**
     * 幂等迁移旧配置载荷，补全 V2 新增的预算字段与思考请求格式。
     *
     * <p>迁移规则（design D8）：
     * <ul>
     *   <li>旧 provider=openai → thinking_request_format=none</li>
     *   <li>旧 provider=其他值 → thinking_request_format=qwen_compatible</li>
     *   <li>预算字段取默认值（8192/1024/max_tokens）</li>
     *   <li>已含新字段的配置不被覆盖（幂等）</li>
     * </ul>
     *
     * @return 迁移后的 StoredConfig（若无需迁移则返回原对象）
     */
    private StoredConfig migrateIfNeeded(StoredConfig stored, String configId) {
        // 已有全部新字段 → 无需迁移
        if (stored.thinkingRequestFormat() != null
                && stored.contextWindowTokens() != null
                && stored.maxOutputTokens() != null
                && stored.outputLimitField() != null) {
            return stored;
        }

        // 推导 thinking_request_format：旧 provider=openai → none，其他 → qwen_compatible
        ThinkingRequestFormat format = stored.thinkingRequestFormat();
        if (format == null) {
            format = deriveThinkingRequestFormat(stored.provider());
        }

        StoredConfig migrated = new StoredConfig(
                stored.provider(),
                stored.baseUrl(),
                stored.model(),
                stored.defaultThinkingMode(),
                stored.contextWindowTokens() != null ? stored.contextWindowTokens() : DEFAULT_CONTEXT_WINDOW_TOKENS,
                stored.maxOutputTokens() != null ? stored.maxOutputTokens() : DEFAULT_MAX_OUTPUT_TOKENS,
                stored.outputLimitField() != null ? stored.outputLimitField() : DEFAULT_OUTPUT_LIMIT_FIELD,
                format,
                stored.createdAt(),
                stored.updatedAt());

        // 写回持久化，使迁移结果可见于 settings 表
        write(configId, migrated);
        LOG.info("已迁移旧模型配置至 V2 字段: id={} provider={} thinkingRequestFormat={}",
                configId, stored.provider(), format);
        return migrated;
    }

    /**
     * 按旧 provider 名推导 thinking_request_format（design D8）。
     *
     * <p>WHY 这样分：OpenAI 官方 API 对未建模的请求字段直接回 400，
     * 旧 provider=openai 的配置从未发送过思考开关；其他 provider（mindie/ollama/自定义）
     * 在 V1 阶段一直走 {@code thinkingExtraBody} 下发两组开关，迁移为 qwen_compatible 保持行为不变。</p>
     */
    private static ThinkingRequestFormat deriveThinkingRequestFormat(String provider) {
        if (provider != null && "openai".equalsIgnoreCase(provider.trim())) {
            return ThinkingRequestFormat.NONE;
        }
        return ThinkingRequestFormat.QWEN_COMPATIBLE;
    }

    // ======================================================================
    // 内部：持久化
    // ======================================================================

    private void write(String configId, StoredConfig stored) {
        settingsService.writeString(configKeyOf(configId), toJson(stored), "json",
                "模型配置: " + stored.provider() + " / " + stored.model());
    }

    private void writeActiveConfigId(String configId) {
        settingsService.writeString(ACTIVE_CONFIG_KEY, configId, "string",
                "当前生效的模型配置 id（model-provider spec）");
    }

    private Optional<String> activeConfigId() {
        return settingsService.rawValue(ACTIVE_CONFIG_KEY).filter(ModelConfigService::hasText);
    }

    private StoredConfig read(String configId) {
        Setting row = settingMapper.findByKey(configKeyOf(configId));
        if (row == null) {
            return null;
        }
        return parse(row.getSettingValue(), row.getSettingKey());
    }

    private StoredRecord requireRecord(String configId) {
        StoredConfig stored = read(configId);
        if (stored == null) {
            throw ModelConfigNotFoundException.forId(configId);
        }
        return new StoredRecord(configId, stored);
    }

    /** 不做"不存在则抛异常"的原始读取，供 findActive 内部使用。 */
    private StoredRecord requireRecordRaw(String configId) {
        StoredConfig stored = read(configId);
        if (stored == null) {
            return null;
        }
        return new StoredRecord(configId, stored);
    }

    /**
     * @return 解析失败时为 null
     *
     * <p>WHY 吞掉解析异常而不是让它冒泡成 500：{@code settings} 是用户可直接编辑的键值表，
     * 一行坏 JSON 不该让整个配置列表打不开（那样用户连"删掉坏行"的入口都没有）。
     * 记 WARN 并跳过，坏行在列表里表现为"缺失"，用户重建即可。</p>
     */
    private StoredConfig parse(String json, String settingKey) {
        if (json == null || json.isBlank()) {
            LOG.warn("模型配置载荷为空，已跳过: settingKey={}", settingKey);
            return null;
        }
        try {
            return objectMapper.readValue(json, StoredConfig.class);
        } catch (JsonProcessingException e) {
            // MUST NOT 把 json 原文写进日志：它虽不含 api key，但把用户配置回显到日志
            // 会让"日志里没有配置内容"这条审计承诺变得不可验证
            LOG.warn("模型配置载荷无法解析，已跳过: settingKey={} reason={}", settingKey, e.getOriginalMessage());
            return null;
        }
    }

    private String toJson(StoredConfig stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            // 载荷是本类自己构造的 POJO，序列化失败只可能是代码缺陷
            throw new IllegalStateException("无法序列化模型配置载荷", e);
        }
    }

    private String configIdOf(String settingKey) {
        return settingKey.substring(CONFIG_KEY_PREFIX.length());
    }

    /**
     * 装配出站 DTO。
     *
     * <p><b>MUST NOT 调用 {@code setApiKey}</b>——见类注释：生成物没有 WRITE_ONLY access，
     * 一旦赋值就会明文回显。</p>
     */
    private ModelConfig toDto(String configId, StoredConfig stored, String activeConfigId) {
        ModelConfig dto = new ModelConfig(stored.provider(), stored.baseUrl(), stored.model());
        dto.setId(UUID.fromString(configId));
        dto.setDefaultThinkingMode(stored.defaultThinkingMode());
        dto.setApiKeySet(credentials.exists(OWNER, configId, CredentialType.LLM_API_KEY));
        dto.setIsActive(configId.equals(activeConfigId));
        // V2 预算字段（design D7/D8）
        dto.setContextWindowTokens(stored.contextWindowTokens());
        dto.setMaxOutputTokens(stored.maxOutputTokens());
        dto.setOutputLimitField(stored.outputLimitField());
        dto.setThinkingRequestFormat(stored.thinkingRequestFormat());
        dto.setCreatedAt(parseInstant(stored.createdAt()));
        dto.setUpdatedAt(parseInstant(stored.updatedAt()));
        return dto;
    }

    private static OffsetDateTime parseInstant(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            LOG.warn("模型配置时间戳无法解析，已按 null 返回: reason={}", e.getMessage());
            return null;
        }
    }

    // ======================================================================
    // 内部：校验
    // ======================================================================

    /**
     * 契约里 {@code base_url} 只声明了 {@code format: uri}，而 {@code URI.create("/v1")}
     * 完全合法——生成的 Bean Validation 拦不住。
     *
     * <p>WHY 必须在服务层拦：一个相对 URI 会让 {@code OpenAiApi.Builder.baseUrl(...)}
     * 构造出一个永远连不上的客户端，用户看到的现象是"保存成功、一发对话就报端点不可达"，
     * 而真实原因（少写了 scheme 和主机）在界面上完全看不出来。</p>
     *
     * <p>V2 新增预算字段校验（design D7）：
     * {@code context_window_tokens} 范围 1024..2097152，
     * {@code max_output_tokens} 正整数，
     * 输入预算 = 容量 - 输出预留 - max(512, ceil(容量*0.10))，至少 512。</p>
     */
    private static List<FieldViolation> violationsOf(ModelConfig request) {
        List<FieldViolation> violations = new ArrayList<>();
        if (!hasText(request.getProvider())) {
            violations.add(new FieldViolation("provider", "provider 不得为空"));
        }
        if (!hasText(request.getModel())) {
            violations.add(new FieldViolation("model", "模型名不得为空"));
        }
        URI baseUrl = request.getBaseUrl();
        if (baseUrl == null) {
            violations.add(new FieldViolation("base_url", "base_url 不得为空"));
        } else if (!baseUrl.isAbsolute()) {
            violations.add(new FieldViolation("base_url", "base_url 必须是绝对地址（含 http/https 协议头）"));
        } else {
            String scheme = baseUrl.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                violations.add(new FieldViolation("base_url", "base_url 的协议必须是 http 或 https"));
            } else if (!hasText(baseUrl.getHost())) {
                violations.add(new FieldViolation("base_url", "base_url 必须包含主机地址"));
            }
        }
        // V2 预算字段校验（design D7）
        Integer contextWindowTokens = request.getContextWindowTokens();
        if (contextWindowTokens != null) {
            if (contextWindowTokens < 1024 || contextWindowTokens > 2097152) {
                violations.add(new FieldViolation("context_window_tokens",
                        "context_window_tokens 必须在 1024 到 2097152 之间"));
            }
        }
        Integer maxOutputTokens = request.getMaxOutputTokens();
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            violations.add(new FieldViolation("max_output_tokens",
                    "max_output_tokens 必须为正整数"));
        }
        // 输入预算校验：容量 - 输出预留 - 安全余量 >= 512
        if (contextWindowTokens != null && maxOutputTokens != null
                && contextWindowTokens >= 1024 && maxOutputTokens >= 1) {
            int inputBudget = computeInputBudget(contextWindowTokens, maxOutputTokens);
            if (inputBudget < 512) {
                violations.add(new FieldViolation("context_window_tokens",
                        "输入预算（context_window_tokens - max_output_tokens - 安全余量）至少为 512"));
            }
        }
        return violations;
    }

    /**
     * 计算输入预算（design D7 步骤 4）。
     *
     * <p>输入预算 = 容量 - 输出预留 - max(512, ceil(容量 * 0.10))</p>
     */
    static int computeInputBudget(int contextWindowTokens, int maxOutputTokens) {
        int safetyMargin = Math.max(512, (int) Math.ceil(contextWindowTokens * 0.10));
        return contextWindowTokens - maxOutputTokens - safetyMargin;
    }

    private static void rejectIfAny(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new InvalidRequestException("请求校验失败", violations);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

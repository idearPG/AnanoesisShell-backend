package com.ananoesis.shell.controller;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.ananoesis.shell.contract.model.ActiveModelConfigRequest;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.ModelConfig;
import com.ananoesis.shell.contract.model.ThinkingMode;
import com.ananoesis.shell.security.MissingModelApiKeyException;
import com.ananoesis.shell.service.ModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * tasks 7.1 的验收（模型配置侧）：{@code /api/model-configs} 的 CRUD、生效切换与 api key 密文化。
 *
 * <p>WHY 断言分三层（响应体 / settings 表 / credentials 表）：
 * "接口返回正确"只证明了序列化层；credential-store spec 的 MUST 级要求是<b>明文不落库</b>与
 * <b>响应不回显</b>，这两条分别只能靠查 {@code credentials.ciphertext} 与查原始响应字节来证明。</p>
 *
 * <p>WHY 每个用例都用唯一的 {@code base_url}：所有集成测试共享同一个 Spring 上下文与
 * 同一个 SQLite 文件（见 {@link AbstractSqliteIntegrationTest}），用唯一值隔离才能让断言
 * 与 JUnit 的方法执行顺序无关。</p>
 *
 * <p>WHY 需要显式切换生效配置而<b>不</b>依赖"第一条自动生效"：
 * 共享库里已经存在别的用例创建的配置，"当前是否已有生效项"不是本用例可控的前置条件。
 * 唯一一处依赖该规则的断言在 {@link #firstConfigBecomesActiveAutomatically} 里，
 * 它先用 JDBC 清空状态，把前置条件变成自己造的。</p>
 */
class ModelConfigsApiIntegrationTest extends AbstractSqliteIntegrationTest {

    /** 刻意选一个绝不会偶然出现在数据里的字符串，使"扫不到"成为强断言。 */
    private static final String API_KEY = "sk-mindie-7Qx2ZtR9vBnM4wKpLdYeHcAa-DO-NOT-LEAK";
    private static final String OTHER_API_KEY = "sk-mindie-ROTATED-8Hs3kLm5nPq2rSt7uVw4xYz-do-not-leak";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ModelConfigService modelConfigService;

    // ======================================================================
    // 新增
    // ======================================================================

    @Test
    @DisplayName("POST /api/model-configs → 201，api key 密文落库、响应只给 api_key_set 掩码")
    void createModelConfigEncryptsApiKeyAndMasksResponse() {
        ModelConfig created = create("10.95.1.11", API_KEY, ThinkingMode.THINKING);

        assertThat(created.getId()).as("服务端应分配 id").isNotNull();
        assertThat(created.getProvider()).isEqualTo("mindie");
        assertThat(created.getBaseUrl()).isEqualTo(URI.create("http://10.95.1.11:8080/v1"));
        assertThat(created.getModel()).isEqualTo("Qwen3-30B");
        assertThat(created.getApiKeySet()).as("以掩码布尔告知 api key 已配置").isTrue();
        assertThat(created.getApiKey()).as("writeOnly：绝不回显明文").isNull();
        assertThat(created.getDefaultThinkingMode()).isEqualTo(ThinkingMode.THINKING);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();

        // credentials 表：只有密文
        String id = created.getId().toString();
        String ciphertext = selectApiKeyCiphertext(id);
        assertThat(ciphertext).as("api key 应以密文落库").startsWith("v1.");
        assertThat(ciphertext).doesNotContain(API_KEY);
        assertThat(allCredentialColumnsOf(id))
                .as("该配置的任何凭据列都不得含明文")
                .noneMatch(value -> value.contains(API_KEY));

        // settings 表：非敏感的 provider/base_url/model 以 json 落库，且**绝不含** api key
        String json = selectConfigJson(id);
        assertThat(json).as("配置本体存在 settings 表").isNotNull();
        assertThat(json).contains("mindie").contains("10.95.1.11").contains("Qwen3-30B");
        assertThat(json).as("api key MUST NOT 出现在 settings 表").doesNotContain(API_KEY);
        assertThat(selectAllSettingValues()).as("settings 表任何一行都不得含 api key 明文")
                .noneMatch(value -> value.contains(API_KEY));
    }

    @Test
    @DisplayName("POST /api/model-configs 不带 api key → 201 且 api_key_set=false（允许先建配置后补密钥）")
    void createModelConfigWithoutApiKey() {
        ModelConfig created = create("10.95.1.12", null, null);

        assertThat(created.getApiKeySet()).isFalse();
        assertThat(countCredentialRows(created.getId().toString()))
                .as("未提供 api key 时不得产生凭据行").isZero();
    }

    @Test
    @DisplayName("库中尚无生效配置时，新建的第一条自动成为生效配置")
    void firstConfigBecomesActiveAutomatically() {
        purgeAllModelConfigs();
        try {
            ModelConfig first = create("10.95.1.13", API_KEY, null);
            assertThat(first.getIsActive())
                    .as("WHY 自动生效：若不自动，用户建完配置后 AI 仍完全不可用，"
                            + "而界面上没有任何提示告诉他还要再点一次「设为生效」")
                    .isTrue();
            assertThat(readActiveConfigId()).isEqualTo(first.getId().toString());

            ModelConfig second = create("10.95.1.14", API_KEY, null);
            assertThat(second.getIsActive()).as("已有生效项时不得抢占").isFalse();
        } finally {
            purgeAllModelConfigs();
        }
    }

    @Test
    @DisplayName("POST /api/model-configs 缺少必填 base_url → 400 validation_error")
    void createWithoutRequiredFieldIsRejected() {
        ResponseEntity<String> raw = rest.postForEntity("/api/model-configs",
                rawJson("{\"provider\":\"mindie\",\"model\":\"Qwen3-30B\"}"), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parseError(raw.getBody()).getDetails()).extracting("field").contains("base_url");
    }

    @Test
    @DisplayName("POST /api/model-configs 的 base_url 不是绝对 http(s) 地址 → 400 validation_error")
    void createWithRelativeBaseUrlIsRejected() {
        ModelConfig request = new ModelConfig("mindie", URI.create("/v1"), "Qwen3-30B");

        ResponseEntity<String> raw = rest.postForEntity("/api/model-configs", jsonEntity(request), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error error = parseError(raw.getBody());
        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(error.getDetails()).extracting("field").contains("base_url");
    }

    // ======================================================================
    // 查询
    // ======================================================================

    @Test
    @DisplayName("GET /api/model-configs/{id} → 200，仍不回显 api key")
    void getModelConfigByIdMasksApiKey() {
        ModelConfig created = create("10.95.2.11", API_KEY, null);

        ResponseEntity<String> raw = rest.getForEntity("/api/model-configs/" + created.getId(), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain(API_KEY);
        ModelConfig found = parse(raw.getBody());
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getApiKey()).isNull();
        assertThat(found.getApiKeySet()).isTrue();
    }

    @Test
    @DisplayName("GET /api/model-configs/{未知 id} → 404 not_found")
    void getUnknownModelConfigIsNotFound() {
        ResponseEntity<String> raw = rest.getForEntity("/api/model-configs/" + UUID.randomUUID(), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parseError(raw.getBody()).getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/model-configs → 200，列表含新建项且至多一条 is_active=true")
    void listModelConfigsMarksSingleActive() {
        ModelConfig created = create("10.95.2.12", API_KEY, null);

        ResponseEntity<String> raw = rest.getForEntity("/api/model-configs", String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<ModelConfig> items = parseList(raw.getBody());
        assertThat(items).extracting(ModelConfig::getId).contains(created.getId());
        assertThat(items).as("生效配置必须唯一，否则「当前用哪个模型」没有答案")
                .filteredOn(item -> Boolean.TRUE.equals(item.getIsActive()))
                .hasSizeLessThanOrEqualTo(1);
        assertThat(raw.getBody()).doesNotContain(API_KEY);
    }

    // ======================================================================
    // 更新
    // ======================================================================

    @Test
    @DisplayName("PUT /api/model-configs/{id} 换 api key → 密文覆盖，凭据行仍只有一条")
    void updateModelConfigRotatesApiKey() {
        ModelConfig created = create("10.95.3.11", API_KEY, null);
        String id = created.getId().toString();
        String before = selectApiKeyCiphertext(id);

        ModelConfig request = new ModelConfig("mindie", URI.create("http://10.95.3.11:8080/v1"), "Qwen3-30B");
        request.setApiKey(OTHER_API_KEY);

        ResponseEntity<String> raw = rest.exchange("/api/model-configs/" + id, HttpMethod.PUT,
                jsonEntity(request), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain(OTHER_API_KEY).doesNotContain(API_KEY);
        ModelConfig updated = parse(raw.getBody());
        assertThat(updated.getApiKeySet()).isTrue();
        assertThat(updated.getApiKey()).isNull();

        assertThat(countCredentialRows(id)).as("轮换不得留下第二条凭据行").isEqualTo(1);
        String after = selectApiKeyCiphertext(id);
        assertThat(after).startsWith("v1.").isNotEqualTo(before);
        assertThat(after).doesNotContain(OTHER_API_KEY);
    }

    @Test
    @DisplayName("PUT /api/model-configs/{id} 不带 api key → 保留原有密钥（改个模型名不该顺手弄丢 key）")
    void updateModelConfigKeepsApiKeyWhenAbsent() {
        ModelConfig created = create("10.95.3.12", API_KEY, null);
        String id = created.getId().toString();
        String before = selectApiKeyCiphertext(id);

        ModelConfig request = new ModelConfig("mindie", URI.create("http://10.95.3.12:8080/v1"), "Qwen3-32B");
        request.setDefaultThinkingMode(ThinkingMode.NON_THINKING);

        ResponseEntity<String> raw = rest.exchange("/api/model-configs/" + id, HttpMethod.PUT,
                jsonEntity(request), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        ModelConfig updated = parse(raw.getBody());
        assertThat(updated.getModel()).isEqualTo("Qwen3-32B");
        assertThat(updated.getDefaultThinkingMode()).isEqualTo(ThinkingMode.NON_THINKING);
        assertThat(updated.getApiKeySet()).as("密钥应原样保留").isTrue();
        assertThat(selectApiKeyCiphertext(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("PUT /api/model-configs/{id} 可清空 per-config 思考模式（回落到全局默认）")
    void updateModelConfigCanClearThinkingMode() {
        ModelConfig created = create("10.95.3.13", API_KEY, ThinkingMode.THINKING);
        String id = created.getId().toString();
        assertThat(selectConfigJson(id)).contains("default_thinking_mode");

        ModelConfig request = new ModelConfig("mindie", URI.create("http://10.95.3.13:8080/v1"), "Qwen3-30B");
        request.setDefaultThinkingMode(null);

        ResponseEntity<String> raw = rest.exchange("/api/model-configs/" + id, HttpMethod.PUT,
                jsonEntity(request), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(raw.getBody()).getDefaultThinkingMode()).isNull();
        // WHY 单独断言这条：全局 update-strategy=not_null 会**静默跳过** null 字段，
        // "把已设置的值清空"因此变成 no-op——界面提交成功、刷新后旧值又回来了
        // WHY 断言 "default_thinking_mode" 而非 "thinking"：V2 新增 thinking_request_format 字段，
        // JSON 里始终含 "thinking_request_format"，宽泛匹配会假红
        assertThat(selectConfigJson(id)).doesNotContain("default_thinking_mode");
    }

    @Test
    @DisplayName("PUT /api/model-configs/{未知 id} → 404 not_found")
    void updateUnknownModelConfigIsNotFound() {
        ModelConfig request = new ModelConfig("mindie", URI.create("http://10.95.3.99:8080/v1"), "Qwen3-30B");

        ResponseEntity<String> raw = rest.exchange("/api/model-configs/" + UUID.randomUUID(), HttpMethod.PUT,
                jsonEntity(request), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parseError(raw.getBody()).getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ======================================================================
    // 生效切换（TRACEABILITY Q3：per-config 思考模式覆盖全局）
    // ======================================================================

    @Test
    @DisplayName("PUT /api/model-configs/active → 200，被激活项 is_active=true 且旧项转为 false")
    void setActiveModelConfigSwitches() {
        ModelConfig first = create("10.95.4.11", API_KEY, null);
        ModelConfig second = create("10.95.4.12", API_KEY, null);
        activate(first.getId());
        activate(second.getId());

        List<ModelConfig> items = parseList(rest.getForEntity("/api/model-configs", String.class).getBody());
        assertThat(items).filteredOn(item -> Boolean.TRUE.equals(item.getIsActive()))
                .extracting(ModelConfig::getId)
                .containsExactly(second.getId());
        assertThat(readActiveConfigId()).isEqualTo(second.getId().toString());
    }

    @Test
    @DisplayName("PUT /api/model-configs/active 指向不存在的配置 → 404 not_found")
    void setActiveUnknownModelConfigIsNotFound() {
        ResponseEntity<String> raw = rest.exchange("/api/model-configs/active", HttpMethod.PUT,
                jsonEntity(new ActiveModelConfigRequest(UUID.randomUUID())), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parseError(raw.getBody()).getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("PUT /api/model-configs/active 缺少 id → 400 validation_error")
    void setActiveWithoutIdIsRejected() {
        ResponseEntity<String> raw = rest.exchange("/api/model-configs/active", HttpMethod.PUT,
                rawJson("{}"), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parseError(raw.getBody()).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("字面量路径 /active 不被 {id} 模板吞掉：传非法 id 走的是业务 404 而不是类型 400")
    void activePathTakesPrecedenceOverIdTemplate() {
        ResponseEntity<String> raw = rest.exchange("/api/model-configs/active", HttpMethod.PUT,
                jsonEntity(new ActiveModelConfigRequest(UUID.randomUUID())), String.class);

        // WHY 值得单独钉住：契约注释明确「路径字面量 active 优先于 {id} 模板匹配」。
        // 若 Spring 把 active 当成了 {id}，"active" 无法解析成 UUID，
        // 客户端会收到 400 validation_error（参数类型不匹配）而不是 404 not_found
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Q3：配置自带的思考模式覆盖全局默认；未设置时回落到全局默认")
    void perConfigThinkingModeOverridesGlobal() {
        ModelConfig thinking = create("10.95.4.21", API_KEY, ThinkingMode.THINKING);
        ModelConfig inherited = create("10.95.4.22", API_KEY, null);

        writeSetting("model.default_thinking_mode", "false");
        assertThat(modelConfigService.resolveThinkingMode(thinking.getId().toString()))
                .as("per-config 值优先").isEqualTo(ThinkingMode.THINKING);
        assertThat(modelConfigService.resolveThinkingMode(inherited.getId().toString()))
                .as("未设置时回落全局默认").isEqualTo(ThinkingMode.NON_THINKING);

        writeSetting("model.default_thinking_mode", "true");
        try {
            assertThat(modelConfigService.resolveThinkingMode(thinking.getId().toString()))
                    .as("全局翻转后 per-config 仍优先").isEqualTo(ThinkingMode.THINKING);
            assertThat(modelConfigService.resolveThinkingMode(inherited.getId().toString()))
                    .as("全局翻转后继承项随之改变").isEqualTo(ThinkingMode.THINKING);
        } finally {
            writeSetting("model.default_thinking_mode", "false");
        }
    }

    // ======================================================================
    // 删除
    // ======================================================================

    @Test
    @DisplayName("DELETE /api/model-configs/{非生效 id} → 204，配置行与凭据行一并清除")
    void deleteInactiveModelConfigRemovesCredentials() {
        ModelConfig keeper = create("10.95.5.11", API_KEY, null);
        activate(keeper.getId());
        ModelConfig victim = create("10.95.5.12", API_KEY, null);
        String id = victim.getId().toString();

        ResponseEntity<String> raw = rest.exchange("/api/model-configs/" + id, HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(selectConfigJson(id)).as("配置行应被删除").isNull();
        assertThat(countCredentialRows(id)).as("孤立密文既占空间又让审计难以解释，必须连带清理").isZero();
        assertThat(readActiveConfigId()).as("不得误伤生效配置").isEqualTo(keeper.getId().toString());
    }

    @Test
    @DisplayName("DELETE /api/model-configs/{生效 id} → 204，生效指针一并清除（零配置是合法状态）")
    void deleteActiveModelConfigClearsActivePointer() {
        ModelConfig active = create("10.95.5.21", API_KEY, null);
        activate(active.getId());
        String id = active.getId().toString();

        ResponseEntity<String> raw = rest.exchange("/api/model-configs/" + id, HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);

        // WHY 204 而不是历史的 409：首条配置自动生效，「只剩一条」必然「它就是生效项」，
        // 409 意味着用户永远删不掉最后一条配置——而零配置本就是空库首启的合法初始态
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(selectConfigJson(id)).as("配置行应被删除").isNull();
        assertThat(countCredentialRows(id)).as("密文应连带清理").isZero();
        assertThat(readActiveConfigId()).as("删除生效配置后不得留下悬空指针").isNull();
    }

    @Test
    @DisplayName("删除最后一条配置 → 204，回到零配置初始态；requireActive 给出与空库首启一致的提示")
    void deleteLastModelConfigReturnsToZeroConfigState() {
        purgeAllModelConfigs();
        try {
            ModelConfig only = create("10.95.5.23", API_KEY, null);
            assertThat(only.getIsActive()).as("唯一配置必然生效").isTrue();

            ResponseEntity<String> raw = rest.exchange("/api/model-configs/" + only.getId(), HttpMethod.DELETE,
                    HttpEntity.EMPTY, String.class);

            assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            List<ModelConfig> items = parseList(rest.getForEntity("/api/model-configs", String.class).getBody());
            assertThat(items).as("列表应回到与空库首启相同的零配置态").isEmpty();
            assertThat(readActiveConfigId()).isNull();
            assertThatThrownBy(() -> modelConfigService.requireActive())
                    .as("零配置时取生效配置应报可读引导，而不是 NPE 或静默")
                    .isInstanceOf(MissingModelApiKeyException.class)
                    .hasMessageContaining("尚无生效的模型配置");
        } finally {
            purgeAllModelConfigs();
        }
    }

    @Test
    @DisplayName("DELETE /api/model-configs/{未知 id} → 404 not_found")
    void deleteUnknownModelConfigIsNotFound() {
        ResponseEntity<String> raw = rest.exchange("/api/model-configs/" + UUID.randomUUID(), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parseError(raw.getBody()).getCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private ModelConfig create(String address, String apiKey, ThinkingMode mode) {
        ModelConfig request = new ModelConfig("mindie", URI.create("http://" + address + ":8080/v1"), "Qwen3-30B");
        request.setApiKey(apiKey);
        request.setDefaultThinkingMode(mode);

        ResponseEntity<String> raw = rest.postForEntity("/api/model-configs", jsonEntity(request), String.class);
        assertThat(raw.getStatusCode()).as("创建应成功: %s", raw.getBody()).isEqualTo(HttpStatus.CREATED);
        // WHY 先断原始响应文本：把 DTO 反序列化后再查字段，会漏掉"多回显了一个未建模字段"的泄露
        if (apiKey != null) {
            assertThat(raw.getBody()).doesNotContain(apiKey);
        }
        return parse(raw.getBody());
    }

    private void activate(UUID id) {
        ResponseEntity<String> raw = rest.exchange("/api/model-configs/active", HttpMethod.PUT,
                jsonEntity(new ActiveModelConfigRequest(id)), String.class);
        assertThat(raw.getStatusCode()).as("切换生效配置应成功: %s", raw.getBody()).isEqualTo(HttpStatus.OK);
    }

    private HttpEntity<String> jsonEntity(Object body) {
        return rawJson(write(body));
    }

    private HttpEntity<String> rawJson(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    private ModelConfig parse(String body) {
        try {
            return objectMapper.readValue(body, ModelConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析响应体: " + body, e);
        }
    }

    private List<ModelConfig> parseList(String body) {
        try {
            return objectMapper.readValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ModelConfig.class));
        } catch (Exception e) {
            throw new IllegalStateException("无法解析响应体: " + body, e);
        }
    }

    private Error parseError(String body) {
        try {
            return objectMapper.readValue(body, Error.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析错误响应体: " + body, e);
        }
    }

    /** 清空模型配置相关的全部状态，使依赖"库为空"的断言拥有自己造的前置条件。 */
    private void purgeAllModelConfigs() {
        execute("DELETE FROM credentials WHERE owner_type = 'model_config'");
        execute("DELETE FROM settings WHERE setting_key LIKE 'model.config.%'");
        execute("DELETE FROM settings WHERE setting_key = 'model.active_config_id'");
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String selectApiKeyCiphertext(String configId) {
        String sql = "SELECT ciphertext FROM credentials WHERE owner_type = 'model_config' "
                + "AND owner_id = ? AND credential_type = 'llm_api_key'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, configId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int countCredentialRows(String configId) {
        String sql = "SELECT COUNT(*) FROM credentials WHERE owner_type = 'model_config' AND owner_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, configId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> allCredentialColumnsOf(String configId) {
        String sql = "SELECT * FROM credentials WHERE owner_type = 'model_config' AND owner_id = ?";
        return collectStrings(sql, configId);
    }

    private List<String> selectAllSettingValues() {
        return collectStrings("SELECT * FROM settings", null);
    }

    private List<String> collectStrings(String sql, String parameter) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            try (ResultSet rs = statement.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                List<String> values = new ArrayList<>();
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

    private String selectConfigJson(String configId) {
        String sql = "SELECT setting_value FROM settings WHERE setting_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ModelConfigService.configKeyOf(configId));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String readActiveConfigId() {
        String sql = "SELECT setting_value FROM settings WHERE setting_key = 'model.active_config_id'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeSetting(String key, String value) {
        String sql = "UPDATE settings SET setting_value = ? WHERE setting_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

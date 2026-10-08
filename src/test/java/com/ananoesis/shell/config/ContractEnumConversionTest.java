package com.ananoesis.shell.config;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 钉住「查询参数里的契约枚举按其<b>线上取值</b>解析」这条不变量。
 *
 * <p>WHY 需要它：Spring MVC 的默认 {@code String -> Enum} 转换走 {@code Enum.valueOf}，
 * 按 Java 常量名匹配；而 openapi-generator 生成的枚举用 {@code @JsonValue getValue()}
 * 声明线上取值（通常是小写下划线），常量名却是大写。两者不一致时，
 * 前端严格按契约发 {@code ?status=open} 会收到 400——而这个缺陷<b>不会</b>被
 * 任何单元测试发现，因为报文体的反序列化走 Jackson、{@code @JsonCreator} 在那里是生效的。</p>
 *
 * <p>WHY 用扫描而不是列举：手写一份"需要检查的参数清单"意味着新增端点时得有人记得来更新它，
 * 而忘记更新的症状是"测试仍然全绿"。扫描 {@code contract/api} 下全部 {@code *Api} 接口后，
 * Wave 3 的 {@code ApprovalsApi.listApprovals(decision)} 会自动被纳入，无需任何人干预。</p>
 *
 * <p>WHY 必须断言"扫描结果非空"：一个遍历空集合的断言循环永远通过。
 * 若某天 codegen 的输出目录变了、资源模式匹配不到任何类，本测试会在
 * "什么都没检查"的状态下报绿——那是最坏的失败模式。所以下面用
 * {@link #scannedApiInterfaces()} 的显式下限把这种静默退化变成红灯。</p>
 *
 * <p>WHY 复用 {@link AbstractSqliteIntegrationTest}：Spring 按配置指纹缓存上下文，
 * 沿用同一个基类才能与其它集成测试共用<b>一个</b>上下文，不必再多启动一次容器。</p>
 */
class ContractEnumConversionTest extends AbstractSqliteIntegrationTest {

    /** MVC 实际使用的那个 ConversionService（由 WebMvcAutoConfiguration 定义）。 */
    @Autowired
    @Qualifier("mvcConversionService")
    private ConversionService conversionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("扫描到全部生成的 *Api 接口（防止资源模式失效导致空转绿灯）")
    void scansAllGeneratedApiInterfaces() {
        List<Class<?>> apis = scannedApiInterfaces();

        // WHY 写下已知的 6 个：数量若下降说明扫描模式或生成目录变了，
        // 那时"枚举参数全部可转换"这条断言已经不再覆盖任何东西
        assertThat(apis).hasSizeGreaterThanOrEqualTo(6);
        assertThat(apis).extracting(Class::getSimpleName).contains(
                "SessionsApi", "HostsApi", "ApprovalsApi", "ModelConfigsApi",
                "ConversationsApi", "SettingsApi");
    }

    @Test
    @DisplayName("所有枚举型请求参数：契约线上取值都能转换回对应常量")
    void everyEnumRequestParameterAcceptsItsContractValues() {
        List<EnumParameter> parameters = enumRequestParameters();

        // 已知契约里有两个：SessionsApi(status) 与 ApprovalsApi(decision)
        assertThat(parameters).hasSizeGreaterThanOrEqualTo(2);

        for (EnumParameter parameter : parameters) {
            for (Enum<?> constant : parameter.type().getEnumConstants()) {
                String wireValue = wireValueOf(constant);
                Object converted = conversionService.convert(wireValue, parameter.type());
                assertThat(converted)
                        .as("%s 的查询参数 %s 应能把契约取值 %s 解析为 %s",
                                parameter.owner(), parameter.name(), wireValue, constant)
                        .isEqualTo(constant);
            }
        }
    }

    @Test
    @DisplayName("枚举型请求参数：非法取值仍然失败（保住 400 而不是被宽容成 null）")
    void illegalEnumRequestParameterValueStillFails() {
        List<EnumParameter> parameters = enumRequestParameters();
        assertThat(parameters).isNotEmpty();

        for (EnumParameter parameter : parameters) {
            assertThatThrownBy(() -> conversionService.convert("definitely-not-a-contract-value", parameter.type()))
                    .as("%s 的查询参数 %s 不应接受非法取值", parameter.owner(), parameter.name())
                    // WHY 允许两种异常：转换失败可能被包装成 ConversionFailedException，
                    // 也可能直接抛出 fromValue 的 IllegalArgumentException；
                    // 两者都会被 Spring MVC 归到"类型不匹配"，最终由 ApiExceptionHandler 译成
                    // 400 validation_error（见 SessionsApiIntegrationTest#invalidStatusIsValidationError）
                    .isInstanceOfAny(ConversionFailedException.class, IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("枚举型请求参数：空串转换为 null（前端清空筛选时发 ?status=，不应 400）")
    void emptyEnumRequestParameterValueBecomesNull() {
        List<EnumParameter> parameters = enumRequestParameters();
        assertThat(parameters).isNotEmpty();

        for (EnumParameter parameter : parameters) {
            assertThat(conversionService.convert("", parameter.type()))
                    .as("%s 的查询参数 %s：空串应视为\"不过滤\"", parameter.owner(), parameter.name())
                    .isNull();
        }
    }

    // ======================================================================
    // 扫描辅助
    // ======================================================================

    /** 一个 {@code *Api} 接口上类型为枚举的请求参数。 */
    private record EnumParameter(String owner, String name, Class<? extends Enum<?>> type) {
    }

    private List<EnumParameter> enumRequestParameters() {
        List<EnumParameter> found = new ArrayList<>();
        for (Class<?> api : scannedApiInterfaces()) {
            for (Method method : api.getMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.getType().isEnum()) {
                        found.add(new EnumParameter(api.getSimpleName(), parameter.getName(),
                                asEnumClass(parameter.getType())));
                    }
                }
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Enum<?>> asEnumClass(Class<?> type) {
        return (Class<? extends Enum<?>>) type;
    }

    /** @return 契约线上取值（应用 {@code @JsonValue}，故得到的是小写下划线形式） */
    private String wireValueOf(Enum<?> constant) {
        // WHY 用 convertValue 而不是 writeValueAsString：后者会带上 JSON 引号，
        // 还得再剥一层；convertValue 直接给出 @JsonValue 的产物
        return objectMapper.convertValue(constant, String.class);
    }

    private List<Class<?>> scannedApiInterfaces() {
        List<Class<?>> apis = new ArrayList<>();
        for (Resource resource : apiResources()) {
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".class")) {
                continue;
            }
            String className = "com.ananoesis.shell.contract.api."
                    + filename.substring(0, filename.length() - ".class".length());
            try {
                Class<?> type = Class.forName(className);
                if (type.isInterface()) {
                    apis.add(type);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("无法加载生成的契约接口: " + className, e);
            }
        }
        return apis;
    }

    private Resource[] apiResources() {
        try {
            return new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:com/ananoesis/shell/contract/api/*Api.class");
        } catch (IOException e) {
            throw new IllegalStateException("无法枚举生成的契约接口", e);
        }
    }
}

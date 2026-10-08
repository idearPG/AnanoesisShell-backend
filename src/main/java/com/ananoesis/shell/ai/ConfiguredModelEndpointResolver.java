package com.ananoesis.shell.ai;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.ananoesis.shell.service.CredentialStoreService;
import com.ananoesis.shell.service.ModelConfigService;
import com.ananoesis.shell.service.ModelConfigService.ActiveModelConfig;

/**
 * {@link ModelEndpointResolver} 的生产实现：从"当前生效的模型配置 + 密文 api key"解析端点。
 *
 * <p>WHY 这一层薄到几乎只有三行：它的存在是为了把两个<b>不同安全级别</b>的依赖
 * 拼成装配器需要的单一取值——{@link ModelConfigService} 给的是非敏感坐标
 * （provider/base_url/model/思考模式/预算字段），{@link CredentialStoreService} 给的是密文解密后的
 * {@code SecretText}。让装配器同时依赖这两个服务，就等于把"取密钥"这件事散落到装配逻辑里；
 * 收在这里，"api key 只有一个来源"才是可验证的。</p>
 *
 * <p>MUST NOT 记录 api key：本类不打任何日志，取值与转交都在一行里完成，
 * 由 {@link OpenAiCompatibleChatModelProvider} 负责在装配后擦除。</p>
 */
@Component
public class ConfiguredModelEndpointResolver implements ModelEndpointResolver {

    private final ModelConfigService modelConfigs;
    private final CredentialStoreService credentials;

    public ConfiguredModelEndpointResolver(ModelConfigService modelConfigs, CredentialStoreService credentials) {
        this.modelConfigs = Objects.requireNonNull(modelConfigs, "modelConfigs 不得为 null");
        this.credentials = Objects.requireNonNull(credentials, "credentials 不得为 null");
    }

    @Override
    public ResolvedEndpoint resolve() {
        // requireActive 在"没有生效配置"时抛 MissingModelApiKeyException（见其注释：
        // 冻结契约没有"未配置模型"错误码，而两种情形的用户处置相同）
        ActiveModelConfig active = modelConfigs.requireActive();
        // requireLlmApiKey 在"配置存在但没填 key"时抛同一个异常，消息取自 spec 原文
        // V2：传递预算字段与思考请求格式（design D7/D8）
        return new ResolvedEndpoint(active.id(), active.provider(), active.baseUrl(), active.model(),
                active.thinkingMode(),
                active.contextWindowTokens(), active.maxOutputTokens(),
                active.outputLimitField(), active.thinkingRequestFormat(),
                credentials.requireLlmApiKey(active.id()));
    }
}

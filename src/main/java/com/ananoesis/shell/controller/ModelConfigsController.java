package com.ananoesis.shell.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.ananoesis.shell.contract.api.ModelConfigsApi;
import com.ananoesis.shell.contract.model.ActiveModelConfigRequest;
import com.ananoesis.shell.contract.model.ModelConfig;
import com.ananoesis.shell.service.ModelConfigService;

/**
 * {@code /api/model-configs} 的 HTTP 适配层（tasks 7.1）。
 *
 * <p>WHY 实现生成的 {@link ModelConfigsApi} 而不是自己写 {@code @RequestMapping}：
 * 路由、HTTP 方法、{@code produces}/{@code consumes}、{@code @Valid} 全部来自冻结契约，
 * 端点漂移在编译期就不可能发生（生成接口的方法是抽象的，签名不符即无法编译）。</p>
 *
 * <p>本类<b>只做转发</b>。这里出现任何 {@code if} 都是分层被侵蚀的信号：
 * 业务规则属 {@link ModelConfigService}，HTTP 状态码与错误体属 {@link ApiExceptionHandler}。</p>
 *
 * <p><b>日志纪律</b>：MUST NOT 记录 {@link ModelConfig} 请求 DTO——生成物的
 * {@code toString()} 会把 {@code apiKey} 原样打印（生成器未产出 WRITE_ONLY access）。
 * 该风险由 {@code ModelConfigsApiCredentialLoggingTest} 在 DEBUG 级别下回归。</p>
 */
@RestController
public class ModelConfigsController implements ModelConfigsApi {

    private final ModelConfigService modelConfigService;

    public ModelConfigsController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @Override
    public ResponseEntity<ModelConfig> createModelConfig(ModelConfig modelConfig) {
        // 201 由契约声明（createModelConfig 的 responses 里 201 是唯一成功码）
        return ResponseEntity.status(201).body(modelConfigService.create(modelConfig));
    }

    @Override
    public ResponseEntity<List<ModelConfig>> listModelConfigs() {
        return ResponseEntity.ok(modelConfigService.list());
    }

    @Override
    public ResponseEntity<ModelConfig> getModelConfig(UUID id) {
        return ResponseEntity.ok(modelConfigService.findById(id));
    }

    @Override
    public ResponseEntity<ModelConfig> updateModelConfig(UUID id, ModelConfig modelConfig) {
        return ResponseEntity.ok(modelConfigService.update(id, modelConfig));
    }

    @Override
    public ResponseEntity<ModelConfig> setActiveModelConfig(ActiveModelConfigRequest request) {
        return ResponseEntity.ok(modelConfigService.setActive(request.getId()));
    }

    @Override
    public ResponseEntity<Void> deleteModelConfig(UUID id) {
        modelConfigService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

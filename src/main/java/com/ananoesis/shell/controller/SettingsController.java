package com.ananoesis.shell.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.ananoesis.shell.contract.api.SettingsApi;
import com.ananoesis.shell.contract.model.Settings;
import com.ananoesis.shell.service.SettingsService;

/**
 * {@code /api/settings} 的 HTTP 适配层（tasks 7.1）。
 *
 * <p>WHY 实现生成的 {@link SettingsApi}：同 {@link ModelConfigsController}——
 * 契约驱动，端点漂移在编译期即暴露。</p>
 *
 * <p>WHY 只有 {@code default_thinking_mode} 一个字段：冻结契约的 {@code Settings} schema
 * 就是如此，审批/执行超时与输出上限按 TRACEABILITY Q5 走 {@code settings} 键值、
 * 由 {@code approval_request.timeout_seconds} 把实值下发给前端，<b>不</b>经 REST 暴露。
 * 擅自给这个 DTO 加字段就是破坏冻结契约。</p>
 */
@RestController
public class SettingsController implements SettingsApi {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public ResponseEntity<Settings> getSettings() {
        return ResponseEntity.ok(settingsService.toContract());
    }

    @Override
    public ResponseEntity<Settings> updateSettings(Settings settings) {
        return ResponseEntity.ok(settingsService.update(settings));
    }
}

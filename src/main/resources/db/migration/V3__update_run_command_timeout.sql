-- ============================================================================
-- V3：run_command 执行超时提升到 1800 秒（30 分钟）（design.md D3）
-- 依据：openspec/changes/add-v020-improvements/specs/ssh-connection/spec.md
--
-- WHY 不回写 V1：V1 已发布到既有用户库，改动其校验和会让 Flyway 校验失败；
--     追加 UPDATE 迁移让新旧库殊途同归（database-standards：迁移一经发布不回写）
-- WHY 不使用 CURRENT_TIMESTAMP：迁移必须可重复执行、结果确定（database-standards）
-- WHY 描述同时写明空闲 120 秒：ssh-connection spec 将执行超时（1800 秒）与
--     空闲心跳检测（120 秒）一并约定，设置界面展示时应可见两者语义
--
-- 全局约定延续 V1 / V2：
-- 1. 主键 TEXT（UUID）
-- 2. 时间 TEXT（ISO-8601）
-- 3. 布尔 INTEGER 0/1 + CHECK
-- 4. 本迁移 MUST NOT 写入任何凭据或秘密
-- ============================================================================

UPDATE settings
SET setting_value = '1800',
    description = '经审批执行的命令超时（30 分钟）；空闲 120 秒心跳检测（ssh-connection spec）'
WHERE setting_key = 'run_command.timeout.seconds';

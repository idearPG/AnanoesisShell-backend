package com.ananoesis.shell.mapper;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.entity.AiConversation;
import com.ananoesis.shell.entity.AiMessage;
import com.ananoesis.shell.entity.Approval;
import com.ananoesis.shell.entity.Credential;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.entity.Setting;
import com.ananoesis.shell.entity.SshSession;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * tasks 3.3 的验收：7 张表各自的 MyBatis-Plus Mapper 最小 CRUD 可用。
 *
 * <p>WHY 用"插入 → 按主键查 → 更新 → 删除"的最小闭环覆盖每个 Mapper：
 * 这四步足以暴露实体字段与 DDL 列不匹配、下划线转驼峰失效、主键策略错误、
 * 自动填充未生效等真实缺陷，而不必为 Wave 1 尚不存在的业务查询写多余断言。</p>
 *
 * <p>WHY 约束类断言只断到 {@link DataAccessException} + 根因消息含 "constraint"：
 * Spring 的 SQLException 翻译表没有 SQLite 专属条目，具体子类可能因版本而异；
 * 而"重复插入被拒绝"这一行为与拒绝原因（约束冲突）才是需要锁定的契约。</p>
 *
 * <p>每个用例自行清理写入的数据，保证与其它测试类的执行顺序无关。</p>
 */
class MapperCrudTest extends AbstractSqliteIntegrationTest {

    @Autowired
    private HostMapper hostMapper;
    @Autowired
    private CredentialMapper credentialMapper;
    @Autowired
    private SshSessionMapper sshSessionMapper;
    @Autowired
    private AiConversationMapper aiConversationMapper;
    @Autowired
    private AiMessageMapper aiMessageMapper;
    @Autowired
    private ApprovalMapper approvalMapper;
    @Autowired
    private SettingMapper settingMapper;

    @Test
    @DisplayName("hosts：插入后可按主键读回，字段无丢失")
    void hostMapperCrud() {
        Host host = newHost("web-01", "10.0.0.11", 22, "root", "password");

        assertThat(hostMapper.insert(host)).isEqualTo(1);
        assertThat(host.getId()).as("主键应由 MyBatis-Plus 自动分配").isNotBlank();

        Host loaded = hostMapper.selectById(host.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getName()).isEqualTo("web-01");
        assertThat(loaded.getHost()).isEqualTo("10.0.0.11");
        assertThat(loaded.getPort()).isEqualTo(22);
        assertThat(loaded.getUsername()).isEqualTo("root");
        assertThat(loaded.getAuthType()).isEqualTo("password");
        assertThat(loaded.getGroupName()).isEqualTo("生产");
        assertThat(loaded.getRemark()).isEqualTo("主站 Web");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();

        loaded.setPort(2222);
        assertThat(hostMapper.updateById(loaded)).isEqualTo(1);
        assertThat(hostMapper.selectById(host.getId()).getPort()).isEqualTo(2222);

        assertThat(hostMapper.deleteById(host.getId())).isEqualTo(1);
        assertThat(hostMapper.selectById(host.getId())).isNull();
    }

    @Test
    @DisplayName("credentials：密文可存取，且同宿主同类型凭据唯一")
    void credentialMapperCrudAndUniqueOwnerTuple() {
        Credential credential = new Credential();
        credential.setOwnerType("host");
        credential.setOwnerId("crud-host");
        credential.setCredentialType("ssh_password");
        credential.setCiphertext("v1.ZmFrZS1jaXBoZXJ0ZXh0");

        assertThat(credentialMapper.insert(credential)).isEqualTo(1);

        Credential loaded = credentialMapper.selectById(credential.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getCiphertext()).isEqualTo("v1.ZmFrZS1jaXBoZXJ0ZXh0");
        assertThat(loaded.getOwnerType()).isEqualTo("host");
        assertThat(loaded.getCredentialType()).isEqualTo("ssh_password");

        // WHY 唯一约束必要：同一宿主同一类型的凭据只能有一条，否则解密时会取到过期密文
        Credential duplicate = new Credential();
        duplicate.setOwnerType("host");
        duplicate.setOwnerId("crud-host");
        duplicate.setCredentialType("ssh_password");
        duplicate.setCiphertext("v1.bbb");
        assertConstraintViolation(catchThrowable(() -> credentialMapper.insert(duplicate)));

        assertThat(credentialMapper.deleteById(credential.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("sessions：生命周期字段可读写，外键约束生效，删除主机级联清理会话")
    void sshSessionMapperCrud() {
        Host host = newHost("db-01", "10.0.0.21", 22, "ops", "private_key");
        hostMapper.insert(host);

        SshSession session = new SshSession();
        session.setHostId(host.getId());
        session.setSessionType("interactive_pty");
        session.setStatus("open");
        session.setStartedAt(LocalDateTime.now());
        assertThat(sshSessionMapper.insert(session)).isEqualTo(1);

        SshSession loaded = sshSessionMapper.selectById(session.getId());
        assertThat(loaded.getStatus()).isEqualTo("open");

        loaded.setStatus("closed");
        loaded.setEndedAt(LocalDateTime.now());
        loaded.setCloseReason("user_disconnect");
        sshSessionMapper.updateById(loaded);

        SshSession reloaded = sshSessionMapper.selectById(session.getId());
        assertThat(reloaded.getStatus()).isEqualTo("closed");
        assertThat(reloaded.getCloseReason()).isEqualTo("user_disconnect");
        assertThat(reloaded.getEndedAt()).isNotNull();

        // foreign_keys 是**连接级** PRAGMA，必须由 JDBC URL 参数保证；
        // 若未生效，指向不存在主机的孤儿会话会被静默写入，审计链随之断裂
        SshSession orphan = new SshSession();
        orphan.setHostId("no-such-host");
        orphan.setSessionType("exec");
        orphan.setStatus("connecting");
        orphan.setStartedAt(LocalDateTime.now());
        assertConstraintViolation(catchThrowable(() -> sshSessionMapper.insert(orphan)));

        // 删除主机应级联清理其会话（ON DELETE CASCADE）
        hostMapper.deleteById(host.getId());
        assertThat(sshSessionMapper.selectById(session.getId())).isNull();
    }

    @Test
    @DisplayName("ai_conversations：会话可创建、归档、删除")
    void aiConversationMapperCrud() {
        AiConversation conversation = new AiConversation();
        conversation.setTitle("排查 nginx 502");
        conversation.setStatus("active");

        assertThat(aiConversationMapper.insert(conversation)).isEqualTo(1);

        AiConversation loaded = aiConversationMapper.selectById(conversation.getId());
        assertThat(loaded.getTitle()).isEqualTo("排查 nginx 502");
        assertThat(loaded.getStatus()).isEqualTo("active");
        assertThat(loaded.getHostId()).as("host_id 可空").isNull();

        loaded.setStatus("archived");
        aiConversationMapper.updateById(loaded);
        assertThat(aiConversationMapper.selectById(conversation.getId()).getStatus()).isEqualTo("archived");

        assertThat(aiConversationMapper.deleteById(conversation.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("ai_messages：思考过程与工具调用明细可分别落库并按 seq 排序")
    void aiMessageMapperCrud() {
        AiConversation conversation = new AiConversation();
        conversation.setTitle("tmp-msg");
        conversation.setStatus("active");
        aiConversationMapper.insert(conversation);

        AiMessage assistant = new AiMessage();
        assistant.setConversationId(conversation.getId());
        assistant.setSeq(1);
        assistant.setRole("assistant");
        assistant.setContent("建议先看磁盘占用");
        assistant.setReasoningContent("用户报告 502，通常与上游超时或磁盘满有关");
        assistant.setToolCalls("[{\"id\":\"call_1\",\"name\":\"run_command\"}]");
        aiMessageMapper.insert(assistant);

        AiMessage tool = new AiMessage();
        tool.setConversationId(conversation.getId());
        tool.setSeq(2);
        tool.setRole("tool");
        tool.setToolCallId("call_1");
        tool.setToolName("run_command");
        tool.setToolArguments("{\"command\":\"systemctl restart nginx\"}");
        tool.setToolResult("用户已拒绝");
        tool.setToolRejected(1);
        aiMessageMapper.insert(tool);

        List<AiMessage> messages = aiMessageMapper.selectList(
                new QueryWrapper<AiMessage>()
                        .eq("conversation_id", conversation.getId())
                        .orderByAsc("seq"));

        assertThat(messages).hasSize(2);
        // 思考过程必须与最终回答分开可读（model-provider spec：思考/非思考双模式）
        assertThat(messages.get(0).getContent()).isEqualTo("建议先看磁盘占用");
        assertThat(messages.get(0).getReasoningContent()).contains("磁盘满");
        // 工具名/参数/结果必须可追溯（ai-agent spec：操作透明性）
        assertThat(messages.get(1).getToolName()).isEqualTo("run_command");
        assertThat(messages.get(1).getToolArguments()).contains("systemctl");
        assertThat(messages.get(1).getToolResult()).isEqualTo("用户已拒绝");
        assertThat(messages.get(1).getToolRejected()).isEqualTo(1);

        // 删除会话应级联清理消息
        aiConversationMapper.deleteById(conversation.getId());
        assertThat(aiMessageMapper.selectList(
                new QueryWrapper<AiMessage>().eq("conversation_id", conversation.getId()))).isEmpty();
    }

    @Test
    @DisplayName("approvals：审计要素（时间/工具/参数/AI分析/决定/执行结果）完整可写可读")
    void approvalMapperCrud() {
        Approval approval = new Approval();
        approval.setToolName("run_command");
        approval.setToolArguments("{\"command\":\"systemctl restart nginx\"}");
        approval.setAiAnalysis("nginx 配置已更新，需要重启才能生效；影响面为该机的 Web 服务");
        approval.setDecision("pending");
        approval.setRequestedAt(LocalDateTime.now());
        approval.setExpiresAt(LocalDateTime.now().plusSeconds(120));
        approval.setExecutionStatus("not_executed");
        approval.setOutputTruncated(0);

        assertThat(approvalMapper.insert(approval)).isEqualTo(1);
        assertThat(approval.getId()).as("approval_id 应自动分配").isNotBlank();

        approval.setDecision("approved");
        approval.setDecidedBy("user");
        approval.setDecidedAt(LocalDateTime.now());
        approval.setExecutionStatus("success");
        approval.setExecutionResult("exit=0; stdout=...");
        approval.setExitCode(0);
        approvalMapper.updateById(approval);

        Approval loaded = approvalMapper.selectById(approval.getId());
        assertThat(loaded.getToolName()).isEqualTo("run_command");
        assertThat(loaded.getToolArguments()).contains("systemctl");
        assertThat(loaded.getAiAnalysis()).contains("重启");
        assertThat(loaded.getDecision()).isEqualTo("approved");
        assertThat(loaded.getDecidedBy()).isEqualTo("user");
        assertThat(loaded.getRequestedAt()).isNotNull();
        assertThat(loaded.getDecidedAt()).isNotNull();
        assertThat(loaded.getExecutionStatus()).isEqualTo("success");
        assertThat(loaded.getExecutionResult()).startsWith("exit=0");
        assertThat(loaded.getExitCode()).isEqualTo(0);
        assertThat(loaded.getOutputTruncated()).isZero();

        assertThat(approvalMapper.deleteById(approval.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("settings：V1 预置的 spec 阈值可读写，主键为 setting_key")
    void settingMapperCrud() {
        Setting timeout = settingMapper.selectById("approval.timeout.seconds");
        assertThat(timeout).as("V1 迁移应预置审批超时阈值").isNotNull();
        assertThat(timeout.getSettingValue()).isEqualTo("120");
        assertThat(timeout.getValueType()).isEqualTo("number");

        timeout.setSettingValue("90");
        assertThat(settingMapper.updateById(timeout)).isEqualTo(1);
        assertThat(settingMapper.selectById("approval.timeout.seconds").getSettingValue()).isEqualTo("90");
        // 还原，避免影响同 JVM 内其它断言
        timeout.setSettingValue("120");
        settingMapper.updateById(timeout);

        Setting created = new Setting();
        created.setSettingKey("test.only.key");
        created.setSettingValue("v");
        created.setValueType("string");
        assertThat(settingMapper.insert(created)).isEqualTo(1);
        assertThat(settingMapper.deleteById("test.only.key")).isEqualTo(1);
    }

    @Test
    @DisplayName("插入自动填充 createdAt/updatedAt，更新自动刷新 updatedAt")
    void auditTimestampsAreAutoFilled() {
        Host host = newHost("fill-01", "10.0.0.31", 22, "root", "password");
        hostMapper.insert(host);

        Host inserted = hostMapper.selectById(host.getId());
        assertThat(inserted.getCreatedAt()).as("插入时应自动填充创建时间").isNotNull();
        assertThat(inserted.getUpdatedAt()).as("插入时应自动填充更新时间").isNotNull();
        assertThat(inserted.getUpdatedAt()).isEqualTo(inserted.getCreatedAt());

        // WHY 显式等待：时间列以秒级 ISO-8601 文本存储，立即更新可能与插入落在同一秒而无法区分
        sleep(1100);
        inserted.setName("fill-01-renamed");
        hostMapper.updateById(inserted);

        Host updated = hostMapper.selectById(host.getId());
        assertThat(updated.getCreatedAt()).as("创建时间不得被更新覆盖").isEqualTo(inserted.getCreatedAt());
        assertThat(updated.getUpdatedAt()).as("更新后 updatedAt 应晚于 createdAt").isAfter(inserted.getCreatedAt());

        hostMapper.deleteById(host.getId());
    }

    /** 断言一次数据库约束冲突：Spring 数据访问异常 + 根因为含 "constraint" 的 SQLException。 */
    private static void assertConstraintViolation(Throwable thrown) {
        assertThat(thrown).as("约束冲突应被拒绝").isInstanceOf(DataAccessException.class);
        assertThat(thrown).rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("constraint");
    }

    private static Host newHost(String name, String host, int port, String username, String authType) {
        Host entity = new Host();
        entity.setName(name);
        entity.setHost(host);
        entity.setPort(port);
        entity.setUsername(username);
        entity.setAuthType(authType);
        entity.setGroupName("生产");
        entity.setRemark("主站 Web");
        return entity;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试被中断", e);
        }
    }
}

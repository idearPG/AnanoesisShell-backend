package com.ananoesis.shell.approval;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ananoesis.shell.contract.model.Approval;
import com.ananoesis.shell.contract.model.ApprovalDecision;
import com.ananoesis.shell.contract.model.ApprovalsPage;
import com.ananoesis.shell.contract.model.ExecutionResult;
import com.ananoesis.shell.contract.model.ExecutionStatus;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.ApprovalMapper;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.support.Timestamps;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code approvals} 表的读写与审计装配（tasks 8.5 / 6.4）。
 *
 * <p>WHY 放在 {@code approval} 包而不是 {@code service} 包：本类只服务审批子系统，
 * 且要与 {@link ApprovalGate}、{@link ApprovedCommandRunner} 共享
 * {@link ApprovalOutcome}/{@link CommandExecution} 这两个内部类型。
 * 放进 {@code service} 会造成 {@code service → approval} 与 {@code approval → service}
 * 的<b>包循环</b>——Java 允许，但它意味着两个包实际上是一个，只是被人为切开了。</p>
 *
 * <h2>审计的四个阶段</h2>
 * <ol>
 *   <li>{@link #insertPending} —— 提案受理时立刻落一行 {@code decision=pending}。
 *       WHY 不等决定出来再写：进程在等待期间被杀（用户直接关窗口）时，
 *       已落库的 pending 行是唯一能证明"这条命令曾被提议过"的痕迹；
 *       事后再写就等于让崩溃吞掉证据。</li>
 *   <li>{@link #recordModification} —— 用户修改命令时更新版本号与最终命令（6.4）。</li>
 *   <li>{@link #recordDecision} —— 拿到用户决定或超时兜底时更新
 *       {@code decision/decided_by/decided_at}。</li>
 *   <li>{@link #recordExecution}/{@link #recordExecutionFailure} —— 命令真的跑过之后
 *       更新执行结果。</li>
 * </ol>
 *
 * <h2>安全</h2>
 * <p>spec 与契约都要求审计 MUST NOT 含明文凭据。本类落库的三处文本
 * （{@code tool_arguments}/{@code ai_analysis}/{@code execution_result}）都来自
 * <b>模型产出</b>与<b>命令输出</b>，凭据解密发生在 {@code SshTargetResolver} 的回调窗口内、
 * 从不经过这里。日志同样只打 id/决定/状态，不打命令原文。</p>
 */
@Service
public class ApprovalAuditService {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalAuditService.class);

    /** {@code approvals.decision} 的"尚无决定"字面值（DDL 默认值）。 */
    private static final String DECISION_PENDING = "pending";

    /** {@code approvals.execution_status} 的"未执行"字面值。 */
    private static final String EXECUTION_NOT_EXECUTED = "not_executed";

    /** 用户取消时回喂与展示的固定文案（spec「用户取消执行」）。 */
    public static final String REJECTED_NOTE = "用户已拒绝";

    /** 超时兜底时的固定文案（spec「审批超时自动取消」）。 */
    public static final String TIMED_OUT_NOTE = "审批超时，已自动取消";

    /** {@code execution_result} 列的 JSON 键。 */
    private static final String KEY_STDOUT = "stdout";
    private static final String KEY_STDERR = "stderr";
    private static final String KEY_NOTE = "note";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ApprovalMapper approvals;
    private final HostMapper hosts;
    private final ObjectMapper objectMapper;

    public ApprovalAuditService(ApprovalMapper approvals, HostMapper hosts, ObjectMapper objectMapper) {
        this.approvals = Objects.requireNonNull(approvals, "approvals 不得为 null");
        this.hosts = Objects.requireNonNull(hosts, "hosts 不得为 null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不得为 null");
    }

    // ==================================================================
    // 写入
    // ==================================================================

    /**
     * 落一条 {@code decision=pending} 的审计行。
     *
     * @param approvalId   由 {@link ApprovalGate} 分配的 id（canonical UUID 形式）
     * @param proposal     提案
     * @param requestedAt  受理时刻
     * @param expiresAt    失效时刻（= requestedAt + 审批时限）
     */
    @Transactional
    public void insertPending(UUID approvalId, ApprovalProposal proposal,
                              OffsetDateTime requestedAt, OffsetDateTime expiresAt) {
        Objects.requireNonNull(approvalId, "approvalId 不得为 null");
        Objects.requireNonNull(proposal, "proposal 不得为 null");

        var row = new com.ananoesis.shell.entity.Approval();
        row.setId(approvalId.toString());
        row.setConversationId(proposal.conversationId().toString());
        row.setMessageId(proposal.messageId() == null ? null : proposal.messageId().toString());
        row.setHostId(proposal.hostId().toString());
        row.setToolName(proposal.toolName().getValue());
        row.setToolArguments(toJson(proposal.toolParams()));
        row.setAiAnalysis(proposal.aiAnalysis());
        row.setDecision(DECISION_PENDING);
        row.setRequestedAt(Timestamps.toLocal(requestedAt));
        row.setExpiresAt(Timestamps.toLocal(expiresAt));
        row.setExecutionStatus(EXECUTION_NOT_EXECUTED);
        row.setOutputTruncated(0);
        row.setVersion(1);
        approvals.insert(row);
        // WHY 日志不含命令原文：见类注释的安全段
        LOG.info("已登记待审批命令: approvalId={} hostId={} tool={} timeoutSeconds={}",
                approvalId, proposal.hostId(), proposal.toolName().getValue(),
                expiresAt == null ? null : expiresAt.toEpochSecond() - requestedAt.toEpochSecond());
    }

    /**
     * 记录用户修改命令（版本递增，design D5 / 6.4）。
     *
     * <p>WHY 只更新 {@code version} 和 {@code final_command} 两列：
     * 修改不改变提案的其他审计要素（工具名、分析、目标主机等），
     * 只记录"最终执行的命令是什么"以及"这是第几个版本"。</p>
     *
     * @param approvalId      审批 id
     * @param modifiedCommand 修改后的命令
     * @param newVersion      递增后的新版本号
     */
    @Transactional
    public void recordModification(UUID approvalId, String modifiedCommand, int newVersion) {
        Objects.requireNonNull(approvalId, "approvalId 不得为 null");
        Objects.requireNonNull(modifiedCommand, "modifiedCommand 不得为 null");

        var row = new com.ananoesis.shell.entity.Approval();
        row.setId(approvalId.toString());
        row.setVersion(newVersion);
        row.setFinalCommand(modifiedCommand);
        approvals.updateById(row);
        LOG.info("审批命令已修改: approvalId={} newVersion={}", approvalId, newVersion);
    }

    /**
     * 记录用户决定（或超时兜底）。
     *
     * <p>WHY 用 {@code updateById} 而不是先查后改：这条路径可能被"用户点击"与
     * "超时定时器"并发触发。闸门已用 {@code CompletableFuture#complete} 的原子性
     * 保证只有一方胜出，因此这里的写不需要再做一次竞态处理；
     * 但只写这三列（不整行覆盖）能避免把对方刚写的执行结果冲掉。</p>
     */
    @Transactional
    public void recordDecision(UUID approvalId, ApprovalOutcome outcome, OffsetDateTime decidedAt) {
        var row = new com.ananoesis.shell.entity.Approval();
        row.setId(approvalId.toString());
        row.setDecision(outcome.dbDecision());
        row.setDecidedBy(outcome.dbDecidedBy());
        row.setDecidedAt(Timestamps.toLocal(decidedAt));
        approvals.updateById(row);
        LOG.info("审批已有决定: approvalId={} decision={} decidedBy={}",
                approvalId, outcome.dbDecision(), outcome.dbDecidedBy());
    }

    /**
     * 记录命令的执行结果（仅在被批准并真的执行之后调用）。
     */
    @Transactional
    public void recordExecution(UUID approvalId, CommandExecution execution) {
        var row = new com.ananoesis.shell.entity.Approval();
        row.setId(approvalId.toString());
        row.setExecutionStatus(execution.dbExecutionStatus());
        row.setExitCode(execution.exitCode());
        row.setOutputTruncated(execution.truncated() || execution.timedOut() ? 1 : 0);
        row.setExecutionResult(toJson(Map.of(
                KEY_STDOUT, execution.stdout(),
                KEY_STDERR, execution.stderr(),
                KEY_NOTE, noteOf(execution))));
        approvals.updateById(row);
        LOG.info("审批命令执行完毕: approvalId={} status={} exit={} truncated={} timedOut={}",
                approvalId, execution.dbExecutionStatus(), execution.exitCode(),
                execution.truncated(), execution.timedOut());
    }

    /**
     * 记录"已批准但没能执行"（连不上主机、通道坏了）。
     *
     * <p>WHY 与 {@link #recordExecution} 分开：{@code CommandExecution} 描述的是
     * 远端 shell 给出的事实，而连接失败时压根没有 shell 参与。
     * 硬塞进同一个记录会让 {@code exitCode} 变成一个编造出来的值。</p>
     */
    @Transactional
    public void recordExecutionFailure(UUID approvalId, String note) {
        var row = new com.ananoesis.shell.entity.Approval();
        row.setId(approvalId.toString());
        row.setExecutionStatus("failed");
        row.setOutputTruncated(0);
        row.setExecutionResult(toJson(Map.of(KEY_STDOUT, "", KEY_STDERR, "", KEY_NOTE, note == null ? "" : note)));
        approvals.updateById(row);
        LOG.warn("审批命令未能执行: approvalId={} note={}", approvalId, note);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询审计记录。
     *
     * <p>WHY 排除 {@code pending}：契约的 {@code ApprovalDecision} 只有
     * {@code approved/cancelled/timed_out} 三个取值，<b>没有</b> pending。
     * 一条还在等待的行无法映射到任何一个——硬选一个就是让审计说谎。
     * "当前有哪些待审批"是实时状态，走 WebSocket 推送，不属于审计查询。</p>
     *
     * <p>WHY 手写 {@code LIMIT/OFFSET} 而不用 MyBatis-Plus 的 {@code Page}：
     * 分页拦截器 {@code PaginationInnerInterceptor} 并未在本项目注册
     * （{@code MybatisPlusConfiguration} 只配了审计字段填充），
     * 传入 {@code Page} 会被静默忽略、返回全表——数据量小时看不出来，
     * 审计攒到几万条后接口就变成一次全表加载。</p>
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    public ApprovalsPage page(int page, int size, @Nullable UUID hostId, @Nullable ApprovalDecision decision) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        QueryWrapper<com.ananoesis.shell.entity.Approval> query = baseQuery(hostId, decision);
        Long total = approvals.selectCount(query);

        QueryWrapper<com.ananoesis.shell.entity.Approval> slice = baseQuery(hostId, decision);
        // WHY 追加 id 作为次级排序键：同一秒内提议的多条命令（智能体一次给多个工具调用时很常见）
        // 否则顺序由 SQLite 的扫描计划决定，翻页时同一条记录可能在两页都出现或都不出现
        slice.orderByDesc("requested_at", "id")
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize);

        List<com.ananoesis.shell.entity.Approval> rows = approvals.selectList(slice);
        Map<String, String> labels = hostLabels();

        List<Approval> items = new ArrayList<>(rows.size());
        for (var row : rows) {
            items.add(toDto(row, labels.get(row.getHostId())));
        }
        ApprovalsPage result = new ApprovalsPage();
        result.setItems(items);
        result.setTotal(total == null ? 0L : total);
        result.setPage(safePage);
        result.setSize(safeSize);
        return result;
    }

    /**
     * WHY 每次都新建 QueryWrapper：MyBatis-Plus 的 wrapper 是<b>有状态</b>的，
     * count 与 list 复用同一个实例会让 {@code ORDER BY}/{@code LIMIT} 被拼进 count 语句，
     * 在 SQLite 上直接报语法错。
     */
    private static QueryWrapper<com.ananoesis.shell.entity.Approval> baseQuery(
            @Nullable UUID hostId, @Nullable ApprovalDecision decision) {
        QueryWrapper<com.ananoesis.shell.entity.Approval> query = new QueryWrapper<>();
        query.ne("decision", DECISION_PENDING);
        if (hostId != null) {
            query.eq("host_id", hostId.toString());
        }
        if (decision != null) {
            query.eq("decision", dbDecisionOf(decision));
        }
        return query;
    }

    /**
     * 读回一条审计行<b>已经落库</b>的决定。
     *
     * <p>WHY 闸门需要它：用户的裁决可能在智能体调 {@code await} <b>之前</b>就已完成——
     * {@code submit} 与 {@code await} 之间隔着一次 {@code ai_stream(tool_call)} 的
     * WebSocket 广播，而那次广播的发送时限是 10 秒。前端弹框极快、或自动化脚本直接回帧时，
     * 挂起条目早已被摘除。此时内存里已无痕迹，闸门只能回落到<b>数据库</b>这个唯一真相来源上，
     * 把真实结局还给等待方。若不回落，用户点了批准、命令却按超时不执行，
     * 而审计行写着 approved —— 三处彼此矛盾，且没有任何报错。</p>
     *
     * @return 已落库的结局；行不存在、或仍是 {@code pending}（尚无结局）时为 {@code null}
     */
    @Nullable
    public ApprovalOutcome decisionOf(@Nullable UUID approvalId) {
        if (approvalId == null) {
            return null;
        }
        com.ananoesis.shell.entity.Approval row = approvals.selectById(approvalId.toString());
        return row == null ? null : ApprovalOutcome.fromDbDecision(row.getDecision());
    }

    /**
     * 回读审批的最终生效命令（即修改后命令）。
     *
     * <p>只有 {@link #recordModification} 会写 final_command：未修改过时该列为
     * {@code null}，调用方应以提案原命令回退。裁决后内存条目已销毁，
     * 审计行是修改事实的唯一留存，执行链路靠本方法拿到用户改后的命令。</p>
     *
     * @return 修改后命令；行不存在或从未修改过时为 {@code null}
     */
    @Nullable
    public String finalCommandOf(@Nullable UUID approvalId) {
        if (approvalId == null) {
            return null;
        }
        com.ananoesis.shell.entity.Approval row = approvals.selectById(approvalId.toString());
        return row == null ? null : row.getFinalCommand();
    }

    private static String dbDecisionOf(ApprovalDecision decision) {
        for (ApprovalOutcome candidate : ApprovalOutcome.values()) {
            if (candidate.toContract() == decision) {
                return candidate.dbDecision();
            }
        }
        // 不可达：契约枚举的三个取值都在 ApprovalOutcome 里
        throw new IllegalStateException("未建模的审批决定: " + decision);
    }

    // ==================================================================
    // 装配
    // ==================================================================

    private Approval toDto(com.ananoesis.shell.entity.Approval row, @Nullable String hostLabel) {
        Approval dto = new Approval();
        dto.setId(UUID.fromString(row.getId()));
        dto.setCreatedAt(Timestamps.toOffset(row.getCreatedAt() == null ? row.getRequestedAt() : row.getCreatedAt()));
        dto.setHostId(row.getHostId() == null ? null : UUID.fromString(row.getHostId()));
        dto.setHostLabel(hostLabel);
        dto.setToolName(row.getToolName());
        dto.setToolParams(parseMap(row.getToolArguments()));
        dto.setAiAnalysis(row.getAiAnalysis());

        ApprovalOutcome outcome = ApprovalOutcome.fromDbDecision(row.getDecision());
        if (outcome != null) {
            dto.setDecision(outcome.toContract());
        }
        dto.setDecidedAt(Timestamps.toOffset(row.getDecidedAt()));
        dto.setExecution(toExecution(outcome, row));
        return dto;
    }

    /**
     * 库里的执行事实 → 契约 {@code ExecutionResult}。
     *
     * <p>WHY 未被批准的行一律回 {@code REJECTED}：契约的 {@code ExecutionStatus}
     * 只有 {@code executed/rejected/execution_timeout/output_truncated} 四个取值，
     * "没执行"这一事实只能落在 {@code rejected} 上，并用 {@code note} 说明是用户拒绝还是超时。
     * 若回 {@code executed} 而 stdout 为空，用户会以为命令跑了但没输出——那是最误导的一种呈现。</p>
     */
    private ExecutionResult toExecution(@Nullable ApprovalOutcome outcome,
                                        com.ananoesis.shell.entity.Approval row) {
        Map<String, Object> stored = parseMap(row.getExecutionResult());
        ExecutionResult execution = new ExecutionResult();
        execution.setExitCode(row.getExitCode());
        execution.setStdout(asText(stored.get(KEY_STDOUT)));
        execution.setStderr(asText(stored.get(KEY_STDERR)));
        execution.setTruncated(row.getOutputTruncated() != null && row.getOutputTruncated() == 1);

        if (outcome == null || !outcome.approved()) {
            execution.setStatus(ExecutionStatus.REJECTED);
            execution.setNote(outcome == ApprovalOutcome.TIMED_OUT ? TIMED_OUT_NOTE : REJECTED_NOTE);
            return execution;
        }

        String dbStatus = row.getExecutionStatus();
        if ("timeout".equals(dbStatus)) {
            execution.setStatus(ExecutionStatus.EXECUTION_TIMEOUT);
        } else if ("truncated".equals(dbStatus)) {
            execution.setStatus(ExecutionStatus.OUTPUT_TRUNCATED);
        } else {
            // success / failed / not_executed / running 都归 EXECUTED：
            // 契约没有"命令返回非 0"这一档，退出码已经如实回给前端了
            execution.setStatus(ExecutionStatus.EXECUTED);
        }
        execution.setNote(asText(stored.get(KEY_NOTE)));
        return execution;
    }

    /**
     * 主机的展示名表。
     *
     * <p>WHY 直接读 {@code HostMapper} 而不走 {@code HostService}：契约的 {@code Host} DTO
     * 里<b>没有</b> name 字段（只有 host 地址、group_name、note），拿不到展示名；
     * 为审批专门给 HostService 加一个 public 方法，会让它承担一个只有本子系统用的展示职责。
     * {@code SessionService#hostLabels()} 已经是同样的做法，此处保持一致。</p>
     */
    private Map<String, String> hostLabels() {
        Map<String, String> labels = new HashMap<>();
        for (Host host : hosts.selectList(null)) {
            labels.put(host.getId(), host.getName());
        }
        return labels;
    }

    private String noteOf(CommandExecution execution) {
        if (execution.timedOut()) {
            return "命令执行超时，已被中断";
        }
        if (execution.truncated()) {
            return "输出超过上限，已截断";
        }
        return "";
    }

    private String toJson(@Nullable Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // 落不下 JSON 也不能让审批流程中断：退化成空对象，审计里少一段参数明细，
            // 但决定与执行结果这些关键事实仍在
            LOG.warn("审批审计的 JSON 序列化失败，已退化为空对象: {}", String.valueOf(e.getMessage()));
            return "{}";
        }
    }

    private Map<String, Object> parseMap(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // 一行坏数据不该把整个审计列表打成 500；退化成空表，其余字段照常呈现
            LOG.warn("审批审计的 JSON 解析失败，已退化为空对象: {}", String.valueOf(e.getMessage()));
            return Map.of();
        }
    }

    private static String asText(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

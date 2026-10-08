package com.ananoesis.shell.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ananoesis.shell.contract.model.AuthType;
import com.ananoesis.shell.contract.model.Host;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.security.CredentialOwnerType;
import com.ananoesis.shell.security.CredentialType;
import com.ananoesis.shell.security.SecretText;
import com.ananoesis.shell.service.InvalidRequestException.FieldViolation;
import com.ananoesis.shell.support.EntityIds;

/**
 * 服务器配置的业务规则与凭据编排（tasks 5.1）。
 *
 * <p>职责边界：本类负责"能不能存、存什么、存完之后旧的怎么清理"，
 * HTTP 形状由 {@code HostsController} 负责，加解密由 {@link CredentialStoreService} 负责。
 * MUST NOT 直接触碰 {@code CredentialMapper}——那是 credential-store spec
 * "加密收口于唯一通道"这条 MUST 的结构保证，绕过它需要显式注入，评审时一眼可见。</p>
 *
 * <p><b>日志纪律</b>：契约 DTO {@code Host} 由 openapi-generator 生成，其 {@code toString()}
 * 只掩码了 {@code password}/{@code passphrase}，却把 {@code privateKey} **完整打印**。
 * 因此本类在任何级别都 MUST NOT 把请求 DTO 写进日志，只记录 id/host/authType 这类坐标。
 * 该风险由 {@code HostsApiCredentialLoggingTest} 在 DEBUG 级别下回归。</p>
 */
@Service
public class HostService {

    private static final Logger LOG = LoggerFactory.getLogger(HostService.class);

    private static final CredentialOwnerType OWNER = CredentialOwnerType.HOST;

    private final HostMapper hostMapper;
    private final CredentialStoreService credentials;

    public HostService(HostMapper hostMapper, CredentialStoreService credentials) {
        this.hostMapper = Objects.requireNonNull(hostMapper, "hostMapper 不得为 null");
        this.credentials = Objects.requireNonNull(credentials, "credentials 不得为 null");
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public List<Host> list() {
        return hostMapper.selectAllOrderedByCreation().stream().map(this::toDto).toList();
    }

    public Host findById(UUID id) {
        return toDto(requireEntity(id));
    }

    /**
     * 取回连接所需的目标信息。
     *
     * <p>WHY 不直接把实体交给 SSH 层：实体是持久化模型，字段随表结构演进；
     * SSH 层需要的只是一组稳定的连接坐标。以本记录作为边界，
     * 日后 {@code hosts} 加列不会波及连接逻辑。</p>
     *
     * @throws HostNotFoundException 配置不存在
     */
    public HostTarget requireTarget(UUID hostId) {
        var entity = requireEntity(hostId);
        return new HostTarget(
                UUID.fromString(entity.getId()),
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                AuthType.fromValue(entity.getAuthType()));
    }

    /**
     * SSH 连接目标（不可变快照）。
     *
     * @param hostId   主机配置主键
     * @param host     主机地址或域名
     * @param port     SSH 端口
     * @param username 登录用户名
     * @param authType 认证方式，决定去 credentials 表取哪一种凭据
     */
    public record HostTarget(UUID hostId, String host, int port, String username, AuthType authType) {
    }

    // ==================================================================
    // 写入
    // ==================================================================

    /**
     * 新建配置并加密保存其凭据。
     *
     * <p>WHY 先校验后写库、且整体在一个事务里：DDL 上 {@code hosts} 与 {@code credentials}
     * 之间没有外键（凭据表用泛化宿主三元组），"存了配置却没存成凭据"不会被数据库拦住。
     * 事务保证两步要么都成、要么都不成，避免留下一台 {@code credential_set=false}
     * 的"半成品主机"让用户困惑。</p>
     */
    @Transactional
    public Host create(Host request) {
        requireAuthType(request);
        rejectIfAny(violationsOnCreate(request));

        var entity = new com.ananoesis.shell.entity.Host();
        // WHY 显式赋 id：见 EntityIds 的类注释——MyBatis-Plus 的 ASSIGN_UUID 产出
        // 无连字符的 32 位串，而契约把 id 声明为 format: uuid，两者不兼容
        entity.setId(EntityIds.newUuid());
        HostAssembler.applyConfiguration(request, entity);
        hostMapper.insert(entity);

        writeCredentials(entity.getId(), request);

        LOG.info("已创建服务器配置: id={} host={}:{} authType={}",
                entity.getId(), entity.getHost(), entity.getPort(), entity.getAuthType());
        // 重新读取：created_at/updated_at 由数据库侧确认，返回给客户端的就是库里的真实值
        return toDto(hostMapper.selectById(entity.getId()));
    }

    /**
     * 更新配置；凭据"给了就换、没给就留、不适用就删"。
     *
     * <p>三条凭据规则的 WHY：
     * <ul>
     *   <li><b>给了就换</b>：契约的 {@code password} 等字段是 writeOnly，响应永不回显，
     *       前端因此无法"回填旧值再提交"。若把"请求里没带凭据"理解成"清空凭据"，
     *       用户改个端口就会顺手把密码弄丢。</li>
     *   <li><b>切换认证方式时删掉不再适用者</b>：残留的旧密码密文既不会被使用，
     *       又会让"这台机器用什么认证"的审计问题出现两个矛盾答案。</li>
     *   <li><b>换私钥但没给新 passphrase 时删掉旧 passphrase</b>：passphrase 是用来解密
     *       <i>某一把</i>私钥的。留着它，sshj 会拿旧口令去解新密钥，
     *       用户看到的是一个与真实原因毫无关系的"认证失败"。</li>
     * </ul></p>
     */
    @Transactional
    public Host update(UUID id, Host request) {
        var entity = requireEntity(id);
        requireAuthType(request);

        AuthType target = request.getAuthType();
        boolean switching = !target.getValue().equals(entity.getAuthType());
        rejectIfAny(violationsOnUpdate(entity.getId(), target, request, switching));

        HostAssembler.applyConfiguration(request, entity);
        hostMapper.updateConfiguration(entity);

        if (switching) {
            dropInapplicableCredentials(entity.getId(), target);
        }
        writeCredentials(entity.getId(), request);
        if (target == AuthType.PRIVATE_KEY
                && HostAssembler.hasText(request.getPrivateKey())
                && !HostAssembler.hasText(request.getPassphrase())) {
            credentials.delete(OWNER, entity.getId(), CredentialType.SSH_PASSPHRASE);
        }

        LOG.info("已更新服务器配置: id={} host={}:{} authType={} switched={}",
                entity.getId(), entity.getHost(), entity.getPort(), entity.getAuthType(), switching);
        return toDto(hostMapper.selectById(entity.getId()));
    }

    /**
     * 删除配置及其全部凭据。
     *
     * <p>WHY 要显式删凭据：{@code credentials.owner_id} 是**没有外键**的泛化宿主标识，
     * 删除 hosts 行不会级联清理它。留下的孤立密文既占空间，
     * 也让"某台机器的密钥还在不在库里"这个安全问题变得无法回答。
     * （{@code sessions} 表则有真正的 FK，由 SQLite 的 {@code ON DELETE CASCADE} 处理。）</p>
     */
    @Transactional
    public void delete(UUID id) {
        var entity = requireEntity(id);
        int removed = credentials.deleteByOwner(OWNER, entity.getId());
        hostMapper.deleteById(entity.getId());
        LOG.info("已删除服务器配置: id={} host={} 连带清理凭据 {} 条", entity.getId(), entity.getHost(), removed);
    }

    // ==================================================================
    // 校验
    // ==================================================================

    /**
     * 认证方式与凭据的条件必填校验。
     *
     * <p>WHY 必须在后端做：这是**跨字段**约束，OpenAPI/JSR-380 表达不了。
     * 放过它，用户就能保存出一台"永远连不上"的配置，且界面上没有任何提示，
     * 直到第一次点连接才收到一个语焉不详的认证失败。</p>
     */
    private static List<FieldViolation> violationsOnCreate(Host request) {
        if (request.getAuthType() == AuthType.PASSWORD && !HostAssembler.hasText(request.getPassword())) {
            return List.of(new FieldViolation("password", "认证方式为 password 时必须提供密码"));
        }
        if (request.getAuthType() == AuthType.PRIVATE_KEY && !HostAssembler.hasText(request.getPrivateKey())) {
            return List.of(new FieldViolation("private_key", "认证方式为 private_key 时必须提供私钥"));
        }
        return List.of();
    }

    /**
     * 更新场景的凭据校验：只有"切换到一种当前库里并不具备的认证方式、又没带新凭据"才违规。
     *
     * <p>WHY 比创建时宽松：更新请求通常只改端口/分组，此时凭据字段为空是**正常**的，
     * 含义是"保持不变"（见 {@link #update} 的三条规则）。</p>
     */
    private List<FieldViolation> violationsOnUpdate(String hostId, AuthType target,
                                                    Host request, boolean switching) {
        if (!switching) {
            return List.of();
        }
        CredentialType required = requiredCredentialOf(target);
        boolean alreadyStored = credentials.exists(OWNER, hostId, required);
        String provided = target == AuthType.PASSWORD ? request.getPassword() : request.getPrivateKey();
        if (alreadyStored || HostAssembler.hasText(provided)) {
            return List.of();
        }
        String field = target == AuthType.PASSWORD ? "password" : "private_key";
        return List.of(new FieldViolation(field,
                "切换到 " + target.getValue() + " 认证时必须提供" + (target == AuthType.PASSWORD ? "密码" : "私钥")));
    }

    private static void requireAuthType(Host request) {
        // Bean Validation 的 @NotNull 已经拦住了绝大多数情况；这里防的是
        // 服务层被其它代码（例如 SSH 层）直接调用时拿到 null
        if (request.getAuthType() == null) {
            throw new InvalidRequestException("auth_type", "认证方式不得为空");
        }
    }

    private static void rejectIfAny(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new InvalidRequestException("请求校验失败", violations);
        }
    }

    // ==================================================================
    // 凭据编排
    // ==================================================================

    private void writeCredentials(String hostId, Host request) {
        if (request.getAuthType() == AuthType.PASSWORD) {
            if (HostAssembler.hasText(request.getPassword())) {
                credentials.save(OWNER, hostId, CredentialType.SSH_PASSWORD,
                        SecretText.of(request.getPassword()));
            }
            return;
        }
        if (HostAssembler.hasText(request.getPrivateKey())) {
            credentials.save(OWNER, hostId, CredentialType.SSH_PRIVATE_KEY,
                    SecretText.of(request.getPrivateKey()));
        }
        if (HostAssembler.hasText(request.getPassphrase())) {
            credentials.save(OWNER, hostId, CredentialType.SSH_PASSPHRASE,
                    SecretText.of(request.getPassphrase()));
        }
    }

    /** 删除与目标认证方式不再匹配的凭据。 */
    private void dropInapplicableCredentials(String hostId, AuthType target) {
        if (target == AuthType.PASSWORD) {
            credentials.delete(OWNER, hostId, CredentialType.SSH_PRIVATE_KEY);
            credentials.delete(OWNER, hostId, CredentialType.SSH_PASSPHRASE);
        } else {
            credentials.delete(OWNER, hostId, CredentialType.SSH_PASSWORD);
        }
    }

    /**
     * 当前认证方式**必需**的那一种凭据。
     * WHY 只看必需项而不看 passphrase：passphrase 是可选的，
     * 用它来判定 {@code credential_set} 会把"无口令私钥"这种完全可用的配置误报成未配置。
     */
    private static CredentialType requiredCredentialOf(AuthType authType) {
        return authType == AuthType.PASSWORD ? CredentialType.SSH_PASSWORD : CredentialType.SSH_PRIVATE_KEY;
    }

    private boolean credentialSetOf(com.ananoesis.shell.entity.Host entity) {
        return credentials.exists(OWNER, entity.getId(),
                requiredCredentialOf(AuthType.fromValue(entity.getAuthType())));
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    private com.ananoesis.shell.entity.Host requireEntity(UUID id) {
        Objects.requireNonNull(id, "id 不得为 null");
        var entity = hostMapper.selectById(id.toString());
        if (entity == null) {
            throw HostNotFoundException.forId(id.toString());
        }
        return entity;
    }

    private Host toDto(com.ananoesis.shell.entity.Host entity) {
        return HostAssembler.toDto(entity, credentialSetOf(entity));
    }
}

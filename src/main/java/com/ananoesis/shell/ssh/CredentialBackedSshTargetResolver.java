package com.ananoesis.shell.ssh;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ananoesis.shell.contract.model.AuthType;
import com.ananoesis.shell.security.CredentialOwnerType;
import com.ananoesis.shell.security.CredentialType;
import com.ananoesis.shell.security.SecretText;
import com.ananoesis.shell.service.CredentialStoreService;
import com.ananoesis.shell.service.HostService;
import com.ananoesis.shell.service.HostService.HostTarget;

/**
 * 生产实现：hostId → 主机配置 + 内存解密后的凭据（credential-store spec「凭据使用时解密」）。
 *
 * <p>WHY 明文的生存期被压缩到回调内部：本方法在 {@code finally} 里擦除凭据，
 * 因此"解密后的明文"从数据库到 sshj 之间<b>没有任何一处</b>会被调用方持有到回调之外。
 * 这是 {@link SshTargetResolver} 设计成回调式的唯一理由——见其接口注释。</p>
 *
 * <p>WHY 解密走 {@link CredentialStoreService} 而不是自己读 {@code credentials} 表：
 * spec 的 MUST 级要求是"加解密收口于唯一通道"。绕过它需要显式注入 Mapper，
 * 那在代码评审中一眼可见。</p>
 *
 * <p><b>日志纪律</b>：本类只记录 hostId 与认证方式，绝不记录凭据对象——
 * 尽管 {@link SecretText#toString()} 已是掩码，依赖"另一个类型恰好安全"是脆弱的组合。</p>
 */
@Component
public class CredentialBackedSshTargetResolver implements SshTargetResolver {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialBackedSshTargetResolver.class);

    private static final CredentialOwnerType OWNER = CredentialOwnerType.HOST;

    private final HostService hosts;
    private final CredentialStoreService credentials;

    public CredentialBackedSshTargetResolver(HostService hosts, CredentialStoreService credentials) {
        this.hosts = Objects.requireNonNull(hosts, "hosts 不得为 null");
        this.credentials = Objects.requireNonNull(credentials, "credentials 不得为 null");
    }

    @Override
    public <T> T withTarget(UUID hostId, Function<SshTarget, T> action) {
        Objects.requireNonNull(hostId, "hostId 不得为 null");
        Objects.requireNonNull(action, "action 不得为 null");

        HostTarget configured = hosts.requireTarget(hostId);
        SshTarget target = null;
        try {
            target = SshTarget.of(configured.host(), configured.port(), configured.username(),
                    loadAuthMethod(configured));
            LOG.debug("已解析 SSH 目标: hostId={} host={}:{} user={} authType={}",
                    hostId, configured.host(), configured.port(), configured.username(),
                    configured.authType().getValue());
            return action.apply(target);
        } finally {
            if (target != null) {
                target.wipeAuth();
            }
        }
    }

    /**
     * 按认证方式取出并解密凭据。
     *
     * <p>WHY 私钥分支要自己处理"取 passphrase 时出错"：{@code privateKey} 此时已是明文，
     * 若第二次查库抛异常就没人擦除它了。用局部 try/catch 保证任何路径都不留下明文。</p>
     */
    private SshAuthMethod loadAuthMethod(HostTarget configured) {
        String ownerId = configured.hostId().toString();
        if (configured.authType() == AuthType.PASSWORD) {
            SecretText password = credentials.find(OWNER, ownerId, CredentialType.SSH_PASSWORD)
                    .orElseThrow(() -> missing(ownerId, "密码"));
            return SshAuthMethod.password(password);
        }

        SecretText privateKey = credentials.find(OWNER, ownerId, CredentialType.SSH_PRIVATE_KEY)
                .orElseThrow(() -> missing(ownerId, "私钥"));
        SecretText passphrase = null;
        try {
            passphrase = credentials.find(OWNER, ownerId, CredentialType.SSH_PASSPHRASE).orElse(null);
        } catch (RuntimeException e) {
            privateKey.wipe();
            throw e;
        }
        return SshAuthMethod.privateKey(privateKey, passphrase);
    }

    /**
     * 凭据缺失。
     * WHY 报 {@code CREDENTIAL_MISSING} 而不是 {@code NOT_FOUND}：主机配置是存在的，
     * 缺的是它的凭据；对用户而言这是一件"去设置里补一下就好"的可自愈问题。
     */
    private static SshConnectException missing(String ownerId, String what) {
        return new SshConnectException(SshFailureKind.CREDENTIAL_MISSING,
                "主机未配置 SSH " + what + ": hostId=" + ownerId);
    }
}

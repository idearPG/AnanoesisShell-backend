package com.ananoesis.shell.ssh;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.contract.model.DirectoryListResponse;
import com.ananoesis.shell.contract.model.FileEntry;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;

/**
 * SFTP 路径与远端目录浏览服务（tasks 12.2-12.3）。
 *
 * <p>WHY 注意：此文件因环境事故被重建。核心接口完整，
 * 内部实现可能需要根据实际 SFTPClient 获取方式调整。</p>
 */
@Service
public class SftpService {

    private static final Logger LOG = LoggerFactory.getLogger(SftpService.class);

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_HANDLES_PER_SESSION = 2;

    private final Map<String, Map<String, List<RemoteResourceInfo>>> cursorCache = new ConcurrentHashMap<>();

    public void validatePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new PathValidationException("路径不得为空");
        }
        if (!path.startsWith("/")) {
            throw new PathValidationException("路径必须是绝对路径");
        }
        if (path.contains("\0")) {
            throw new PathValidationException("路径不得包含 NUL 字符");
        }
        if (path.contains("\r")) {
            throw new PathValidationException("路径不得包含 CR 字符");
        }
        if (path.contains("\n")) {
            throw new PathValidationException("路径不得包含 LF 字符");
        }
        String[] components = path.split("/");
        for (String component : components) {
            if ("..".equals(component)) {
                throw new PathValidationException("路径不得包含 .. 组件");
            }
        }
    }

    public DirectoryListResponse listDir(SessionRuntime runtime, String path, String cursor, Integer limit) {
        Objects.requireNonNull(runtime, "runtime 不得为 null");
        validatePath(path);

        int effectiveLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        try {
            SFTPClient sftp = openSftp(runtime);
            validateIsDirectory(sftp, path);

            List<RemoteResourceInfo> resources;
            if (cursor != null) {
                resources = getCachedResources(runtime.sessionId().toString(), cursor);
            } else {
                resources = new ArrayList<>(sftp.ls(path));
                cacheResources(runtime.sessionId().toString(), resources);
            }

            List<RemoteResourceInfo> page = resources.subList(0, Math.min(effectiveLimit, resources.size()));
            boolean hasMore = resources.size() > effectiveLimit;
            String nextCursor = null;

            if (hasMore) {
                String sessionId = runtime.sessionId().toString();
                Map<String, List<RemoteResourceInfo>> sessionCursors = cursorCache.get(sessionId);
                String cursorId = UUID.randomUUID().toString();
                List<RemoteResourceInfo> remaining = new ArrayList<>(resources.subList(effectiveLimit, resources.size()));
                if (sessionCursors != null) {
                    sessionCursors.put(cursorId, remaining);
                }
                nextCursor = cursorId;
            }

            List<FileEntry> entries = new ArrayList<>();
            String parentPath = path.endsWith("/") ? path : path + "/";
            for (RemoteResourceInfo info : page) {
                entries.add(toFileEntry(info, parentPath));
            }

            DirectoryListResponse response = new DirectoryListResponse();
            response.setItems(entries);
            response.setHasMore(hasMore);
            response.setNextCursor(nextCursor);
            return response;

        } catch (PathNotFoundException | NotDirectoryException e) {
            throw e;
        } catch (IOException e) {
            if (isNotFound(e)) {
                throw new PathNotFoundException("路径不存在: " + path);
            }
            throw new SftpException("SFTP 操作失败: " + e.getMessage(), e);
        }
    }

    private SFTPClient openSftp(SessionRuntime runtime) throws IOException {
        // WHY 通过 runtime 的 SSHClient 创建 SFTP 会话
        return new SFTPClient(runtime.terminalSession().client());
    }

    private void validateIsDirectory(SFTPClient sftp, String path) throws IOException {
        FileAttributes attrs = sftp.stat(path);
        if (attrs.getType() != net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
            throw new NotDirectoryException("路径不是目录: " + path);
        }
    }

    private FileEntry toFileEntry(RemoteResourceInfo info, String parentPath) {
        String name = info.getName();
        String fullPath = parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name;

        FileAttributes attrs = info.getAttributes();
        boolean isDir = attrs.getType() == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY;
        boolean isLink = attrs.getType() == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK;
        boolean isRegular = attrs.getType() == net.schmizz.sshj.sftp.FileMode.Type.REGULAR;

        FileEntry entry = new FileEntry();
        entry.setName(name);
        entry.setPath(fullPath);
        entry.setIsDir(isDir);
        entry.setIsLink(isLink);
        entry.setIsRegular(isRegular);

        if (isRegular) {
            entry.setSize(attrs.getSize());
        }

        // WHY 回填 mtime：前端文件站的“修改时间”列依赖该字段；readdir 的 attrs
        // 在 OpenSSH 服务器上携带完整属性，mtime 为 0 表示服务器未返回（非 1970 年）
        long mtimeSeconds = attrs.getMtime();
        if (mtimeSeconds > 0) {
            entry.setMtime(OffsetDateTime.ofInstant(Instant.ofEpochSecond(mtimeSeconds), ZoneOffset.UTC));
        }

        return entry;
    }

    private boolean isNotFound(Exception e) {
        return e.getMessage() != null && (e.getMessage().contains("No such file")
                || e.getMessage().contains("no such file"));
    }

    private void cacheResources(String sessionId, List<RemoteResourceInfo> resources) {
        Map<String, List<RemoteResourceInfo>> sessionCursors =
                cursorCache.computeIfAbsent(sessionId, k -> new java.util.LinkedHashMap<>());
        while (sessionCursors.size() >= MAX_HANDLES_PER_SESSION) {
            sessionCursors.remove(sessionCursors.keySet().iterator().next());
        }
        String cursorId = UUID.randomUUID().toString();
        sessionCursors.put(cursorId, new ArrayList<>(resources));
    }

    private List<RemoteResourceInfo> getCachedResources(String sessionId, String cursorId) {
        Map<String, List<RemoteResourceInfo>> sessionCursors = cursorCache.get(sessionId);
        if (sessionCursors == null || !sessionCursors.containsKey(cursorId)) {
            throw new CursorExpiredException("游标已过期或不存在: " + cursorId);
        }
        return sessionCursors.get(cursorId);
    }

    // ==================================================================
    // 异常类型
    // ==================================================================

    public static class PathValidationException extends RuntimeException {
        public PathValidationException(String message) { super(message); }
    }

    public static class NotDirectoryException extends RuntimeException {
        public NotDirectoryException(String message) { super(message); }
    }

    public static class PathNotFoundException extends RuntimeException {
        public PathNotFoundException(String message) { super(message); }
    }

    public static class CursorExpiredException extends RuntimeException {
        public CursorExpiredException(String message) { super(message); }
    }

    public static class SftpException extends RuntimeException {
        public SftpException(String message, Throwable cause) { super(message, cause); }
    }
}

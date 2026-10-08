package com.ananoesis.shell.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.ananoesis.shell.service.TransferService;
import com.ananoesis.shell.service.TransferService.DownloadResult;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 二进制上传/下载的 HTTP 适配层（design.md D9「SFTP 传输」）。
 *
 * <p>WHY 手写而非 OpenAPI 生成：openapi.yaml 中 TransferContent tag 标记了
 * {@code x-skip-codegen: true}，因为生成器无法正确处理 octet-stream 的原始流式传输。
 * Spring 生成的接口会用 {@code Resource} 包装请求体，导致整个文件被加载到内存。</p>
 *
 * <h2>上传</h2>
 * <p>使用 servlet 原始 {@link InputStream}，64 KiB 固定缓冲，不经 multipart/Base64/byte[]。
 * 数据直接流入 SFTP 临时文件，内存占用恒定。</p>
 *
 * <h2>下载</h2>
 * <p>使用 {@link StreamingResponseBody} 实现异步流式传输。
 * 响应头包含 no-store、Referrer-Policy:no-referrer、Content-Disposition。</p>
 */
@RestController
public class TransferContentController {

    private static final Logger LOG = LoggerFactory.getLogger(TransferContentController.class);

    private final TransferService transferService;

    public TransferContentController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * PUT /api/transfers/{id}/content — 上传二进制内容。
     *
     * <p>WHY 使用 {@link HttpServletRequest#getInputStream()} 而非 {@code @RequestBody Resource}：
     * Spring MVC 对 {@code application/octet-stream} 的 {@code Resource} 包装会把整个请求体
     * 缓冲到临时文件再包装成 Resource，对于大文件（最大 10 GiB）不可接受。
     * 直接使用 servlet 原始流，配合 TransferService 内的 64 KiB 固定缓冲，内存占用恒定。</p>
     */
    @RequestMapping(
            method = RequestMethod.PUT,
            value = "/api/transfers/{id}/content",
            consumes = {MediaType.APPLICATION_OCTET_STREAM_VALUE}
    )
    public ResponseEntity<Void> uploadContent(@PathVariable("id") UUID id,
                                              HttpServletRequest request) {
        try {
            InputStream inputStream = request.getInputStream();
            transferService.uploadContent(id.toString(), inputStream);
            return ResponseEntity.ok().build();
        } catch (com.ananoesis.shell.service.TransferConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (com.ananoesis.shell.service.ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IOException e) {
            LOG.error("读取上传流失败: transfer={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (RuntimeException e) {
            LOG.error("上传内容失败: transfer={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * GET /api/transfers/{id}/content — 下载二进制内容。
     *
     * <p>使用 {@link StreamingResponseBody} 实现流式传输，避免将整个文件加载到内存。
     * 票据消费与状态变更在 TransferService 中原子化完成。</p>
     *
     * <p>响应头安全约束：</p>
     * <ul>
     *   <li>Cache-Control: no-store — 禁止缓存敏感文件</li>
     *   <li>Referrer-Policy: no-referrer — 防止文件名通过 Referer 泄露</li>
     *   <li>Content-Disposition: ASCII fallback + UTF-8 filename* — RFC 6266</li>
     * </ul>
     */
    @RequestMapping(
            method = RequestMethod.GET,
            value = "/api/transfers/{id}/content",
            produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE}
    )
    public ResponseEntity<StreamingResponseBody> downloadContent(
            @PathVariable("id") UUID id,
            @RequestParam(value = "ticket") String ticket) {

        DownloadResult result;
        try {
            result = transferService.startDownload(id.toString(), ticket);
        } catch (com.ananoesis.shell.service.ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (com.ananoesis.shell.service.NotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (RuntimeException e) {
            LOG.error("开始下载失败: transfer={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // 构建 Content-Disposition
        String fileName = result.getFileName();
        String asciiName = toAsciiFallback(fileName);
        String utf8Name = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String contentDisposition = String.format(
                "attachment; filename=\"%s\"; filename*=UTF-8''%s",
                asciiName, utf8Name);

        // 使用 StreamingResponseBody 流式传输
        StreamingResponseBody body = outputStream -> {
            try (result) {
                InputStream input = result.getInputStream();
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            } catch (Exception e) {
                LOG.error("下载流传输失败: transfer={}", id, e);
                throw e;
            } finally {
                // 下载完成，更新状态
                try {
                    transferService.completeDownload(id.toString());
                } catch (RuntimeException e) {
                    LOG.warn("完成下载状态更新失败: transfer={}", id, e);
                }
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
        headers.setCacheControl("no-store");
        headers.set("Referrer-Policy", "no-referrer");

        return ResponseEntity.ok()
                .headers(headers)
                .body(body);
    }

    /**
     * 将文件名转为 ASCII 回退值：替换非 ASCII 字符为 '_'。
     */
    private static String toAsciiFallback(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "download";
        }
        StringBuilder sb = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c >= 0x20 && c < 0x7f) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}

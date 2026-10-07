package com.example.aicodebackend.controller;

import com.example.aicodebackend.constant.AppConstant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.File;

@RestController
@RequestMapping("/static")
public class StaticResourceController {

    // 应用生成根目录（用于浏览）
    private static final String PREVIEW_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR;

    /**
     * 提供静态资源访问，支持目录重定向
     * 访问格式：http://localhost:8123/api/static/{deployKey}/
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveStaticResource(@PathVariable String deployKey, HttpServletRequest request) {
        try {
            // 获取资源路径
            String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            resourcePath = resourcePath.substring(("/static/" + deployKey).length());
            // 如果是目录访问（不带斜杠），重定向到带斜杠的URL
            if (resourcePath.isEmpty()) {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Location", request.getRequestURI() + "/");
                return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
            }
            // 默认返回 index.html
            if (resourcePath.equals("/")) {
                resourcePath = "/index.html";
            }
            // 构建文件路径
            String filePath = PREVIEW_ROOT_DIR + "/" + deployKey + resourcePath;
            File file = new File(filePath);
            // 检查文件是否存在
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }
            // 返回文件资源
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .header("Content-Type", getContentTypeWithCharset(filePath))
                    // 生成产物的文件名是固定的（index.html / style.css / script.js），
                    // 如果浏览器沿用强缓存，用户重新生成后预览里还是旧内容、必须手动强刷。
                    // 这里要求每次校验（no-cache 允许缓存但必须回源验证），
                    // 配合 ETag/Last-Modified 既能拿到新产物，又不会丢掉 304 复用。
                    .header("Cache-Control", "no-cache")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 根据文件扩展名返回带字符编码的 Content-Type
     * <p>
     * 生成站点里除了 html/css/js，还会出现 svg 图标、webp 图片、woff2 字体等资源。
     * 这些如果统一回 {@code application/octet-stream}，浏览器不会按图片/字体解析：
     * 字体加载失败会导致文字回退甚至整页排版错乱，svg 图标也可能不显示（实测问题）。
     * 因此这里按扩展名给出准确的类型，未知扩展名再兜底为二进制流。
     *
     * @param filePath 文件路径
     *
     * @return 带字符集的 Content-Type
     */
    private String getContentTypeWithCharset(String filePath) {
        String extension = resolveExtension(filePath);
        return switch (extension) {
            case "html", "htm" -> "text/html; charset=UTF-8";
            case "css" -> "text/css; charset=UTF-8";
            case "js", "mjs" -> "application/javascript; charset=UTF-8";
            case "json", "map", "webmanifest" -> "application/json; charset=UTF-8";
            case "txt", "text" -> "text/plain; charset=UTF-8";
            case "xml" -> "application/xml; charset=UTF-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "eot" -> "application/vnd.ms-fontobject";
            case "wasm" -> "application/wasm";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    /**
     * 取小写扩展名（不含点），没有扩展名时返回空串
     *
     * @param filePath 文件路径
     *
     * @return 小写扩展名
     */
    private String resolveExtension(String filePath) {
        int dotIndex = filePath.lastIndexOf('.');
        int separatorIndex = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (dotIndex <= separatorIndex || dotIndex == filePath.length() - 1) {
            return "";
        }
        return filePath.substring(dotIndex + 1).toLowerCase();
    }
}

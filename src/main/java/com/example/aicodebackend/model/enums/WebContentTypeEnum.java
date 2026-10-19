package com.example.aicodebackend.model.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/** 静态资源扩展名与 Content-Type 的映射 */
@Getter
public enum WebContentTypeEnum {

    HTML("text/html; charset=UTF-8", "html", "htm"),
    CSS("text/css; charset=UTF-8", "css"),
    JAVASCRIPT("application/javascript; charset=UTF-8", "js", "mjs"),
    JSON("application/json; charset=UTF-8", "json", "map", "webmanifest"),
    TEXT("text/plain; charset=UTF-8", "txt", "text"),
    XML("application/xml; charset=UTF-8", "xml"),
    SVG("image/svg+xml", "svg"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg", "jpeg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    AVIF("image/avif", "avif"),
    BMP("image/bmp", "bmp"),
    ICO("image/x-icon", "ico"),
    WOFF("font/woff", "woff"),
    WOFF2("font/woff2", "woff2"),
    TTF("font/ttf", "ttf"),
    OTF("font/otf", "otf"),
    EOT("application/vnd.ms-fontobject", "eot"),
    WASM("application/wasm", "wasm"),
    MP4("video/mp4", "mp4"),
    WEBM("video/webm", "webm"),
    MP3("audio/mpeg", "mp3"),
    WAV("audio/wav", "wav"),
    PDF("application/pdf", "pdf"),
    OCTET_STREAM("application/octet-stream");

    private final String contentType;

    private final String[] extensions;

    WebContentTypeEnum(String contentType, String... extensions) {
        this.contentType = contentType;
        this.extensions = extensions;
    }

    /** 按扩展名取 Content-Type，未知扩展名回落到二进制流 */
    public static String ofExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return OCTET_STREAM.contentType;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> Arrays.asList(type.extensions).contains(normalized))
                .map(WebContentTypeEnum::getContentType)
                .findFirst()
                .orElse(OCTET_STREAM.contentType);
    }
}

package com.example.aicodebackend.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 静态资源 Content-Type 回归测试
 * <p>
 * 回归背景：生成站点里的 svg 图标、webp 图片、woff2 字体此前统一被当成
 * {@code application/octet-stream} 返回，浏览器不按图片/字体解析（字体加载失败会让排版错乱），
 * 因此这里按扩展名逐一固定住类型。
 */
class StaticResourceControllerTest {

    @Test
    void shouldReturnFontTypesForFontFiles() throws Exception {
        assertEquals("font/woff2", contentType("assets/font.woff2"));
        assertEquals("font/woff", contentType("assets/font.woff"));
        assertEquals("font/ttf", contentType("assets/font.ttf"));
        assertEquals("font/otf", contentType("assets/font.otf"));
        assertEquals("application/vnd.ms-fontobject", contentType("assets/font.eot"));
    }

    @Test
    void shouldReturnImageTypesForImageFiles() throws Exception {
        assertEquals("image/svg+xml", contentType("assets/logo.svg"));
        assertEquals("image/webp", contentType("assets/hero.webp"));
        assertEquals("image/x-icon", contentType("favicon.ico"));
        assertEquals("image/png", contentType("assets/logo.png"));
        assertEquals("image/jpeg", contentType("assets/photo.jpeg"));
        assertEquals("image/gif", contentType("assets/anim.gif"));
    }

    /**
     * 原有类型不能因为这次改动而回归（html/css/js 是预览的主要产物）
     */
    @Test
    void shouldKeepExistingWebTypes() throws Exception {
        assertEquals("text/html; charset=UTF-8", contentType("dist/index.html"));
        assertEquals("text/css; charset=UTF-8", contentType("dist/assets/index.css"));
        assertEquals("application/javascript; charset=UTF-8", contentType("dist/assets/index.js"));
    }

    @Test
    void shouldReturnJsonAndWasmTypes() throws Exception {
        assertEquals("application/json; charset=UTF-8", contentType("dist/assets/index.js.map"));
        assertEquals("application/json; charset=UTF-8", contentType("site.webmanifest"));
        assertEquals("application/wasm", contentType("assets/module.wasm"));
        assertEquals("application/xml; charset=UTF-8", contentType("sitemap.xml"));
    }

    /**
     * 扩展名大小写不敏感，且不能被目录名里的点干扰
     */
    @Test
    void shouldResolveExtensionCaseInsensitively() throws Exception {
        assertEquals("image/png", contentType("assets/LOGO.PNG"));
        assertEquals("text/html; charset=UTF-8", contentType("build.v2/dist/index.html"));
    }

    /**
     * 未知扩展名与没有扩展名时仍兜底为二进制流
     */
    @Test
    void shouldFallbackToOctetStream() throws Exception {
        assertEquals("application/octet-stream", contentType("assets/data.bin"));
        assertEquals("application/octet-stream", contentType("assets/noextension"));
    }

    private String contentType(String filePath) throws Exception {
        Method method = StaticResourceController.class.getDeclaredMethod("getContentTypeWithCharset", String.class);
        method.setAccessible(true);
        return (String) method.invoke(new StaticResourceController(), filePath);
    }
}

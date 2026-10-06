package com.example.aicodebackend.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「空格丢失」损伤检测测试
 * <p>
 * 正例取自真实丢空格的输出，反例取自同一模型正常输出的样式 / 脚本：
 * 检测器既不能漏掉整轮丢空格（那会让坏代码落盘），也不能把正常代码误判（那会白花一次修复调用、
 * 还可能把本来正确的文件换成模型重写过的版本）。
 */
class CodeDamageDetectorTest {

    /** 真实形态：整轮丢空格的 HTML（标签名与属性粘连） */
    private static final String GLUED_HTML = """
            <!DOCTYPEhtml>
            <htmllang="zh-CN">
            <head>
              <metacharset="UTF-8">
              <metaname="viewport"content="width=device-width,initial-scale=1.0">
              <linkrel="stylesheet"href="style.css">
            </head>
            <body>
              <divclass="containerhero-inner">
                <ahref="#top"class="logo">林默</a>
              </div>
              <scriptsrc="script.js"></script>
            </body>
            </html>
            """;

    private static final String CLEAN_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <link rel="stylesheet" href="style.css">
            </head>
            <body>
              <div class="container hero-inner">
                <a href="#top" class="logo">林默</a>
              </div>
              <script src="script.js"></script>
            </body>
            </html>
            """;

    /** 真实形态：丢空格的 CSS */
    private static final String GLUED_CSS = """
            :root{
            --primary:#1652f0;
            --shadow-sm:04px16pxrgba(15,27,45,.06);
            }

            .container{
            width:100%;
            margin:0auto;
            padding:024px;
            transition:transformvar(--ease),box-shadowvar(--ease);
            }

            .card{
            border:1.5pxsolidvar(--border);
            }

            .cards-grid{
            display:grid;
            grid-template-columns:1fr1fr;
            }
            """;

    /** 反例：同一个模型正常输出的 CSS（取自真实产物） */
    private static final String CLEAN_CSS = """
            :root{
              --brand:#4f46e5;
              --shadow-1:0 2px 6px rgba(15,23,42,.05),0 12px 30px rgba(15,23,42,.06);
              --grad:linear-gradient(120deg,#4f46e5 0%,#7c3aed 48%,#06b6d4 100%);
              --ease:cubic-bezier(.22,1,.36,1);
            }

            body{
              margin:0;
              font-family:'PingFang SC','Hiragino Sans GB',Arial,sans-serif;
              font-size:16px;
              line-height:1.7;
              -webkit-font-smoothing:antialiased;
            }

            a{color:inherit;text-decoration:none;transition:color .3s var(--ease);}

            .hero-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:24px;}

            .card:hover{transform:translateY(-6px) scale(1.02);box-shadow:var(--shadow-2);}

            @media (max-width:1024px){
              .about-grid{gap:40px;}
            }
            """;

    /** 真实形态：丢空格的 JavaScript */
    private static final String GLUED_JS = """
            (function(){
            'usestrict';

            /*全局引用*/
            varheader=null;
            varmainNav=null;
            varnavToggle=null;

            functionsetYear(){
              varel=document.getElementById('year');
              if(el)el.textContent=newDate().getFullYear();
            }

            functioninitMobileNav(){
              navToggle=document.getElementById('navToggle');
              if(!navToggle||!mainNav)return;
              if(mainNav.classList.contains('open')){
                closeNav();
              }else{
                openNav();
              }
            }
            })();
            """;

    /** 反例：同一个模型正常输出的 JavaScript（取自真实产物） */
    private static final String CLEAN_JS = """
            (function () {
              'use strict';

              var header = null;
              var nav = null;
              var backTop = null;

              document.addEventListener('DOMContentLoaded', function () {
                header = document.getElementById('header');
                setYear();
                initScrollState();
              });

              function setYear() {
                var el = document.getElementById('year');
                if (el) el.textContent = new Date().getFullYear();
              }

              function initActiveNav() {
                var links = Array.prototype.slice.call(document.querySelectorAll('.nav-link'));
                var sections = links.map(function (link) {
                  var hash = link.getAttribute('href');
                  return hash && hash.length > 1 ? document.querySelector(hash) : null;
                });
                var index = 0;
                return index;
              }

              if (!('IntersectionObserver' in window)) {
                return;
              }
            })();
            """;

    @Test
    void shouldDetectGluedHtml() {
        assertTrue(CodeDamageDetector.scoreHtml(GLUED_HTML) > 0, "标签粘连必须能识别");
        assertFalse(CodeDamageDetector.detect(GLUED_HTML, CLEAN_CSS, CLEAN_JS).css(), "干净的 CSS 不应被误判");
        assertFalse(CodeDamageDetector.detect(GLUED_HTML, CLEAN_CSS, CLEAN_JS).js(), "干净的 JavaScript 不应被误判");
    }

    @Test
    void shouldNotFlagCleanHtml() {
        assertEquals(0, CodeDamageDetector.scoreHtml(CLEAN_HTML));
    }

    @Test
    void shouldDetectGluedCss() {
        assertTrue(CodeDamageDetector.scoreCss(GLUED_CSS) > 0, "值与单位粘连必须能识别: " + GLUED_CSS);
        assertTrue(CodeDamageDetector.detect(CLEAN_HTML, GLUED_CSS, CLEAN_JS).css());
        assertFalse(CodeDamageDetector.detect(CLEAN_HTML, GLUED_CSS, CLEAN_JS).js(), "干净的 JavaScript 不应被误判");
    }

    @Test
    void shouldNotFlagCleanCss() {
        assertEquals(0, CodeDamageDetector.scoreCss(CLEAN_CSS),
                "正常 CSS 不能被误判（否则会白花一次修复调用）：" + CLEAN_CSS);
    }

    @Test
    void shouldDetectGluedJs() {
        assertTrue(CodeDamageDetector.scoreJs(GLUED_JS) > 0);
        assertTrue(CodeDamageDetector.detect(CLEAN_HTML, CLEAN_CSS, GLUED_JS).js());
        assertFalse(CodeDamageDetector.detect(CLEAN_HTML, CLEAN_CSS, GLUED_JS).css(), "干净的 CSS 不应被误判");
    }

    @Test
    void shouldNotFlagCleanJs() {
        assertEquals(0, CodeDamageDetector.scoreJs(CLEAN_JS),
                "正常 JavaScript 不能被误判：index / classList / new Date() 这类写法不算粘连");
    }

    /**
     * 弱信号（可能是标识符前缀的关键字）必须有交叉佐证才判定受损：
     * 只出现 newValue / newIndex 这种命名时不能误判，但同一轮 HTML 也粘连时必须判出来
     */
    @Test
    void weakJsSignalsNeedCorroboration() {
        String weakOnly = """
                var newValue = 1;
                var newIndex = 2;
                console.log(newValue, newIndex);
                """;
        assertFalse(CodeDamageDetector.detect(CLEAN_HTML, CLEAN_CSS, weakOnly).js(),
                "只有命名像关键字前缀时不能误判");
        assertTrue(CodeDamageDetector.detect(GLUED_HTML, CLEAN_CSS, weakOnly).js(),
                "同一轮 HTML 已经粘连时，JavaScript 的弱信号应被判为受损");
    }

    /**
     * 类名粘连（{@code class="containerhero-inner"}）：单看 HTML 完全合法，
     * 必须拿 CSS 里的类选择器当字典才能验出来
     */
    @Test
    void shouldDetectMergedClassValuesUsingCssDictionary() {
        String css = """
                .container{width:100%;}
                .hero-inner{display:flex;}
                .card{padding:24px;}
                .reveal{opacity:0;}
                """;
        String merged = "<div class=\"containerhero-inner\"><article class=\"cardreveal\">x</article></div>";
        String clean = "<div class=\"container hero-inner\"><article class=\"card reveal\">x</article></div>";

        assertEquals(2, CodeDamageDetector.countMergedClassValues(merged, css));
        assertEquals(0, CodeDamageDetector.countMergedClassValues(clean, css), "正常的多类名不能被误判");
        assertEquals(0, CodeDamageDetector.countMergedClassValues(merged, null), "没有 CSS 字典时不做判断");
        assertFalse(CodeDamageDetector.detect(clean, css, CLEAN_JS).html(), "正常的多类名不能被误判");
        assertTrue(CodeDamageDetector.detect(merged, css, CLEAN_JS).html(), "类名粘连必须被判为受损");
    }

    /**
     * 整轮丢空格时 CSS 选择器同样会被粘（{@code .hero-metrics li} -> {@code .hero-metricsli}），
     * 但只要独立选择器还在，切分仍然成立——不能因为"这个取值也在 CSS 里出现过"就跳过判断
     */
    @Test
    void mergedClassDetectionShouldSurviveGluedCssSelectors() {
        String gluedCss = """
                .hero-metrics{
                display:flex;
                }
                .reveal{
                opacity:0;
                }
                .hero-metricsli{
                display:flex;
                }
                """;
        String html = "<ul class=\"hero-metricsreveal\"><li>x</li></ul>";

        assertEquals(1, CodeDamageDetector.countMergedClassValues(html, gluedCss));
    }

    @Test
    void shouldReportScoresAndCleanStateForNothingDamaged() {
        CodeDamageDetector.Damage damage = CodeDamageDetector.detect(CLEAN_HTML, CLEAN_CSS, CLEAN_JS);

        assertFalse(damage.any());
        assertEquals(0, damage.htmlScore());
        assertEquals(0, damage.cssScore());
        assertEquals(0, damage.jsScore());
        assertFalse(CodeDamageDetector.detect(null, null, null).any(), "空内容不算受损");
    }
}

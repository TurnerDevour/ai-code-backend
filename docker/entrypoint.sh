#!/bin/sh
# =============================================================================
# 容器启动脚本
#
# 主要做一件事：把镜像里 apt 装好的 chromedriver 登记到 WebDriverManager 的缓存目录，
# 并固定驱动版本，让截图功能不再依赖"运行时联网下载驱动"。
#
# 背景（实测 + 源码确认）：
#   WebScreenshotUtils 每次初始化 Chrome 都会调用 WebDriverManager.chromedriver().setup()。
#   WebDriverManager 的解析流程是"探测浏览器版本 -> 到 Chrome for Testing 端点查驱动版本 ->
#   下载到 ~/.cache/selenium -> 用系统属性 webdriver.chrome.driver 导出路径"，
#   它<b>不会</b>自动使用 PATH 里已有的 /usr/bin/chromedriver。
#   而 chrome-for-testing 走的是 storage.googleapis.com，国内服务器基本拉不通，
#   一旦下载失败，截图（应用封面）就整体不可用。
#
# 规避方式：
#   1. 镜像里 apt 安装的 chromium 与 chromium-driver 来自同一个源码版本，天然匹配；
#   2. 这里把它放进 WebDriverManager 期望的缓存结构
#      ${WDM_CACHEPATH}/chromedriver/linux64/<版本>/chromedriver；
#   3. 导出 WDM_CHROMEDRIVERSION=<版本>（对应配置项 wdm.chromeDriverVersion）。
#      驱动版本一旦已知，WebDriverManager 会跳过"联网查版本"这一步，
#      直接命中本地缓存；即使镜像换版本，也会由本脚本重新计算，无需改配置。
# =============================================================================
set -e

WDM_CACHE_DIR="${WDM_CACHEPATH:-/opt/wdm-cache}"
CHROMEDRIVER_BIN="${CHROMEDRIVER_BIN:-/usr/bin/chromedriver}"

if [ -x "$CHROMEDRIVER_BIN" ]; then
    CHROMEDRIVER_VERSION="$("$CHROMEDRIVER_BIN" --version 2>/dev/null | awk '{print $2}')"
    if [ -n "$CHROMEDRIVER_VERSION" ]; then
        TARGET_DIR="$WDM_CACHE_DIR/chromedriver/linux64/$CHROMEDRIVER_VERSION"
        if [ ! -x "$TARGET_DIR/chromedriver" ]; then
            mkdir -p "$TARGET_DIR"
            cp "$CHROMEDRIVER_BIN" "$TARGET_DIR/chromedriver"
            chmod +x "$TARGET_DIR/chromedriver"
            echo "[entrypoint] chromedriver $CHROMEDRIVER_VERSION 已写入 WebDriverManager 缓存: $TARGET_DIR"
        fi
        export WDM_CHROMEDRIVERSION="${WDM_CHROMEDRIVERSION:-$CHROMEDRIVER_VERSION}"
    fi
fi

# chromedriver 会在 PATH 里依次找 chrome / google-chrome / chromium / chromium-browser，
# debian 装的 /usr/bin/chromium 能被直接找到；这里额外导出 CHROME_BIN 作为显式兜底。
if [ -z "${CHROME_BIN:-}" ]; then
    CHROME_BIN="$(command -v chromium || command -v chromium-browser || true)"
    export CHROME_BIN
fi

# JAVA_OPTS 需要按空格拆分成多个参数，因此这里不加引号
# shellcheck disable=SC2086
exec java ${JAVA_OPTS} -jar /app/app.jar "$@"

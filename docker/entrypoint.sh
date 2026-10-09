#!/bin/sh
# =============================================================================
# 容器启动脚本
#
# 只做一件事：把镜像里 apt 装好的 Chromium / ChromeDriver 路径显式导出，并在启动日志里打印一次版本。
#
# 背景（线上实测）：
#   镜像里 apt 装的 chromium 与 chromium-driver 本来同源同版本，但截图代码原来每次都让
#   WebDriverManager.chromedriver().setup() 自己解析驱动（探测浏览器版本 → 联网查驱动版本 →
#   从 storage.googleapis.com 下载）。国内服务器拉不通这条链路不说，一旦它的解析结果与镜像里的
#   浏览器不是同一主版本，Selenium 就会报：
#     session not created: This version of ChromeDriver only supports Chrome version 155
#     Current browser version is 154.0.8037.92 with binary path /usr/bin/chromium
#   表现是所有截图（应用封面）整体失败，而报错里看不出该改什么。
#
# 现在（见 WebScreenshotUtils#buildDriverService 与 ChromeInstallation）：
#   1. 直接用系统里 apt 装的 /usr/bin/chromedriver，完全离线，不再由 WebDriverManager 猜版本；
#   2. Dockerfile 在构建期校验 chromium 与 chromedriver 主版本一致，不一致根本构建不出来；
#   3. 这里导出路径只是"显式化"：代码的定位顺序是 系统属性 -> 这两个环境变量 -> /usr/bin 下的常见路径，
#      去掉这两行也能被常见路径兜住。
# =============================================================================
set -e

CHROMEDRIVER_BIN="${CHROMEDRIVER_BIN:-/usr/bin/chromedriver}"
if [ -z "${CHROME_BIN:-}" ]; then
    CHROME_BIN="$(command -v chromium || command -v chromium-browser || true)"
fi
export CHROMEDRIVER_BIN CHROME_BIN

# 启动日志里打印一次：下次出问题先看这两行，比进容器敲 --version 快
echo "[entrypoint] chromedriver: $("$CHROMEDRIVER_BIN" --version 2>/dev/null || echo '未找到')"
echo "[entrypoint] chromium:    $("${CHROME_BIN:-chromium}" --version 2>/dev/null || echo '未找到')"

# JAVA_OPTS 需要按空格拆分成多个参数，因此这里不加引号
# shellcheck disable=SC2086
exec java ${JAVA_OPTS} -jar /app/app.jar "$@"

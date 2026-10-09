# =============================================================================
# ai-code-backend 生产镜像
#
# 同一个 Dockerfile 支持两种构建方式（用 build target 区分，运行时层完全共用）：
#
#   ① jar 模式（推荐，2 核 4G 服务器）—— 本地先打包，镜像里只装运行时
#        本地：  mvn clean package -DskipTests
#        上传：  scp target/ai-code-backend-1.0.0.jar <用户>@<服务器>:/opt/ai-code-backend/app.jar
#        服务器：docker compose build backend        # .env 里 BACKEND_BUILD_TARGET=runtime-from-jar
#        → 服务器不跑 Maven：只 apt 装 chromium/字体 + 下 JRE + 拷贝 jar，几分钟内完成，几乎不吃额外内存
#
#   ② 源码模式（默认 target，CI 或想一条命令从源码出镜像时）
#        docker compose build backend               # BACKEND_BUILD_TARGET=runtime（或不设该变量）
#        → 在容器里跑 Maven；服务器上约 5~10 分钟，构建阶段内存峰值 1G 左右
#
# 运行时依赖（两种模式一致，由项目代码与 pom.xml 推导）：
#   1. JRE 21                —— pom 的 java.version=21，代码里用到虚拟线程 Thread.ofVirtual()
#   2. Node.js + npm         —— VueProjectBuilder 通过 ProcessBuilder 调 `npm install` /
#                               `npm run build` 构建用户生成的 Vue 工程（部署功能必需）
#   3. Chromium + ChromeDriver —— WebScreenshotUtils 用 Selenium 打开部署后的页面截图，
#                               压缩后上传腾讯云 COS 作为应用封面。
#                               两者必须同主版本，构建期会校验（见下方 chromium/chromedriver 校验步骤）
#   4. 中日韩字体            —— 截图的页面多为中文，无 CJK 字体会渲染成方块
#   5. 可写目录 /app/temp    —— AppConstant 用 user.dir 拼出 temp/code_output（生成源码）、
#                               temp/code_deploy（部署产物）、temp/screenshots（截图临时文件）
#   6. 外网连通性            —— 阿里云百炼（AI 模型）、腾讯云 COS、npm registry
#
# MySQL / Redis 不装进本镜像，由 docker-compose.yml 编排为独立服务。
# =============================================================================

# -----------------------------------------------------------------------------
# 运行时公共层：系统依赖 + JRE + 运行用户 + 启动脚本
# 两种构建方式的差别只有「app.jar 从哪来」，其余全部复用这一层
#
# 基础镜像用 node:22-bookworm-slim，原因：
#   - bookworm 是 Debian，apt 里有"版本互相对得上"的 chromium + chromium-driver
#     （Ubuntu 22.04/24.04 的 chromium 是 snap 壳子，容器里装了也跑不起来）
#   - glibc，npm 生态原生模块（rollup / esbuild / tailwind 等）的预编译包最全
#   - Node 22 已内置，满足 Vite 5/6/7 对 Node 版本的要求
# -----------------------------------------------------------------------------
FROM node:22-bookworm-slim AS runtime-base

# JRE 下载地址（默认 Adoptium 官方 API；国内可换成清华镜像，见 DOCKER.md）
ARG JRE_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse"

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:$PATH"

# 系统依赖：
#   chromium / chromium-driver —— 截图用，二者同源同版本（构建期会校验主版本一致）
#   fonts-noto-cjk 等          —— 中文渲染（想瘦身可换成体积小得多的 fonts-wqy-zenhei）
#   curl                       —— 下载 JRE + 容器 HEALTHCHECK
#   tzdata / procps            —— 时区与容器内排查工具
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        chromium \
        chromium-driver \
        fonts-noto-cjk \
        fonts-liberation \
        fonts-dejavu-core \
        ca-certificates \
        curl \
        tzdata \
        procps; \
    rm -rf /var/lib/apt/lists/*; \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime; \
    echo $TZ > /etc/timezone

# 构建期校验：chromium 与 chromedriver 必须同主版本。
# 运行期截图用的就是这一对（WebScreenshotUtils 显式使用 /usr/bin/chromedriver，见 ChromeInstallation），
# 一旦不匹配，Selenium 会报
#   session not created: This version of ChromeDriver only supports Chrome version X
#   Current browser version is Y with binary path /usr/bin/chromium
# 所有截图（应用封面）整体不可用。与其等线上暴露，不如让构建直接失败。
RUN set -eux; \
    chrome_major="$(chromium --version | awk '{print $2}' | cut -d. -f1)"; \
    driver_major="$(chromedriver --version | awk '{print $2}' | cut -d. -f1)"; \
    echo "chromium major=$chrome_major, chromedriver major=$driver_major"; \
    [ "$chrome_major" = "$driver_major" ]

# 安装 Temurin JRE 21（解压到 /opt/java）
RUN set -eux; \
    mkdir -p /opt/java; \
    curl -fsSL "$JRE_URL" -o /tmp/jre.tar.gz; \
    tar -xzf /tmp/jre.tar.gz -C /opt/java --strip-components=1; \
    rm -f /tmp/jre.tar.gz; \
    java -version; \
    chromium --version; \
    chromedriver --version; \
    node --version; \
    npm --version

WORKDIR /app

COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh

# temp/* 必须存在且属于运行用户：命名卷首次挂载时会继承这里的属主，
# 否则 Vue 工程构建（npm install）会因为没有写权限而失败
RUN set -eux; \
    chmod +x /usr/local/bin/entrypoint.sh; \
    mkdir -p /app/temp/code_output /app/temp/code_deploy /app/temp/screenshots; \
    chown -R node:node /app

EXPOSE 8123

# 容器内自检（context-path 默认 /api，可用环境变量覆盖）
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT:-8123}${SERVER_SERVLET_CONTEXT_PATH:-/api}/health" || exit 1

# node 镜像自带的非 root 用户（uid 1000）：chrome 代码里已经用 --no-sandbox 运行，无需 root
USER node

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# -----------------------------------------------------------------------------
# 模式 ①：直接用本地打好的 fat jar
#
# 前置条件：构建上下文根目录下有 app.jar（就是上传上来的那个 Spring Boot 可执行 jar）
#   docker build --target runtime-from-jar -t ai-code-backend:1.0.0 .
#   docker compose build backend        # .env 里 BACKEND_BUILD_TARGET=runtime-from-jar
# -----------------------------------------------------------------------------
FROM runtime-base AS runtime-from-jar
COPY app.jar /app/app.jar

# -----------------------------------------------------------------------------
# 模式 ②：容器内从源码全流程构建（Maven + JDK 21）
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先只复制 pom.xml：只要依赖没变，这一层就能命中缓存，改代码不必重新下载依赖
# （go-offline 只是预热依赖缓存，允许失败：真正的失败会在下面的 package 阶段明确报出来）
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline || true

# 再复制源码并打包（跳过测试：单测里有依赖真实模型/网络的用例）
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true clean package \
    && cp target/*.jar /build/app.jar

# 默认 target：放在最后 = docker build / docker compose build 不带 target 时构建这个
FROM runtime-base AS runtime
COPY --from=builder /build/app.jar /app/app.jar

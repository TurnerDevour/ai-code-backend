# Docker 部署说明

> **想直接照着做**：操作手册见 [DEPLOY.md](DEPLOY.md)（一步一步的命令、验证与排错）。
> 本文侧重"为什么这么设计"：依赖分析、镜像里的关键机制、参数取舍。

本文件说明如何用 `Dockerfile` + `docker-compose.yml` 部署 ai-code-backend。

## 1. 依赖分析结论（决定了镜像里装什么）

| 依赖 | 来源 | 容器内如何满足 |
| --- | --- | --- |
| Java 21 | `pom.xml` → `java.version=21`；代码用 `Thread.ofVirtual()` | 镜像安装 Temurin JRE 21 |
| Spring Boot 3.5.6（web / aop / session） | pom | 打成可执行 jar，`java -jar` 启动 |
| MySQL 8 | pom `mysql-connector-j`，prod 配置写死了线上 IP | compose 起 `mysql` 服务，首次启动执行 `src/main/resources/sql/ai_code_db.sql` |
| Redis | `jedis` + `spring-session-data-redis` + `langchain4j-community-redis`（对话记忆） | compose 起 `redis` 服务（带密码 + AOF） |
| Node.js + npm | `VueProjectBuilder` 用 `ProcessBuilder` 执行 `npm install` / `npm run build` | 基础镜像用 `node:22-bookworm-slim`，npm 直接可用 |
| Chromium + ChromeDriver | `selenium-java` + `webdrivermanager`（`WebScreenshotUtils` 无头截图） | Debian apt 安装 `chromium` + `chromium-driver`，并在容器启动时把驱动写入 WebDriverManager 缓存（见第 4 节） |
| 中文字体 | 截图对象是中文页面 | 安装 `fonts-noto-cjk` 等字体 |
| 本地文件目录 | `AppConstant` 用 `user.dir` 拼 `temp/code_output`、`temp/code_deploy`、`temp/screenshots` | `WORKDIR /app` + 命名卷 `app-temp:/app/temp` |
| 腾讯云 COS | `cos_api`，封面截图上传 | 密钥由 `.env` 注入 |
| 阿里云百炼 | `langchain4j-open-ai` 等 | 密钥由 `.env` 注入 |
| 静态站点托管 | 部署产物写在 `temp/code_deploy/{deployKey}/`，地址前缀是 `code.deploy-host` | compose 起 `nginx`，以 `/dist/{deployKey}/` 对外提供 |
| 前端站点 | 独立仓库 `ai-code-frontend`（Vue 3 + Vite 7），构建产物是 `dist/` | compose 把宿主机 `./frontend-dist` 只读挂给 nginx，由 `location /` 提供（打包与上传见 [DEPLOY.md](DEPLOY.md) 第 10 步） |

## 2. 服务拓扑（生产统一入口 `http://wlbc.top`）

```
浏览器 ──┬─ http://wlbc.top/                     ──> nginx ──静态──> frontend-dist 目录（ai-code-frontend 的 dist）
         ├─ http://wlbc.top/api/...              ──> nginx ──反代──> backend:8123（context-path=/api）
         ├─ http://wlbc.top/dist/{key}/          ──> nginx ──(只读)── app-temp 卷 ←─ backend 写入的部署产物
         └─ http://wlbc.top:8123/api/...         ──> backend（端口仍发布，直连调试用）

backend ──> mysql:3306（业务库）
backend ──> redis:6379（Session + 对话记忆）
backend ──> 阿里云百炼 / 腾讯云 COS / npm registry（公网）
```

> `/api/` 由 nginx 反代（含 SSE 长连接的关缓冲配置）。若前端仍直连 `:8123`，
> 可删掉 `docker/nginx.conf` 里的 `location /api/`，不影响部署站点托管。
> 需保证域名 `wlbc.top` 解析到本机，且本机 80 端口未被占用。

## 3. 快速开始

```bash
cp .env.example .env      # 填好数据库、Redis、COS、百炼密钥
docker compose up -d --build
docker compose ps
```

- 后端接口：`http://wlbc.top/api`
- 接口文档：`http://wlbc.top/api/doc.html`（默认 admin / admin123）
- 部署队列水位：`http://wlbc.top/api/health/deploy-queue`
- 用户部署的站点：`http://wlbc.top/dist/{deployKey}/`

> 首次启动 MySQL 会自动执行建表脚本；数据卷 `mysql-data` 已有数据时不会重复执行。

## 4. 关于截图（最容易踩的坑）

`WebScreenshotUtils` 每次创建 Chrome 都会调用 `WebDriverManager.chromedriver().setup()`。
WebDriverManager 的默认流程是：**探测浏览器版本 → 访问 Chrome for Testing 端点查驱动版本 →
从 `storage.googleapis.com` 下载驱动 → 导出 `webdriver.chrome.driver`**。
它不会自动使用系统里已有的 `chromedriver`，而 `storage.googleapis.com` 在国内服务器基本不通，
一旦下载失败，所有截图（应用封面）都会失败。

因此镜像里做了两件事：

1. apt 安装 **同源同版本** 的 `chromium` 与 `chromium-driver`；
2. 容器启动时（`docker/entrypoint.sh`）把 `/usr/bin/chromedriver` 放进 WebDriverManager 期望的
   缓存结构 `${WDM_CACHEPATH}/chromedriver/linux64/<版本>/chromedriver`，
   并导出 `WDM_CHROMEDRIVERSION=<版本>`。驱动版本已知时 WebDriverManager 会跳过联网查版本，
   直接命中本地缓存 —— 全程离线。

另外 `CODE_DEPLOY_HOST` 既是返回给前端的部署地址，也是后台截图访问的地址，
**必须容器内也能访问**。生产值就是 `http://wlbc.top/dist`：

- 正常情况：容器内解析 `wlbc.top` → 公网 IP，回流到本机 nginx:80（云厂商 NAT 回流一般可用）
- 若容器内访问不到该域名（回流被禁 / 域名在 CDN 后面）：临时改成
  `CODE_DEPLOY_HOST=http://host.docker.internal/dist`（compose 已加 `extra_hosts`）先保证截图可用
- 自检命令：`docker compose exec backend curl -sI http://wlbc.top/dist/ | head -1`

## 5. 国内网络注意事项

| 场景 | 处理方式 |
| --- | --- |
| 拉不动 Docker Hub 镜像 | 给 Docker 配置镜像加速器；`nginx` / `redis` 用了本机已有 tag，`mysql:8.0` 需要能拉到 |
| 构建时下载 JRE 慢 | 在 `.env` 里打开 `JRE_URL`（清华 Adoptium 镜像） |
| `npm install` 慢 | `.env` 里 `NPM_REGISTRY` 已默认 `https://registry.npmmirror.com` |
| apt 慢 | 可在 Dockerfile 里把 `deb.debian.org` 换成国内镜像源 |

## 6. 资源与调优（按 2 核 4G 服务器配置）

**内存预算**（`docker-compose.yml` 里每个服务都有 `mem_limit`，防止某个容器把整机吃光后被内核随机 OOM）：

| 服务 | mem_limit | 说明 |
| --- | --- | --- |
| backend | `2600m` | JVM 堆固定 1G + Metaspace 256m，余量给 Chrome（2 × ~300MB）与 npm 构建进程 |
| mysql | `800m` | innodb buffer pool 128M、max_connections 100、关闭 performance_schema |
| redis | `320m` | `maxmemory 256mb` + `allkeys-lru`：写满淘汰键，而不是被内核 OOM 杀掉丢掉全部会话 |
| nginx | `64m` | 只有静态文件与反向代理 |
| 合计 | ≈ 3.7G | 留 ~300MB 给系统与 Docker 自身 |

**已按 2 核 4G 下调的参数**：

| 参数 | 值（原值） | 原因 |
| --- | --- | --- |
| `JAVA_OPTS` 堆 | `-Xms256m -Xmx1024m -XX:MaxMetaspaceSize=256m`（原 `MaxRAMPercentage=75`） | 容器未设 cgroup 限制时，`MaxRAMPercentage` 会按整机 4G 算，堆能长到 3G |
| 截图浏览器池 | `-Dscreenshot.max-drivers=2`（原 4） | 每个 Chrome 约占 200~400MB |
| 截图排队超时 | `-Dscreenshot.borrow-timeout-seconds=300`（原 180） | 池子变小、排队变长，避免"截图服务繁忙" |
| 部署构建并发 | `DEPLOY_QUEUE_WORKERS=2`（原 4/8） | 每个构建吃 1 核 + 0.4~0.8G 内存，机器只有 2 核 |
| 构建队列容量 | `DEPLOY_QUEUE_MAX_SIZE=32`（原 64） | 小机器上排队过长没意义 |
| MySQL 连接池 | `DB_POOL_MAX_SIZE=20` / `min-idle 5`（原 64/16） | 64 个连接会把 MySQL 内存与连接数吃满 |
| Jedis 连接池 | `REDIS_POOL_MAX_ACTIVE=32` / `max-idle 16` / `min-idle 4`（原 128/64/16） | 同上 |

`application-prod.yaml`（裸机跑生产时用的 profile）里的连接池与构建队列也已同步改成同一套数值。

**调优建议**：

- 部署期间接口变慢是正常的：一个 Vue 工程 `npm install` + `build` 在 2 核上通常要 1~3 分钟，
  同时部署会排队（水位看 `http://wlbc.top/api/health/deploy-queue`）。
- 如果出现 OOM（`docker compose ps` 看到容器重启、日志里有 `OutOfMemory`）：先把
  `DEPLOY_QUEUE_WORKERS` 降到 1，再把 `-Xmx1024m` 降到 `896m`。
- 想再省内存：把 `-Dscreenshot.max-drivers` 改成 1（截图串行，每张约 3 秒）。
- 将来升级到 4 核 8G：`-Xmx2g`、`DEPLOY_QUEUE_WORKERS=4`、各服务 `mem_limit` 翻倍即可。
- 虚拟线程（`spring.threads.virtual.enabled=true`）**默认没开**，与线上 prod 行为保持一致，2 核机器也不建议开。
- **构建镜像本身也吃内存**（Maven 编译 + apt 装 chromium，约 5~10 分钟，且构建阶段不受 `mem_limit` 约束）：
  建议先 `docker compose build backend` 再 `docker compose up -d`，构建时保证有 1G 以上空闲内存，
  必要时临时加 swap，避免边跑 MySQL 边编译把机器压爆。

## 7. 常用运维命令

```bash
docker compose logs -f backend          # 后端日志（含 npm 构建输出，前缀 [npm]）
docker compose logs -f nginx
docker compose up -d backend             # 改完 .env 后必须用 up -d 重建容器（restart 不会重读 .env）
docker compose up -d --build backend     # 代码更新后重新构建并重建
docker compose down                      # 停止（保留数据卷）
docker compose down -v                   # 停止并删除数据卷（会清空数据库和已部署站点）
docker compose exec mysql mysql -uroot -p ai_code_db   # 进库
docker compose exec backend sh                          # 进容器排查
```

数据库备份：

```bash
docker compose exec mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" ai_code_db' > backup.sql
```

## 8. 常见问题

| 现象 | 排查方向 |
| --- | --- |
| 启动失败，日志报数据库连接不上 | `docker compose ps` 看 mysql 是否 healthy；确认 `.env` 的 `MYSQL_USER/MYSQL_PASSWORD` 与初始化时一致（改密码不会同步到已有数据卷，需 `down -v` 重建） |
| 部署成功但封面为空 / 日志有 `初始化 ChromeDriver 失败` | 看 `docker compose logs backend | grep -i chrom`；确认 `CODE_DEPLOY_HOST` 容器内可访问（`docker compose exec backend curl -I http://wlbc.top/dist/`） |
| 部署报 `npm install 失败` | 日志里会带 npm 末尾输出；多为 registry 不通（换 `NPM_REGISTRY`）或内存不足 |
| 截图里中文变方块 | 字体缺失（镜像已装 `fonts-noto-cjk`，自行改镜像时别删） |
| 部署后打开页面白屏 | 产物用了 `base: './'`（`VueProjectBuilder` 会注入），确认访问地址带了 `/dist/{deployKey}/` 且结尾有斜杠 |
| 想改配置但不想改代码 | 任何 Spring 配置都能用环境变量覆盖（如 `SERVER_PORT`、`DEPLOY_QUEUE_WORKERS`），见 `application-docker.yaml` |

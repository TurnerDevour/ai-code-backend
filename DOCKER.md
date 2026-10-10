# Docker 部署说明

> **想直接照着做**：操作手册见 [DEPLOY.md](DEPLOY.md)（一步一步的命令、验证与排错）。
> 本文侧重"为什么这么设计"：依赖分析、镜像里的关键机制、参数取舍。

本文件说明如何用 `Dockerfile` + `docker-compose.yaml` 部署 ai-code-backend。

## 1. 依赖分析结论（决定了镜像里装什么）

| 依赖 | 来源 | 容器内如何满足 |
| --- | --- | --- |
| Java 21 | `pom.xml` → `java.version=21`；代码用 `Thread.ofVirtual()` | 镜像安装 Temurin JRE 21 |
| Spring Boot 3.5.6（web / aop / session） | pom | 打成可执行 jar，`java -jar` 启动 |
| MySQL 8 | pom `mysql-connector-j`，prod 配置写死了线上 IP | compose 起 `mysql` 服务，首次启动执行 `src/main/resources/sql/ai_code_db.sql` |
| Redis | `jedis` + `spring-session-data-redis` + `langchain4j-community-redis`（对话记忆） | compose 起 `redis` 服务（带密码 + AOF） |
| Node.js + npm | `VueProjectBuilder` 用 `ProcessBuilder` 执行 `npm install` / `npm run build` | 基础镜像用 `node:22-bookworm-slim`，npm 直接可用 |
| Chromium + ChromeDriver | `selenium-java` + `webdrivermanager`（`WebScreenshotUtils` 无头截图） | Debian apt 安装 `chromium` + `chromium-driver`（同源同版本，构建期校验主版本一致），运行期显式使用系统驱动（见第 4 节） |
| 中文字体 | 截图对象是中文页面 | 安装 `fonts-noto-cjk` 等字体 |
| 本地文件目录 | `AppConstant` 用 `user.dir` 拼 `temp/code_output`、`temp/code_deploy`、`temp/screenshots` | `WORKDIR /app` + 命名卷 `app-temp:/app/temp` |
| 腾讯云 COS | `cos_api`，封面截图上传 | 密钥由 `.env` 注入 |
| 阿里云百炼 | `langchain4j-open-ai` 等 | 密钥由 `.env` 注入 |
| 静态站点托管 | 部署产物写在 `temp/code_deploy/{deployKey}/`，地址前缀是 `code.deploy-host` | compose 起 `nginx`，以 `/dist/{deployKey}/` 对外提供 |
| 前端站点 | 独立仓库 `ai-code-frontend`（Vue 3 + Vite 7），构建产物是 `dist/` | compose 把宿主机 `./frontend-dist` 只读挂给 nginx，由 `location /` 提供（打包与上传见 [DEPLOY.md](DEPLOY.md) 第 10 步） |

## 2. 服务拓扑（生产统一入口 `https://wlbc.top`）

```
浏览器 ──┬─ https://wlbc.top/                     ──> nginx:443 ──静态──> frontend-dist 目录（ai-code-frontend 的 dist）
         ├─ https://wlbc.top/api/...              ──> nginx:443 ──反代──> backend:8123（context-path=/api）
         ├─ https://wlbc.top/dist/{key}/          ──> nginx:443 ──(只读)── app-temp 卷 ←─ backend 写入的部署产物
         ├─ http://wlbc.top/...                   ──> nginx:80  ──301──> https://wlbc.top/...（只做跳转）
         └─ 127.0.0.1:8123/api/...                ──> backend（默认只绑本机，宿主机上调试用）

backend ──> mysql:3306（业务库）      ┐
backend ──> redis:6379（Session + 对话记忆）│ 都走自定义网络 ai-code-net（服务名互相解析）
backend ──> 阿里云百炼 / 腾讯云 COS / npm registry（公网）
```

> 80 端口上有两个 server：规范域名 `wlbc.top` / `www.wlbc.top` 一律 301 到 https；
> 其它 Host（IP、以及截图兜底用的 `host.docker.internal`）保留 `/dist/` 与 `/nginx-health` 的 http 直连，
> 原因是跳到 https 后证书与这些 Host 不匹配，浏览器会直接拒绝（详见第 5 节与 `docker/nginx.conf` 里的注释）。

**网络**：所有服务接在同一张自定义 bridge 网络 `ai-code-net` 上（`docker-compose.yaml` 末尾 `networks:`），
不用 compose 默认生成的 `<项目名>_default`：

- 名字**固定**（`name: ai-code-net`），不随 compose 项目名/部署目录变化；临时排查容器可以直接接进来：
  ```bash
  docker run --rm -it --network ai-code-net redis:8.10 redis-cli -h redis -a "$REDIS_PASSWORD" ping
  ```
- 同一张用户自定义网络上，容器之间用**服务名**互相解析（`backend` 连的就是 `mysql` / `redis`），
  且只有同网络的容器能互访，比默认 bridge 隔离性更好。
- **网段是显式指定的**：`10.201.0.0/24`（`.env` 的 `DOCKER_SUBNET` 可改）。
  为什么要显式指定：Docker 的默认可分配池是 `172.17.0.0/16 ~ 172.31.0.0/16`，
  一旦和宿主机自己的 VPC / 内网网段撞车，症状是容器连内网或公网时**超时**（TLS 握手卡住、`npm install` 挂起），
  而且很难联想到是网段问题。选的 `10.201.0.0/24` 既不在默认池内，也不与最常见的 `/16` 内网段
  （`10.0.0.0/16`、`172.16.0.0/16`、`192.168.0.0/16`）重叠。
  - 部署前核对本机网段：`ip -4 addr show | grep inet`、`ip route | grep -v docker`；
    如果本机真的用了 `10.201.0.x`，把 `DOCKER_SUBNET` 换成 `10.202.0.0/24` 之类。
  - 改完直接 `docker compose up -d` 即可：compose 发现网络配置变化会重建网络和容器
    （实测：`10.201.0.0/24` 改成 `10.202.0.0/24` 后 `up -d`，网络网段随之更新）。
    若因网络被别的容器占用而重建失败，先 `docker network rm ai-code-net`（确认没别的容器在用）再 `up -d`。
  - 核对实际网段：`docker network inspect ai-code-net --format '{{range .IPAM.Config}}{{.Subnet}} {{.Gateway}}{{end}}'`
    → 期望 `10.201.0.0/24 10.201.0.1`。
- 如果服务器上**已存在同名但不是 compose 创建**的网络，compose 只会打一条 warning 并复用它
  （不会启动失败）。想消掉警告：`docker network rm ai-code-net`（确认没别的容器在用），
  或在编排里把它声明成 `external: true`。
- 排查用：`docker network inspect ai-code-net --format '{{range .Containers}}{{.Name}} {{end}}'`。

> `/api/` 由 nginx 反代（含 SSE 长连接的关缓冲配置）。前端若直连后端端口（`:8123`），
> 需要把 `.env` 的 `SERVER_BIND` 改成 `0.0.0.0`（默认只绑 `127.0.0.1`，外部访问不到）。
> 需保证域名 `wlbc.top` 解析到本机，且本机 80、443 端口未被占用（443 还要在云防火墙放行）。

## 3. 快速开始

```bash
cp .env.example .env      # 填好数据库、Redis、COS、百炼密钥
docker compose up -d --build
docker compose ps
```

- 后端接口：`https://wlbc.top/api`
- 接口文档：`https://wlbc.top/api/doc.html`（默认 admin / admin123）
- 部署队列水位：`https://wlbc.top/api/health/deploy-queue`
- 用户部署的站点：`https://wlbc.top/dist/{deployKey}/`

> 首次启动 MySQL 会自动执行建表脚本；数据卷 `mysql-data` 已有数据时不会重复执行。

## 4. 关于截图（最容易踩的坑）

截图要求**驱动与浏览器同主版本**。镜像里 apt 安装的是同源同版本的 `chromium` + `chromium-driver`，
代码显式使用系统里的驱动：`ChromeInstallation` 的定位顺序是
系统属性 `screenshot.chrome-driver-path` → 环境变量 `CHROMEDRIVER_BIN` → `/usr/bin/chromedriver`；
浏览器同理（`screenshot.chrome-binary-path` / `CHROME_BIN` / `/usr/bin/chromium`）。
容器启动时 `docker/entrypoint.sh` 会把这两个路径导出并在日志里打印一次版本。

**为什么不靠 WebDriverManager 自动解析**（原来就是它踩的坑）：`WebDriverManager.chromedriver().setup()`
的流程是"探测浏览器版本 → 访问 Chrome for Testing 端点查驱动版本 → 从 `storage.googleapis.com`
下载驱动"。它既会卡在国内网络上（下载失败则截图整体不可用），也可能解析出与镜像里浏览器不同版本的驱动，
报出实测过的这条错误：

```
session not created: This version of ChromeDriver only supports Chrome version 155
Current browser version is 154.0.8037.92 with binary path /usr/bin/chromium
```

现在 WebDriverManager 只在**系统里没有驱动**时才兜底（开发机 Windows/macOS 走这条路径），
并且：

1. `Dockerfile` 在**构建期**校验 `chromium` 与 `chromedriver` 主版本一致，不一致直接构建失败；
2. 运行期创建实例前再校验一次，不匹配时抛出说清修法的业务异常（而不是 Selenium 的 500）；
3. 实际使用的一对会打进日志：`docker compose logs backend | grep "截图使用的浏览器与驱动"`。

另外 `CODE_DEPLOY_HOST` 既是返回给前端的部署地址，也是后台截图访问的地址，
**必须容器内也能访问**，也**必须是 https**（前端页面是 https，给 http 地址会被浏览器当混合内容拦掉）。
生产值就是 `https://wlbc.top/dist`：

- 正常情况：容器内解析 `wlbc.top` → 公网 IP，回流到本机 nginx:443（云厂商 NAT 回流一般可用）
- 若容器内访问不到该域名（回流被禁 / 域名在 CDN 后面）：临时改成
  `CODE_DEPLOY_HOST=http://host.docker.internal/dist`（compose 已加 `extra_hosts`）先保证截图可用。
  这里**刻意用 http**：nginx 的 80 端口对非规范 Host 保留了 `/dist` 的直连，不会跳 https；
  否则 `host.docker.internal` 会被 301 到 https，而证书是按 `wlbc.top` 签发的，Chrome 会直接拒绝
- 自检命令：`docker compose exec backend curl -sI https://wlbc.top/dist/ | head -1`

## 5. HTTPS 与证书续期

**证书在哪**：`src/main/resources/ssl_nginx/`（腾讯云免费 DV 证书，TrustAsia 签发），
compose 把它只读挂到 nginx 容器的 `/etc/nginx/ssl`：

| 文件 | 用途 |
| --- | --- |
| `wlbc.top_bundle.crt` | 站点证书 + 中间证书（3 张，顺序：站点 → 中间 → 交叉根），给 `ssl_certificate` |
| `wlbc.top.key` | 私钥（RSA，无口令），给 `ssl_certificate_key` |
| `wlbc.top_bundle.pem` | 与 `.crt` 内容完全相同，备用 |

**当前有效期：2026-10-10 ~ 2027-01-08**（免费证书 90 天）。到期前必须在腾讯云**重新申请**并替换
上面两个文件（文件名不变），然后：

```bash
docker compose restart nginx
# 确认换上了（subject 应为 CN=wlbc.top，notAfter 是新日期）
echo | openssl s_client -connect 127.0.0.1:443 -servername wlbc.top 2>/dev/null | openssl x509 -noout -subject -dates
```

**为什么必须提前**：nginx 开了 HSTS（`Strict-Transport-Security`），证书一旦过期，
浏览器不会再给"继续访问"的按钮，用户只能换浏览器/清 HSTS 缓存才能访问 —— 建议**提前一周**换。

**出问题时怎么回滚/自查**：

| 现象 | 原因与处理 |
| --- | --- |
| nginx 容器起不来，日志报 `cannot load certificate ... No such file` | 宿主机 `src/main/resources/ssl_nginx/` 里文件被删/改名；确认两个文件名与 nginx.conf 里一致 |
| 日志报 `key values mismatch` | 换了证书没换私钥（或反之）；两者必须配对：`openssl x509 -noout -modulus -in wlbc.top_bundle.crt \| openssl md5` 与 `openssl rsa -noout -modulus -in wlbc.top.key \| openssl md5` 应相同 |
| 浏览器提示 `NET::ERR_CERT_DATE_INVALID` | 证书过期，按上面流程换新 |
| 想临时关掉 HSTS | 注释 `docker/nginx.conf` 里 4 处 `add_header Strict-Transport-Security ...`（443 server 与 /api/、/dist/、/assets/、= /index.html、/ 各处），`docker compose restart nginx` |

> ⚠️ **私钥入库提醒**：`wlbc.top.key` 目前随仓库提交。仓库若有可能被公开（或已推到公有平台），
> 建议把它加进 `.gitignore` 并在腾讯云**重新签发**一张证书（旧私钥视为已泄露），
> 服务器上用新文件覆盖即可。密钥只读挂载，权限不用额外调整。

## 6. 国内网络注意事项

| 场景 | 处理方式 |
| --- | --- |
| 拉不动 Docker Hub 镜像 | 给 Docker 配置镜像加速器；`nginx` / `redis` 用了本机已有 tag，`mysql:8.0` 需要能拉到 |
| 构建时下载 JRE 慢 | 在 `.env` 里打开 `JRE_URL`（清华 Adoptium 镜像） |
| `npm install` 慢 | `.env` 里 `NPM_REGISTRY` 已默认 `https://registry.npmmirror.com` |
| apt 慢 | 可在 Dockerfile 里把 `deb.debian.org` 换成国内镜像源 |

## 7. 资源与调优（按 2 核 4G 服务器配置）

**内存预算**（`docker-compose.yaml` 里每个服务都有 `mem_limit`，防止某个容器把整机吃光后被内核随机 OOM）：

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
  同时部署会排队（水位看 `https://wlbc.top/api/health/deploy-queue`）。
- 如果出现 OOM（`docker compose ps` 看到容器重启、日志里有 `OutOfMemory`）：先把
  `DEPLOY_QUEUE_WORKERS` 降到 1，再把 `-Xmx1024m` 降到 `896m`。
- 想再省内存：把 `-Dscreenshot.max-drivers` 改成 1（截图串行，每张约 3 秒）。
- 将来升级到 4 核 8G：`-Xmx2g`、`DEPLOY_QUEUE_WORKERS=4`、各服务 `mem_limit` 翻倍即可。
- 虚拟线程（`spring.threads.virtual.enabled=true`）**默认没开**，与线上 prod 行为保持一致，2 核机器也不建议开。
- **构建镜像有两种方式**（同一个 `Dockerfile`，用 build target 区分，见文件头部注释）：
  - `BACKEND_BUILD_TARGET=runtime-from-jar`（推荐）：本地 `mvn clean package -DskipTests` 后把
    `target/ai-code-backend-1.0.0.jar` 传成 `./app.jar`，服务器只装运行时 —— **不跑 Maven**，约 2~3 分钟、几乎不吃额外内存；
  - `BACKEND_BUILD_TARGET=runtime`（默认）：容器内从源码编译，2 核上约 5~10 分钟、构建阶段内存峰值约 1G
    （构建不受 `mem_limit` 约束，所以要保证有 1G 以上空闲内存，必要时临时加 swap）。
  无论哪种方式，都建议先 `docker compose build backend` 再 `docker compose up -d`。

## 8. 常用运维命令

```bash
docker compose logs -f backend          # 后端日志（含 npm 构建输出，前缀 [npm]）
docker compose logs -f nginx
docker compose up -d backend             # 改完 .env 后必须用 up -d 重建容器（restart 不会重读 .env）
docker compose build backend && docker compose up -d backend   # jar 模式升级：先传新 app.jar 再执行这行
docker compose down                      # 停止（保留数据卷）
docker compose down -v                   # 停止并删除数据卷（会清空数据库和已部署站点）
docker compose exec mysql mysql -uroot -p ai_code_db   # 进库
docker compose exec backend sh                          # 进容器排查
docker compose exec nginx nginx -t                      # 校验 nginx 配置（改完 nginx.conf 先跑这条）
docker compose restart nginx                            # 换证书后重启（配置不变，不用重建容器）
```

HTTPS / 证书自查：

```bash
# 证书主体与有效期（应为 CN=wlbc.top，notAfter 是新日期）
echo | openssl s_client -connect 127.0.0.1:443 -servername wlbc.top 2>/dev/null | openssl x509 -noout -subject -dates
# http 是否正常跳转（期望 301 + Location: https://...）
curl -sI http://wlbc.top/ | head -2
# https 是否正常（期望 200；不需要 -k，证书是可信 CA 签发的）
curl -s -o /dev/null -w '%{http_code}\n' https://wlbc.top/api/health
```

数据库备份：

```bash
docker compose exec mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" ai_code_db' > backup.sql
```

## 9. 常见问题

| 现象 | 排查方向 |
| --- | --- |
| 启动失败，日志报数据库连接不上 | `docker compose ps` 看 mysql 是否 healthy；确认 `.env` 的 `MYSQL_USER/MYSQL_PASSWORD` 与初始化时一致（改密码不会同步到已有数据卷，需 `down -v` 重建） |
| 部署成功但封面为空 / 日志有 `初始化 ChromeDriver 失败` | 看 `docker compose logs backend \| grep -i chrom`；若日志里有 `ChromeDriver 与浏览器版本不匹配`，说明驱动与浏览器不同主版本（重建镜像即可，构建期会拦住），或 `-Dscreenshot.chrome-driver-path` / `CHROMEDRIVER_BIN` 手工指错了路径。另确认 `CODE_DEPLOY_HOST` 容器内可访问（`docker compose exec backend curl -I https://wlbc.top/dist/`） |
| 部署报 `npm install 失败` | 日志里会带 npm 末尾输出；多为 registry 不通（换 `NPM_REGISTRY`）或内存不足 |
| 截图里中文变方块 | 字体缺失（镜像已装 `fonts-noto-cjk`，自行改镜像时别删） |
| 部署后打开页面白屏 | 产物用了 `base: './'`（`VueProjectBuilder` 会注入），确认访问地址带了 `/dist/{deployKey}/` 且结尾有斜杠 |
| 想改配置但不想改代码 | 任何 Spring 配置都能用环境变量覆盖（如 `SERVER_PORT`、`DEPLOY_QUEUE_WORKERS`），见 `application-docker.yaml` |
| 浏览器提示证书无效/过期，或地址栏没有 🔒 | 见第 5 节：确认证书文件在、没换错、没过期；`docker compose exec nginx nginx -t` 与上面的 openssl 自查命令 |
| `http://wlbc.top` 打不开，但 `https://` 正常 | 80 端口没放行或被占用；`.env` 的 `NGINX_PORT`、云防火墙的 80 规则 |
| `https://wlbc.top` 连接超时/被拒 | 443 没在云防火墙放行（腾讯云轻量：控制台 → 实例 → 防火墙）；`sudo ss -lntp \| grep 443` 看容器是否真在监听 |
| 页面能开但封面图不显示 | `CODE_DEPLOY_HOST` 必须是 https（否则混合内容被拦）；容器内能否访问见第 4 节 |

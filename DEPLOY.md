# 部署步骤详解

**目标环境**：Ubuntu Server 24.04 LTS 64bit（x86_64）· 2 核 4G · 域名 `https://wlbc.top`
（跑在容器里的系统是 Debian 12，与宿主发行版无关；本文按 Ubuntu 24.04 写命令）

> 本文是**按顺序照抄即可**的操作手册；每一步都说明「在干什么 / 怎么验证 / 失败了怎么办」。
> 设计说明与依赖分析见 [DOCKER.md](DOCKER.md)。

## 步骤总览

| # | 做什么 | 耗时 | 完成后状态 |
| --- | --- | --- | --- |
| 1 | 服务器准备：装 Docker + 免 sudo + 镜像加速 + swap | 5 分钟 | 服务器具备部署条件 |
| 2 | **本地打包 → 上传部署文件（jar + 编排文件）到服务器** | 2 分钟 | `/opt/ai-code-backend` 里有 `app.jar`，**没有 Java 源码** |
| 3 | 写 `.env`（密钥/密码/域名） | 5 分钟 | 配置就绪 |
| 4 | 静态校验配置 | 10 秒 | 配置无语法错 |
| 5 | 构建后端镜像（只装运行时 + 换 jar） | 2~3 分钟 | 有 `ai-code-backend:1.0.0` 镜像 |
| 6 | 只启动 MySQL + Redis | 1~2 分钟 | 库表已建好、健康 |
| 7 | 启动 backend | 1~2 分钟 | 健康检查通过 |
| 8 | 启动 nginx 并验证路由（含 https 证书） | 10 秒 | `/api` 与 `/dist` 都通，`nginx -t` 通过 |
| 9 | 域名解析 + 云防火墙放行 80/443 + 证书检查 | 5 分钟 | 浏览器能打开 🔒 https |
| 10 | **打包前端并发布到 nginx** | 3~5 分钟 | `https://wlbc.top/` 打开是前端页面 |
| 11 | 业务冒烟（注册→提权→生成→部署→截图） | 10 分钟 | 全链路可用 |
| 12 | 收尾（自启/日志/备份） | 5 分钟 | 可长期运行 |
| 13 | 以后每次升级（后端 + 前端） | 每次 5~10 分钟 | — |
| 14 | 出问题时的速查表 | 参考 | — |

---

## 第 1 步：服务器准备（Ubuntu Server 24.04）

**在干什么**：让这台 2 核 4G 的 Ubuntu 24.04 具备跑容器的条件。要点：装**官方新版** Docker（别用 apt 里的
`docker.io` 旧版）、让当前用户免 `sudo`、配镜像加速与日志轮转、按需补 swap。

```bash
# 1.1 先确认基本信息
uname -m                          # 期望 x86_64（本文的 JRE 包按 x64 写；镜像里 chromium/chromedriver 由 apt 安装）
cat /etc/os-release | head -2      # 期望 Ubuntu 24.04.x LTS
nproc && free -h && df -h /

# 1.2 时区（影响日志时间、备份文件名；容器内已单独设 TZ）
sudo timedatectl set-timezone Asia/Shanghai
date

# 1.3 安装 Docker（官方脚本装最新版；Ubuntu 24.04 仓库里的 docker.io 是 24.0.x 老版本）
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker

# 1.4 让当前用户免 sudo 用 docker —— 很重要：
#     否则 compose 创建的挂载目录（如 frontend-dist）会归 root，第 10 步 scp 前端产物会 Permission denied
sudo usermod -aG docker "$USER"
# 退出终端重新登录（或执行 newgrp docker）后验证：
docker version && docker compose version      # 期望 compose v2.x，且都不需要 sudo

# 1.5 镜像加速 + 日志轮转（4G 机器磁盘小，日志必须限大小）
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "registry-mirrors": ["https://<你的镜像加速器地址>"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "50m", "max-file": "3" }
}
EOF
sudo systemctl restart docker
docker info | grep -A3 "Registry Mirrors"     # 确认加速器已生效

# 1.6 swap：先看有没有，不够再补 2G（第 5 步编译镜像 / 用户工程 npm 构建时的保命余量）
swapon --show
# 若上面是空的（没有 swap），再执行下面四条：
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

**预期**：`nproc` = 2；`docker version` 与 `docker compose version` 免 sudo 可跑；swap ≥ 2G；
根分区剩余 ≥ 20G（镜像 + 构建缓存 + 用户生成的代码都在这台机器上）。

**Ubuntu 24.04 上要注意的三点**：
1. **别用 `apt install docker.io`**：noble 仓库里是 24.0.x，还要自己另装 compose 插件；官方脚本一步到位。
2. **不要启用 ufw**（Ubuntu Server 默认就是关闭状态，保持关闭即可）：Docker 发布端口时直接写
   iptables/nftables 的 `DOCKER` 链，**绕过 ufw 规则**，开了反而容易出现"规则看着对、端口其实还是通的"。
   这台机器的门禁统一用**云防火墙/安全组**（腾讯云轻量应用服务器叫"防火墙"，CVM 叫"安全组"，见第 9 步）。
3. Ubuntu 24.04 默认就是 **cgroup v2**，compose 里的 `mem_limit` 直接生效，不用改 grub。

**失败怎么办**：
- `docker compose version` 报错 → `sudo apt update && sudo apt install -y docker-compose-v2`（noble 里的包名）
- 拉不到镜像（`docker pull hello-world` 超时）→ 加速器地址不可用，换一个，或用云厂商的容器镜像服务
- 官方脚本装完仍提示 permission denied → 没重新登录（docker 组要重新登录才生效）

---

## 第 2 步：本地打包 → 上传部署文件（含 app.jar）

**在干什么**：后端在**本地**（你的 Windows 机器）用 Maven 打成 Spring Boot 可执行 jar，
再把「jar + 几个部署文件」整体传到服务器。**服务器上不需要 Java 源码，也不用装 Maven**，
它只需要这些：

```
app.jar                               ← 本地打好的可执行 jar（镜像里真正跑的东西）
Dockerfile                            ← 怎么把 jar 装成镜像
docker-compose.yaml                   ← 四个服务的编排
.dockerignore                         ← 让 temp/、frontend-dist/ 等不进构建上下文
.env                                  ← 密钥/密码/域名
docker/entrypoint.sh, nginx.conf      ← 启动脚本、nginx 配置
src/main/resources/ssl_nginx/         ← HTTPS 证书（wlbc.top_bundle.crt + wlbc.top.key）
                                        nginx 是从宿主机挂载这个目录的，**不跟着传就起不来**
src/main/resources/sql/ai_code_db.sql ← MySQL 首次启动建表用（compose 挂载的文件）
```

**2.1 本地打包**

```powershell
cd D:\JAVA\ai-code-backend
mvn clean package -DskipTests     # 跳过测试：单测里有依赖真实模型/网络的用例（实测约 12 秒）
# 用 IDEA 也行：右侧 Maven 面板 → Lifecycle → package（记得跳过测试）
```

**2.2 确认产物是「可执行 fat jar」**（别省这步，很容易传错文件）

```powershell
Get-ChildItem target\*.jar* | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,2)}}
# 实测期望：
#   ai-code-backend-1.0.0.jar            ≈ 103 MB  ← 要的就是这个（Spring Boot 可执行 jar）
#   ai-code-backend-1.0.0.jar.original   ≈ 0.3 MB  ← 原始"瘦 jar"，传上去会启动失败
```

**2.3 打成部署包并上传**（Windows 10/11 自带 `tar`，一条命令且能带上 `.env` 这类隐藏文件）

```powershell
# jar 复制成 Dockerfile 要求的名字（放在项目根 = 构建上下文根）
Copy-Item target\ai-code-backend-1.0.0.jar app.jar -Force

# 打包"服务器需要的东西"：注意不含 src/main/java、不含 pom.xml
# src/main/resources/ssl_nginx 是 HTTPS 证书，nginx 从宿主机挂载它，必须一起打包
tar -czf deploy.tgz Dockerfile docker-compose.yaml .dockerignore .env docker app.jar src/main/resources/ssl_nginx src/main/resources/sql/ai_code_db.sql

# 上传到服务器（实测包大小约 94 MB，jar 本身 103 MB）
scp deploy.tgz <用户名>@<服务器IP>:/opt/
```

```bash
# 服务器上解包（Ubuntu 默认用户不是 root，/opt 属于 root，所以要先建目录并改属主）
sudo mkdir -p /opt/ai-code-backend && sudo chown -R "$USER":"$USER" /opt/ai-code-backend
cd /opt/ai-code-backend
tar -xzf /opt/deploy.tgz && rm -f /opt/deploy.tgz
```

**2.4 核对（30 秒，能挡掉后面 90% 的低级错误）**

```bash
ls -a
# 期望：.dockerignore  .env  Dockerfile  app.jar  docker  docker-compose.yaml  src
ls docker                        # entrypoint.sh  nginx.conf
ls src/main/resources/sql        # ai_code_db.sql（MySQL 首次建表用，别删）
ls src/main/resources/ssl_nginx  # wlbc.top_bundle.crt  wlbc.top.key（HTTPS 证书，缺了 nginx 起不来）
ls -lh app.jar                   # 约 103MB —— 只有几十 KB 说明传成了 .jar.original

# 更严格：确认清单里有 Spring Boot 启动类（unzip 没装就 apt install -y unzip）
unzip -p app.jar META-INF/MANIFEST.MF | grep -E "Main-Class|Start-Class"
#   期望 Main-Class: org.springframework.boot.loader.launch.JarLauncher

# 脚本换行检查（用 scp/WinSCP 传文本文件最容易踩的坑）
grep -c $'\r' docker/entrypoint.sh    # 期望 0；不是 0 就修：sed -i 's/\r$//' docker/entrypoint.sh
```

> **为什么 SQL 在 `src/` 下**：compose 的 mysql 服务把 `./src/main/resources/sql/ai_code_db.sql`
> 挂进容器做首次初始化，所以这个文件必须跟着部署包走（但它跟 Java 源码无关）。
>
> **如果更习惯用 git 管服务器上的部署文件**：也可以先在服务器 `git clone` 仓库，
> 再单独 `scp target\ai-code-backend-1.0.0.jar <用户名>@<服务器IP>:/opt/ai-code-backend/app.jar`。
> 好处是服务器上有完整源码（第 5 步的方式 B 就能用）；代价是多同步一份代码，且要自己保证 jar 是最新的。
>
> **如果 `/opt` 不想用**：放家目录也行 —— `mkdir -p ~/apps/ai-code-backend`，
> 之后把本文所有 `/opt/ai-code-backend` 换成 `~/apps/ai-code-backend`。

---

## 第 3 步：确认/修改 `.env`

**在干什么**：所有密钥、密码、域名都在这个文件里（它已被 `.gitignore` 忽略，不会进仓库，也不会被打进镜像）。
部署包里已经带了本地那份 `.env`，所以这步是**核对**；如果没带（想自己在服务器上写），就从模板拷一份：

```bash
cd /opt/ai-code-backend
ls -l .env      # 部署包里已经带了本地那一份（内含所有密钥），正常无需再拷
# 万一缺了：把本地的 .env 单独传上来 → scp .env <用户名>@<服务器IP>:/opt/ai-code-backend/
vi .env
```

**必须改/确认的项**：

| 变量 | 填什么 | 说明 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | 自定义强密码 | 容器内 root 密码，只在**首次**初始化时生效 |
| `MYSQL_PASSWORD` | 自定义强密码 | 应用连库用的密码，同样只在首次初始化生效 |
| `REDIS_PASSWORD` | 自定义强密码 | Redis 密码 |
| `CODE_DEPLOY_HOST` | `https://wlbc.top/dist` | 已填好；**必须容器内也能访问**（第 8 步会验证），且必须是 **https**（前端页面是 https，给 http 地址会被浏览器当混合内容拦掉） |
| `COS_*` | 腾讯云密钥 | 不填也能跑，但部署后没有封面图 |
| `AI_BASE_URL / AI_WORKSPACE_ID / AI_API_KEY` | 阿里云百炼 | 不填则代码生成接口不可用 |
| `NGINX_PORT` | `80` | http 入口，只做 301 跳转到 https |
| `NGINX_SSL_PORT` | `443` | https 入口，承载全部业务（443 要在云防火墙放行，见第 9 步） |
| `DOCKER_SUBNET` | `10.201.0.0/24` | 容器自定义网络 `ai-code-net` 的网段，**刻意避开 Docker 默认池 172.17~172.31**；部署前先用 `ip -4 addr show \| grep inet` 核对本机/VPC 网段，撞了就换成 `10.202.0.0/24` 之类 |
| `NPM_REGISTRY` | 已默认 `https://registry.npmmirror.com` | 用户工程构建时 `npm install` 用 |
| `JAVA_OPTS` | 已按 2 核 4G 调好 | 堆 1G、Chrome 池 2 |
| `DEPLOY_QUEUE_WORKERS` | `2` | 并发构建数 |

> **两个坑**：
> 1. 密码里如果含 `$`，compose 会当成变量插值 → 要么别用 `$`，要么写成 `$$`。
> 2. 数据库密码只在**第一次**初始化数据卷时写入 MySQL；之后改 `.env` 不会改数据库里已有的密码，会导致连不上（解决办法见第 6 步的"重置"）。

---

## 第 4 步：静态校验配置（不启动任何服务）

**在干什么**：启动前先把 compose 文件解析一遍，确认变量都填了、没有语法错。这一步不需要联网。

```bash
docker compose config -q && echo "配置 OK"
docker compose config | grep -E "CODE_DEPLOY_HOST|mem_limit|MYSQL_USER"
# 核对"实际生效"的端口（见下面的坑）
docker compose config | grep -A2 "published" | head -30
# 核对四个服务都挂在自定义网络 ai-code-net 上（漏挂服务 → 服务名解析不了 → 后端起不来）
# 期望 6：mysql/redis/backend/nginx 各 1 次 + 网络定义 2 次（ai-code-net: 与 name: ai-code-net）
docker compose config | grep -c "ai-code-net"
# 核对容器网络网段（应是你 .env 里 DOCKER_SUBNET 的值，默认 10.201.0.0/24）
docker compose config | grep -A6 "^networks:"
```

**预期**：打印 `配置 OK`，列出 `CODE_DEPLOY_HOST: https://wlbc.top/dist` 和各服务 `mem_limit`，
以及网络定义里的 `subnet: 10.201.0.0/24`。

> **坑：shell 里的同名环境变量会覆盖 `.env`**
> compose 的优先级是「**当前 shell 的环境变量 > `.env` 文件**」。如果这台机器的 profile / 环境里
> 已经导出过 `MYSQL_PORT`、`REDIS_PORT`、`COS_HOST`、`MYSQL_PASSWORD` 之类的变量，
> 那么 `.env` 里写的值**不会生效**（实测：环境里 `REDIS_PORT=14073` 时，Redis 就发布到 14073 而不是 `.env` 的 6379）。
> 排查方法：
> ```bash
> # 看哪些同名变量被 shell 覆盖了
> env | grep -E '^(MYSQL|REDIS|COS|AI|SERVER|NGINX|CODE|DEPLOY)_' 
> # 核对实际生效的端口
> docker compose config | grep -B1 -A1 published
> ```
> 想让 `.env` 说了算：`unset REDIS_PORT MYSQL_PORT COS_HOST` 后重新执行，
> 或每次都用干净环境：`env -u REDIS_PORT -u MYSQL_PORT docker compose up -d`。

**失败怎么办**：报 `请在 .env 中设置 XXX` → 说明 `.env` 里该项还是空值/模板值，回去补；报 YAML 缩进错 → 检查是否误编辑了 `docker-compose.yaml`。

---

## 第 5 步：构建后端镜像

**在干什么**：用第 2 步传上来的 `app.jar` 加上运行时环境（Chromium/ChromeDriver/中文字体 + JRE 21 + Node 22），
拼出可以直接跑的镜像。**服务器不参与编译**，所以这一步很快，也不会和 MySQL 抢内存。

> 两种方式共用同一个 `Dockerfile`，用 build target 区分（运行时层完全一样），
> 由 `.env` 的 `BACKEND_BUILD_TARGET` 决定：`runtime-from-jar`（方式 A，本手册采用）/ `runtime`（方式 B）。

### 方式 A（本手册采用）：用已上传的 `app.jar` 打包

服务器只装运行时（apt 装 chromium/字体 + 下 JRE）+ 拷 jar，**不跑 Maven**。

```bash
cd /opt/ai-code-backend
ls -lh app.jar                            # 先确认 jar 在位（约 103MB）
docker compose build backend
docker images | grep ai-code-backend      # 期望 ai-code-backend:1.0.0
```

### 方式 B（可选）：服务器内从源码编译

服务器上有完整源码（第 2 步选了 git clone 那种方式）、或想让 CI 一条命令出镜像时用：

```bash
# 把 .env 的 BACKEND_BUILD_TARGET 改成 runtime，或只在这次命令里临时指定
BACKEND_BUILD_TARGET=runtime docker compose build backend
```

代价：容器里要跑 Maven（下载依赖 + 编译），2 核机器约 5~10 分钟，构建阶段内存峰值约 1G。

### 两种方式的共同点与排错

**耗时**：方式 A 约 2~3 分钟（主要花在 apt 装 chromium/字体 + 下载 JRE）；方式 B 约 5~10 分钟。
**镜像大小**：约 1.5~2G。

| 报错 / 现象 | 原因与处理 |
| --- | --- |
| `COPY failed: ... stat app.jar: file does not exist` | 方式 A 但服务器上没有 `app.jar`（没传、传错目录、或名字不对）；回第 2 步 2.3/2.4 |
| 容器起来就退出，日志 `no main manifest attribute` | 传成了 `.jar.original`（瘦 jar，0.3MB）；回第 2 步 2.2/2.4 重传 |
| 卡在下载 JRE / TLS 超时 | `.env` 里打开 `JRE_URL` 换成清华镜像，再 build |
| apt 太慢 | 在 `Dockerfile` 的 apt 命令前把 Debian 源换成国内源 |
| 方式 B 报 `Killed` / 编译中途进程消失 | 内存不够：确认 swap 已生效（`free -h`），或直接改用方式 A |
| 想省磁盘 | `docker builder prune -f`（清构建缓存，不影响已生成的镜像） |

> 如果服务器拉不到 `node:22-bookworm-slim`（方式 B 还需要 `maven:3.9-eclipse-temurin-21`）：
> 可以在能联网的机器上 `docker compose build backend`，然后
> `docker save ai-code-backend:1.0.0 | gzip > app.tgz`，传到服务器 `gunzip -c app.tgz | docker load`。
> 注意 MySQL/Redis/nginx 三个基础镜像在服务器上仍需能拉取。

---

## 第 6 步：只启动 MySQL 和 Redis

**在干什么**：先把数据库起来，并**首次执行建表脚本** `src/main/resources/sql/ai_code_db.sql`（用户表、应用表、对话历史表 + 索引）。这一步做完再起后端，避免后端连不上库刷一堆报错。

```bash
docker compose up -d mysql redis
docker compose ps                       # 等 STATUS 变成 healthy（约 30~60 秒）
docker compose logs --tail=50 mysql     # 看到 "ready for connections" 即完成初始化
```

**验证库表真的建好了**（`sh -c` 里的变量由**容器内**的环境变量展开，不用在宿主机上导出密码）：

```bash
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ai_code_db -e "show tables;"'
```

**预期**：列出 `app`、`chat_history`、`user` 三张表。

**失败怎么办**：
- 日志里 SQL 报错（比如某条 `alter table` 失败）→ 建表脚本**不是幂等**的，只能清空重来：
  `docker compose down -v`（会删除数据卷！）然后回到本步重新 `up -d mysql redis`
- 改了 `.env` 里的数据库密码后连不上 → 同样是密码只在首次初始化生效，`down -v` 重建，或进库改密码

---

## 第 7 步：启动 backend

**在干什么**：起 Spring Boot 服务（容器内监听 8123；默认只映射到宿主机 `127.0.0.1:8123`，对外统一走 nginx 的 80），它会连 MySQL/Redis、初始化 AI 模型配置。

```bash
docker compose up -d backend
docker compose logs -f backend          # 看到 "Started AiCodeBackendApplication" 后 Ctrl+C 退出跟踪
```

**预期日志**：`Started AiCodeBackendApplication in xx seconds`；可能伴随模型可用性巡检的警告（配了 AI 密钥才有意义，可忽略）。

**验证健康检查**（容器自带 healthcheck，`start_period` 90 秒）：

```bash
docker compose ps                                   # backend 应为 healthy
curl -fsS http://127.0.0.1:8123/api/health           # {"code":0,...,"data":"ok"}
curl -s  http://127.0.0.1:8123/api/health/deploy-queue

# 确认服务确实接在自定义网络 ai-code-net 上（backend 就是靠它解析 mysql / redis 的）
docker network inspect ai-code-net --format '{{range .Containers}}{{.Name}} {{end}}'
# 期望看到：ai-code-mysql ai-code-redis ai-code-backend

# 确认网段就是你要的（默认 10.201.0.0/24，刻意避开 Docker 默认池 172.17~172.31）
docker network inspect ai-code-net --format '{{range .IPAM.Config}}subnet={{.Subnet}} gateway={{.Gateway}}{{end}}'
# 期望：subnet=10.201.0.0/24 gateway=10.201.0.1
```

**失败怎么办**：
- 日志刷 `Communications link failure` / `Access denied` → 数据库没起好或密码不一致，回第 6 步
- 日志刷 `UnknownHostException: mysql` / 连不上 `redis` → 服务没接在同一张网络里：
  `docker network inspect ai-code-net` 看容器列表，确认 `docker-compose.yaml` 里每个服务都有 `networks: [ai-code-net]`
- 日志刷 Redis 连接异常 → 确认 `docker compose ps` 里 redis 是 healthy、`.env` 的 `REDIS_PASSWORD` 与 compose 注入的一致
- 容器不断重启 → `docker compose logs --tail=200 backend` 看最后一段异常；内存不足会在 `docker inspect ai-code-backend --format '{{.State.OOMKilled}}'` 显示 `true`

---

## 第 8 步：启动 nginx 并验证路由

**在干什么**：nginx 是唯一对外入口，一次配好三条规则 —— `/` 给前端站点、`/api/...` 反代到后端、
`/dist/{deployKey}/` 提供用户部署的静态站点（前端产物到第 10 步才发布，所以这一步 `/` 先返回 404 是正常的）。
80 端口只做 301 跳转，业务全在 **443（HTTPS）** 上；证书来自 `src/main/resources/ssl_nginx/`（见第 9 步的说明）。

```bash
docker compose up -d nginx
docker compose ps
# 先确认容器里的证书与配置没问题（有问题这里就会报出来，不用等浏览器）
docker compose exec nginx nginx -t
```

**验证**（下面几条都要通；用 `--resolve` 指向本机，这样不依赖第 9 步的 DNS 解析）：

```bash
# ① http 必须跳 https：期望 301，且 Location 是 https://wlbc.top/api/health
curl -sI --resolve wlbc.top:80:127.0.0.1 http://wlbc.top/api/health | head -2

# ② https 的 API 路由：应返回 200（证书可信，不需要 -k）
curl -s -o /dev/null -w '%{http_code}\n' --resolve wlbc.top:443:127.0.0.1 https://wlbc.top/api/health

# ③ 健康探针（80 与 443 各一个，都应输出 ok）
curl -s http://127.0.0.1/nginx-health                                  # ok
curl -s --resolve wlbc.top:443:127.0.0.1 https://wlbc.top/nginx-health  # ok

# ④ 部署目录路由：还没有任何部署，返回 403/404 都算正常，关键是不能是 502/连不上
curl -s -o /dev/null -w '%{http_code}\n' --resolve wlbc.top:443:127.0.0.1 https://wlbc.top/dist/

# ⑤ 证书信息（确认用的是 wlbc.top 这张、且有效期没读到过期的旧文件）
echo | openssl s_client -connect 127.0.0.1:443 -servername wlbc.top 2>/dev/null \
  | openssl x509 -noout -subject -dates

# ⑥ 最关键的：后端容器内能不能访问到截图用的地址（域名回流）
docker compose exec backend curl -sI https://wlbc.top/dist/ | head -1
```

> 此时访问 `https://wlbc.top/`（或 `http://127.0.0.1/`）会是 **404**：根路径留给前端产物，而前端要到第 10 步才发布。
> 这不是故障，第 10 步发布完就正常了。

**第 ⑥ 条的两种结果**：
- 打印出 `HTTP/1.1 403`/`404`（或部署过之后的 `200`）→ ✅ 通，截图功能可用
- 报 `Couldn't connect to server` → 服务器上的 NAT 回流被禁或域名还没解析，先按下面兜底，再排查网络：
  ```bash
  # 临时改用宿主机地址，保证封面截图可用（改完执行 docker compose up -d backend）
  # 这里刻意用 http：nginx 的 80 端口对非规范 Host 保留了 /dist 的直连（不跳转），
  # 否则 host.docker.internal 会被跳到 https，而证书是按 wlbc.top 签发的，Chrome 会直接拒绝
  sed -i 's#^CODE_DEPLOY_HOST=.*#CODE_DEPLOY_HOST=http://host.docker.internal/dist#' .env
  docker compose up -d backend
  ```

---

## 第 9 步：域名解析 + 云防火墙 + HTTPS 证书 + 浏览器验收

**在干什么**：让 `https://wlbc.top` 真正指向这台服务器，并确认证书链没问题。

1. **DNS**：在域名解析处加 A 记录，`wlbc.top` → 服务器公网 IP（证书 SAN 里还有 `www.wlbc.top`，
   想同时用 www 访问就再加一条）。验证：
   ```bash
   dig +short wlbc.top        # 应输出你的公网 IP（没装 dig 就 apt install -y dnsutils）
   ```
2. **云防火墙/安全组**：放行入方向 **TCP 80 与 TCP 443**（443 承载业务，80 只做跳转）。
   - 腾讯云**轻量应用服务器**：控制台 → 该实例 → **防火墙** → 添加规则 `TCP:443`（80 通常已默认放行）
   - 腾讯云 CVM/其它云：在**安全组**里加同样的入方向规则
   - 只放行 443 也能用，但用户手敲 http:// 时会直接连不上（没有跳转）

   > **Ubuntu 24.04 上请保持 ufw 关闭**（默认就是关闭的，不用动它）：
   > Docker 发布端口时直接写 iptables/nftables 的 `DOCKER` 链，**会绕过 ufw 规则** ——
   > `ufw allow 443` 既不能真正放行、也拦不住已发布的容器端口，开了只会让网络排查变复杂。
   > 需要限制暴露面时，用编排里的绑定地址来做（见下表），比 ufw 可靠。

   | 端口 | 默认绑定 | 谁能访问 | 怎么收紧 |
   | --- | --- | --- | --- |
   | 443（nginx，HTTPS） | 所有网卡 | 公网 | **必须开放**（真正承载业务）；要换端口改 `.env` 的 `NGINX_SSL_PORT` |
   | 80（nginx，跳转） | 所有网卡 | 公网 | 建议开放（http 自动跳 https + 备用探活）；要换端口改 `.env` 的 `NGINX_PORT` |
   | 8123（后端） | `127.0.0.1` | 仅宿主机 | 已是安全默认；要外部直连才把 `SERVER_BIND` 改成 `0.0.0.0` |
   | `MYSQL_PORT` | `0.0.0.0` | 公网 | 用 Navicat 从本机连才需要；否则把 `MYSQL_BIND` 改成 `127.0.0.1` |
   | `REDIS_PORT` | `0.0.0.0` | 公网 | 同上，改 `REDIS_BIND` |

   ```bash
   # 改完绑定地址后重建容器生效
   docker compose up -d
   # 核对实际监听情况（应只看到你允许的绑定）
   sudo ss -lntp | grep -E ':(80|443|8123|3306|6379)\b'
   ```

3. **HTTPS 证书**：仓库里已经带好了腾讯云免费 DV 证书（TrustAsia 签发），不用自己生成：

   | 文件 | 作用 | nginx 里的位置 |
   | --- | --- | --- |
   | `src/main/resources/ssl_nginx/wlbc.top_bundle.crt` | 站点证书 + 中间证书（共 3 张，**顺序不能改**） | `ssl_certificate` |
   | `src/main/resources/ssl_nginx/wlbc.top.key` | 私钥（与上面证书配对） | `ssl_certificate_key` |
   | `src/main/resources/ssl_nginx/wlbc.top_bundle.pem` | 与 `.crt` 内容完全相同，仅备用 | 不用 |

   compose 已把该目录只读挂到容器内的 `/etc/nginx/ssl`，所以换证书**不用改配置、不用重建镜像**：

   ```bash
   # 换新证书后（覆盖上面两个文件名即可）：
   docker compose restart nginx
   # 确认真的换上了：subject 应为 CN=wlbc.top，notAfter 是新日期
   echo | openssl s_client -connect 127.0.0.1:443 -servername wlbc.top 2>/dev/null | openssl x509 -noout -subject -dates
   ```

   > ⚠️ **这是一个 90 天的免费证书，有效期 2026-10-10 ~ 2027-01-08**，到期前必须在腾讯云重新申请并替换，
   > 否则站点直接打不开。因为 nginx 开了 HSTS，证书过期后浏览器不会再给"继续访问"的入口，
   > **请提前一周换**。续期与回滚步骤见 [DOCKER.md](DOCKER.md) 的"HTTPS 与证书续期"。

4. **浏览器验收**：
   - 接口文档：`https://wlbc.top/api/doc.html`（默认 `admin` / `admin123`，**上线后请改掉**，见第 12 步）
   - 健康检查：`https://wlbc.top/api/health`
   - 地址栏应是 🔒（证书有效、无混合内容）；`curl -sI http://wlbc.top/ | head -1` 应返回 `301`

**注意**：80 与 443 端口都必须空闲。如果服务器上已有别的 nginx/apache 占用，先停掉它，或把 `.env` 的 `NGINX_PORT` / `NGINX_SSL_PORT` 改成别的端口（那样地址就带端口了）。Ubuntu 上查占用：`sudo ss -lntp | grep -E ':(80|443)\b'`。

---

## 第 10 步：打包前端并发布到 nginx

**在干什么**：前端是独立仓库 `ai-code-frontend`（Vue 3 + Vite 7 + TS）。nginx 已经把 `/` 留给了它：
`docker/nginx.conf` 里的 `location /` → 根目录 `/var/www/frontend` → 由 compose 从宿主机目录
`./frontend-dist` 只读挂载。本步就是：**构建出 `dist/` → 传到服务器的 `frontend-dist/` → 浏览器验收**。

前端的 `.env.production` 里三个地址都是相对路径，**不用改**：

| 前端变量 | 值 | 由 nginx 的哪条规则承接 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api` | `location /api/` 反代到 backend:8123 |
| `VITE_APP_PREVIEW_BASE_URL` | `/api/static` | 同上（生成产物的预览） |
| `VITE_APP_DEPLOY_BASE_URL` | `/dist` | `location /dist/` 提供用户部署的站点 |

> 三个地址都是相对路径，所以**前端必须和后端同域**（都在 `https://wlbc.top` 下）。
> 前端 `src/utils/request.ts` 里是 `withCredentials: true`，Session Cookie 一旦跨站就会被浏览器的
> SameSite 策略拦掉，表现为"登录成功但下一个请求就未登录"。所以不要把前端单独放到别的域名。

**10.1 先在服务器上建好挂载目录**

```bash
cd /opt/ai-code-backend
mkdir -p frontend-dist
```

**10.2 打包前端（两种方式，任选其一）**

方式 A：**本机打包再上传**（推荐，本机已有 Node 环境和 `node_modules`）

```powershell
cd D:\JAVA\ai-code-frontend
npm install        # 首次部署或依赖有变动时执行
npm run build      # = vue-tsc 类型检查 + vite build，产物在 ai-code-frontend\dist
```

```powershell
# 上传：注意是把 dist 里面的内容铺到 frontend-dist 下，不要再套一层 dist/
# <用户名> 用你登录服务器用的账号（Ubuntu 云服务器常见是 ubuntu；如果是 root 就写 root）
scp -r .\dist\* <用户名>@<服务器IP>:/opt/ai-code-backend/frontend-dist/
```

方式 B：**在服务器上用容器打包**（服务器上没装 Node，也不想装时；直接复用第 5 步建好的后端镜像，
它里面已经有 Node 22 + npm）

```bash
# 先把前端源码放到服务器，例如 /opt/ai-code-frontend
cd /opt/ai-code-frontend
docker run --rm --entrypoint sh --user "$(id -u):$(id -g)" -e HOME=/tmp -w /app \
  -v /opt/ai-code-frontend:/app \
  ai-code-backend:1.0.0 \
  -c "npm install --registry=https://registry.npmmirror.com && npm run build"

mkdir -p /opt/ai-code-backend/frontend-dist
cp -r /opt/ai-code-frontend/dist/* /opt/ai-code-backend/frontend-dist/
```

> 方式 B 用的是后端镜像里的 Node 22（Vite 7 要求 Node ≥ 20.19 / ≥ 22.12，满足）；
> `--user "$(id -u):$(id -g)"` 是为了让 `node_modules`、`dist` 归属当前用户，避免之后 `git pull` 时权限报错。

**10.3 生效与验证**：`frontend-dist` 是 bind mount，**换掉文件立即生效，nginx 不需要重启**

```bash
ls /opt/ai-code-backend/frontend-dist/                 # 应看到 index.html 和 assets/
curl -s -o /dev/null -w '%{http_code}\n' https://wlbc.top/        # 期望 200
curl -s -o /dev/null -w '%{http_code}\n' https://wlbc.top/xxx/yyy  # 深链接也期望 200（SPA 回退到 index.html）
```

浏览器打开 `https://wlbc.top/`，能看到前端首页（此时还没登录）。

**注意**：
- 如果 `docker compose up -d nginx` 时 `frontend-dist/` 还不存在，Docker 会把它建成 **root 所有**的空目录，
  之后用普通用户 scp 会 `Permission denied`。所以务必先做 10.1；万一已经出现，执行
  `sudo chown -R "$USER":"$USER" /opt/ai-code-backend/frontend-dist` 修一下。
- 同理，**别用 `sudo docker compose ...`**：sudo 跑出来的容器会以 root 创建挂载目录，
  后面普通用户上传前端、看日志都会遇到权限问题。第 1 步把用户加进 docker 组就是为了避免这件事。
- 前端路由是 history 模式，刷新 `/xxx/yyy` 这类深链接由 nginx 的 `try_files ... /index.html` 兜住。
- 如果还没发布前端就访问 `https://wlbc.top/`，会是 404 —— 根路径只有前端产物，这是正常的。

---

## 第 11 步：业务冒烟（全链路验证）

**在干什么**：走一遍完整业务，确认 AI 生成、Vue 构建、部署、截图四个重活都能跑通。

**11.1 注册第一个账号**（账号 4~20 位、密码 6~20 位，这是接口的校验规则）

```bash
curl -s -X POST https://wlbc.top/api/user/register \
  -H 'Content-Type: application/json' \
  -d '{"userAccount":"admin","userPassword":"Admin2026","checkPassword":"Admin2026"}'
```

**11.2 把它设为管理员**（后台管理功能需要 admin 角色）

```bash
# 从 .env 里取 root 密码，避免在宿主机上逐条 source（.env 里的 JAVA_OPTS 含空格，source 会报错）
DB_ROOT_PWD=$(grep -E '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2-)
docker compose exec mysql mysql -uroot -p"$DB_ROOT_PWD" ai_code_db \
  -e "update user set user_role='admin' where user_account='admin';"
```

**11.3 用前端界面走一遍**（后端日志同步观察）

| 操作 | 观察点 |
| --- | --- |
| 登录 | `https://wlbc.top/api/user/get/login` 返回当前用户 |
| 创建应用 + 输入需求，开始生成 | 后端日志出现模型调用；页面能看到流式输出（SSE） |
| 生成结束后的预览 | 预览地址是 `/api/static/{部署目录}/`，由 Spring 直接提供 |
| 点「部署」 | 日志出现 `[npm] ...`（这就是容器里在跑 npm install / build），随后 `Vue 项目构建成功`、`应用部署成功，appId: ..., 部署地址: https://wlbc.top/dist/xxxxxx/` |
| 打开部署地址 | 浏览器访问 `https://wlbc.top/dist/{deployKey}/` 页面正常 |
| 封面图 | 日志出现 `网页截图上传成功，cosUrl: ...`（截图失败不影响部署成功，但封面会空） |

**部署很慢是正常的**：2 核机器上一个 Vue 工程 `npm install` + `build` 通常 1~3 分钟；同时部署会排队，队列水位看 `https://wlbc.top/api/health/deploy-queue`。

---

## 第 12 步：收尾

1. **改掉默认密码**（接口文档）：`application-docker.yaml` 已经打进 jar 里了，服务器上改不了它 ——
   直接在 `.env` 里加两行 `KNIFE4J_USER=xxx` / `KNIFE4J_PASSWORD=xxx`（配置项支持环境变量覆盖），
   然后 `docker compose up -d backend`。
2. **开机自启**：compose 里各服务已 `restart: unless-stopped`，只要 `docker` 服务是 enabled（第 1 步已做）就会自启。验证：`sudo reboot` 后 `docker compose ps`。
3. **备份数据库**（建议加进 crontab）：
   ```bash
   docker compose exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" ai_code_db' > backup-$(date +%F).sql
   ```
4. **要备份的用户产物**在命名卷 `ai-code-backend_app-temp` 里（生成源码 + 部署产物），需要时用项目自己的镜像打包
   （不用额外拉 alpine；容器默认以非 root 的 `node` 用户运行，所以要 `--user root` 才能写入宿主机目录）：
   ```bash
   docker run --rm --user root --entrypoint tar \
     -v ai-code-backend_app-temp:/data -v "$PWD":/backup \
     ai-code-backend:1.0.0 czf /backup/app-temp-$(date +%F).tar.gz -C /data .
   ```
5. **看资源占用**：`docker stats --no-stream`（backend 常驻约 1~1.5G，部署时会冲高）。

---

## 第 13 步：以后每次升级

**升级后端**（jar 模式：本地重新打包 → 上传 → 服务器重建镜像）

```powershell
# ① 本地（Windows）
cd D:\JAVA\ai-code-backend
git pull
mvn clean package -DskipTests
scp target\ai-code-backend-1.0.0.jar <用户名>@<服务器IP>:/opt/ai-code-backend/app.jar
```

```bash
# ② 服务器
cd /opt/ai-code-backend
docker compose build backend              # 只重装运行时 + 换 jar，约 1~2 分钟
docker compose up -d backend              # 用新镜像重建容器
docker compose logs -f --tail=100 backend # 确认 Started AiCodeBackendApplication
```

> **别忘了上传新 jar** —— 只 `up -d` 不会让代码生效（镜像里的 jar 没变）。
>
> 如果这次还改了 **部署文件**（`Dockerfile` / `docker-compose.yaml` / `docker/nginx.conf` / 建表 SQL），
> 把第 2 步 2.3 的部署包重新打一次传上去（`tar` + `scp` + 解包覆盖），再执行上面第 ② 步；
> 只改了 nginx 配置的话，重建 nginx 即可：`docker compose up -d nginx`。
> 用方式 B（服务器上有源码）时则是：`git pull` 后 `docker compose build backend && docker compose up -d backend`。

**升级前端**（改了 `ai-code-frontend` 时）：

```powershell
cd D:\JAVA\ai-code-frontend
git pull
npm install        # 只有依赖变动时才需要
npm run build
scp -r .\dist\* <用户名>@<服务器IP>:/opt/ai-code-backend/frontend-dist/
```

```bash
# 服务器上确认新产物已就位（对比 index.html 的修改时间）
ls -l /opt/ai-code-backend/frontend-dist/index.html /opt/ai-code-backend/frontend-dist/assets | head -5
```

- 前端更新**不需要重启任何容器**（`frontend-dist` 是 bind mount，nginx 每次请求都从磁盘读）；
  浏览器 `Ctrl+F5` 强刷一次即可（`index.html` 已配 `no-cache`，普通刷新也能拿到新版本）。
- 只有改了 `docker/nginx.conf` 时才需要 `docker compose up -d nginx`（nginx 的配置是单文件挂载，重建容器最稳）。

**重要**：改了 `.env` 之后必须用 `docker compose up -d`（重建容器才会重新注入环境变量），`docker compose restart` **不会**重新读取 `.env`。

---

## 第 14 步：出问题时的速查表

| 现象 | 先看哪里 | 大概率原因 |
| --- | --- | --- |
| 浏览器打不开 `https://wlbc.top` | `dig +short wlbc.top`；`docker compose ps`；`sudo ss -lntp \| grep -E ':(80\|443)\b'` | DNS 没生效 / 云防火墙没放行 443（80 只影响 http 跳转）/ nginx 没起 |
| 浏览器提示证书无效 / 过期 | 见 [DOCKER.md](DOCKER.md) 第 5 节 | 证书文件缺失、私钥不配对、或 90 天免费证书过期（有效期见第 5 节） |
| `http://wlbc.top` 不跳转或打不开 | `curl -sI http://wlbc.top/ \| head -2` | 80 没放行/被占用；`.env` 的 `NGINX_PORT`；正常应返回 301 到 https |
| 打开 `https://wlbc.top/` 是 404 | `ls /opt/ai-code-backend/frontend-dist/` | 前端还没发布（第 10 步），或 `dist` 多套了一层目录（应为 `frontend-dist/index.html`） |
| 前端页面能打开，但接口全 404 / 跨域报错 | 浏览器 F12 → Network 看请求地址 | 请求没打到同域 `/api`；正确做法见第 10 步的 `VITE_API_BASE_URL=/api` |
| 登录提示成功，下一个请求却变未登录 | F12 → Application → Cookies 里有没有 `SESSION` | Cookie 跨站被 SameSite 拦掉：前端必须与后端同域，不要分两个域名 |
| 发版后前端还是旧页面 | `frontend-dist/assets/` 里的文件名有没有变 | 浏览器缓存（`index.html` 已 no-cache，强刷一次即可） |
| `502 Bad Gateway` 访问 `/api/...` | `docker compose logs backend` | 后端没起来或崩了 |
| 容器起来就退出，日志 `no main manifest attribute` | `ls -lh app.jar` | 传成了 `.jar.original`（瘦 jar，0.3MB）；应传 103MB 那个 fat jar（第 2 步 2.2/2.4） |
| `docker compose build backend` 报 `stat app.jar: file does not exist` | `ls -l /opt/ai-code-backend/app.jar` | jar 模式（`BACKEND_BUILD_TARGET=runtime-from-jar`）但没上传 jar；或改用 `runtime` 让服务器自己编译 |
| 改了代码、镜像也重建了，行为却没变 | `ls -l app.jar` 的修改时间 | 忘了在本地重新 `mvn package` 并 scp 覆盖 `app.jar`（只 `up -d` 不会换代码） |
| 后端起不来（连不上库） | `docker compose logs mysql` | 库没初始化完；或改过 `.env` 密码但数据卷还是旧的 |
| 启动时出现 `a network with name ai-code-net exists but was not created by compose` | `docker network inspect ai-code-net` | 只是**警告**（compose 会复用它继续启动）；想消掉：确认没别的容器在用后 `docker network rm ai-code-net`，或把它声明成 `external: true` |
| 日志刷 `UnknownHostException: mysql` / `redis` | `docker network inspect ai-code-net` | 服务没接在同一张网络：检查 `docker-compose.yaml` 里每个服务都有 `networks: [ai-code-net]` |
| 容器连公网/内网**超时**（AI 接口不通、TLS 握手卡住、`npm install` 挂起），但 `ping` 网关正常 | `ip route \| grep -v docker` 对比 `docker network inspect ai-code-net --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}'` | 网段与宿主机 VPC 撞车：改 `.env` 的 `DOCKER_SUBNET`（如 `10.202.0.0/24`）后 `docker compose up -d`（compose 会自动重建网络与容器） |
| 部署报 `npm install 失败` | `docker compose logs backend \| grep npm` | registry 不通（换 `NPM_REGISTRY`）/ 内存不足 |
| 部署报 `ERR_MODULE_NOT_FOUND: … node_modules/dist/node/cli.js … imported from …/.bin/vite` | `docker compose exec backend ls -l /app/temp/code_output/` | **旧版本 bug**：构建暂存目录把上一次的 `node_modules` 整份复制过去，Linux 上 npm 的 `.bin/*` 是符号链接，复制时被实体化成真实文件，里面的相对导入就错位了。已在代码里修复（复制时跳过 `node_modules` / `dist`）—— 本地重新 `mvn package` 并上传新 `app.jar` 后重建镜像即可；`code_output` 里残留的 `node_modules` 无需手工清理 |
| 部署成功但封面为空 | `docker compose logs backend \| grep -i -E "chrom\|截图"` | 容器内访问不到 `wlbc.top`（第 8 步第 ④ 条）；或 COS 密钥不对 |
| 截图里中文是方块 | — | 镜像里的 `fonts-noto-cjk` 被删了，别动 Dockerfile 那一行 |
| 容器反复重启 | `docker inspect ai-code-backend --format '{{.State.OOMKilled}}'` | 内存不够：先把 `DEPLOY_QUEUE_WORKERS` 降到 1，再把 `-Xmx1024m` 降到 `896m` |
| 部署排队很久 | `https://wlbc.top/api/health/deploy-queue` | 2 核机器正常现象；`workers` 不要超过 2 |
| 生成的站点页面白屏 | 访问地址是否形如 `/dist/{key}/` 且**结尾带斜杠** | 产物是相对路径（`base: './'`），少了斜杠资源就找不到 |

---

## 附加：把**已经在跑**的站点从 http 原地升级到 https

上面第 1~14 步是全新部署。如果站点已经在 http 上跑着（本文的目标场景），按下面 6 步升级即可，
**不用重建后端镜像、不用重新打包前端**（前端三个地址都是相对路径，会跟着页面协议走）。
换 https 不需要额外备案，域名已备案即可。

```bash
# ---------- 本地（Windows）：把新文件传上去 ----------
cd D:\JAVA\ai-code-backend
scp docker\nginx.conf          <用户>@<服务器IP>:/opt/ai-code-backend/docker/
scp docker-compose.yaml        <用户>@<服务器IP>:/opt/ai-code-backend/
# 证书目录：nginx 是从宿主机挂载它的，服务器上没有这个目录 nginx 直接起不来
ssh <用户>@<服务器IP> "mkdir -p /opt/ai-code-backend/src/main/resources"
scp -r src\main\resources\ssl_nginx <用户>@<服务器IP>:/opt/ai-code-backend/src/main/resources/
```

```bash
# ---------- 服务器：改 .env ----------
cd /opt/ai-code-backend
sed -i 's#^CODE_DEPLOY_HOST=.*#CODE_DEPLOY_HOST=https://wlbc.top/dist#' .env
grep -q '^NGINX_SSL_PORT=' .env || echo 'NGINX_SSL_PORT=443' >> .env
grep -E '^(CODE_DEPLOY_HOST|NGINX_PORT|NGINX_SSL_PORT)=' .env
# 期望：CODE_DEPLOY_HOST=https://wlbc.top/dist / NGINX_PORT=80 / NGINX_SSL_PORT=443

# ---------- 服务器：先确认证书文件在 ----------
ls -l src/main/resources/ssl_nginx/    # wlbc.top_bundle.crt + wlbc.top.key 都要在

# ---------- 云控制台：放行 443（这一步不做，外网验证一定超时）----------
# 腾讯云轻量应用服务器：控制台 → 该实例 → 防火墙 → 添加规则 TCP:443
```

```bash
# ---------- 服务器：起 nginx（先校验配置，配置错会在这里直接报出来）----------
docker compose up -d nginx
docker compose exec nginx nginx -t
docker compose ps

# ---------- 服务器：backend 必须重建（CODE_DEPLOY_HOST 是环境变量注入的）----------
# 注意：restart 不会重读 .env，必须 up -d
docker compose up -d backend

# ---------- 验收 ----------
curl -sI http://wlbc.top/ | head -2                                      # 期望 301 → https://wlbc.top/
curl -s -o /dev/null -w '%{http_code}\n' https://wlbc.top/api/health      # 期望 200
curl -s https://wlbc.top/nginx-health                                    # ok
docker compose exec backend curl -sI https://wlbc.top/dist/ | head -1     # 截图用的地址（403/404 也算通）
echo | openssl s_client -connect 127.0.0.1:443 -servername wlbc.top 2>/dev/null | openssl x509 -noout -subject -dates
```

**升级后的预期与注意事项**：

- 浏览器第一次访问 `http://wlbc.top` 会拿到 301；被浏览器记过 http 的话 `Ctrl+F5` 强刷一次。
- 已发出的部署链接不用改：同域名，`/dist/{key}/` 依旧可访问；页面里的封面/部署地址由
  `CODE_DEPLOY_HOST` 生成，改完就是 https。
- 前端不用重新构建（`VITE_API_BASE_URL=/api` 是相对路径，自动跟随页面协议）。
- **回滚**：`.env` 里把 `CODE_DEPLOY_HOST` 改回 `http://wlbc.top/dist`，再用旧版 `docker/nginx.conf`
  与 `docker-compose.yaml` 覆盖回去，`docker compose up -d` 即可（证书文件留着不影响）。
- 证书 **2027-01-08 到期**（90 天免费证书），续期与排错见 [DOCKER.md](DOCKER.md) 第 5 节。

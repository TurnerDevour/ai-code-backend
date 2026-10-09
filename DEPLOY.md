# 部署步骤详解

**目标环境**：Ubuntu Server 24.04 LTS 64bit（x86_64）· 2 核 4G · 域名 `http://wlbc.top`
（跑在容器里的系统是 Debian 12，与宿主发行版无关；本文按 Ubuntu 24.04 写命令）

> 本文是**按顺序照抄即可**的操作手册；每一步都说明「在干什么 / 怎么验证 / 失败了怎么办」。
> 设计说明与依赖分析见 [DOCKER.md](DOCKER.md)。

## 步骤总览

| # | 做什么 | 耗时 | 完成后状态 |
| --- | --- | --- | --- |
| 1 | 服务器准备：装 Docker + 免 sudo + 镜像加速 + swap | 5 分钟 | 服务器具备部署条件 |
| 2 | 把项目代码放到服务器 | 1 分钟 | `/opt/ai-code-backend` 有代码 |
| 3 | 写 `.env`（密钥/密码/域名） | 5 分钟 | 配置就绪 |
| 4 | 静态校验配置 | 10 秒 | 配置无语法错 |
| 5 | 构建后端镜像 | 5~10 分钟 | 有 `ai-code-backend:1.0.0` 镜像 |
| 6 | 只启动 MySQL + Redis | 1~2 分钟 | 库表已建好、健康 |
| 7 | 启动 backend | 1~2 分钟 | 健康检查通过 |
| 8 | 启动 nginx 并验证路由 | 10 秒 | `/api` 与 `/dist` 都通 |
| 9 | 域名解析 + 云安全组放行 | 5 分钟 | 浏览器能打开 |
| 10 | **打包前端并发布到 nginx** | 3~5 分钟 | `http://wlbc.top/` 打开是前端页面 |
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
uname -m                          # 期望 x86_64（本文的 JRE 包与 chromedriver 缓存路径都按 x64 写）
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
   这台机器的门禁统一用**云安全组**（见第 9 步）。
3. Ubuntu 24.04 默认就是 **cgroup v2**，compose 里的 `mem_limit` 直接生效，不用改 grub。

**失败怎么办**：
- `docker compose version` 报错 → `sudo apt update && sudo apt install -y docker-compose-v2`（noble 里的包名）
- 拉不到镜像（`docker pull hello-world` 超时）→ 加速器地址不可用，换一个，或用云厂商的容器镜像服务
- 官方脚本装完仍提示 permission denied → 没重新登录（docker 组要重新登录才生效）

---

## 第 2 步：把代码放到服务器

**在干什么**：把后端仓库放到服务器的一个固定目录（本文统一用 `/opt/ai-code-backend`），后续所有命令都在这里执行。

> Ubuntu 云服务器的默认登录用户通常不是 root（比如 `ubuntu`），`/opt` 本身属于 root，
> 所以**不能**只 `sudo mkdir -p /opt` 就直接 clone（会在最后一步 Permission denied）。

```bash
# 方式一（本文采用）：放 /opt，先把目录建好并改属主
sudo mkdir -p /opt/ai-code-backend
sudo chown -R "$USER":"$USER" /opt/ai-code-backend
git clone <你的仓库地址> /opt/ai-code-backend
cd /opt/ai-code-backend

# 方式二：放自己家目录，完全不需要 sudo
#   mkdir -p ~/apps && cd ~/apps && git clone <仓库地址> ai-code-backend
#   之后把本文所有 /opt/ai-code-backend 替换成 ~/apps/ai-code-backend
```

**如果是用 scp/WinSCP 上传（不是 git）**，一定要检查脚本没有被换成 Windows 换行（CRLF 会让容器启动直接失败）：

```bash
grep -c $'\r' docker/entrypoint.sh    # 期望输出 0
# 如果输出大于 0，修一下：
sed -i 's/\r$//' docker/entrypoint.sh
```

**验证**：`ls` 能看到 `Dockerfile`、`docker-compose.yml`、`docker/`、`src/`、`.env.example`。

---

## 第 3 步：写 `.env`

**在干什么**：所有密钥、密码、域名都在这个文件里（它已被 `.gitignore` 忽略，不会进仓库，也不会被打进镜像）。

```bash
cp .env.example .env
vi .env
```

**必须改/确认的项**：

| 变量 | 填什么 | 说明 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | 自定义强密码 | 容器内 root 密码，只在**首次**初始化时生效 |
| `MYSQL_PASSWORD` | 自定义强密码 | 应用连库用的密码，同样只在首次初始化生效 |
| `REDIS_PASSWORD` | 自定义强密码 | Redis 密码 |
| `CODE_DEPLOY_HOST` | `http://wlbc.top/dist` | 已填好；**必须容器内也能访问**（第 8 步会验证） |
| `COS_*` | 腾讯云密钥 | 不填也能跑，但部署后没有封面图 |
| `AI_BASE_URL / AI_WORKSPACE_ID / AI_API_KEY` | 阿里云百炼 | 不填则代码生成接口不可用 |
| `NGINX_PORT` | `80` | wlbc.top 不带端口访问就是 80 |
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
```

**预期**：打印 `配置 OK`，并列出 `CODE_DEPLOY_HOST: http://wlbc.top/dist` 和各服务 `mem_limit`。

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

**失败怎么办**：报 `请在 .env 中设置 XXX` → 说明 `.env` 里该项还是空值/模板值，回去补；报 YAML 缩进错 → 检查是否误编辑了 `docker-compose.yml`。

---

## 第 5 步：构建后端镜像

**在干什么**：容器里跑 Maven 编译打包（跳过测试）→ 装 Chromium/ChromeDriver/中文字体 → 装 JRE 21 → 拼出运行镜像。**这一步最吃资源，务必单独做**（此时 MySQL 还没起，内存全给它）。

```bash
docker compose build backend
```

**预期**：最后出现 `naming to docker.io/library/ai-code-backend:1.0.0`，`docker images | grep ai-code` 能看到镜像（约 1.5~2G）。

**耗时**：2 核机器大约 5~10 分钟（Maven 下载依赖 + apt 装 chromium + 下 JRE）。

**失败怎么办**：
- 卡在下载 JRE / 报 TLS 超时 → 打开 `.env` 里的 `JRE_URL` 换成清华镜像（文件末尾有现成的一行），再重新 build
- apt 太慢 → 在 `Dockerfile` 的 apt 命令前把 Debian 源换成国内源
- 报 `Killed` / 编译中途进程消失 → 内存不够，确认第 1 步的 swap 已生效（`free -h`），必要时 `sudo swapoff -a && sudo swapon -a`
- 构建完想省磁盘：`docker builder prune -f`（清构建缓存，不影响已生成的镜像）

> 如果服务器完全拉不到 `node:22-bookworm-slim` / `maven:3.9-eclipse-temurin-21`：可以在能联网的机器上 `docker compose build backend`，然后
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
```

**失败怎么办**：
- 日志刷 `Communications link failure` / `Access denied` → 数据库没起好或密码不一致，回第 6 步
- 日志刷 Redis 连接异常 → 确认 `docker compose ps` 里 redis 是 healthy、`.env` 的 `REDIS_PASSWORD` 与 compose 注入的一致
- 容器不断重启 → `docker compose logs --tail=200 backend` 看最后一段异常；内存不足会在 `docker inspect ai-code-backend --format '{{.State.OOMKilled}}'` 显示 `true`

---

## 第 8 步：启动 nginx 并验证路由

**在干什么**：nginx 是唯一对外入口，一次配好三条规则 —— `/` 给前端站点、`/api/...` 反代到后端、
`/dist/{deployKey}/` 提供用户部署的静态站点（前端产物到第 10 步才发布，所以这一步 `/` 先返回 404 是正常的）。

```bash
docker compose up -d nginx
docker compose ps
```

**验证**（三条命令都要通）：

```bash
# ① API 路由：应返回 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/api/health

# ② 健康探针
curl -s http://127.0.0.1/nginx-health          # ok

# ③ 部署目录路由：还没有任何部署，返回 403/404 都算正常，关键是不能是 502/连不上
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/dist/

# ④ 最关键的：后端容器内能不能访问到截图用的地址（域名回流）
docker compose exec backend curl -sI http://wlbc.top/dist/ | head -1
```

> 此时访问 `http://127.0.0.1/` 会是 **404**：根路径留给前端产物，而前端要到第 10 步才发布。
> 这不是故障，第 10 步发布完就正常了。

**第 ④ 条的两种结果**：
- 打印出 `HTTP/1.1 301` 或 `403`/`404` → ✅ 通，截图功能可用
- 报 `Couldn't connect to server` → 服务器上的 NAT 回流被禁或域名还没解析，先按下面兜底，再排查网络：
  ```bash
  # 临时改用宿主机地址，保证封面截图可用（改完执行 docker compose up -d backend）
  sed -i 's#^CODE_DEPLOY_HOST=.*#CODE_DEPLOY_HOST=http://host.docker.internal/dist#' .env
  docker compose up -d backend
  ```

---

## 第 9 步：域名解析 + 云安全组 + 浏览器验收

**在干什么**：让 `http://wlbc.top` 真正指向这台服务器。

1. **DNS**：在域名解析处加 A 记录，`wlbc.top` → 服务器公网 IP。验证：
   ```bash
   dig +short wlbc.top        # 应输出你的公网 IP（没装 dig 就 apt install -y dnsutils）
   ```
2. **云安全组**：在云控制台的**安全组**里放行入方向 **TCP 80**（这是唯一必须放行的端口，也是真正的门禁）。

   > **Ubuntu 24.04 上请保持 ufw 关闭**（默认就是关闭的，不用动它）：
   > Docker 发布端口时直接写 iptables/nftables 的 `DOCKER` 链，**会绕过 ufw 规则** ——
   > `ufw allow 80` 既不能真正放行、也拦不住已发布的容器端口，开了只会让网络排查变复杂。
   > 需要限制暴露面时，用编排里的绑定地址来做（见下表），比 ufw 可靠。

   | 端口 | 默认绑定 | 谁能访问 | 怎么收紧 |
   | --- | --- | --- | --- |
   | 80（nginx） | 所有网卡 | 公网 | 必须开放；若要换端口改 `.env` 的 `NGINX_PORT` |
   | 8123（后端） | `127.0.0.1` | 仅宿主机 | 已是安全默认；要外部直连才把 `SERVER_BIND` 改成 `0.0.0.0` |
   | `MYSQL_PORT` | `0.0.0.0` | 公网 | 用 Navicat 从本机连才需要；否则把 `MYSQL_BIND` 改成 `127.0.0.1` |
   | `REDIS_PORT` | `0.0.0.0` | 公网 | 同上，改 `REDIS_BIND` |

   ```bash
   # 改完绑定地址后重建容器生效
   docker compose up -d
   # 核对实际监听情况（应只看到你允许的绑定）
   sudo ss -lntp | grep -E ':(80|8123|3306|6379)\b'
   ```

3. **浏览器验收**：
   - 接口文档：`http://wlbc.top/api/doc.html`（默认 `admin` / `admin123`，**上线后请改掉**，见第 12 步）
   - 健康检查：`http://wlbc.top/api/health`

**注意**：80 端口必须空闲。如果服务器上已有别的 nginx/apache 占用 80，先停掉它，或把 `.env` 的 `NGINX_PORT` 改成别的端口（那样地址就带端口了）。Ubuntu 上查占用：`sudo ss -lntp | grep :80`。

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

> 三个地址都是相对路径，所以**前端必须和后端同域**（都在 `http://wlbc.top` 下）。
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
curl -s -o /dev/null -w '%{http_code}\n' http://wlbc.top/        # 期望 200
curl -s -o /dev/null -w '%{http_code}\n' http://wlbc.top/xxx/yyy  # 深链接也期望 200（SPA 回退到 index.html）
```

浏览器打开 `http://wlbc.top/`，能看到前端首页（此时还没登录）。

**注意**：
- 如果 `docker compose up -d nginx` 时 `frontend-dist/` 还不存在，Docker 会把它建成 **root 所有**的空目录，
  之后用普通用户 scp 会 `Permission denied`。所以务必先做 10.1；万一已经出现，执行
  `sudo chown -R "$USER":"$USER" /opt/ai-code-backend/frontend-dist` 修一下。
- 同理，**别用 `sudo docker compose ...`**：sudo 跑出来的容器会以 root 创建挂载目录，
  后面普通用户上传前端、看日志都会遇到权限问题。第 1 步把用户加进 docker 组就是为了避免这件事。
- 前端路由是 history 模式，刷新 `/xxx/yyy` 这类深链接由 nginx 的 `try_files ... /index.html` 兜住。
- 如果还没发布前端就访问 `http://wlbc.top/`，会是 404 —— 根路径只有前端产物，这是正常的。

---

## 第 11 步：业务冒烟（全链路验证）

**在干什么**：走一遍完整业务，确认 AI 生成、Vue 构建、部署、截图四个重活都能跑通。

**11.1 注册第一个账号**（账号 4~20 位、密码 6~20 位，这是接口的校验规则）

```bash
curl -s -X POST http://wlbc.top/api/user/register \
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
| 登录 | `http://wlbc.top/api/user/get/login` 返回当前用户 |
| 创建应用 + 输入需求，开始生成 | 后端日志出现模型调用；页面能看到流式输出（SSE） |
| 生成结束后的预览 | 预览地址是 `/api/static/{部署目录}/`，由 Spring 直接提供 |
| 点「部署」 | 日志出现 `[npm] ...`（这就是容器里在跑 npm install / build），随后 `Vue 项目构建成功`、`应用部署成功，appId: ..., 部署地址: http://wlbc.top/dist/xxxxxx/` |
| 打开部署地址 | 浏览器访问 `http://wlbc.top/dist/{deployKey}/` 页面正常 |
| 封面图 | 日志出现 `网页截图上传成功，cosUrl: ...`（截图失败不影响部署成功，但封面会空） |

**部署很慢是正常的**：2 核机器上一个 Vue 工程 `npm install` + `build` 通常 1~3 分钟；同时部署会排队，队列水位看 `http://wlbc.top/api/health/deploy-queue`。

---

## 第 12 步：收尾

1. **改掉默认密码**（接口文档）：编辑 `application-docker.yaml` 里的 `KNIFE4J_PASSWORD`，或直接在 `.env` 加 `KNIFE4J_USER` / `KNIFE4J_PASSWORD`，然后 `docker compose up -d backend`。
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

**升级后端**：

```bash
cd /opt/ai-code-backend
git pull                                  # 或重新上传代码
docker compose build backend              # 重新编译镜像
docker compose up -d backend              # 用新镜像重建容器
docker compose logs -f --tail=100 backend # 确认启动成功
```

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
| 浏览器打不开 `http://wlbc.top` | `dig +short wlbc.top`；`docker compose ps` | DNS 没生效 / 安全组没放行 80 / nginx 没起 |
| 打开 `http://wlbc.top/` 是 404 | `ls /opt/ai-code-backend/frontend-dist/` | 前端还没发布（第 10 步），或 `dist` 多套了一层目录（应为 `frontend-dist/index.html`） |
| 前端页面能打开，但接口全 404 / 跨域报错 | 浏览器 F12 → Network 看请求地址 | 请求没打到同域 `/api`；正确做法见第 10 步的 `VITE_API_BASE_URL=/api` |
| 登录提示成功，下一个请求却变未登录 | F12 → Application → Cookies 里有没有 `SESSION` | Cookie 跨站被 SameSite 拦掉：前端必须与后端同域，不要分两个域名 |
| 发版后前端还是旧页面 | `frontend-dist/assets/` 里的文件名有没有变 | 浏览器缓存（`index.html` 已 no-cache，强刷一次即可） |
| `502 Bad Gateway` 访问 `/api/...` | `docker compose logs backend` | 后端没起来或崩了 |
| 后端起不来（连不上库） | `docker compose logs mysql` | 库没初始化完；或改过 `.env` 密码但数据卷还是旧的 |
| 部署报 `npm install 失败` | `docker compose logs backend \| grep npm` | registry 不通（换 `NPM_REGISTRY`）/ 内存不足 |
| 部署成功但封面为空 | `docker compose logs backend \| grep -i -E "chrom\|截图"` | 容器内访问不到 `wlbc.top`（第 8 步第 ④ 条）；或 COS 密钥不对 |
| 截图里中文是方块 | — | 镜像里的 `fonts-noto-cjk` 被删了，别动 Dockerfile 那一行 |
| 容器反复重启 | `docker inspect ai-code-backend --format '{{.State.OOMKilled}}'` | 内存不够：先把 `DEPLOY_QUEUE_WORKERS` 降到 1，再把 `-Xmx1024m` 降到 `896m` |
| 部署排队很久 | `http://wlbc.top/api/health/deploy-queue` | 2 核机器正常现象；`workers` 不要超过 2 |
| 生成的站点页面白屏 | 访问地址是否形如 `/dist/{key}/` 且**结尾带斜杠** | 产物是相对路径（`base: './'`），少了斜杠资源就找不到 |

# 后端部署指南

> 让手机在任何网络都能访问后端。三个方案按推荐顺序排列。

## 前提：你手上有什么

| 你看过的文档 | 用途 |
|-------------|------|
| `backend-architecture.md` | 后端架构、API 端点、数据库设计 |
| `auth-sync.md` | 认证流程、前后端对接 |
| `nginx-https-plan.md` | Nginx + HTTPS 配置细节 |
| `ci-cd.md` | CI/CD 自动部署 |

---

## 方案 A：ngrok（0 元，1 分钟，开发用）

把本地 8080 端口暴露到公网，不部署任何东西。

```bash
# 1. 安装 ngrok
# 下载：https://ngrok.com/download
# 或 winget install ngrok

# 2. 启动隧道
ngrok http 8080

# 输出：
# Forwarding  https://abc123.ngrok-free.app -> http://localhost:8080
```

然后改 `.env`：
```
EXPO_PUBLIC_API_URL=https://abc123.ngrok-free.app/api/v1
```

重启 Expo 开发服务器即可。

| 优点 | 缺点 |
|------|------|
| 免费、不用部署 | 重启 URL 会变 |
| 立刻能用 | 免费版有速率限制 |
| 本地调试方便 | 电脑关机就断 |

**适合**：开发阶段让手机测试登录/注册/同步。

---

## 方案 B：Railway PaaS（$5/月，10 分钟）

代码推到 GitHub，Railway 自动构建部署。不需要管理服务器。

### B.1 注册 + 连接

1. 打开 [railway.com](https://railway.com) → **Sign in with GitHub**
2. **New Project** → **Deploy from GitHub** → 选 `fanwenjing729/CodeCard`
3. Railway 自动识别 `backend/pom.xml`，配置 Maven + Java 21 构建

### B.2 加 PostgreSQL

1. 项目里点 **+ New** → **Database** → **PostgreSQL**
2. Railway 自动生成连接信息

### B.3 设环境变量

在后端 Service → **Variables**：

| 变量 | 值 |
|------|-----|
| `JWT_SECRET` | `openssl rand -base64 64` 生成的随机串 |
| `SMTP_HOST` | `smtp.resend.com` |
| `SMTP_PORT` | `587` |
| `SMTP_USER` | 你的邮件服务账号 |
| `SMTP_PASS` | 你的邮件服务密码 |

> DB 连接 Railway 自动注入，不用手动配。

### B.4 部署

Push 代码到 GitHub 即自动部署。或 Railway 里手动点 **Deploy**。

成功后 Railway 给一个 URL：`https://codecard.up.railway.app`

### B.5 前端连上

```bash
# .env
EXPO_PUBLIC_API_URL=https://codecard.up.railway.app/api/v1
```

### B.6 CORS 说明

APK 发 `fetch()` 不走浏览器 CORS，不需要改 `CorsConfig.java`。

### 成本

| 项 | 费用 |
|----|------|
| Railway 后端 | $5/月 免费额度，不超额 = ¥0 |
| Railway PostgreSQL | 同上 |
| 超出免费额度 | $5/月起 ≈ ¥36/月 |

---

## 方案 C：阿里云学生机（¥9.9/月，30 分钟）

国内访问最快。贵州师大一般有教育网。

### C.1 买服务器

阿里云搜索"学生优惠"或"云翼计划"：

| 配置 | 价格 |
|------|------|
| 轻量应用服务器 1C2G | ~¥9.9/月 |
| 轻量应用服务器 2C4G | ~¥54/月 |
| 香港轻量云 2C2G | ~¥34/月（免备案） |

选 Ubuntu 22.04，国内机需域名备案。

### C.2 装环境

```bash
# SSH 登录
ssh root@<服务器IP>

# 装 Java 21
apt update && apt install openjdk-21-jdk-headless -y

# 装 PostgreSQL
apt install postgresql -y
sudo -u postgres createuser codecard -P
sudo -u postgres createdb codecard -O codecard

# 执行建表
psql -U codecard -d codecard -f schema.sql
```

### C.3 部署后端

```bash
# 本地打包
cd G:\CodeCard\backend
mvn package -DskipTests
# 产出：target/codecard-1.0.0.jar

# 上传到服务器
scp target/codecard-1.0.0.jar root@<服务器IP>:/opt/codecard/

# 服务器上跑
ssh root@<服务器IP>
cd /opt/codecard
nohup java -jar codecard-1.0.0.jar > app.log 2>&1 &
```

### C.4 开端口

阿里云控制台 → 安全组 → 入方向 → 放行 8080。

### C.5 前端连上

```bash
# .env
EXPO_PUBLIC_API_URL=http://<服务器IP>:8080/api/v1
```

> 这是 HTTP 明文。加 HTTPS 见下文。

### C.6 加 HTTPS（可选但推荐）

详见 `nginx-https-plan.md`，核心步骤：

```bash
# 装 Nginx
apt install nginx certbot python3-certbot-nginx -y

# 签发证书
certbot --nginx -d api.codecard.app

# Nginx 代理 443 → 8080
```

---

## 方案对比

| | ngrok | Railway | 学生机 |
|------|:---:|:---:|:---:|
| 成本 | ¥0 | ¥0-36/月 | ¥9.9-68/月 |
| 上手时间 | 1 分钟 | 10 分钟 | 30 分钟 |
| 国内速度 | 一般 | 一般（海外节点）| 快 |
| HTTPS | 自动 | 自动 | 需手动配 |
| 数据库 | 本地 PG | Railway PG | 自建 PG |
| 适合阶段 | 开发调试 | 个人上线 | 正式运营 |
| 备案 | 不需要 | 不需要 | 国内机需要 |

---

## 推荐路线

```
现在 ── 方案 A（ngrok）── 手机能调接口、验证功能
  │
  ├─ 功能验证 OK，需要长期可用
  │   └─ 方案 B（Railway）── 免费额度够 1 人用
  │
  └─ 用户变多、需要国内速度
      └─ 方案 C（学生机）── ¥9.9/月起步
```

---

## 相关文档

| 文档 | 什么时候看 |
|------|-----------|
| `nginx-https-plan.md` | 方案 C 配 HTTPS 时 |
| `ci-cd.md` | 想 push 代码自动部署时 |
| `backend-architecture.md` §8 | 环境变量 + 生产检查清单 |

# Sealos PostgreSQL 接入配置

## 连接信息

| 项 | 值 |
|----|-----|
| 地址 | `dbconn.sealoshzh.site` |
| 端口 | `37540` |
| 数据库 | `postgres` |
| 用户名 | `postgres` |
| 密码 | `dwr9mdxq` |
| SSL | 强制（`sslmode=require`） |
| JDBC URL | `jdbc:postgresql://dbconn.sealoshzh.site:37540/postgres?sslmode=require` |

## Profile 切换

项目使用 Spring Boot 多 profile，数据库配置在各自 profile 文件中。

| Profile | 文件 | 数据库 |
|---------|------|--------|
| `local`（默认） | `application-local.yml` | 本地 PostgreSQL `localhost:5432/codecard` |
| `sealos` | `application-sealos.yml` | Sealos 云端 `dbconn.sealoshzh.site:37540/postgres` |

## 启动命令

### 本地开发

```powershell
cd G:\CodeCard\backend
mvn spring-boot:run
```

### Sealos 云端

```powershell
cd G:\CodeCard\backend
.\start-sealos.ps1
```

环境变量在 `start-sealos.ps1` 中设置（已加入 `.gitignore`，不提交）。

## 首次接入步骤

1. 在 Sealos 控制台创建 PostgreSQL 实例
2. 开启公网访问，拿到域名和端口
3. 用 psql 连接并执行 `schema.sql` 建表：

```bash
psql -h <host> -p <port> -U postgres -d postgres
\i src/main/resources/schema.sql
\dt
\q
```

4. 配置环境变量并启动后端
5. 验证：浏览器打开 `http://localhost:8080/actuator/health`，应返回 `{"status":"UP"}`

## 配置文件说明

```
backend/src/main/resources/
├── application.yml           ← 公共配置（JWT、CORS、日志、限流）
├── application-local.yml     ← 本地 PostgreSQL
└── application-sealos.yml    ← Sealos PostgreSQL
```

- `application.yml` 设置 `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:local}`，默认 local
- Sealos 部署时通过环境变量或 `-Dspring-boot.run.profiles=sealos` 覆盖
- 两个 profile 都用 PostgreSQL，DDL 和 SQL 行为完全一致

## 可视化数据库

### 方式 1：Sealos 控制台工作台（最快，不用安装）

Sealos 控制台 → 点击 `test-db-postgresql` 实例 → "工作台" → 左侧展开表 → 右键"查询数据"

### 方式 2：DBeaver（功能最强）

1. 下载安装 [DBeaver](https://dbeaver.io/download)
2. 新建连接 → PostgreSQL
3. 填入上面连接信息，勾选 SSL
4. 可浏览表结构、查询数据、编辑数据、执行 SQL

### 数据库表

| 表名 | 内容 |
|------|------|
| `users` | 用户（邮箱、手机、密码） |
| `user_progress` | 学习进度（JSON） |
| `refresh_tokens` | 登录令牌 |
| `otp_codes` | 验证码 |

### Sealos 控制台功能

| 功能 | 用途 |
|------|------|
| **工作台** | 网页版 SQL 编辑器，查数据、改表、执行 SQL |
| **仪表板** | 监控面板，看连接数、CPU、内存、磁盘用量、QPS |
| **定时备份（Cron）** | 设置定时自动备份数据库，建议开启（如每天凌晨 3 点） |

## 后端部署到 App Launchpad（计划）

### 是什么

| | DevBox | App Launchpad |
|----|--------|---------------|
| 定位 | 云端开发环境（边写边改） | 生产部署平台（稳定运行） |
| 适合 | 写代码、调试 | 让后端 7×24 跑着 |
| 类比 | 云上的开发电脑 | 云上的服务器 |

项目已有完整源码，不需要云端再搭开发环境，所以用 App Launchpad 部署后端更合适。

### 需要准备

| 需要 | 说明 |
|------|------|
| `Dockerfile` | 告诉 Sealos 怎么打包后端（需新建） |
| Docker Desktop | Windows 本地安装，用来打镜像 |
| Sealos 镜像仓库 | Sealos 会提供，存放镜像 |
| 环境变量 | DB_URL、JWT_SECRET 等在页面上填写 |

### 部署流程（3 步）

**第 1 步：打包镜像**

本地执行 `docker build`，自动完成：下载依赖 → 编译 → 生成 `.jar` → 放入 JRE 镜像

**第 2 步：推送镜像**

`docker push` 推到 Sealos 镜像仓库

**第 3 步：App Launchpad 创建应用**

填 3 项配置：

| 配置项 | 值 |
|--------|-----|
| 镜像地址 | 第 2 步推送的地址 |
| 端口 | `8080` |
| 环境变量 | DB_URL（用内网地址）、DB_USER、DB_PASSWORD、JWT_SECRET |

Sealos 自动分配 `https://xxx.sealoshzh.site` 域名。

### 部署后的架构

```
之前：手机 → localhost:8080 → 本地后端 → 公网数据库（慢）
之后：手机 → https://域名 → Sealos 后端 → 内网数据库（快）
```

好处：
- 后端 7×24 运行，关电脑也不停
- 自动 HTTPS 证书
- 数据库改用内网地址 `test-db-postgresql.ns-ynkwsk8t.svc:5432`，延迟更低
- 挂了自动重启

### 前端适配

拿到域名后，设一个环境变量即可，代码零改动：

```bash
EXPO_PUBLIC_API_URL=https://你的域名/api/v1
```

当前 `src/lib/api.ts` 已支持：如果设了 `EXPO_PUBLIC_API_URL` 就用它，否则回退到 `localhost:8080`。

### 部署前待处理

| 问题 | 状态 |
|------|------|
| 邮件 SMTP 未配 | `management.health.mail.enabled: false` 已跳过 |
| 域名 | App Launchpad 自动分配 |
| CORS | 需将新域名加入 `cors.allowed-origins` |

## 常见问题

**Q: 健康检查返回 `{"status":"DOWN"}`？**

邮件健康检查超时导致。Sealos profile 已禁用邮件健康检查（`management.health.mail.enabled: false`）。如需恢复，先配置 SMTP 账号密码。

**Q: 启动报 "Connection refused: localhost:5432"？**

忘传 `-Dspring-boot.run.profiles=sealos`，应用用了默认 local profile。

**Q: 启动报 "Illegal base64 character"？**

JWT_SECRET 必须用标准 Base64（含 `+` `/` `=`），不能用 URL-safe Base64（含 `-` `_`）。生成方式：

```bash
openssl rand -base64 64
```

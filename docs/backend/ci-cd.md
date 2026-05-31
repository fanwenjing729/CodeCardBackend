# CI/CD 方案

## CI（持续集成）— 每次 push 自动跑测试

每次 `git push` 到 GitHub 后自动跑测试和类型检查，挂了邮件通知。

## 为什么不做 CD

- APK 打包需要 Expo 凭证 + Google Play 账号，配置复杂
- 后端部署需要服务器 + 域名 + SSL，目前仍是本地开发
- CI 价值最大（防回归），CD 等需要频繁发版时再配

## 目标

| 目标 | 当前 | CI 后 |
|------|------|-------|
| 测试什么时候跑 | 手动 `npm test` | 每次 push 自动跑 |
| 我怎么知道挂了 | 几小时后想起来再跑 | push 后 5 分钟，邮件/通知 |
| 谁来保证别人没搞坏 | 我自己跑 | 机器人自动检查 |

---

## 实现计划

### Phase 1 — 前端 CI（先做）

**文件**：`.github/workflows/test-frontend.yml`

**触发**：push 到 `master` 或者开 PR 到 `master`

**步骤**：

```
1. checkout 代码
2. setup Node.js 22（当前 LTS）
3. npm ci（比 npm install 快，且失败即停）
4. npm run lint（tsc --noEmit，类型检查）
5. npm test（vitest run，当前 163 条）
```

**workflow 文件**：

```yaml
name: Frontend Tests

on:
  push:
    branches: [master]
  pull_request:
    branches: [master]

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm

      - run: npm ci

      - run: npm run lint

      - run: npm test
```

**为什么省略缓存**：`cache: npm` 已经让 setup-node 自动缓存 node_modules，不需要额外步骤。

**预计耗时**：首次 ~2min，后续 ~30s（有缓存）。

**免费额度**：GitHub Actions 每月 2000 分钟（公开仓库无限），这个项目每次跑不到 1 分钟，完全用不完。

---

### Phase 2 — 后端 CI（后做）

**文件**：`.github/workflows/test-backend.yml`

**触发**：push 到 `master`，且改动路径匹配 `backend/**`（前端 commit 不触发后端 CI）

**挑战**：集成测试需要 PostgreSQL。两个方案：

#### 方案 A：GitHub Actions Service Container（推荐）

```yaml
name: Backend Tests

on:
  push:
    branches: [master]
    paths:
      - 'backend/**'

jobs:
  test:
    runs-on: ubuntu-latest

    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_USER: codecard
          POSTGRES_PASSWORD: codecard
          POSTGRES_DB: codecard
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
          cache: maven

      - run: cd backend && ./mvnw test
```

**优点**：用真实 PostgreSQL，测试结果和生产一致。
**缺点**：首次冷启动 ~1min（下载 PG 镜像）。

#### 方案 B：跳过集成测试，只跑单元测试

```yaml
- run: cd backend && ./mvnw test -DexcludedGroups=integration
```

**优点**：不需要 PostgreSQL。
**缺点**：需要给现有集成测试加 `@Tag("integration")` 注解，否则全部跳过就没测试了。

**推荐方案 A**。当前只有 2 个集成测试文件，PG 容器启动快，没必要切割。

---

### Phase 3 — 通知（可选）

GitHub Actions 默认：
- PR 上显示 ✅ 或 ❌
- 仓库 Settings → Notifications → 勾选 Email（失败时发邮件）

不需要额外配。如果要微信/钉钉通知，加个 webhook step。

---

## 执行步骤

### Step 1：创建文件

```bash
mkdir -p .github/workflows
```

写入 `test-frontend.yml`（Phase 1 内容）。

### Step 2：本地验证（可选）

装 [act](https://github.com/nektos/act) 在本地跑 GitHub Actions：

```bash
act push
```

或直接用 Docker：

```bash
docker run --rm -v $PWD:/workspace -w /workspace \
  node:22 sh -c "npm ci && npm run lint && npm test"
```

### Step 3：push 到 GitHub

```bash
git add .github/workflows/test-frontend.yml
git commit -m "ci: add frontend test workflow"
git push
```

push 后去 GitHub 仓库 → Actions tab → 看到 workflow 在跑 → 几分钟后绿 ✅

### Step 4：验证真实效果

- 故意写一个类型错误 → push → 看 CI 挂红 ❌
- 修复 → push → 看 CI 恢复 ✅

---

## 成本

| 项 | 成本 |
|----|------|
| 时间 | ~15min（写 1 个文件 + push） |
| 金钱 | ¥0（GitHub Actions 免费） |
| 维护 | 0（除非改测试命令或 Node 版本） |

## CD（持续部署）— 自动构建 + 分发

### 为什么现在不做

CD 的前提是有东西可部署。当前项目状态：

| 层 | 状态 | 部署目标 |
|----|------|----------|
| 前端 APK | 本地 `expo start` 开发中 | 用户设备 |
| 后端 | 本地 `mvn spring-boot:run` | 服务器 |

两者都还没到"需要频繁部署给真实用户"的阶段。但方案先设计好，到时候照着做。

---

### 前端 CD — APK 自动构建 + 分发

#### 方案：EAS Build + EAS Submit

Expo 官方服务，在 Expo 云端构建 APK/AAB，省去本地配 Android SDK 的麻烦。

```
git push (打 tag v1.0.0)
  → GitHub Actions 触发
  → CI 全部通过
  → eas build --platform android --profile production
  → Expo 云端构建 (~10min)
  → 产出 APK/AAB
  → eas submit --platform android (提交到 Google Play)
  → Google Play 审核 → 用户更新
```

#### workflow 文件

```yaml
# .github/workflows/build-apk.yml
name: Build Android APK

on:
  push:
    tags:
      - 'v*'     # 只在打 tag（如 v1.0.0）时触发

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm

      - run: npm ci

      - run: npm test     # 先确保测试全绿

      - run: npx eas build \
          --platform android \
          --profile production \
          --non-interactive
        env:
          EXPO_TOKEN: ${{ secrets.EXPO_TOKEN }}
```

#### 需要什么

| 条件 | 获取方式 | 成本 |
|------|---------|------|
| Expo 账号 | [expo.dev](https://expo.dev) 注册 | 免费 |
| EXPO_TOKEN | Expo 后台 → Access Tokens → 生成 | 免费 |
| Google Play 开发者账号 | 一次性 $25（约 ¥180） | 一次性 |
| 应用签名密钥 | EAS 自动管理 | 免费 |

#### EAS 免费额度

| 项 | 免费版 |
|----|--------|
| Android 构建 | 每月 30 次 |
| iOS 构建 | 每月 30 次 |
| 构建耗时 | 通常 10-15 分钟/次 |

对于个人项目，30 次/月完全够用。

#### 另一种方式：EAS Update（热更新）

不改 APK，直接推送 JS Bundle 到用户设备，跳过应用商店审核：

```bash
npx eas update --branch production --message "fix: 修复登录 bug"
```

适用场景：JS/TS 层面的修改（卡片数据、UI、逻辑）。不适合：改了 native 模块、Expo SDK 升级。

| 方式 | 适用 | 用户端 | 审核 |
|------|------|--------|------|
| EAS Build | native 变更、首次安装 | 需要下载新 APK | 需要 |
| EAS Update | JS/TS 变更 | 重启 app 即生效 | 不需要 |

---

### 后端 CD — JAR 构建 + 部署到服务器

#### 前提：有一台服务器

以最便宜的阿里云 ECS 为例：

| 配置 | 价格 |
|------|------|
| 2C 2G + 40G SSD | ~¥60/月 |
| 域名（.com） | ~¥70/年 |
| SSL 证书 | 免费（Let's Encrypt） |

或者用 PaaS（平台即服务），不需要自己管服务器：

| 平台 | 免费额度 | 适合 |
|------|---------|------|
| Railway | $5/月额度 | Spring Boot 一键部署 |
| Fly.io | 3 个 app 免费 | Docker 部署 |
| Render | 750h/月 | Web Service 免费 |

#### 方案 A：Docker + GitHub Actions + VPS

```
git push (到 master)
  → CI 通过
  → mvn package -DskipTests (打包 JAR)
  → docker build -t codecard-backend .
  → docker save → scp 到服务器
  → ssh → docker compose up -d
```

**workflow 文件**：

```yaml
# .github/workflows/deploy-backend.yml
name: Deploy Backend

on:
  push:
    branches: [master]
    paths:
      - 'backend/**'

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
          cache: maven

      - run: cd backend && ./mvnw package -DskipTests

      - name: Build Docker image
        run: |
          cd backend
          docker build -t codecard-backend:${{ github.sha }} .

      - name: Push to server
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.SSH_HOST }}
          username: ${{ secrets.SSH_USER }}
          key: ${{ secrets.SSH_KEY }}
          script: |
            cd /opt/codecard
            docker compose pull
            docker compose up -d
```

**需要什么**：

| 条件 | 获取方式 | 成本 |
|------|---------|------|
| 服务器 | 阿里云/腾讯云 ECS | ~¥60/月 |
| 域名 | 阿里云万网 | ~¥70/年 |
| Docker | 服务器安装 `docker` + `docker compose` | 免费 |
| Nginx + SSL | certbot 自动续签 | 免费 |
| GitHub Secrets | 仓库 Settings → Secrets 添加 | 免费 |

#### 方案 B：PaaS 部署（不需要管理服务器）

以 Railway 为例：

```yaml
# .github/workflows/deploy-backend.yml
- run: cd backend && ./mvnw package -DskipTests
- uses: railwayapp/railway-action@v1
  with:
    service: codecard-backend
    railway-token: ${{ secrets.RAILWAY_TOKEN }}
```

**优点**：不需要买服务器、配 Nginx、搞 SSL。Railway 自动给 HTTPS 域名。
**缺点**：免费额度有限（$5/月），超出后需付费。

---

### 完整 CD 路线图

```
现在 ── CI（自动测试）
   │
   ├─ 有真实用户使用 APK ── EAS Update（热更新，99% 的更新走这）
   │
   ├─ 改了 native 代码 ── EAS Build（打新 APK，手动分发）
   │
   ├─ 要上架应用商店 ── EAS Submit（提交到 Google Play）
   │
   └─ 用户数据需要跨设备 ── 后端部署到服务器 ── CD（自动部署）
```

### 总结

| | CI | CD（前端） | CD（后端） |
|----|----|----|----|
| 什么时候做 | **现在** | 有真实用户时 | 需要跨设备同步时 |
| 成本 | ¥0 | 一次性 $25（Google Play） | ~¥60/月（服务器）或 Railway $5/月 |
| 配置量 | 1 个文件，30 行 | 1 个文件 + Expo Token | 1 个文件 + 服务器 |
| 复杂度 | 低 | 中（EAS 配置 + 签名） | 高（服务器运维） |

---

---

## Railway 部署指南

### Railway 是什么

PaaS（平台即服务）——代码给它，它帮你跑。自动搞定服务器、JDK、数据库、HTTPS、自动重启。

### 架构变化

```
现在（本地开发）：
  APK → http://10.0.2.2:8080 → 你电脑上的 Spring Boot → 你电脑上的 PostgreSQL

上线后（Railway）：
  APK → https://codecard.up.railway.app → Railway 上的 Spring Boot → Railway 上的 PostgreSQL
```

### Step 0：准备 — 让后端适配 Railway

#### 0.1 schema 自动建表

当前 `application.yml` 用 `ddl-auto: validate`（只验证不建表）。Railway 上 PostgreSQL 是空的，需要改为 `update` 或通过 `schema.sql` 初始化。

**改法**：加一个 Railway profile 或直接用 `update`：

```yaml
# application.yml
spring:
  jpa:
    hibernate:
      ddl-auto: update  # Railway 上自动建表（开发环境可以用 validate）
```

> 安全提示：`ddl-auto: update` 在生产环境不如 flyway/liquibase 严谨，但对个人项目完全够用。

#### 0.2 CORS（不是问题）

APK 发 `fetch()` 不走浏览器 CORS 策略。CORS 只影响网页访问 API。**不需要改 CORS 配置。**

#### 0.3 端口

已有 `server.port: ${PORT:8080}`，Railway 自动设 `PORT` 环境变量。**不需要改。**

---

### Step 1：Railway 注册 + 建项目

1. 打开 [railway.com](https://railway.com) → **Sign in with GitHub**（用你的 GitHub 账号）
2. 点 **New Project** → **Deploy from GitHub** → 选 `fanwenjing729/CodeCard`
3. Railway 自动识别 `backend/pom.xml`，配置 Maven + Java 21 构建

### Step 2：加 PostgreSQL

1. 在项目里点 **+ New Service** → **Database** → **PostgreSQL**
2. Railway 自动生成 `DATABASE_URL`、`DB_USER`、`DB_PASSWORD` 等环境变量
3. 把 PostgreSQL 的变量绑定到后端 service

### Step 3：设环境变量

在 Railway 后端的 service → **Variables** 里加：

| 变量名 | 值 | 说明 |
|--------|-----|------|
| `DB_URL` | `${{ Postgres.DATABASE_URL }}` | Railway 自动引用 PG 的 URL |
| `JWT_SECRET` | `openssl rand -base64 64` 生成的随机串 | JWT 签名密钥，不要用默认值 |
| `SMTP_HOST` | `smtp.resend.com` | 邮件服务，你现在用的 |
| `SMTP_PORT` | `587` | |
| `SMTP_USER` | `你的 Resend API Key` | |
| `SMTP_PASS` | `你的 Resend SMTP 密码` | |

Railway 会自动注入 `DB_URL` 拼接格式（如果它叫 `DATABASE_URL`）。如果不行，手动拼：

```
DB_URL = jdbc:postgresql://<host>:<port>/<database>
DB_USER = <user>
DB_PASSWORD = <password>
```

### Step 4：首次部署

1. push 代码到 GitHub → Railway 自动触发构建
2. 或者 Railway 里点 **Deploy**
3. 构建日志里看到 `Started CodeCardApplication in X seconds` → 成功
4. Railway 生成一个 URL：`https://codecard.up.railway.app`

### Step 5：改前端连上 Railway

在 APK 打包前设环境变量：

```bash
EXPO_PUBLIC_API_URL=https://codecard.up.railway.app/api/v1
```

当前 `api.ts` 的 `getBaseUrl()` 已经支持这个变量：

```ts
if (process.env.EXPO_PUBLIC_API_URL) {
  return process.env.EXPO_PUBLIC_API_URL;  // ← 生产环境走这行
}
```

打 APK 时带上：

```bash
EXPO_PUBLIC_API_URL=https://codecard.up.railway.app/api/v1 npx expo run:android
```

或者在 EAS Build 配置里写死。

---

### 成本汇总

| 项 | 费用 |
|----|------|
| Railway 后端（512MB） | $5/月 免费额度覆盖（不超额 = ¥0） |
| Railway PostgreSQL（256MB） | $5/月 免费额度覆盖 |
| 域名 | 不需要，Railway 给 `xxx.up.railway.app` |

**如果免费额度不够**：升级到 $5/月起步计划，约 ¥36/月。

### 和 GitHub 的关系

```
你写代码 → git push → GitHub（存代码）
                        ├─ GitHub Actions（跑测试）
                        └─ Railway 监听 push → 自动部署后端
```

三者串联：GitHub 是代码仓库 + 测试站，Railway 是运行站。

### 当前不需要做的

| 不做 | 理由 |
|------|------|
| 买域名 | Railway 自带 HTTPS 域名 |
| 配 Nginx | Railway 自带反向代理 + SSL |
| 配 CDN | 单体应用，用户规模小不需要 |
| 配监控 | 等用户 >100 再加 |

---

## 后续扩展

| 时机 | 加什么 |
|------|--------|
| 多人协作 | PR 上要求 CI 通过才能 merge（Branch Protection Rule） |
| 需要发版 APK | 加 `eas build` step |
| 需要部署后端 | 加 Docker build + push + SSH deploy |
| 测试变慢（>3min） | 拆分 job：lint 和 test 并行跑 |

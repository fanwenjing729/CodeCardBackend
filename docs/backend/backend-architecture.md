# CodeCard 后端架构文档

> 合并了入门指南 + 完整架构设计。日常操作看 §快速开始，架构细节看正文。

---

## 快速开始

### 环境要求

| 组件 | 版本 | 备注 |
|------|------|------|
| JDK | 21+ | 当前机器 Java 25，需 `JAVA_TOOL_OPTIONS`（见下） |
| PostgreSQL | 17 | ZIP 免安装，路径 `G:\CodeCard\posterSQL\pgsql\` |
| Maven | 3.9+ | 系统 Maven `E:/Maven/apache-maven-3.9.16/bin/mvn` |

### Java 25 兼容

Java 25 更严格限制 native access，Maven 需要加 JVM 参数：

```bash
export JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"
```

建议加到 `~/.bashrc` 或每次启动前执行。

### Maven 注意事项

项目自带 Maven Wrapper（`mvnw`），但 Java 25 下 wrapper 有兼容问题。**直接用系统 Maven**：

```bash
/e/Maven/apache-maven-3.9.16/bin/mvn spring-boot:run
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `PGDATA` | 数据目录 | `G:\CodeCard\posterSQL\data` |
| `JWT_SECRET` | JWT 密钥 | 已有开发默认值 |
| `DB_URL` | 数据库 URL | `jdbc:postgresql://localhost:5432/codecard` |
| `DB_USER` | 数据库用户 | `codecard` |
| `DB_PASSWORD` | 数据库密码 | `codecard` |
| `JAVA_TOOL_OPTIONS` | Java 25 兼容 | `--enable-native-access=ALL-UNNAMED` |

### 日常命令

```bash
# 1. 启动数据库（重启电脑后需手动执行）
"G:/CodeCard/posterSQL/pgsql/bin/pg_ctl" start -D "G:/CodeCard/posterSQL/data" -l "G:/CodeCard/posterSQL/pg.log"

# 2. 启动后端
export JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"
cd G:/CodeCard/backend
/e/Maven/apache-maven-3.9.16/bin/mvn spring-boot:run

# 3. Swagger 调试
# 浏览器打开 http://localhost:8080/swagger-ui.html

# 4. 停止数据库
"G:/CodeCard/posterSQL/pgsql/bin/pg_ctl" stop -D "G:/CodeCard/posterSQL/data"

# 5. 跑测试
/e/Maven/apache-maven-3.9.16/bin/mvn test
```

> 国内网络下载 Maven 依赖慢？在 `E:\Maven\apache-maven-3.9.16\conf\settings.xml` 的 `<mirrors>` 里配了阿里云镜像，新装环境记得加。

### PostgreSQL 安装记录

安装方式：ZIP 免安装版，路径 `G:\CodeCard\posterSQL\pgsql\`。

首次初始化（已完成，无需重复）：
```bash
initdb -D G:\CodeCard\posterSQL\data -U postgres --encoding=UTF8
pg_ctl -D G:\CodeCard\posterSQL\data -l G:\CodeCard\posterSQL\pg.log start
psql -U postgres -d postgres
  ALTER USER postgres WITH PASSWORD '你的密码';
  CREATE USER codecard WITH PASSWORD 'codecard';
  CREATE DATABASE codecard OWNER codecard;
  \q
psql -U codecard -d codecard -f G:\CodeCard\backend\src\main\resources\schema.sql
```

---

> 版本 1.2.0 | 2026-05-30 | 合并入门指南

---

## 目录

1. [架构概览](#1-架构概览)
2. [项目结构](#2-项目结构)
3. [数据库设计](#3-数据库设计)
4. [API 接口设计](#4-api-接口设计)
5. [认证与安全](#5-认证与安全)
6. [进度同步策略](#6-进度同步策略)
7. [前端对接层](#7-前端对接层)
8. [配置与部署](#8-配置与部署)
9. [从 Supabase 迁移](#9-从-supabase-迁移)
10. [扩展路线](#10-扩展路线)

---

## 1. 架构概览

### 1.1 整体拓扑

```
┌─────────────────────────────────────────┐
│  React Native App (Android)             │
│  src/lib/api.ts  ← HTTP 客户端          │
│  src/store/authStore.ts  ← 认证状态     │
│  src/store/syncEngine.ts ← 进度同步     │
└──────────────┬──────────────────────────┘
               │ HTTPS / HTTP
               │ JSON over REST
               ▼
┌─────────────────────────────────────────┐
│  Spring Boot 3.4.1 (Java 21)            │
│  com.codecard                           │
│  ├── config/  安全 + JWT + CORS + 异常  │
│  ├── auth/    认证控制器 + 服务 + OTP    │
│  ├── progress/ 进度控制器 + 服务         │
│  ├── user/    用户实体 + 仓库            │
│  └── otp/     验证码实体 + 仓库          │
└──────────────┬──────────────────────────┘
               │ JDBC
               ▼
┌─────────────────────────────────────────┐
│  PostgreSQL                             │
│  ├── users             用户表            │
│  ├── otp_codes         验证码表          │
│  ├── user_progress     学习进度 (JSONB)  │
│  └── refresh_tokens    刷新令牌表        │
└─────────────────────────────────────────┘
```

### 1.2 技术选型

| 层 | 选型 | 版本 | 原因 |
|----|------|------|------|
| 框架 | Spring Boot | 3.4.1 | 成熟生态，与 Java 21 虚拟线程兼容 |
| 构建 | Maven | 3.9.9 | 国内镜像可用，wrapper 轻量 |
| 语言 | Java | 21 (LTS) | 虚拟线程、record、switch 表达式 |
| 安全 | Spring Security + JWT | 6.x + jjwt 0.12.6 | 无状态认证，移动端标准方案 |
| ORM | Spring Data JPA + Hibernate | 6.x | JSONB 直接映射，减少手写 SQL |
| 数据库 | PostgreSQL | 17 | JSONB 与原 Supabase 数据兼容 |
| 邮件 | Spring Mail (JavaMailSender) | — | 发送 OTP 验证码 |
| 密码 | BCrypt | — | Spring Security 默认，与 Supabase 兼容 |
| 测试 | JUnit 5 + H2 | — | 内存数据库，无需 PostgreSQL |

---

## 2. 项目结构

### 2.1 目录树

```
G:\CodeCard\backend\
├── pom.xml                                    # Maven 项目定义
├── mvnw / mvnw.cmd                           # Maven Wrapper
├── .mvn/wrapper/
│   ├── maven-wrapper.jar                      # Wrapper JAR (63KB)
│   └── maven-wrapper.properties               # 指向 Maven 3.9.9
├── run.bat                                    # 一键启动脚本
└── src/
    ├── main/
    │   ├── java/com/codecard/
    │   │   ├── CodeCardApplication.java       # @SpringBootApplication 入口
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java        # SecurityFilterChain + BCrypt Bean
    │   │   │   ├── JwtAuthFilter.java         # OncePerRequestFilter
    │   │   │   ├── JwtService.java            # Token 生成/验证/解析
    │   │   │   ├── CorsConfig.java            # 跨域 CorsFilter Bean
    │   │   │   └── GlobalExceptionHandler.java # @RestControllerAdvice
    │   │   ├── auth/
    │   │   │   ├── AuthController.java        # 9 个 REST 端点
    │   │   │   ├── AuthService.java           # 核心认证逻辑
    │   │   │   ├── OtpService.java            # OTP 生成 + 邮件发送
    │   │   │   ├── RefreshToken.java          # Entity: refresh_tokens
    │   │   │   ├── RefreshTokenRepository.java
    │   │   │   └── dto/
    │   │   │       ├── LoginRequest.java
    │   │   │       ├── RegisterRequest.java
    │   │   │       ├── OtpSendRequest.java
    │   │   │       ├── OtpVerifyRequest.java
    │   │   │       ├── SetPasswordRequest.java
    │   │   │       ├── RefreshRequest.java
    │   │   │       ├── AuthResponse.java        # 嵌套 UserProfile
    │   │   │       └── UpdateProfileRequest.java
    │   │   ├── progress/
    │   │   │   ├── ProgressController.java    # 3 个 REST 端点
    │   │   │   ├── ProgressService.java       # JSONB 存取 + 同步
    │   │   │   ├── UserProgress.java          # Entity: user_progress
    │   │   │   ├── UserProgressRepository.java
    │   │   │   └── dto/
    │   │   │       ├── ProgressSyncRequest.java
    │   │   │       └── ProgressSyncResponse.java
    │   │   ├── user/
    │   │   │   ├── User.java                  # Entity: users
    │   │   │   └── UserRepository.java
    │   │   └── otp/
    │   │       ├── OtpCode.java               # Entity: otp_codes
    │   │       └── OtpCodeRepository.java
    │   └── resources/
    │       ├── application.yml                 # 主配置（环境变量驱动）
    │       └── schema.sql                      # DDL 建表脚本
    └── test/
        ├── java/com/codecard/                 # （测试待添加）
        └── resources/
            └── application.yml                 # H2 内存库测试配置
```

### 2.2 Maven 依赖

```xml
<!-- 核心 -->
spring-boot-starter-web            <!-- REST API -->
spring-boot-starter-security       <!-- 认证授权 -->
spring-boot-starter-data-jpa       <!-- ORM -->
spring-boot-starter-validation     <!-- @Valid 校验 -->
spring-boot-starter-mail           <!-- 邮件发送 -->

<!-- JWT -->
io.jsonwebtoken:jjwt-api:0.12.6    <!-- 编译期 API -->
io.jsonwebtoken:jjwt-impl          <!-- 运行时实现 -->
io.jsonwebtoken:jjwt-jackson       <!-- JSON 序列化 -->

<!-- 数据库 -->
org.postgresql:postgresql          <!-- PostgreSQL JDBC 驱动 -->

<!-- 工具 -->
org.projectlombok:lombok           <!-- 编译期注解，打包时排除 -->

<!-- 测试 -->
spring-boot-starter-test           <!-- JUnit 5 + Mockito -->
spring-security-test               <!-- @WithMockUser -->
com.h2database:h2                  <!-- 内存数据库 -->
```

### 2.3 关键类职责

| 类 | 职责 | 行数 |
|----|------|------|
| `SecurityConfig` | 声明白名单端点，禁用 CSRF + Session，注入 JWT Filter，自定义 AuthenticationEntryPoint 返回 JSON 401 | 56 |
| `JwtAuthFilter` | 从 Header 提取 JWT，验证签名 + 过期 + type（拒绝 refresh token 用于 access），无 DB 查询，直接注入 userId 到 SecurityContext | 56 |
| `JwtService` | HMAC-SHA256 签名，Access(15min)+Refresh(30d)，含 JTI + type claim。`extractUserIdStr`/`extractType`/`getRefreshExpirationMs` 辅助方法 | 87 |
| `AuthService` | 注册、密码/OTP 登录、Token 刷新（硬编码 30d 改为读取 jwtService.getRefreshExpirationMs()）、登出、用户资料 CRUD | 175 |
| `OtpService` | 生成 6 位随机码，存库，60s 频率限制，SLF4J 日志替代静默吞异常，10 分钟有效期 | 87 |
| `ProgressService` | JSONB 序列化/反序列化，upsert 与 sync 逻辑 | 97 |
| `AuthController` | `/api/v1/auth/*` 9 个端点，`@AuthenticationPrincipal String` 注入当前用户 ID | 74 |
| `ProgressController` | `/api/v1/progress/*` 3 个端点，`@AuthenticationPrincipal String` 注入 | 57 |
| `GlobalExceptionHandler` | `AuthException`→401，`MethodArgumentNotValidException`→400，`Exception`→500（JSON 格式） | 41 |

---

## 3. 数据库设计

### 3.1 ER 图

```
┌──────────────────┐       ┌──────────────────┐
│     users        │       │   otp_codes      │
├──────────────────┤       ├──────────────────┤
│ id        UUID PK│       │ id        UUID PK│
│ email     VARCHAR│  ◄─── │ target    VARCHAR│  (email 或 phone)
│ phone     VARCHAR│  (关联 │ code      VARCHAR│  6位数字
│ password  VARCHAR│   字段)│ purpose   VARCHAR│  login/register/reset
│ display_id VAR   │       │ expires_at TIMEST│  10分钟后过期
│ avatar_url TEXT  │       │ used      BOOL  │  用后标记
│ created_at TIMEST│       │ created_at TIMEST│
│ updated_at TIMEST│       └──────────────────┘
└──────┬───────────┘
       │ 1:1
       ▼
┌──────────────────┐       ┌──────────────────┐
│ user_progress    │       │ refresh_tokens   │
├──────────────────┤       ├──────────────────┤
│ user_id UUID PK  │       │ id        UUID PK│
│ data     JSONB   │       │ user_id   UUID FK│──► users(id)
│ version  INTEGER │       │ token_jid VARCHAR │  JWT ID，唯一
│ updated_at TIMEST│       │ expires_at TIMEST│  30天后过期
└──────────────────┘       │ created_at TIMEST│
                           └──────────────────┘
```

### 3.2 表结构详解

#### 3.2.1 `users` — 用户表

```sql
CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) UNIQUE,
    phone           VARCHAR(32) UNIQUE,
    password_hash   VARCHAR(255),
    display_id      VARCHAR(64),
    avatar_url      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_users_phone ON users(phone) WHERE phone IS NOT NULL;
```

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | UUID | PK, gen_random_uuid() | 全局唯一用户标识 |
| `email` | VARCHAR(255) | UNIQUE, nullable | 邮箱登录用。unique 约束确保不重复 |
| `phone` | VARCHAR(32) | UNIQUE, nullable | 手机登录用。32 位足够容纳 +8613800138000 |
| `password_hash` | VARCHAR(255) | nullable | BCrypt $2a$ 哈希。OAuth/OTP 用户可为 null |
| `display_id` | VARCHAR(64) | nullable | 用户昵称，可修改 |
| `avatar_url` | TEXT | nullable | 头像 URL，未来接 Supabase Storage 或 OSS |
| `created_at` | TIMESTAMPTZ | NOT NULL | @PrePersist 自动赋值 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | @PreUpdate 自动赋值 |

**设计决策：email 和 phone 都允许为 null**

原因：用户可以通过邮箱注册（phone=null），或通过手机注册（email=null），或通过 OTP 登录自动创建。`LoginRequest` 和 `RegisterRequest` 通过 `@AssertTrue hasIdentity()` 确保至少提供一个。OTP 验证码验证时，根据 target 是否包含 `@` 判断是邮箱还是手机，自动创建对应用户。

**设计决策：部分索引**

```sql
CREATE INDEX idx_users_email ON users(email) WHERE email IS NOT NULL;
```

`WHERE email IS NOT NULL` 确保索引只包含有 email 的行，减小索引体积。查询 `findByEmail` 时走索引，`findByPhone` 同理。

#### 3.2.2 `otp_codes` — 验证码表

```sql
CREATE TABLE IF NOT EXISTS otp_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target      VARCHAR(255) NOT NULL,
    code        VARCHAR(8) NOT NULL,
    purpose     VARCHAR(20) NOT NULL DEFAULT 'login',
    expires_at  TIMESTAMPTZ NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_otp_target ON otp_codes(target, purpose);
```

| 字段 | 说明 |
|------|------|
| `target` | 接收方：email 地址或 phone 号码 |
| `code` | 6 位数字，`SecureRandom` 生成 |
| `purpose` | `login` / `register` / `reset`，防止跨用途复用 |
| `expires_at` | 创建时间 + 10 分钟 |
| `used` | 验证成功后标记 true，防止重放 |

**查询逻辑**：`findTopByTargetAndPurposeAndUsedFalseOrderByCreatedAtDesc` — 取该 target+purpose 最新的未使用码，天然防止旧码干扰。

**设计决策：不绑定 user_id**

OTP 验证时用户可能还未创建（注册/首次登录场景），所以 `target` 是唯一标识。复用 `target + purpose` 组合索引保证查询性能。

#### 3.2.3 `user_progress` — 学习进度表

```sql
CREATE TABLE IF NOT EXISTS user_progress (
    user_id     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    data        JSONB NOT NULL DEFAULT '{}',
    version     INTEGER NOT NULL DEFAULT 3,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

| 字段 | 说明 |
|------|------|
| `user_id` | PK + FK，一对一关联 users |
| `data` | JSONB，存储完整的 `PersistedData` 对象 |
| `version` | 数据格式版本号，用于客户端迁移（当前 v3） |

**JSONB `data` 结构**（与前端 `PersistedData` 接口一致）：

```json
{
  "version": 3,
  "global": {
    "totalXP": 1550,
    "level": 5
  },
  "courses": {
    "cpp": {
      "completedCards": {
        "cpp-01-hello-world-c1": true,
        "cpp-01-hello-world-c2": true
      },
      "xp": 35,
      "quizScores": {
        "cpp-01-hello-world": 80
      },
      "nodePositions": {
        "cpp-01-hello-world": 3
      },
      "wrongCards": {
        "cpp-02-pointer-c3": true
      }
    }
  }
}
```

**设计决策：JSONB 而非关系型**

| 方案 | 优点 | 缺点 |
|------|------|------|
| JSONB（当前） | 与客户端数据结构 1:1 映射，零转换。一个 API 读写整棵树。迁移简单 | 不能按课程/卡片做 SQL 查询 |
| 关系型（多表） | 可以做排行榜、数据分析 | 读写需要多次 JOIN，API 复杂，客户端需要重组数据 |

当前用户量小、无排行榜需求，JSONB 是正确选择。将来如需数据分析，PostgreSQL JSONB 本身支持索引和查询（`data->'courses'->'cpp'->>'xp'`），无需迁移。

**设计决策：主键即 user_id**

`@Id @Column(name = "user_id")` 而非自增 ID。因为 `user_progress` 与 `users` 是一对一关系，直接用 user_id 做主键省去一次 JOIN。

#### 3.2.4 `refresh_tokens` — 刷新令牌表

```sql
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_jid   VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens(user_id);
```

| 字段 | 说明 |
|------|------|
| `token_jid` | JWT ID（JTI），JWT 标准字段 `jti`，用于精确吊销 |
| `expires_at` | 30 天后过期。删过期记录由定时任务或登录时懒清理 |
| `user_id` | 外键，`ON DELETE CASCADE` 删除用户时自动清理 |

**Token 轮转流程**（详见 §5.3）：

1. 登录 → 生成 Access Token + Refresh Token → Refresh Token 的 JTI 写入此表
2. 刷新 → 验证 JWT 签名 + 查此表确认未吊销 → 删除旧记录 → 签发新 Token → 写入新记录
3. 登出 → 删除此表中对应 JTI 的记录

**设计决策：一个用户可以有多条 refresh_tokens 记录**

允许多设备同时登录。每台设备持有自己的 Refresh Token（不同 JTI），互不影响。用户在某设备登出只删除该设备的 JTI。

---

## 4. API 接口设计

### 4.1 通用约定

| 项目 | 值 |
|------|-----|
| Base URL | `/api/v1` |
| 内容类型 | `application/json` |
| 认证方式 | `Authorization: Bearer <access_token>` |
| 成功响应码 | 200 OK |
| 认证失败 | 401 `{"error": "..."}` — `AuthService.AuthException` |
| 参数校验失败 | 400 `{"error": "field: message; ..."}` — `MethodArgumentNotValidException` |
| 服务器错误 | 500（Spring Boot 默认） |
| 空响应体 | 204 No Content（当前实现用 `{"ok": true}` 替代） |

### 4.1.1 Swagger UI（在线调试）

启动后端后访问 `http://localhost:8080/swagger-ui.html`，可以：

- 查看所有 API 接口列表
- 点 **Try it out** → 填参数 → **Execute**，直接在线调接口
- 看到请求格式和返回结果，不需要写 curl

所有接口分两组：**auth-controller**（认证，10 个）和 **progress-controller**（进度，3 个）。

### 4.2 公开端点（无需认证）

白名单在 `SecurityConfig.filterChain()` 中声明：

```java
.requestMatchers(
    "/api/v1/auth/register",
    "/api/v1/auth/login",
    "/api/v1/auth/send-otp",
    "/api/v1/auth/verify-otp",
    "/api/v1/auth/refresh",
    "/swagger-ui.html",
    "/swagger-ui/**",
    "/api-docs/**",
    "/v3/api-docs/**"
).permitAll()
```

#### 4.2.1 `POST /api/v1/auth/register` — 注册

**Controller**: `AuthController.register(RegisterRequest) → AuthService.register()`

```
Request:
{
  "email": "user@example.com",    // email 和 phone 至少一个
  "phone": null,                  // 或 "+8613800138000"
  "password": "123456"            // 至少 6 位
}

Response 200:
{
  "user": {
    "id": "a1b2c3d4-...",
    "email": "user@example.com",
    "phone": null,
    "displayId": null,
    "avatarUrl": null
  },
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "isNewUser": true
}
```

**校验规则**（`RegisterRequest`）：

```java
@Size(min = 6, message = "password must be at least 6 characters")
private String password;

@AssertTrue(message = "email or phone is required")
public boolean hasIdentity() {
    return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
}
```

**业务逻辑**（`AuthService.register()`）：

1. 检查 `email` 是否已存在 → `userRepo.existsByEmail()`
2. 检查 `phone` 是否已存在 → `userRepo.existsByPhone()`
3. `passwordEncoder.encode(password)` → BCrypt 哈希
4. 保存用户 → 生成 JWT → 保存 Refresh Token → 返回

#### 4.2.2 `POST /api/v1/auth/login` — 密码登录

```
Request:
{
  "email": "user@example.com",    // email 和 phone 二选一
  "phone": null,
  "password": "123456"
}

Response 200: 同上 register 响应
Response 401: {"error": "invalid credentials"}
```

**业务逻辑**（`AuthService.login()`）：

1. 根据 email 或 phone 查 `users` 表
2. `passwordEncoder.matches(password, user.getPasswordHash())` → BCrypt 验证
3. 密码哈希为 null（OTP 注册用户）→ 拒绝
4. 生成 JWT 对 → 返回

#### 4.2.3 `POST /api/v1/auth/send-otp` — 发送验证码

```
Request:
{
  "target": "user@example.com",   // 邮箱 或 "+8613800138000"
  "purpose": "login"              // "login" | "register" | "reset"
}

Response 200:
{"ok": true}
```

**业务逻辑**（`OtpService.sendCode()`）：

1. `SecureRandom.nextInt(1_000_000)` → 6 位数字字符串
2. 创建 `OtpCode` 实体：`target + code + purpose + expiresAt(now+10min)`
3. 保存到 `otp_codes` 表
4. 如果 target 包含 `@` → 通过 `JavaMailSender` 发邮件
5. 邮件发送失败不抛异常（开发环境 SMTP 可能未配置）

**邮件格式**：

```
To: user@example.com
Subject: CodeCard — Login Code
Body: Your verification code is: 482917

This code expires in 10 minutes.
```

**未实现**：手机短信（`if (!target.contains("@"))` 分支为空）。后续通过 `SmsProvider` 接口扩展。

#### 4.2.4 `POST /api/v1/auth/verify-otp` — 验证 OTP

```
Request:
{
  "target": "user@example.com",
  "code": "482917",
  "purpose": "login"
}

Response 200:
{
  "user": { "id": "...", "email": "...", ... },
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "isNewUser": true           // 首次验证 → 自动注册 → true
}
```

**业务逻辑**（`AuthService.verifyOtp()`）：

1. `OtpService.verifyCode(target, code, purpose)`：
   - 查最新未使用的 OTP 码
   - 检查是否过期（`expiresAt.isBefore(now)`）
   - 检查 code 是否匹配
   - 匹配成功 → `setUsed(true)` → 保存
2. 根据 target 查找或创建用户：
   - 包含 `@` → `userRepo.findByEmail(target)`
   - 否则 → `userRepo.findByPhone(target)`
   - 不存在 → 创建新用户（只填 email 或 phone，`password_hash = null`）
   - `isNewUser = (user 是新建的)`
3. 生成 JWT → 返回

#### 4.2.5 `POST /api/v1/auth/refresh` — 刷新令牌

```
Request:
{"refreshToken": "eyJhbGciOiJIUzI1NiJ9..."}

Response 200: 同上 register/login 响应
Response 401: {"error": "invalid refresh token"}
Response 401: {"error": "token revoked"}
Response 401: {"error": "token expired"}
```

**业务逻辑**（详见 §5.3 Token 轮转）：

1. 验证 JWT 签名
2. 提取 JTI → 查 `refresh_tokens` 表确认存在
3. 检查 `expires_at` 是否过期
4. **删除旧记录** → 签发新 Access + Refresh Token → 保存新 JTI
5. 返回新 Token 对

### 4.3 认证端点（需要 JWT）

所有未列在 4.2 白名单中的端点都需要：`Authorization: Bearer <access_token>`。

请求经过 `JwtAuthFilter`：

1. 提取 Header → 去掉 "Bearer " 前缀
2. `jwtService.extractUserId(token)` → 解析 JWT
3. `userRepository.findById(userId)` → 验证用户仍存在
4. 构造 `UsernamePasswordAuthenticationToken(user, null, [])` → 写入 SecurityContext
5. Controller 通过 `@AuthenticationPrincipal User user` 获取当前用户

#### 4.3.1 `GET /api/v1/auth/me` — 获取当前用户

```
Response 200:
{
  "id": "a1b2c3d4-...",
  "email": "user@example.com",
  "phone": null,
  "displayId": "CodeMaster",
  "avatarUrl": null
}
```

#### 4.3.2 `PUT /api/v1/auth/profile` — 更新资料

```
Request:
{
  "displayId": "NewName",           // 可选，只传要改的字段
  "avatarUrl": "https://..."        // 可选
}

Response 200: 同上 me 响应，含更新后的字段
```

**当前实现**：直接更新 `users` 表字段。`avatarUrl` 需要上层先上传图片到 OSS/Storage 获得 URL 后再调用此接口。

#### 4.3.3 `POST /api/v1/auth/set-password` — 设置/修改密码

```
Request:
{"password": "newpassword123"}      // 至少 6 位

Response 200:
{"ok": true}
```

**注意**：此端点不做旧密码验证。密码重置场景（forgot password）的前置步骤是 OTP 验证，验证通过后再调此端点设置新密码。

#### 4.3.4 `POST /api/v1/auth/logout` — 登出

```
Request:
{"refreshToken": "eyJ..."}          // 可选，不传也可以

Response 200:
{"ok": true}
```

**业务逻辑**：从 `refresh_tokens` 表删除对应 JTI，使该 Refresh Token 失效。其他设备的 Token 不受影响。

### 4.4 进度端点

#### 4.4.1 `GET /api/v1/progress` — 获取进度

```
Response 200:
{
  "data": {
    "version": 3,
    "global": { "totalXP": 1550, "level": 5 },
    "courses": {
      "cpp": {
        "completedCards": { "cpp-01-hello-world-c1": true },
        "xp": 35,
        "quizScores": {},
        "nodePositions": {},
        "wrongCards": {}
      }
    }
  },
  "version": 3,
  "updatedAt": "2026-05-28T12:00:00Z",
  "merged": false
}
```

**无进度时**：返回 `{"data": {}, "version": 3, "merged": false}`

#### 4.4.2 `PUT /api/v1/progress` — 上传进度

```
Request:
{
  "data": {                        // 完整的 PersistedData 对象
    "version": 3,
    "global": { "totalXP": 1550, "level": 5 },
    "courses": { ... }
  },
  "version": 3
}

Response 200:
{
  "data": { ... },                 // 回显刚写入的数据
  "version": 3,
  "updatedAt": "2026-05-28T12:01:00Z",
  "merged": false
}
```

**实现**（`ProgressService.upsertProgress()`）：

```java
UserProgress progress = progressRepo.findById(userId).orElse(new UserProgress());
progress.setUserId(userId);
progress.setData(mapper.writeValueAsString(req.getData()));  // Map → JSON String
progress.setVersion(req.getVersion());
progress.setUpdatedAt(Instant.now());
progressRepo.save(progress);
```

Hibernate 自动判断 INSERT 还是 UPDATE（`save()` 方法在 entity 有 id 时 merge）。

#### 4.4.3 `POST /api/v1/progress/sync` — 同步进度

```
Request: 同 PUT /progress
Response 200: 同 PUT /progress，但 merged=true 表示返回的是服务端数据
```

**业务逻辑**（`ProgressService.syncProgress()`）：

1. 查 `user_progress` 表
2. 无远程数据 → `upsertProgress()` 保存客户端数据，`merged = false`
3. 有远程数据 → 直接返回远程数据，`merged = true`，**不写入**

客户端拿到 `merged: true` 后自行执行合并算法（见 §6.2），再调 `PUT /progress` 写入合并结果。

**设计决策：服务端不做合并**

合并逻辑（max(xp), union(completedCards), max(quizScores)）放在客户端 `syncEngine.ts` 中有三个原因：
- 逻辑与本地计算一致（`calcLevel` 在前端）
- 避免服务端需要理解业务语义
- 服务端保持简单，仅做 JSONB 存取

### 4.5 错误响应格式

所有错误统一返回：

```json
{"error": "human-readable message"}
```

| HTTP 状态 | 触发条件 | 示例消息 |
|-----------|---------|---------|
| 400 | `@Valid` 校验失败 | `"email: must be a well-formed email address; password: password must be at least 6 characters"` |
| 401 | 认证失败 | `"invalid credentials"` |
| 401 | Token 过期/无效 | `"invalid refresh token"` |
| 401 | Token 已吊销 | `"token revoked"` |
| 401 | 无 Token | Spring Security 默认 401（无响应体，需改进） |
| 409 | 重复注册 | `"email already registered"` |

**注意**：无 Token 时的 401 是 Spring Security 默认行为，不经过 `GlobalExceptionHandler`。生产环境建议加 `AccessDeniedHandler` 和 `AuthenticationEntryPoint` 统一响应格式。

---

## 5. 认证与安全

### 5.1 JWT 结构

#### Access Token

```json
Header:  {"alg": "HS256"}
Payload: {
  "sub": "a1b2c3d4-e5f6-...",    // userId (UUID)
  "iat": 1716900000,              // 签发时间 (epoch second)
  "exp": 1716900900,              // 过期时间 = iat + 900s (15分钟)
  "type": "access"
}
```

生成代码：

```java
Jwts.builder()
    .subject(userId.toString())
    .issuedAt(Date.from(now))
    .expiration(Date.from(now.plusMillis(accessExpirationMs)))  // 900000ms
    .claim("type", "access")
    .signWith(key)  // HMAC-SHA256
    .compact();
```

#### Refresh Token

```json
Header:  {"alg": "HS256"}
Payload: {
  "sub": "a1b2c3d4-e5f6-...",
  "iat": 1716900000,
  "exp": 1719492000,              // = iat + 2592000s (30天)
  "jti": "f9e8d7c6-b5a4-...",    // JWT ID，存 refresh_tokens.token_jid
  "type": "refresh"
}
```

**配置值**（`application.yml`）：

```yaml
jwt:
  secret: ${JWT_SECRET:}           # Base64 编码，至少 256 bits
  access-expiration-ms: 900000     # 15 分钟
  refresh-expiration-ms: 2592000000 # 30 天
```

### 5.2 签名密钥

`JwtService` 构造时从配置读取 `jwt.secret`，通过 `Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))` 生成 `SecretKey`。

**生成命令**：

```bash
openssl rand -base64 64
# 输出示例：dEVzdC1zZWNyZXQta2V5LTEyMzQ1Njc4OTAtYWJjZGVmZ2hpamtsbW5vcC1taW4tMjU2LWJpdHM=
```

**要求**：至少 256 bits（32 字节），Base64 编码后至少 44 字符。HMAC-SHA256 接受任意长度，不足不报错但安全性降低。

**存储**：环境变量 `JWT_SECRET`，不入 git。`application.yml` 中 `${JWT_SECRET:}` 默认空——生产环境不设即启动失败。

### 5.3 Token 轮转流程

```
┌─ 登录/注册 ──────────────────────────────────────────────────┐
│                                                               │
│  1. AuthService.buildAuthResponse(user)                       │
│     ├── accessToken  = jwtService.generateAccessToken(id)     │
│     ├── refreshToken = jwtService.generateRefreshToken(id)    │
│     ├── new RefreshToken { userId, tokenJid=jti, expiresAt }  │
│     └── refreshTokenRepo.save(rt)                             │
│                                                               │
└───────────────────────────────────────────────────────────────┘

┌─ Token 刷新 ─────────────────────────────────────────────────┐
│                                                               │
│  客户端: api.ts → POST /auth/refresh { refreshToken }         │
│                                                               │
│  服务端: AuthService.refresh(tokenStr)                        │
│     ├── jwtService.isTokenValid(tokenStr)   // 签名+过期      │
│     ├── jwtService.extractJid(tokenStr)     // 取 JTI         │
│     ├── refreshTokenRepo.findByTokenJid(jid) // 查DB确认未吊销│
│     ├── saved.getExpiresAt().isBefore(now)  // DB过期检查     │
│     ├── refreshTokenRepo.delete(saved)      // 删旧记录       │
│     └── buildAuthResponse(user)             // 发新Token对    │
│                                                               │
│  客户端: api.ts 收到新Token → setTokens() → 重试原请求       │
│                                                               │
└───────────────────────────────────────────────────────────────┘

┌─ 登出 ───────────────────────────────────────────────────────┐
│                                                               │
│  客户端: authStore.logout()                                   │
│     ├── POST /auth/logout { refreshToken }                    │
│     └── clearTokens() → AsyncStorage.removeItem()             │
│                                                               │
│  服务端: AuthService.logout(tokenStr)                         │
│     └── refreshTokenRepo.findByTokenJid(jid).ifPresent(       │
│             refreshTokenRepo::delete                          │
│         )                                                     │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

**为什么 Token 轮转重要**：

- 旧 Refresh Token 一旦泄露，攻击者用它换新 Token。轮转后旧 Token 被删除 → 攻击者的 Token 失效 → **谁先刷新谁存活**。
- 如果合法用户和攻击者同时刷新：攻击者先到→合法用户收到"token revoked"→重新登录。用户发现异常→安全事件。

### 5.4 密码加密

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

BCrypt 特性：
- 自动加盐（每个密码独立随机盐）
- 工作因子默认 10（2^10 次迭代）
- 输出格式：`$2a$10$...`（与 Supabase Auth 完全兼容）

**迁移兼容性**：Supabase 的 `auth.users` 表也使用 BCrypt `$2a$` 前缀。从 Supabase 导出的密码哈希可直接写入 `users.password_hash`，用户无需重置密码。

### 5.5 SecurityConfig 详解

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // 1. 禁用 CSRF — REST API 用 JWT，无 Cookie，不需要 CSRF 保护
        .csrf(csrf -> csrf.disable())

        // 2. 无状态会话 — 不创建 HttpSession，每个请求独立验证 JWT
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        // 3. 白名单 — 这 5 个端点无需 Token
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/auth/send-otp",
                "/api/v1/auth/verify-otp",
                "/api/v1/auth/refresh"
            ).permitAll()
            .anyRequest().authenticated()   // 其他全部需要 JWT
        )

        // 4. JWT Filter 在 UsernamePasswordAuthenticationFilter 之前执行
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

### 5.6 CORS 配置

```java
config.setAllowedOrigins(List.of("*"));    // 开发阶段全放通
config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
config.setAllowedHeaders(List.of("*"));
```

**生产环境应限制**：`setAllowedOrigins(List.of("codecard://"))` 或具体域名。

React Native App 通过 `fetch` 发请求不受浏览器 CORS 限制——这是给 Web 端或调试工具用的。

### 5.7 JwtAuthFilter 流程

```
请求进入
  │
  ├─ Header "Authorization" 缺失、不以 "Bearer " 开头、或长度 < 10？
  │   └─ YES → filterChain.doFilter()（继续，由 SecurityConfig 拦截 → 401 JSON）
  │
  ├─ 提取 token = header.substring(7)
  │
  ├─ jwtService.isTokenValid(token)  → 签名验证 + 过期检查
  │   └─ 失败 → doFilter() → 401 JSON
  │
  ├─ jwtService.extractType(token) = "refresh"？
  │   └─ YES → doFilter() → 401 JSON（禁止将 refresh token 当作 access token 使用）
  │
  ├─ jwtService.extractUserIdStr(token)
  │   └─ 解析成功 → 直接将 userId 字符串注入 SecurityContext
  │                （无需 DB 查询，JWT 自包含身份信息）
  │
  └─ Controller 通过 @AuthenticationPrincipal String userId 获取当前用户 ID
```

**设计决策：不查数据库**

JWT 是自包含凭证，签名验证通过即证明身份。不再每次请求查 `users` 表验证用户存在性——用户删除后持有未过期 Token 也能访问，但 15 分钟窗口内用户不会消失。去掉了每次请求的 DB 查询，减少无谓 IO。

**设计决策：拒绝 refresh token 作 access token**

`Jwts.builder().claim("type", "access")` 在生成时标记 token 类型。Filter 检查 `type` claim——如果为 `"refresh"`（从 `Authorization` header 传入）则直接拒绝。这关闭了一个安全漏洞：攻击者拿到 refresh token 后直接调用业务接口。

---

## 6. 进度同步策略

### 6.1 数据流

```
┌─ App 启动 ───────────────────────────────────────────────────┐
│                                                               │
│  authStore.initialize()                                       │
│    ├── loadTokens() → AsyncStorage 读 Token                   │
│    ├── apiGet('/auth/me') → 验证 Token 有效                   │
│    └── syncOnLogin(userId) → 开始同步                         │
│                                                               │
└───────────────────────────────────────────────────────────────┘

┌─ syncOnLogin(userId) ────────────────────────────────────────┐
│                                                               │
│  1. GET /progress                                             │
│     ├── 远程无数据 → uploadProgress(本地数据) → 结束          │
│     └── 远程有数据 → 进入合并                                  │
│                                                               │
│  2. 合并算法（客户端 syncEngine.ts）                          │
│     ├── 遍历远程 courses                                      │
│     │   ├── 本地无此课程 → 直接用远程                          │
│     │   ├── 本地已重置 (completedCards空 && xp=0) → 跳过远程  │
│     │   └── 双端都有 → 按字段合并：                            │
│     │       ├── completedCards: UNION (两个 set 的并集)       │
│     │       ├── wrongCards: UNION                             │
│     │       ├── xp: MAX(local.xp, remote.xp)                 │
│     │       ├── quizScores: 逐 key 取 MAX                     │
│     │       └── nodePositions: 逐 key 取 MAX                  │
│     │                                                         │
│  3. 重算 global.totalXP = SUM(course.xp)                      │
│     global.level = calcLevel(totalXP)                         │
│                                                               │
│  4. useProgressStore.setState(merged) → 更新 Zustand          │
│                                                               │
│  5. uploadProgress(userId) → PUT /progress → 回写合并结果     │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

### 6.2 合并算法详解

`syncOnLogin()` 是客户端函数（`src/store/syncEngine.ts`），服务端只负责数据存取，不参与合并。

```typescript
// 核心合并逻辑
for (const [cid, rp] of Object.entries(remoteData.courses ?? {})) {
  const lp = mergedCourses[cid];

  if (!lp) {
    // 情况 1：仅远程有此课程 → 直接用远程
    mergedCourses[cid] = rp;
    continue;
  }

  if (Object.keys(lp.completedCards ?? {}).length === 0 && (lp.xp ?? 0) === 0) {
    // 情况 2：本地已重置（无完成卡片+零XP）
    // → 跳过远程合并，防止重置操作被恢复
    continue;
  }

  // 情况 3：双端都有 → 字段级合并
  mergedCourses[cid] = {
    completedCards: { ...lp, ...rp },           // 并集
    wrongCards:     { ...lp.wrongCards, ...rp.wrongCards },  // 并集
    xp:             Math.max(lp.xp, rp.xp),     // 取最大
    quizScores:     mergeMax(lp.quizScores, rp.quizScores), // 逐key取最大
    nodePositions:  mergeMax(lp.nodePositions, rp.nodePositions), // 逐key取最大
  };
}
```

**为什么用 MAX 而非求和**：XP 每次学习加固定值（5 或 10），如果双端各自学习同一张卡后同步，求和会导致 XP 翻倍。MAX 确保 XP 不会超过实际学习所得。

**为什么 completedCards 用 UNION**：一张卡片在设备 A 完成、设备 B 也完成——并集后仍然只有一条记录（`Record<string, true>`），不影响结果。

**重置保护**：`completedCards` 为空且 `xp === 0` 时跳过远程合并——防止用户在设备 A 重置课程后，同步又把设备 B 的旧数据恢复回来。这是一个业务级别的冲突解决策略。

### 6.3 uploadProgress — 回写

```typescript
export async function uploadProgress(userId: string): Promise<void> {
  const local = useProgressStore.getState();
  const data = { version: local.version, global: local.global, courses: local.courses };
  await apiPut('/progress', { data, version: local.version });
}
```

每次同步完成后回写合并结果。上传失败不阻塞 UI（`console.warn`）。

### 6.4 潜在冲突场景

| 场景 | 当前行为 | 风险 |
|------|---------|------|
| 双设备同时学习同一门课 | MAX(xp) 合并，completedCards 取并集 | 低——学习进度只增不减 |
| 设备 A 重置课程，设备 B 学了很多 | 重置后本地 xp=0，跳过远程合并 → 回写空数据覆盖远程 | **中等**——设备 B 的进度丢失 |
| 设备 A 离线学 5 张卡，设备 B 离线学另外 5 张 | 合并后 10 张都完成 | 低——预期行为 |
| 同一张卡在双设备完成 | completedCards 并集不重复 | 低——正常工作 |

**重置覆盖问题**：当前实现中，`syncOnLogin` 在 "本地已重置" 时跳过远程合并，但随后的 `uploadProgress` 仍然会用本地空数据全量覆盖远程数据。这是因为 `uploadProgress` 不检查合并状态。修复方案：在 `uploadProgress` 前检查是否有本地重置的课程，对这部分用 MAX 而非覆盖。

---

## 7. 前端对接层

### 7.1 `src/lib/api.ts` — HTTP 客户端

**核心设计**：单例模式持有 Token，401 自动刷新，调用方无感知。

```
apiGet / apiPost / apiPut
  └─ request(method, path, body)
       ├─ 拼接 URL: BASE_URL + path
       ├─ 附加 Header: Authorization: Bearer <accessToken>
       ├─ fetch()
       ├─ 200 → res.json()
       ├─ 401 → refreshAccessToken()
       │        ├─ POST /auth/refresh { refreshToken }
       │        ├─ 成功 → setTokens() → 重试原请求
       │        └─ 失败 → clearTokens() → throw ApiError(401)
       └─ 其他错误 → throw ApiError(status, body)
```

**Token 持久化**：

```typescript
const ACCESS_KEY = 'codecard-access-token';
const REFRESH_KEY = 'codecard-refresh-token';

export function setTokens(access, refresh) {
  // 内存 + AsyncStorage 双写
  accessToken = access;
  refreshToken = refresh;
  AsyncStorage.setItem(ACCESS_KEY, access);
  AsyncStorage.setItem(REFRESH_KEY, refresh);
}

export async function loadTokens() {
  // App 启动时从 AsyncStorage 恢复
  const [a, r] = await Promise.all([
    AsyncStorage.getItem(ACCESS_KEY),
    AsyncStorage.getItem(REFRESH_KEY),
  ]);
  if (a && r) { accessToken = a; refreshToken = r; return { accessToken: a, refreshToken: r }; }
  return null;
}
```

**BASE_URL**：`process.env.EXPO_PUBLIC_API_URL ?? 'http://10.0.2.2:8080/api/v1'`

- Android 模拟器：`10.0.2.2` 映射宿主机 localhost
- 真机开发：设为电脑局域网 IP `http://192.168.x.x:8080/api/v1`
- 生产环境：`https://api.codecard.app/api/v1`

### 7.2 `src/store/authStore.ts` — 认证状态

**接口不变**（与 Supabase 版本完全相同的 TypeScript 接口）：

```typescript
interface AuthStore {
  user: User | null;
  isLoggedIn: boolean;
  isMounted: boolean;

  initialize:     () => Promise<void>;
  loginByEmail:   (email, password) => Promise<{error?}>;
  registerByEmail:(email, password) => Promise<{error?, info?}>;
  sendEmailOtp:   (email) => Promise<{error?}>;
  verifyEmailOtp: (email, token) => Promise<{error?}>;
  sendPhoneOtp:   (phone) => Promise<{error?}>;
  verifyPhoneOtp: (phone, token) => Promise<{error?, isNewUser?}>;
  setPassword:    (password) => Promise<{error?}>;
  logout:         () => Promise<void>;
  setDisplayId:   (displayId) => Promise<void>;
  updateAvatar:   (uri) => void;               // 本地 AsyncStorage 持久化
}
```

**映射关系**（Supabase → Spring Boot）：

| authStore 方法 | HTTP 调用 | 说明 |
|---------------|-----------|------|
| `initialize()` | `loadTokens()` → `GET /auth/me` | 恢复 Token + 验证有效 + 触发 syncOnLogin |
| `loginByEmail(email, pwd)` | `POST /auth/login {email, password}` | 成功后 setTokens + syncOnLogin |
| `registerByEmail(email, pwd)` | `POST /auth/register {email, password}` | 直接登录（无邮件确认流程） |
| `sendEmailOtp(email)` | `POST /auth/send-otp {target, purpose:"login"}` | |
| `verifyEmailOtp(email, code)` | `POST /auth/verify-otp {target, code, purpose:"login"}` | isNewUser 由服务端判断 |
| `sendPhoneOtp(phone)` | `POST /auth/send-otp {target, purpose:"login"}` | |
| `verifyPhoneOtp(phone, code)` | `POST /auth/verify-otp {target, code, purpose:"login"}` | |
| `setPassword(pwd)` | `POST /auth/set-password {password}` | 需要已登录（JWT） |
| `logout()` | `POST /auth/logout` + `clearTokens()` | |
| `setDisplayId(id)` | `PUT /auth/profile {displayId}` | 乐观更新 → fire-and-forget |
| `updateAvatar(uri)` | 仅本地 AsyncStorage | 服务端上传后续实现 |

### 7.3 `User` 接口适配

```typescript
// 服务端返回 (ApiUser)
{ id, email?, phone?, displayId?, avatarUrl? }

// 客户端 User 接口（与 Supabase 版本完全一致）
interface User {
  id: string;
  phone?: string;
  email?: string;
  name?: string;      // 当前未使用，预留
  avatar?: string;     // 本地 URI + AsyncStorage 兜底
  displayId?: string;
}

// 适配函数
function toUser(api: ApiUser): User {
  return {
    id: api.id,
    email: api.email,
    phone: api.phone,
    name: undefined,
    avatar: api.avatarUrl,    // avatarUrl → avatar
    displayId: api.displayId,
  };
}
```

### 7.4 服务端 `@AuthenticationPrincipal`

Controller 方法签名示例：

```java
@GetMapping("/me")
public ResponseEntity<UserProfile> me(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(authService.getProfile(user.getId()));
}
```

Spring Security 自动从 SecurityContext 取出 `UsernamePasswordAuthenticationToken` 的 principal（即 `JwtAuthFilter` 中注入的 `User` 实体），Controller 直接拿到当前用户的 JPA 实体。

---

## 8. 配置与部署

### 8.1 环境变量

| 变量 | 用途 | 默认值 | 必需 |
|------|------|--------|------|
| `DB_URL` | PostgreSQL 连接 | `jdbc:postgresql://localhost:5432/codecard` | 否 |
| `DB_USER` | 数据库用户 | `codecard` | 否 |
| `DB_PASSWORD` | 数据库密码 | `codecard` | 否 |
| `SMTP_HOST` | 邮件服务器 | `smtp.resend.com` | 否 |
| `SMTP_PORT` | 邮件端口 | `587` | 否 |
| `SMTP_USER` | 邮件账号 | （空） | 开发阶段否 |
| `SMTP_PASS` | 邮件密码 | （空） | 开发阶段否 |
| `JWT_SECRET` | JWT 签名密钥 (Base64) | （空） | **生产必需** |
| `PORT` | 服务端口 | `8080` | 否 |

### 8.2 开发环境启动

```bash
# 1. 启动 PostgreSQL
pg_ctl start  # 或用 Windows 服务

# 2. 创建数据库 + 执行 schema
createdb codecard
psql codecard < src/main/resources/schema.sql

# 3. 设置 JWT 密钥
export JWT_SECRET=$(openssl rand -base64 64)

# 4. 启动后端
cd backend
./mvnw spring-boot:run
# 或 Windows:
run.bat

# 5. 启动 App
cd ..
npm start
```

### 8.3 测试配置

`src/test/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:codecard-test
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop          # 自动建表，测试结束销毁
    database-platform: org.hibernate.dialect.H2Dialect
  mail:
    host: localhost
    port: 25

jwt:
  secret: dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLW1pbmltdW0tMjU2LWJpdHM=
  access-expiration-ms: 900000
  refresh-expiration-ms: 2592000000
```

H2 内存数据库 + `ddl-auto: create-drop` 确保测试完全隔离，不依赖 PostgreSQL。

### 8.4 生产部署检查清单

- [ ] `JWT_SECRET` 设为 64 字节随机 Base64
- [ ] `DB_URL` / `DB_USER` / `DB_PASSWORD` 指向生产 PostgreSQL
- [ ] `SMTP_HOST` / `SMTP_USER` / `SMTP_PASS` 配置真实邮件服务
- [ ] `CorsConfig.setAllowedOrigins` 限制为生产域名
- [ ] 启用 HTTPS（Nginx 反向代理或 Spring Boot 直接配置 SSL）
- [ ] `application.yml` 中 JPA `ddl-auto` 保持 `validate`（生产不要 `create`/`update`）
- [ ] 添加 `AuthenticationEntryPoint` 统一 401 响应格式
- [ ] 配置日志级别：`logging.level.com.codecard: INFO`

---

## 9. 从 Supabase 迁移

### 9.1 数据导出

```sql
-- 在 Supabase SQL Editor 中执行

-- 导出 users（密码哈希兼容）
COPY (SELECT id, email, phone, NULL as password_hash, NULL as display_id, NULL as avatar_url, created_at, updated_at FROM auth.users) TO '/tmp/users.csv' CSV HEADER;
-- 注意：password_hash 在 Supabase 中是 encrypted_password 列

-- 导出 user_progress
COPY (SELECT user_id, data, COALESCE(data->>'version', '3')::int as version, updated_at FROM public.user_progress) TO '/tmp/progress.csv' CSV HEADER;
```

### 9.2 数据导入

```sql
-- 导入到新 PostgreSQL
\copy users FROM '/tmp/users.csv' CSV HEADER;
\copy user_progress FROM '/tmp/progress.csv' CSV HEADER;
```

### 9.3 密码哈希兼容性

Supabase 使用 `crypt()` 函数和 `bf` (Blowfish/BCrypt) 算法，输出格式 `$2a$06$...`。Spring Security 的 `BCryptPasswordEncoder` 输出 `$2a$10$...`。两者都遵循 BCrypt 标准：

- `$2a$` 版本标识兼容
- `$06$` vs `$10$` 是工作因子差异（2^6 vs 2^10 次迭代），Spring Security 验证时会自动适配——任何 `$2a$` 前缀的哈希都能通过 `passwordEncoder.matches()`。

**结论**：Supabase 的密码哈希可以直接写入 `users.password_hash` 列，用户无需重置密码。

---

## 10. 扩展路线

### 10.1 近期（v1.1）

- [ ] **短信验证码**：新增 `SmsProvider` 接口，实现阿里云短信（~0.045 元/条）
- [ ] **头像上传**：`POST /api/v1/user/avatar` → 保存到本地文件系统或阿里云 OSS
- [ ] **统一 401 格式**：添加 `AuthenticationEntryPoint` 和 `AccessDeniedHandler`
- [ ] **单元测试**：AuthService、ProgressService、OtpService 的 JUnit 测试
- [ ] **集成测试**：`@SpringBootTest` + MockMvc 端到端测试

### 10.2 中期（v1.2）

- [ ] **排行榜**：基于 `user_progress.data->'global'->>'totalXP'` 的 JSONB 查询排序
- [ ] **学习统计**：每日活跃用户、完成卡片数统计
- [ ] **课程服务端下发**：`GET /api/v1/courses` + `GET /api/v1/courses/{id}`，配合客户端异步加载
- [ ] **定时清理过期 OTP**：`@Scheduled` 每天清理 `expires_at < now()` 且 `used = false` 的记录
- [ ] **定时清理过期 Refresh Token**：懒清理 + 定时兜底

### 10.3 远期（v2.0）

- [ ] **多语言**：`Accept-Language` header → i18n 错误消息
- [ ] **Web 管理后台**：React/Vue 前端 + 相同 API
- [ ] **消息推送**：Firebase Cloud Messaging / 极光推送
- [ ] **社交功能**：好友、学习圈子
- [ ] **微服务拆分**：Auth Service + Progress Service 独立部署（当前单体足够支撑到 10 万用户）

---

## 附录 A：客户端-服务端数据流全视图

```
┌──────────────────────────────────────────────────────────────┐
│                      用户操作                                 │
├────────────┬────────────┬────────────┬───────────┬───────────┤
│  注册/登录  │  学习卡片   │  同步进度   │  修改资料  │  登出    │
└─────┬──────┴──────┬─────┴──────┬─────┴─────┬─────┴────┬──────┘
      │             │            │           │          │
      ▼             ▼            ▼           ▼          ▼
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│authStore │ │useProg-  │ │syncEngine│ │authStore │ │authStore │
│.login()  │ │ressStore │ │.manual-  │ │.setDis-  │ │.logout() │
│.register │ │.reward-  │ │Sync()    │ │playId()  │ │          │
│.verifyOtp│ │Card()    │ │          │ │          │ │          │
└────┬─────┘ └──────────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
     │                         │            │            │
     ▼                         ▼            ▼            ▼
┌──────────┐            ┌──────────┐ ┌──────────┐ ┌──────────┐
│ api.ts   │            │ api.ts   │ │ api.ts   │ │ api.ts   │
│ POST     │            │ PUT      │ │ PUT      │ │ POST     │
│ /auth/*  │            │ /progress│ │ /auth/   │ │ /auth/   │
│          │            │ /sync    │ │ profile  │ │ logout   │
└────┬─────┘            └────┬─────┘ └────┬─────┘ └────┬─────┘
     │                       │            │            │
     ▼                       ▼            ▼            ▼
┌──────────────────────────────────────────────────────────────┐
│                   Spring Boot 后端                            │
│                                                              │
│  AuthController          ProgressController                  │
│  ├─ /register            ├─ GET  /progress                   │
│  ├─ /login               ├─ PUT  /progress                   │
│  ├─ /send-otp            └─ POST /progress/sync               │
│  ├─ /verify-otp                                              │
│  ├─ /refresh                                                 │
│  ├─ /me                                                     │
│  ├─ /profile                                                 │
│  ├─ /set-password                                            │
│  └─ /logout                                                  │
└──────────────────────────────────────────────────────────────┘
```

## 附录 B：Card ID 格式规范

```
{courseId}-{两位模块序号}-{topic}-c{序号}

示例：
  cpp-01-hello-world-c1    C++ 课程 · 01基础模块 · hello-world节点 · 第1张卡
  cpp-03-static-members-c7 C++ 课程 · 03OOP模块 · static-members节点 · 第7张卡

模块序号：两位数字，对应课程目录名中的序号（01-basics → 01）
序号：从 1 开始递增，同一节点内不可重复
```

---

## 附录 C：变更日志 (Changelog)

### v1.1.0 (2026-05-28) — 代码审查 + 端到端修复

**Critical 修复**

| # | 文件 | 变更 |
|---|------|------|
| 1 | `authStore.ts` | `logout()` 不再传 null，改为导出并用 `getRefreshToken()` 获取真实 token 传给服务端吊销 |

**High 修复**

| # | 文件 | 变更 |
|---|------|------|
| 2 | `LoginRequest.java` | `password` 加 `@NotBlank` 校验，防止 NPE |
| 3 | `JwtAuthFilter.java` | 拒绝 `type="refresh"` 的 token 当作 access token；去掉 UserRepository 依赖改为注入 userId 字符串 |
| 4 | `RefreshTokenRepository.java` | 删除未使用的 `deleteByUserId()`（缺少 @Transactional 会在运行时抛异常） |

**Medium 修复**

| # | 文件 | 变更 |
|---|------|------|
| 5 | `OtpService.java` | 新增日志 (`SLF4J`)；邮件失败时打印验证码到日志；同一 target+purpose 60s 频率限制 |
| 6 | `SecurityConfig.java` | 新增 `AuthenticationEntryPoint` 返回 JSON `{"error":"authentication required"}` 替代默认 HTML 401 |
| 7 | `GlobalExceptionHandler.java` | 新增 `@ExceptionHandler(Exception.class)` → JSON 500 响应 |
| 8 | `ProgressSyncRequest.java` | `data` 加 `@NotNull`，`version` 加 `@Positive` 校验 |

**Low / 优化**

| # | 文件 | 变更 |
|---|------|------|
| 9 | `JwtService.java` | 新增 `extractUserIdStr()`、`extractType()`、`getRefreshExpirationMs()` |
| 10 | `AuthService.java` | `refreshToken` DB 过期时间改用 `jwtService.getRefreshExpirationMs()` 统一来源；移除 `verifyOtp()` 中重复的 `setIsNewUser` |
| 11 | `AuthController.java` | `@AuthenticationPrincipal User` → `String userId`（配合 JwtAuthFilter 改动） |
| 12 | `ProgressController.java` | 同上 |
| 13 | `api.ts` | 新增 `tryParseError()` 提取服务端 JSON `error` 字段，用户弹窗不再显示原始 JSON；新增 `getRefreshToken()` 导出；删除未使用的 `setOnTokenRefreshed` |
| 14 | `authStore.ts` | `logout()` 传入真实 refreshToken |

**端到端耦合度验证**

| 检查项 | 结论 |
|--------|------|
| 9 组 API 数据契约 | 全部一致，唯一字段名差异 `avatarUrl→avatar` 有适配函数 |
| 错误传递链 | `tryParseError()` 修复了原始 JSON 泄漏到用户弹窗的问题 |
| Token 生命周期 | 生成→存储→使用→刷新→吊销 完整闭环 |
| 进度同步流程 | 客户端合并方案正确，服务端零业务耦合 |
| 死代码 | `setOnTokenRefreshed` 已删除；`POST /progress/sync` 保留作为未来钩子 |

---

*文档结束*

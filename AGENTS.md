# CodeCard Backend

Spring Boot 3.4.1 REST API，为 CodeCard 前端提供认证 + 进度同步。

前端仓库：`https://github.com/fanwenjing729/CodeCard`

## Tech stack

| 层 | 技术 |
|---|------|
| 框架 | Spring Boot 3.4.1 + Java 21 |
| 安全 | Spring Security + JWT (HMAC-SHA) + BCrypt |
| 数据库 | PostgreSQL (H2 for tests) |
| ORM | Spring Data JPA |
| 验证码 | 邮箱 OTP (JavaMailSender) |
| 限流 | Bucket4j 8.10 (令牌桶) |
| API 文档 | Swagger / SpringDoc 2.7 (http://localhost:8080/swagger-ui.html) |
| 测试 | JUnit 5 + SpringBootTest + AssertJ |

## Package structure

```
com.codecard/
├── CodeCardApplication.java     ← @SpringBootApplication
├── auth/                        ← 认证模块
│   ├── AuthController.java      ← /api/v1/auth/*
│   ├── AuthService.java         ← 注册/登录/OTP/登出逻辑
│   ├── AuthException.java       ← 认证异常
│   ├── OtpService.java          ← 邮箱验证码发送
│   ├── OtpCode.java             ← OTP 实体
│   ├── OtpCodeRepository.java
│   ├── RefreshToken.java        ← RefreshToken 实体
│   ├── RefreshTokenRepository.java
│   └── dto/                     ← 7 个 DTO (Register/Login/OtpSend/...)
├── config/                      ← 基础设施
│   ├── SecurityConfig.java      ← Filter 链 + CORS + BCrypt
│   ├── JwtService.java          ← JWT 签发/解析/校验
│   ├── JwtAuthFilter.java       ← 从 Authorization header 提取 JWT
│   ├── RateLimitFilter.java     ← Bucket4j 令牌桶限流
│   ├── RateLimitProperties.java ← 限流配置 (application.yml 驱动)
│   ├── TraceIdFilter.java      ← 请求追踪 (UUID traceId + JSON 日志)
│   ├── CorsConfig.java          ← CORS (已合并到 SecurityConfig)
│   ├── CleanupScheduler.java    ← OTP + RefreshToken 定期清理
│   └── GlobalExceptionHandler.java ← 统一异常 → JSON
├── progress/                    ← 进度同步模块
│   ├── ProgressController.java  ← /api/v1/progress/*
│   ├── ProgressService.java     ← 版本冲突解决 + CRUD
│   ├── UserProgress.java        ← JSONB 实体
│   ├── UserProgressRepository.java
│   └── dto/                     ← ProgressSyncRequest/Response
└── user/
    ├── User.java                ← 用户实体 (email/phone/password/login_failures)
    └── UserRepository.java
```

## API endpoints

| Method | Path | Auth | 说明 |
|--------|------|------|------|
| POST | `/api/v1/auth/register` | — | 邮件注册（返回 JWT） |
| POST | `/api/v1/auth/login` | — | 邮箱登录 |
| POST | `/api/v1/auth/send-otp` | — | 发送邮箱验证码 |
| POST | `/api/v1/auth/verify-otp` | — | 验证 OTP |
| POST | `/api/v1/auth/refresh` | — | 刷新 access token |
| GET | `/api/v1/progress` | JWT | 获取用户进度 |
| PUT | `/api/v1/progress` | JWT | 全量上传进度 |
| POST | `/api/v1/progress/sync` | JWT | 版本冲突感知同步 |
| GET | `/swagger-ui.html` | — | Swagger UI |
| GET | `/actuator/health` | — | 健康检查 |

## Database

4 张表（见 `src/main/resources/schema.sql`）：

| 表 | 用途 | 关键列 |
|----|------|--------|
| `users` | 用户 | email, phone, password_hash, login_failures, locked_until |
| `otp_codes` | 验证码 | target, code, purpose, expires_at, used |
| `user_progress` | 进度 | user_id, data (JSONB), version |
| `refresh_tokens` | 刷新令牌 | user_id, token_jid, expires_at |

`user_progress.data` 是 JSONB blob，后端不解析内部结构。前端负责序列化/合并。

## 进度同步逻辑

`ProgressService.syncProgress()` 版本比较：

```
client version > server version → 客户端更新，覆盖保存 (merged=false)
client version < server version → 服务端更新，返回服务端数据 (merged=true)
version 相同                   → 无冲突 (merged=false)
服务端无数据                    → 保存客户端数据 (merged=false)
```

## 限流规则

| Path | 限制 |
|------|------|
| `/api/v1/auth/login` | 10/min |
| `/api/v1/auth/register` | 3/min |
| `/api/v1/auth/send-otp` | 3/min |
| `/api/v1/auth/verify-otp` | 5/min |

本地开发 `rate-limit.enabled: false`（`application-local.yml`）。

## CRITICAL — What to read

| Task | What to read | What NOT to read |
|------|-------------|------------------|
| Add API endpoint | `AGENTS.md` + 对应 Controller | 其他 Controller |
| Modify auth logic | `AuthService.java` + `AuthController.java` | Progress/Config 包 |
| Modify progress sync | `ProgressService.java` + `ProgressController.java` | Auth/Config 包 |
| Change security/filter | `SecurityConfig.java` + 目标 Filter | Controller/Service |
| Change JWT | `JwtService.java` + `JwtAuthFilter.java` | 其他 |
| Modify DB schema | `schema.sql` + 对应 Entity + `application.yml` | 其他 |
| Add rate limit | `RateLimitProperties.java` + `application.yml` | 其他 |
| Fix tests | 失败的测试文件 + 被测类 | 其他测试 |

## How to run

```bash
# 测试（H2 内存数据库，无需 PostgreSQL）
mvn test

# 本地启动（需要 PostgreSQL）
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run

# CI 用阿里云镜像
mvn -s .github/maven-settings.xml test
```

## Conventions

- 构造器注入（不用 `@Autowired`）
- 异常统一用 `AuthException` 抛出，`GlobalExceptionHandler` 转 JSON
- DTO 手写 getter/setter（不用 Lombok，虽然依赖在 pom 中）
- `application.yml` 放默认配置，profile 特定配置放 `application-{profile}.yml`
- JWT secret 通过 `JWT_SECRET` 环境变量注入，空值时 `JwtService` 构造器抛异常
- 密码最小长度 8 位，`BCryptPasswordEncoder` 加密
- 登录失败 ≥ 5 次锁定 15 分钟 (`User.loginFailures` + `locked_until`)

## 已知待做

| 优先级 | 内容 | 触发条件 | 详情 |
|--------|------|----------|------|
| 🟡 | Service 层 Mockito 单元测试 | 新增 Auth/Progress 逻辑时 | 当前只有 13 个集成测试 |
| 🟡 | GlobalExceptionHandler 补异常类型 | 出未捕获异常时 | `HttpMessageNotReadableException` 等 |
| ⏸️ | Access token 黑名单 (Redis) | 引入 Redis 时 | 当前 15min 过期，风险低 |
| ⏸️ | UserProgress 外键 | 引入 Flyway / 加删除用户功能 | 当前 ddl-auto=validate |
| ⏸️ | DTO 改用 Java Record | 新增 DTO 时 | 项目已是 Java 21 |
| ⏸️ | SMS 短信验证 | 营业执照就绪 | `OtpService` 对手机号抛异常 |

详见 `docs/` 和 `CODE_REVIEW_FIXES.md`（底部有修复记录）。

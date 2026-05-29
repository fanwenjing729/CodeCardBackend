# CodeCard 后端代码审查 — 问题分析与修复方案

> 审查时间：2026-05-29  
> 技术栈：Spring Boot 3.4.1 + Java 21 + PostgreSQL + JWT + JPA

---

## 🔴 安全问题

### 1. CORS 配置过于宽松

**文件：** `com/codecard/config/CorsConfig.java:17-19`

**问题：**
```java
config.setAllowedOrigins(List.of("*"));
config.setAllowedHeaders(List.of("*"));
```
`*` 允许任意域名跨域访问，结合 `*` 请求头，任意网站可无限制调用 API。虽然 JWT 通过 Authorization header 传递不依赖 Cookie，但攻击者可在恶意网站上构造请求窃取响应数据，且为后续 CSRF 类攻击留了口子。

**修复方案：**
```java
@Value("${cors.allowed-origins:http://localhost:8081}")
private List<String> allowedOrigins;

@Bean
public CorsFilter corsFilter() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(allowedOrigins);  // 具体域名列表，不用 *
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);  // 如果需要携带 Authorization header

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return new CorsFilter(source);
}
```
同时在 `application.yml` 中添加：
```yaml
cors:
  allowed-origins:
    - http://localhost:8081
    - https://your-production-domain.com
```

---

### 2. 密码最小长度仅 6 位

**文件：** `auth/dto/RegisterRequest.java:11`, `auth/dto/SetPasswordRequest.java:7`

**问题：**
`@Size(min = 6)` 密码太短，易于暴力破解。NIST 和 OWASP 建议至少 8 位。

**修复方案：**
```java
// RegisterRequest.java
@Size(min = 8, message = "password must be at least 8 characters")
private String password;

// SetPasswordRequest.java
@Size(min = 8, message = "password must be at least 8 characters")
private String password;
```
如果需要更强的密码策略，可以增加一个自定义校验注解 `@ValidPassword`，检查：
- 至少 8 位
- 至少包含一个大写字母、一个小写字母、一个数字
- 不包含常见的弱密码（如 "password123"）

---

### 3. 登录无暴力破解防护

**文件：** `auth/AuthService.java:51-66`

**问题：**
`login()` 方法对失败尝试无限制，攻击者可无限枚举密码。

**修复方案（二选一）：**

**方案 A：数据库记录失败计数（轻量）**

User 表增加字段：
```java
@Column(name = "login_failures")
private int loginFailures = 0;

@Column(name = "locked_until")
private Instant lockedUntil;
```

AuthService 修改：
```java
@Transactional
public AuthResponse login(LoginRequest req) {
    User user = findUser(req);
    
    // 检查锁定
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
        throw new AuthException("account temporarily locked, try again later");
    }
    
    if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
        user.setLoginFailures(user.getLoginFailures() + 1);
        if (user.getLoginFailures() >= 5) {
            user.setLockedUntil(Instant.now().plus(15, ChronoUnit.MINUTES));
        }
        userRepo.save(user);
        throw new AuthException("invalid credentials");
    }
    
    // 登录成功，重置计数器
    user.setLoginFailures(0);
    user.setLockedUntil(null);
    userRepo.save(user);
    
    return buildAuthResponse(user, false);
}
```

**方案 B：Redis 计数（适合多实例部署）**

用 Redis 记录 `login_failures:{email}` 和 `login_failures:{ip}`，用 TTL 自动过期。复杂度较高，当前项目规模推荐方案 A。

---

### 4. Access Token 无法服务端撤销

**状态：⏸️ DEFERRED（推迟）**

**文件：** `auth/AuthService.java:159-161`

**问题：**
`logout()` 只删除 refresh token，access token 是无状态的，在过期前仍然有效，无法即时撤销。这是 JWT 的固有限制。

**推迟理由：** 需要引入 Redis 依赖，增加架构复杂度。当前 access token 过期时间仅 15 分钟（`application.yml` 中 `access-expiration-ms: 900000`），风险窗口很小。

**触发修复的条件（满足任一）：**
- 系统中出现需要即时撤销权限的业务场景（如封禁用户、强制下线）
- 项目已引入 Redis 做其他功能（缓存、session），边际成本降低
- access token 过期时间增加到 30 分钟以上，风险窗口变大

**修复方案：**

引入 Redis 黑名单。用户登出时将 access token 的 JTI 加入 Redis，TTL 设置为该 token 剩余有效期：

1. JwtService 生成 token 时加入 `jti`：
```java
public String generateAccessToken(UUID userId) {
    Instant now = Instant.now();
    return Jwts.builder()
            .subject(userId.toString())
            .id(UUID.randomUUID().toString())   // 添加 jti
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(accessExpirationMs)))
            .claim("type", "access")
            .signWith(key)
            .compact();
}
```

2. JwtAuthFilter 增加黑名单检查：
```java
String jti = jwtService.extractJid(token);
if (redisTemplate.hasKey("blacklist:" + jti)) {
    filterChain.doFilter(request, response);
    return;
}
```

3. logout 时加入黑名单：
```java
public void logout(UUID userId, String accessToken) {
    refreshTokenRepo.deleteByUserId(userId);
    String jti = jwtService.extractJid(accessToken);
    long ttl = jwtService.getRemainingTtl(accessToken);
    redisTemplate.opsForValue().set("blacklist:" + jti, "1", ttl, TimeUnit.MILLISECONDS);
}
```

**注意：** 这需要引入 Redis 依赖，增加了架构复杂度。如果业务对即时登出要求不高（如 15 分钟 access token 过期是可接受的），可以暂时不修，标记为已知限制。

---

### 5. JWT Secret 默认值为空

**文件：** `src/main/resources/application.yml:28`

**问题：**
```yaml
jwt:
  secret: ${JWT_SECRET:}
```
JWT_SECRET 未设置时 secret 为空字符串，`Keys.hmacShaKeyFor(Decoders.BASE64.decode(""))` 行为不确定，可能生成可预测的 key。

**修复方案：**

在 `JwtService` 构造函数中校验：
```java
public JwtService(
        @Value("${jwt.secret}") String secret,
        @Value("${jwt.access-expiration-ms}") long accessExpirationMs,
        @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs) {
    if (secret == null || secret.isBlank()) {
        throw new IllegalStateException(
            "JWT_SECRET is not configured. Generate one with: openssl rand -base64 64");
    }
    this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    // ...
}
```

或者使用 `@PostConstruct` 校验，确保应用启动时就失败，而不是等到第一个请求才报错。

---

## 🟡 JPA / 数据库问题

### 6. OTP 表无定期清理

**文件：** `auth/OtpCode.java`, `auth/OtpCodeRepository.java`

**问题：**
验证码使用后仅设置 `used=true`，不删除已使用或过期的记录。表会持续增长。

**修复方案：**

**1. 添加清理方法到 Repository：**
```java
public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {
    Optional<OtpCode> findTopByTargetAndPurposeAndUsedFalseOrderByCreatedAtDesc(
            String target, String purpose);

    @Modifying
    @Query("DELETE FROM OtpCode o WHERE o.used = true OR o.expiresAt < :now")
    int deleteUsedOrExpired(Instant now);
}
```

**2. 添加定时任务：**
```java
@Component
@EnableScheduling
public class OtpCleanupScheduler {

    private final OtpCodeRepository otpRepo;

    @Scheduled(cron = "0 0 3 * * *")  // 每天凌晨 3 点
    @Transactional
    public void cleanExpiredOtps() {
        int deleted = otpRepo.deleteUsedOrExpired(Instant.now());
        log.info("Cleaned {} expired/used OTP codes", deleted);
    }
}
```

**3. 在 `sendCode` 中额外清理同一 target 的旧记录：**
```java
@Transactional
public void sendCode(String target, String purpose) {
    // 废弃同一 target 的旧验证码
    otpRepo.findTopByTargetAndPurposeAndUsedFalseOrderByCreatedAtDesc(target, purpose)
            .ifPresent(recent -> {
                if (recent.getCreatedAt().plusSeconds(60).isAfter(Instant.now())) {
                    throw new AuthException("please wait before requesting another code");
                }
                // 废弃旧码，只保留最新的
                recent.setUsed(true);
            });
    
    // ... 生成新验证码
}
```

---

### 7. RefreshToken 表缺少全局清理

**文件：** `auth/RefreshTokenRepository.java`

**问题：**
`deleteExpiredByUserId` 只在用户登录/刷新时被调用。长期不活跃的用户过期 token 不会被删除。

**修复方案：**

添加定时清理：
```java
// RefreshTokenRepository 已有 query 方法，再加一个全局的：
@Modifying
@Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now")
int deleteAllExpired(Instant now);
```

同样在 `OtpCleanupScheduler` 或单独的 Scheduler 中调用。

---

### 8. UserProgress 无外键约束

**状态：⏸️ DEFERRED（推迟）**

**文件：** `progress/UserProgress.java:16-18`

**问题：**
`user_id` 与 User 表的 `id` 没有在数据库层面建立外键关系，删除 User 后 UserProgress 会成为孤儿数据。

**推迟理由：** 当前项目没有删除用户的业务功能，且采用 `ddl-auto=validate`，JPA 不会自动建外键，需要手动在数据库添加。在没有数据库迁移工具（Flyway/Liquibase）的情况下，手动管理外键容易和环境不一致。

**触发修复的条件（满足任一）：**
- 引入 Flyway 或 Liquibase 管理数据库变更
- 上线删除用户的功能
- 首次生产环境部署前做数据库 schema 审查

**修复方案：**

**方案 A：JPA 映射（推荐）**
```java
@Entity
@Table(name = "user_progress")
public class UserProgress {

    @Id
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;
    
    // ... 其他字段
}
```
这样 JPA 的 `ddl-auto=validate` 不会报错，但外键仍然需要手动在数据库中创建。

**方案 B：手动添加数据库外键（更安全）**

在数据库迁移脚本中添加：
```sql
ALTER TABLE user_progress
    ADD CONSTRAINT fk_user_progress_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE;
```

如果 `ddl-auto=validate` 要配合使用，建议在 `application.yml` 中改为：
```yaml
jpa:
  hibernate:
    ddl-auto: validate
  properties:
    hibernate:
      physical_naming_strategy: org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
```

同时配合 Flyway 或 Liquibase 管理数据库变更，确保外键存在于数据库中。

---

## 🟠 代码设计与 Spring Boot 模式

### 9. 邮箱/手机号判断依赖 contains("@") 启发式

**文件：** `auth/AuthService.java:80,89`, `auth/AuthService.java:53-58`

**问题：**
用 `target.contains("@")` 区分邮箱和手机号。如果手机号格式变更，或邮箱地址不含 `@`（理论上不可能），判断会出错。

**修复方案：**

**方案 A：DTO 增加 type 字段（推荐）**
```java
// OtpSendRequest.java
public class OtpSendRequest {
    @NotBlank
    private String target;

    @NotBlank
    private String purpose;

    @NotNull
    private IdentityType type;  // EMAIL, PHONE
}

public enum IdentityType { EMAIL, PHONE }
```

前端明确告知是邮箱还是手机号，后端不再猜测。

**方案 B：用正则校验（轻量改动）**
```java
private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.\\w{2,}$");
private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?\\d{7,15}$");

private IdentityType detectType(String target) {
    if (EMAIL_PATTERN.matcher(target).matches()) return IdentityType.EMAIL;
    if (PHONE_PATTERN.matcher(target).matches()) return IdentityType.PHONE;
    throw new AuthException("invalid target format");
}
```

---

### 10. JWT Token 在 Filter 中被重复解析

**文件：** `config/JwtAuthFilter.java:40-52`

**问题：**
```java
jwtService.isTokenValid(token)     // 解析 JWT（第一次）
jwtService.extractType(token)      // 解析 JWT（第二次）
jwtService.extractUserIdStr(token) // 解析 JWT（第三次）
```
每次调用都执行 `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)`，三次即三次完整解析 + 签名验证。

**修复方案：**

在 `JwtService` 中提供一个返回 Claims 的方法，Filter 只解析一次：
```java
// JwtService.java - 新增方法
public Claims parseToken(String token) {
    return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
}

// JwtService.java - 添加基于 Claims 的便捷方法
public String extractUserIdStr(Claims claims) {
    return claims.getSubject();
}

public String extractType(Claims claims) {
    return claims.get("type", String.class);
}
```

```java
// JwtAuthFilter.java - 修改
Claims claims;
try {
    claims = jwtService.parseToken(token);
} catch (Exception e) {
    log.warn("JWT parsing failed: {}", e.getMessage());
    filterChain.doFilter(request, response);
    return;
}

if ("refresh".equals(jwtService.extractType(claims))) {
    filterChain.doFilter(request, response);
    return;
}

String userId = jwtService.extractUserIdStr(claims);
UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
SecurityContextHolder.getContext().setAuthentication(auth);
```

**性能收益：** 每次请求节省约 2 次 JWT 签名验证（通常 1-5ms/次），高并发场景下效果明显。

---

### 11. SecurityConfig 缺少 accessDeniedHandler

**文件：** `config/SecurityConfig.java:37-44`

**问题：**
- `authenticationEntryPoint` → 未认证用户 → 返回 JSON 401 ✓
- 无 `accessDeniedHandler` → 已认证但权限不足（如将来的 RBAC） → 返回默认 HTML 403 ✗

**修复方案：**
```java
.exceptionHandling(ex -> ex
    .authenticationEntryPoint((request, response, authException) -> {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                Map.of("error", "authentication required"));
    })
    .accessDeniedHandler((request, response, accessDeniedException) -> {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                Map.of("error", "access denied", "traceId", MDC.get("traceId")));
    })
)
```

---

### 12. Lombok 依赖未使用

**状态：⏸️ DEFERRED（推迟）**

**文件：** `pom.xml:78-81`

**问题：**
`pom.xml` 引入了 Lombok，但所有实体、DTO 全是手写 getter/setter，没有任何 `@Data`、`@Getter`、`@Setter` 注解。

**推迟理由：** 不影响功能，纯代码风格问题。当前手写 getter/setter 清晰可读，改动收益低。如果要改，推荐直接用 Java Record 替代 DTO（项目已是 Java 21），比加 Lombok 更现代且无额外依赖。

**触发修复的条件（满足任一）：**
- 新增大量实体/DTO，手写 getter/setter 成为负担
- 团队决定统一代码风格（Lombok 或 Record 二选一）

**修复方案（二选一）：**

**方案 A：使用 Lombok（减少样板代码）**
```java
// User.java
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    // ... 字段定义，不再需要手写 getter/setter
}
```
适用于 DTO、实体类。Service 层保留构造器注入。

**方案 B：移除 Lombok 依赖（保持一致性）**
```xml
<!-- 删除以下依赖 -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```
同时删除 `spring-boot-maven-plugin` 中的 `lombok exclude` 配置。

**建议：** 采用方案 A。项目体量小，使用 Lombok 可显著减少代码量。但如果团队偏好 Java Record（Java 21 支持），DTO 可以用 Record 替代：
```java
public record RegisterRequest(
    String email,
    String phone,
    @Size(min = 8) String password
) {
    @AssertTrue(message = "email or phone is required")
    public boolean hasIdentity() {
        return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
    }
}
```
注意：Spring 对 Record 的 `@Valid` 校验支持在 3.x 中已完善，可以放心使用。

---

### 13. Progress 同步逻辑过于简单

**状态：⏸️ DEFERRED（推迟）**

**文件：** `progress/ProgressService.java:40-53`

**问题：**
`syncProgress` 发现远程有数据时直接返回远程数据 + `merged=true`，让客户端自行合并。没有基于版本的冲突检测，多设备同时修改时可能丢数据。

**推迟理由：** 当前是单设备场景（一个用户一部手机），同时修改的概率极低。现有的合并逻辑（远程优先，客户端自行处理）在一个设备掉线重连的场景下是够用的。

**触发修复的条件（满足任一）：**
- 支持多设备同步（同一用户在手机和平板上同时使用）
- 用户反馈进度丢失或不同步
- 需要离线编辑 + 在线同步的能力

**修复方案：**

引入基于版本号的乐观锁冲突解决：

```java
@Transactional
public ProgressSyncResponse syncProgress(UUID userId, ProgressSyncRequest req) {
    return progressRepo.findById(userId)
            .map(remote -> {
                if (req.getVersion() > remote.getVersion()) {
                    // 客户端版本更新 → 用客户端数据覆盖
                    remote.setData(req.getData());
                    remote.setVersion(req.getVersion());
                    remote.setUpdatedAt(Instant.now());
                    progressRepo.save(remote);
                    ProgressSyncResponse resp = toResponse(remote);
                    resp.setMerged(false);
                    return resp;
                } else if (req.getVersion() < remote.getVersion()) {
                    // 服务端版本更新 → 返回服务端数据让客户端合并
                    ProgressSyncResponse resp = toResponse(remote);
                    resp.setMerged(true);
                    return resp;
                } else {
                    // 版本相同 → 深度合并或直接返回
                    ProgressSyncResponse resp = toResponse(remote);
                    resp.setMerged(false);
                    return resp;
                }
            })
            .orElseGet(() -> {
                // 服务端无数据 → 保存客户端数据
                UserProgress progress = new UserProgress();
                progress.setUserId(userId);
                progress.setData(req.getData());
                progress.setVersion(req.getVersion());
                progress.setUpdatedAt(Instant.now());
                progressRepo.save(progress);
                ProgressSyncResponse resp = toResponse(progress);
                resp.setMerged(false);
                return resp;
            });
}
```

更进一步可以用 `@Version` 注解做 JPA 乐观锁（在 `UserProgress` 的 `version` 字段上加 `@Version`），但注意当前 `version` 字段是业务版本号，需要与 JPA 版本号区分开。

---

## 🟢 可选优化建议

| 优化项 | 说明 |
|--------|------|
| 补充 Service 层单元测试 | 当前只有集成测试，建议用 Mockito 对 AuthService、ProgressService 写单测 |
| Actuator 暴露更多端点 | `health` 之外可以考虑 `metrics`（需认证保护） |
| 请求日志拦截器 | 记录每个请求的方法、路径、响应状态码和耗时 |
| OTP Store 改用 Redis | 当前 OTP 存数据库，高并发下频繁读写。Redis TTL 机制天然适合验证码过期清理 |
| `GlobalExceptionHandler` 增加更多异常类型 | `HttpMessageNotReadableException`（JSON 解析失败）、`MissingRequestHeaderException` 等 |

---

## 修复记录

### 2026-05-29 — P0 + P1 + P2 修复（9 项）

| 编号 | 问题 | 状态 | 涉及文件 |
|------|------|------|----------|
| #1 | CORS 限制具体域名 | ✅ 已修复 | `CorsConfig.java`, `application.yml` |
| #2 | 密码最小长度 6→8 | ✅ 已修复 | `RegisterRequest.java`, `SetPasswordRequest.java` |
| #3 | 登录暴力破解防护 | ✅ 已修复 | `User.java`（+loginFailures, +lockedUntil）, `AuthService.java` |
| #5 | JWT secret 空值校验 | ✅ 已修复 | `JwtService.java` |
| #6 | OTP 表定期清理 | ✅ 已修复 | `OtpCodeRepository.java`, `CleanupScheduler.java`（新建） |
| #7 | RefreshToken 全局清理 | ✅ 已修复 | `RefreshTokenRepository.java`, `CleanupScheduler.java` |
| #9 | 邮箱/手机号判断改用正则 | ✅ 已修复 | `AuthService.java`, `OtpService.java` |
| #10 | JWT 重复解析优化 | ✅ 已修复 | `JwtService.java`, `JwtAuthFilter.java` |
| #11 | accessDeniedHandler | ✅ 已修复 | `SecurityConfig.java` |

### 待修复（推迟）

| 编号 | 问题 | 触发条件 |
|------|------|----------|
| #4 | access token 黑名单 | 引入 Redis 时修复 |
| #8 | UserProgress 外键 | 引入 Flyway / 删除用户功能上线时修复 |
| #12 | Lombok 统一使用 | 新增大量实体/DTO 时统一 |
| #13 | Progress 同步冲突解决 | 多设备同步需求出现时修复 |

### 2026-05-29 — 第二轮修复（3 项小问题）

| 问题 | 状态 | 涉及文件 |
|------|------|----------|
| 删除未使用的 PHONE_PATTERN | ✅ 已修复 | `AuthService.java` |
| 手机号格式校验 | ✅ 已修复 | `AuthService.java` — `verifyOtp()` 中非邮箱输入校验手机号正则 `^\+?\d{7,15}$` |
| `@EnableScheduling` 移到主类 | ✅ 已修复 | `CodeCardApplication.java` ← `CleanupScheduler.java` |

---

## 附录：本地开发环境搭建说明

### 当前已配置环境

| 组件 | 路径 | 状态 |
|------|------|------|
| Java 25 (Temurin) | `E:\JDK` | ✅ |
| Maven 3.9.16 | `E:\Maven\apache-maven-3.9.16` | ✅ |
| 阿里云 Maven 镜像 | `~/.m2/settings.xml` | ✅ |

### 运行测试（无需数据库）

```powershell
cd G:\CodeCard\backend
mvn test
```

测试使用 H2 内存数据库，13 个测试全部通过。

### 本地启动完整应用

**PostgreSQL 仅在需要本地启动完整后端服务时才需要安装。** 日常开发如果只涉及接口联调、代码修改，可以临时切换为 H2：

修改 `src/main/resources/application.yml`：
```yaml
spring:
  datasource:
    # 临时改为 H2，无需装 PostgreSQL
    url: jdbc:h2:mem:codecard
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop  # 每次重启自动建表
```

然后启动：
```powershell
cd G:\CodeCard\backend
mvn spring-boot:run
```

⚠️ H2 模式数据在内存中，重启即丢失，仅用于临时本地调试。

### 何时需要装 PostgreSQL

- 需要持久化本地数据（重启不丢）
- 需要测试 PostgreSQL 特有的 SQL 或 JSON 功能
- 部署前做本地验证

**安装方式：**
```powershell
# 通过 winget 安装
winget install -e --id PostgreSQL.PostgreSQL.16 --accept-package-agreements

# 建库建用户
psql -U postgres -c "CREATE USER codecard WITH PASSWORD 'codecard';"
psql -U postgres -c "CREATE DATABASE codecard OWNER codecard;"
```

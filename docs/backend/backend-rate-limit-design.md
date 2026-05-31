# Rate Limiting 方案设计 — Bucket4j

## 状态

| 步骤 | 状态 | 日期 |
|------|------|------|
| Step 1: pom.xml 加依赖 | ✅ 完成 | 2026-05-30 |
| Step 2: RateLimitProperties + application.yml | ✅ 完成 | 2026-05-30 |
| Step 3: RateLimitFilter | ✅ 完成 | 2026-05-31 |
| Step 4: SecurityConfig 注册 | ✅ 完成 | 2026-05-31 |

## 环境

- Java 25 + Maven 3.9.16
- Spring Boot 3.4.1
- Bucket4j 8.10.1

## 安装

零系统安装。Bucket4j 是纯 Maven 依赖，纯 Java 库无字节码操作，Java 21–25 完全兼容。

## Step 3 有什么用

不补 Step 3 的后果：pom.xml 多了一个用不到的 jar，application.yml 多了一段读不出来的配置。**限流完全不生效。**

### 防什么

RateLimitFilter 拦截在 Spring Security 链的最前面。被限请求返回 429，后面的 TraceId、JWT 认证、Controller 业务逻辑一概不执行。

两种攻击靠前端 UI 和账号锁防不住：

**1. 暴力破解密码**

攻击者拿 100 个常见密码，对 100 个已知邮箱/手机号各试 1 个密码。每人只错 1 次，`loginFailures` 计数器永远到不了 5，账号锁不触发。

```
POST /api/v1/auth/login { email: "user01@qq.com", password: "123456" }  → 200 或 401
POST /api/v1/auth/login { email: "user02@qq.com", password: "123456" }  → 200 或 401
POST /api/v1/auth/login { email: "user03@qq.com", password: "123456" }  → 200 或 401
...同一 IP 短时间内无限试
```

IP 限流生效后：同一 IP 每分钟最多 10 次 `/login` 请求。100 个账号需要 10 分钟才能试完，暴力成本从"秒级"变成"小时级"。

**2. 注册轰炸**

```
POST /api/v1/auth/register { email: "bot1@tmp.com", password: "123456" }
POST /api/v1/auth/register { email: "bot2@tmp.com", password: "123456" }
...每分钟几百个假账号
```

数据库 1GB 免费额度，但垃圾账号影响后续分析（活跃用户数失真）、SMTP 配额浪费、邮箱域名信誉下降。

IP 限流生效后：同一 IP 每分钟最多 3 次注册。自动脚本注册效率降低 20 倍+。

**3. 短信轰炸（send-otp）**

OtpService 已有 60 秒 per-target 限制（同一个手机号/邮箱 60 秒内只能发一次），但攻击者可以用不同手机号遍历触发短信。IM 平台计费按条算，一天几百条假短信纯浪费钱。

IP 限流生效后：同一 IP 每分钟最多 3 次 OTP 发送。不同手机号的遍历频率大幅降低。

**4. 验证码暴力试验（verify-otp）**

6 位数字验证码空间 1,000,000 种。即使加上 OtpService 的 5 次错即失效机制，攻击者可以通过换 OTP（过 60 秒发新的）续命。IP 限流每分钟 5 次兜底。

### 阈值为啥是这样

```
┌────────────────────────────────────┬────────┬───────┐
│ 端点                                │ 正常人 │ 攻击者 │
├────────────────────────────────────┼────────┼───────┤
│ /login      10 次/分钟              │ 1-2 次 │ 100+  │
│ /register   3 次/分钟               │ 1 次   │ 50+   │
│ /send-otp   3 次/分钟               │ 1 次   │ 30+   │
│ /verify-otp 5 次/分钟               │ 1-2 次 │ 50+   │
└────────────────────────────────────┴────────┴───────┘
```

阈值给正常用户留了 5-10 倍余量，但把攻击者的效率砍掉 95%+。正常用户一分钟内不会输错 10 次密码、注册 3 个账号、发送 3 次验证码。

### 什么时候必须补

| 触发条件 | 原因 |
|----------|------|
| 上架应用商店 | APK 任何人都能抓包看后端 API |
| 加 Web 端 | 浏览器 F12 直接看到请求 |
| 用户过千 | 树大招风 |
| 被攻击过 | 不用说 |

在这之前补上就行。

## Step 3 怎么做（留给以后）

### 新建文件：`backend/src/main/java/com/codecard/config/RateLimitFilter.java`

```java
package com.codecard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rate-limit-cleanup");
        t.setDaemon(true);
        return t;
    });

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ========== 生命周期 ==========

    @jakarta.annotation.PostConstruct
    void startCleanup() {
        cleanup.scheduleWithFixedDelay(() -> {
            Instant cutoff = Instant.now().minus(10, TimeUnit.MINUTES.toChronoUnit());
            buckets.entrySet().removeIf(e -> e.getValue().lastAccess.isBefore(cutoff));
        }, 10, 10, TimeUnit.MINUTES);
    }

    @jakarta.annotation.PreDestroy
    void stopCleanup() {
        cleanup.shutdown();
    }

    // ========== 核心逻辑 ==========

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        RateLimitProperties.PathLimit limit = findLimit(path);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = getClientIP(request);
        String key = ip + "|" + path;
        BucketEntry entry = buckets.computeIfAbsent(key, k -> new BucketEntry(createBucket(limit)));
        entry.lastAccess = Instant.now();

        if (entry.bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded: {} {}", ip, path);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(limit.durationSeconds()));
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", "too many requests",
                "retryAfter", limit.durationSeconds()
            ));
        }
    }

    // ========== 辅助方法 ==========

    private Bucket createBucket(RateLimitProperties.PathLimit limit) {
        Bandwidth bandwidth = Bandwidth.builder()
            .capacity(limit.limit())
            .refillGreedy(limit.limit(), limit.duration())
            .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private RateLimitProperties.PathLimit findLimit(String path) {
        return properties.paths().stream()
            .filter(p -> p.path().equals(path))
            .findFirst()
            .orElse(null);
    }

    private String getClientIP(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class BucketEntry {
        final Bucket bucket;
        volatile Instant lastAccess;

        BucketEntry(Bucket bucket) {
            this.bucket = bucket;
            this.lastAccess = Instant.now();
        }
    }
}
```

### 修改文件：`SecurityConfig.java`

**改动 1** — 构造器加一个参数：

```java
// 在 RateLimitFilter rateLimitFilter 后面加这个参数
public SecurityConfig(RateLimitFilter rateLimitFilter,
                      TraceIdFilter traceIdFilter,
```

**改动 2** — 在 filter 链最前面注册：

```java
// 在 .addFilterBefore(traceIdFilter...) 之前加：
.addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
```

最终 filter 执行顺序：`RateLimitFilter → TraceIdFilter → JwtAuthFilter → Controller`

### 不需改的文件

`RateLimitProperties.java`、`application.yml`、`CodeCardApplication.java` 已完成，直接可用。

### 测试验证

```bash
# 编译验证
mvn compile -q

# 限流测试：同一个 IP 连打 11 次 login
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email":"test@test.com","password":"wrong"}'
done
# 期望：前 10 次 401，第 11 次 429
```

### 恢复

若限流影响开发或测试，改一行临时关掉：

```yaml
# application.yml
rate-limit:
  enabled: false   # ← 临时关闭
```

### 实现注意事项（代码审查 2026-05-30）

以下两点在实际写 `RateLimitFilter` 时需要注意：

**1. `paths` 为 null 的防御性处理**

`RateLimitProperties` 是 record，如果 yml 中未配置 `rate-limit.paths` 段，`paths` 会被绑定为 `null`。`findLimit()` 中调用 `properties.paths().stream()` 会 NPE。

```java
// findLimit 要防御 null
private RateLimitProperties.PathLimit findLimit(String path) {
    if (properties.paths() == null) return null;
    return properties.paths().stream()
        .filter(p -> p.path().equals(path))
        .findFirst()
        .orElse(null);
}
```

**2. 本地开发时关闭限流**

`application-local.yml` 未覆盖 `rate-limit.enabled`，会继承主 yml 的 `enabled: true`。RateLimitFilter 实现后，本地开发连点测试会被 429 拦截。

修复：在 `application-local.yml` 加一行：

```yaml
rate-limit:
  enabled: false
```

---

## 架构位置

```
请求 → RateLimitFilter → TraceIdFilter → JwtAuthFilter → ... → Controller
         ↑ 被限 → 429，后面的 filter 全部不执行
```

## 限流规则

| 端点 | 次数 | 窗口 |
|------|------|------|
| POST /api/v1/auth/login | 10 | 1 min |
| POST /api/v1/auth/register | 3 | 1 min |
| POST /api/v1/auth/send-otp | 3 | 1 min |
| POST /api/v1/auth/verify-otp | 5 | 1 min |

## 设计决策

### 令牌桶（greedy refill）

`Bandwidth.builder().capacity(N).refillGreedy(N, Duration.ofSeconds(S))`

容量 = N，窗口内均匀补充令牌。等效平均速率 = N/S，允许短时突发（最多 N 个并发消耗）。用 greedy 而非 intervalAligned，避免窗口边界的瞬时突防。

### 限流 key = IP + path

同一个 IP 在不同端点上独立计数。IP 获取优先读 `X-Forwarded-For` 头（兼容前置 Nginx）。

### 内存清理

`ConcurrentHashMap<String, BucketEntry>` 按 key 存桶。`ScheduledExecutorService` daemon 线程每 10 分钟扫描一次，清掉 `lastAccess` 超过 10 分钟的桶。避免攻击者换 IP 攻击导致 map 无限膨胀。

## 前端影响

零。`api.ts` 已能处理非 2xx 响应。429 的错误信息会走现有错误处理路径。

## 依赖（pom.xml）

```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>
```

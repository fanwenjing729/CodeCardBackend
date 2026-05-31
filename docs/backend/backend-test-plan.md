# 后端测试方案

> 2026-05-30 | 当前：15 个集成测试，覆盖正常路径，缺边界和异常

---

## 一、当前测试是什么

15 个集成测试（`@SpringBootTest` + 真实 H2 数据库），覆盖：

| 测试类 | 用例 | 覆盖 |
|--------|:--:|------|
| `AuthIntegrationTest` | 10 | 注册、登录、OTP 发送/验证、token 刷新、获取/修改 profile、登出 |
| `ProgressIntegrationTest` | 5 | 进度读取、写入、同步 |

全是**正向路径**——正常输入、正常返回。没有测"如果有人发错密码""如果 token 过期了""如果验证码输错"。

---

## 二、测试分层

```
┌─ 单元测试（JUnit 5 + Mockito）── 快，不启动容器
│   ├── JwtService       ← 最值得测
│   ├── OtpService       ← 边界多
│   ├── AuthService 边界  ← 安全关键
│   └── ProgressService  ← 合并逻辑
│
├─ 集成测试（@SpringBootTest + H2）
│   ├── 认证流程（已有 10 个）
│   │   ├── ✅ 正常注册/登录
│   │   └── ❌ 缺：密码错误锁定、token 过期、refresh 令牌当 access 用
│   ├── 进度同步（已有 5 个）
│   │   ├── ✅ 正常读写
│   │   └── ❌ 缺：版本冲突、空数据、合并规则
│   └── 安全（缺）
│       └── ❌ 缺：未登录访问需认证的接口、CORS header
│
└─ 安全测试  ← 暂不需要
```

---

## 三、优先级排序

### P0：必做（安全相关，不做上线有风险）

**1. AuthService 登录失败锁定（单元测试，~20 个用例）**

```java
// 当前代码逻辑
// loginFailures >= 3 → lockedUntil = now + 15min
// lockedUntil 过期 → loginFailures 重置

// 应该测的：
test("连续输错 3 次后账户被锁定")        // loginFailures=3 → lockedUntil 非 null
test("锁定期间输入正确密码也返回错误")     // 拒绝是有原因的
test("锁定时间到期后可以正常登录")         // lockedUntil < now → 允许登录
test("锁定到期后正确登录重置失败计数")     // loginFailures 归零
test("密码正确时失败计数归零")             // 成功登录 → loginFailures = 0
```

**2. JwtAuthFilter 令牌类型校验（单元测试，~5 个用例）**

```java
// 当前逻辑：refresh token 当 access token 用时直接拒绝

test("有效的 access token 通过")
test("过期的 access token 不通过")
test("refresh token 当作 access token 被拒绝")
test("没有 Authorization header 被放行（交给 SecurityConfig 处理）")
test("Bearer 后面是垃圾字符串不崩溃")
```

### P1：重要（边界和异常，防线上 bug）

**3. JwtService 单测（~10 个用例）**

```java
test("生成的 token 包含 userId + type")
test("access token 有效期为 15 分钟")
test("refresh token 有效期为 30 天")
test("解析篡改过的 token 抛出异常")
test("解析时密钥不匹配抛出异常")
test("extractUserId 能正确提取用户 ID")
test("extractType 区分 access 和 refresh")
```

**4. OtpService 单测（~10 个用例）**

```java
test("生成的 OTP 是 6 位数字且在 100000-999999 范围内")
test("验证码未过期 → 验证通过")
test("验证码过期 → 验证失败")
test("验证码不匹配 → 验证失败")
test("重复验证同一验证码 → 第二次失败（已使用）")
test("发送 OTP 先发邮件再入库（发送失败不残留）")
test("同一 target 再次发码 → 旧码失效")
```

**5. ProgressService 合并逻辑（集成测试，~6 个用例）**

```java
test("客户端和服务端同时完成不同卡片 → 合并后两张都完成")
test("客户端 xp 比服务端高 → 取客户端")
test("服务端 xp 比客户端高 → 取服务端")
test("合并后 quizScores 取每门最高分")
test("无服务端数据时直接保存客户端数据")
test("version 字段在合并后自增")
```

### P2：锦上添花（有闲工夫时做）

**6. 安全测试（集成测试，~5 个用例）**

```java
test("未登录访问 /api/v1/progress → 401")
test("未登录访问 /api/v1/auth/me → 401")
test("OPTIONS 预检请求返回 CORS header")
test("请求 /actuator/health 不需要认证")
```

---

## 四、工作量

| 优先级 | 做什么 | 用例数 | 预计 |
|--------|--------|:--:|------|
| P0 | AuthService 锁定 + JwtAuthFilter 类型校验 | ~25 | 2h |
| P1 | JwtService + OtpService + ProgressService 合并 | ~26 | 3h |
| P2 | 安全测试 | ~5 | 0.5h |
| **合计** | | **~56** | **5.5h** |

---

## 五、什么时候做

| 场景 | 做哪些 |
|------|--------|
| 现在（solo 开发） | 不需要，跑通就行 |
| 准备给其他人测试前 | P0 |
| 准备上线 / 多设备同步 | P0 + P1 |
| Code review / 开源 | P0 + P1 + P2 |

---

## 六、不改什么

- 不引入新框架（Mockito 已有，JUnit 5 已有）
- 不改业务代码（只加测试文件）
- 不追求覆盖率数字（专注关键路径）
- 不对前端 Screen 层补测试（另案处理）

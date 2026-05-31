# SMS 延后方案：手机号入口优雅降级 + 邮箱验证码为主

> 2026-05-29

## 当前状态

- 邮箱验证码（Resend SMTP）：**已可用**，100 封/天免费额度
- 手机号验证码：后端 `OtpService` 只打日志不发送，但 OTP 码被存入 DB，前端 UI 完整
- 问题：用户在手机号注册/登录流程中点击"发送"后会卡住，不知道发生了什么

## 目标

保留手机号入口 UI，但用户使用时收到明确提示引导到邮箱流程。不删代码，为将来接入 SMS 留好扩展点。

## 改动清单

| 文件 | 改动 | 行数 |
|------|------|:--:|
| `backend/.../auth/OtpService.java` | phone target 抛明确错误 | ~5 行 |
| `src/screens/LoginScreen.tsx` | 手机号发送失败时 Alert 提供"切换到邮箱"按钮 | ~3 行 |
| `src/screens/RegisterScreen.tsx` | 手机号注册发送失败时 Alert 提供"切换到邮箱注册"按钮 | ~5 行 |

---

## 后端：`OtpService.sendCode()`

**文件**：`backend/src/main/java/com/codecard/auth/OtpService.java`

把 phone 检测移到 rate limit 检查之后、生成 OTP 之前，直接抛 `AuthException`，不存 DB。

```java
@Transactional
public void sendCode(String target, String purpose) {
    // Rate limit: same target+purpose cannot send another OTP within 60 seconds
    otpRepo.findTopByTargetAndPurposeAndUsedFalseOrderByCreatedAtDesc(target, purpose)
            .ifPresent(recent -> {
                if (recent.getCreatedAt().plusSeconds(60).isAfter(Instant.now())) {
                    throw new AuthException("please wait before requesting another code");
                }
            });

    // Phone OTP not yet supported — tell client to use email
    if (!target.contains("@")) {
        throw new AuthException("SMS暂不支持，请使用邮箱验证码");
    }

    String code = String.format("%06d", random.nextInt(1_000_000));

    OtpCode otp = new OtpCode();
    otp.setTarget(target);
    otp.setCode(code);
    otp.setPurpose(purpose);
    otp.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
    otp.setUsed(false);
    otpRepo.save(otp);

    sendEmail(target, code, purpose);
}
```

**变更说明**：
- 之前：手机号 target 走到 `else` 分支打 log 并存入 OTP，但用户收不到
- 之后：手机号 target 直接抛 `AuthException`，不创建 DB 记录，错误信息传到前端
- rate limit 检查对手机号也生效（防止刷接口）

---

## 前端

### LoginScreen.tsx

**文件**：`src/screens/LoginScreen.tsx`

`handlePhoneSend` 中，错误提示改为 Alert 按钮形式，提供"使用邮箱验证码"选项：

```ts
const handlePhoneSend = async () => {
    const p = phone.trim();
    if (!p || p.length < 11) {
      Alert.alert('请输入正确的手机号');
      return;
    }
    setSending(true);
    const { error } = await sendPhoneOtp(p);
    setSending(false);
    if (error) {
      Alert.alert('暂不支持', error, [
        { text: '使用邮箱验证码', onPress: () => switchMode('code') },
        { text: '取消' },
      ]);
      return;
    }
    startCountdown();
  };
```

**效果**：用户点"手机号登录"→ 输手机号 → 点"发送" → 弹出 Alert "SMS暂不支持，请使用邮箱验证码" → 点"使用邮箱验证码"自动切到邮箱验证码 tab，用户继续操作。

### RegisterScreen.tsx

**文件**：`src/screens/RegisterScreen.tsx`

`handleSendCode` 中，手机号发送失败时区分处理：

```ts
const handleSendCode = async () => {
    const v = validate();
    if (!v) return;
    setSending(true);
    const fn = authType === 'email' ? sendEmailOtp : sendPhoneOtp;
    const { error } = await fn(v);
    setSending(false);
    if (error) {
      if (authType === 'phone') {
        Alert.alert('暂不支持', error, [
          { text: '切换到邮箱注册', onPress: () => setAuthType('email') },
          { text: '取消' },
        ]);
      } else {
        Alert.alert('发送失败', error);
      }
      return;
    }
    startCountdown();
  };
```

**效果**：手机号注册 → 点"发送" → Alert "SMS暂不支持，请使用邮箱验证码" → 点"切换到邮箱注册"自动切换 toggle 为邮箱，用户继续注册流程。

---

## 邮箱验证码当前状态（已就绪）

无需改动。现在的链路：

```
LoginScreen/RegisterScreen
    ↓ sendEmailOtp(email)
authStore → POST /auth/send-otp { target: email, purpose: 'login' }
    ↓
OtpService:
  1. 检查 60s rate limit
  2. 生成 6 位数字验证码
  3. 存入 otp_codes 表，10 分钟过期
  4. 通过 Resend SMTP 发送邮件
    ↓
用户收到邮件 → 填入验证码
    ↓
POST /auth/verify-otp { target: email, code, purpose: 'login' }
    ↓
后端: 验证码匹配 → 标记 used=true → 若用户不存在则自动创建 → 返回 JWT token pair
    ↓
authStore: setTokens() + 自动同步进度
```

**配置**（已在 `application.yml`）：
```yaml
spring:
  mail:
    host: ${SMTP_HOST:smtp.resend.com}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USER:}
    password: ${SMTP_PASS:}
```

**Resend 免费额度**：100 封/天。开发调试完全够用，用户量上来后再升级套餐或切自建 SMTP。

---

## 验证清单

- [ ] `cd backend && mvn test` — 13 个测试全过
- [ ] LoginScreen → 切到"手机号登录"tab → 输入手机号 → 点"发送" → Alert 弹出正确提示 → 点"使用邮箱验证码" → 自动切到邮箱验证码 tab
- [ ] RegisterScreen → 切到"手机号"注册 → 输入手机号 → 点"发送" → Alert 弹出正确提示 → 点"切换到邮箱注册" → 自动切回邮箱
- [ ] 邮箱验证码登录流程不受影响：输入邮箱 → 发送 → 收到邮件 → 填验证码 → 登录成功
- [ ] 邮箱验证码注册流程不受影响：邮箱 → 验证码 → 设密码 → 注册成功
- [ ] 密码登录不受影响

---

## 将来接入 SMS 时

只需改两处，其他代码不变：

**1. `OtpService.java`** — 删除 phone 检测抛异常的 3 行，改回原来的 `sendSms()` 调用：
```java
if (target.contains("@")) {
    sendEmail(target, code, purpose);
} else {
    sendSms(target, code);  // 替换 throw new AuthException(...)
}
```

**2. `LoginScreen.tsx` + `RegisterScreen.tsx`** — Alert 按钮逻辑改回普通错误提示（或直接删除 alert，因为错误不再出现）。

不需要改：`authStore.ts`、`api.ts`、`OtpCode` 实体、`OtpCodeRepository`、前端 UI 结构。

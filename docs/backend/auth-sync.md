# 认证与同步

> 最后更新：2026-05-31
>
> 当前架构：**Spring Boot + JWT**（方案 B）。方案 A（Supabase）已废弃，见[附录](#附录方案-asupabase未采用)。

## 认证流程

### 注册

```
RegisterScreen
  ├─ 邮箱模式：输入邮箱 → sendEmailOtp → 填验证码 → setPassword → 完成
  └─ 手机模式：输入手机号 → sendPhoneOtp → 填验证码 → setPassword → 完成

authStore 调用链：
  sendEmailOtp / sendPhoneOtp → POST /api/v1/auth/send-otp { target, purpose }
  verifyEmailOtp / verifyPhoneOtp → POST /api/v1/auth/verify-otp { target, code, purpose }
    返回 { user, accessToken, refreshToken, isNewUser }
    → setTokens() 写入 AsyncStorage
    → syncOnLogin(user.id) 拉取云端进度
  setPassword → POST /api/v1/auth/set-password { password }（需 JWT）
```

### 登录

```
LoginScreen
  ├─ 密码登录：邮箱 + 密码 → POST /api/v1/auth/login { email, password }
  ├─ 邮箱验证码：邮箱 → 发码 → 填码 → POST /api/v1/auth/verify-otp
  └─ 手机验证码：手机号 → 发码 → 填码 → POST /api/v1/auth/verify-otp

成功后：setTokens() → syncOnLogin() → 自动返回主页
```

### JWT 生命周期

| Token | 有效期 | 存储位置 |
|-------|--------|---------|
| Access Token | 15 分钟 | 内存 + AsyncStorage |
| Refresh Token | 30 天 | 内存 + AsyncStorage |

**自动刷新**（`api.ts`）：当 API 返回 401 时自动调 `POST /auth/refresh` 换新 Token，重试原请求。调用方无感知。

**Token 轮转**：刷新时服务端删除旧 Refresh Token 的 JTI，签发新 Token 对 → 旧 Token 立即失效。

### 登出

```
authStore.logout()
  ├─ POST /api/v1/auth/logout { refreshToken }  → 服务端吊销 JTI
  └─ clearTokens() → AsyncStorage.removeItem() → UI 回到未登录态
```

核心学习流程不检查登录态，登出后仍可离线学习。

## 进度同步

### 数据分层

```
静态数据（APK 自带）              个人数据（需网络同步）
┌────────────────────┐        ┌──────────────────────┐
│ 课程内容（卡片、代码）│        │ 学习进度（XP、完成记录）│
│ 所有用户一样         │        │ 每人不同              │
│ 不进数据库           │        │ user_progress 表      │
└────────────────────┘        └──────────────────────┘
```

### 同步时机

| 时机 | 触发方 | 调用 |
|------|--------|------|
| App 启动 | `authStore.initialize()` | `syncOnLogin(userId)` |
| 登录/注册成功 | authStore 各 action | `syncOnLogin(userId)` |
| 手动同步 | SettingsScreen | `manualSync(userId)` |

`useAutoSync` hook 提供 3 秒防抖自动上传（`uploadProgress`），后台运行。

### syncOnLogin 流程

```
1. GET /api/v1/progress → 获取远程进度
2. 远程无数据 → uploadProgress(本地) → 结束
3. 远程有数据 → 合并：
   ├─ completedCards: UNION（并集）
   ├─ wrongCards: UNION
   ├─ xp: MAX(本地, 远程)
   ├─ quizScores: 逐 key 取 MAX
   └─ nodePositions: 逐 key 取 MAX
4. 重算 global.totalXP / level → setState() 更新 Zustand
5. PUT /api/v1/progress → 回写合并结果到服务端
```

**合并策略要点**：
- `completedCards` 用并集而非求和 — 同一张卡完成多次不重复计数
- `xp` 用 MAX 而非求和 — 防止双设备同步导致 XP 翻倍
- 本地已重置的课程（`completedCards` 为空且 `xp=0`）跳过远程合并 — 防止重置被同步恢复

### 合并在客户端完成

服务端只做 JSONB 存取，不参与业务合并。原因：
- 合并逻辑与本地计算一致（`calcLevel` 在前端）
- 避免服务端需要理解业务语义
- 服务端保持简单

## 关键文件

| 层 | 文件 | 职责 |
|----|------|------|
| 前端 | `src/lib/api.ts` | HTTP 客户端，JWT 管理，401 自动刷新 |
| 前端 | `src/store/authStore.ts` | 认证状态（登录/注册/OTP/登出/资料） |
| 前端 | `src/store/syncEngine.ts` | 同步逻辑（上传/下载合并/手动同步） |
| 前端 | `src/hooks/useAutoSync.ts` | 3s 防抖自动上传 |
| 前端 | `src/screens/LoginScreen.tsx` | 登录 UI（密码/邮箱验证码/手机号/找回密码） |
| 前端 | `src/screens/RegisterScreen.tsx` | 注册 UI（邮箱/手机号+验证码+设密码） |
| 后端 | `AuthController.java` | `/api/v1/auth/*` 9 个端点 |
| 后端 | `AuthService.java` | 核心认证逻辑 |
| 后端 | `ProgressController.java` | `/api/v1/progress/*` 3 个端点 |
| 后端 | `ProgressService.java` | JSONB 存取 + sync |
| 后端 | `SecurityConfig.java` | 白名单 + JWT Filter 注册 |
| 后端 | `JwtAuthFilter.java` | Token 提取/验证/注入 SecurityContext |

后端架构细节见 [`backend-architecture.md`](./backend-architecture.md)。

## 接口契约

authStore 和 syncEngine 的导出签名是 stable public API，实现可换（Supabase → Spring Boot 就是一次实现替换，不改任何调用方）。

```typescript
// authStore — 对外承诺
useAuthStore                    // ZustandStore<AuthStore>
  selector: s => s.user         // User | null
  selector: s => s.isLoggedIn   // boolean
  action:   initialize()        // App 启动恢复 session
  action:   loginByEmail()
  action:   registerByEmail()
  action:   sendEmailOtp / verifyEmailOtp
  action:   sendPhoneOtp / verifyPhoneOtp
  action:   setPassword()
  action:   logout()            // 登出 + 清理本地 Token
  action:   setDisplayId()
  action:   updateAvatar()

// syncEngine — 对外承诺
uploadProgress(userId): Promise<void>
syncOnLogin(userId): Promise<void>
manualSync(userId): Promise<{ lastSyncedAt: Date | null }>
```

## 头像跨设备

当前：`expo-image-picker` 返回本地 URI → 存 AsyncStorage。换设备不显示。

后续方案：上传到 OSS/Storage → 公开 URL 存 `users.avatar_url` → `toUser()` 已读 `apiUser.avatarUrl`，后端就绪，差前端上传逻辑。

## 测试

14 个文件，145 个用例全部通过（vitest v4.1）。

| 文件 | 用例 | 覆盖范围 |
|------|:--:|------|
| `store/useProgressStore.test.ts` | 38 | rewardCard/addXP/saveQuizScore/wrongCard/resetCourse/hydrate |
| `store/authStore.test.ts` | 6 | logout/setDisplayId/updateAvatar/initialize |
| `store/syncEngine.test.ts` | 2 | 合并策略核心场景 |
| `lib/xp.test.ts` | 14 | calcLevel, xpForLevelStart, xpForNextLevel |
| `lib/courseProgress.test.ts` | 7 | countCards, countNodeCards |
| `screens/quizReducer.test.ts` | 14 | 全部 action |
| `components/cards/QuestionRenderer.test.ts` | 18 | normalize, isCorrectAnswer |
| `components/cards/ConceptCard.test.tsx` | 3 | 空标题/正文/完整渲染 |
| `components/cards/CodeCard.test.tsx` | 6 | 代码行/高亮/空代码 |
| `components/cards/renderCard.test.tsx` | 6 | concept/code/animation/practice 分发 |
| `components/shared/ErrorBoundary.test.tsx` | 5 | crash/flush/retry |
| `data/courses/validate.test.ts` | 18 | 卡片 ID 唯一性/节点完整性 |
| `data/animations/index.test.ts` | 4 | 动画场景 + 组件注册 |
| `theme/ThemeContext.test.tsx` | 4 | Provider/默认值/context 结构 |

```bash
npm test              # 单次运行
npm run test:watch    # watch 模式
```

---

## 附录：方案 A（Supabase，未采用）

项目最初设计用 Supabase BaaS，代码侧全部实现完毕后切换到自建 Spring Boot 后端。方案 A 的代码曾跑通但已归档。

切换原因：自建后端对国内网络更稳定、部署灵活度更高、不依赖第三方 BaaS 定价变化。

如需了解 Supabase 方案的实现细节（`@supabase/supabase-js`、RLS 配置、Storage 头像上传等），见 git 历史 `023590f` 之前的 `auth-sync.md`。

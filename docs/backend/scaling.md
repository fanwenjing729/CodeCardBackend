# 将来规模化 — 什么时候改、怎么改

以下各项在 v1.0 solo 阶段不需要动。各自有明确触发条件。

---

## 0. 接后端与云同步

**触发条件（任一）：** 需要跨设备同步 / 需要登录 / 需要排行榜或论坛

**方案：** 用 Supabase BaaS，不自己搭后端。操作顺序和完整代码见两个文档：

| 文档 | 内容 |
|------|------|
| `auth-sync.md` | 操作顺序、接口设计、合并策略、不改什么 |
| `backend-architecture.md` | 后端架构与环境搭建 |

**核心原则：** 替换 3 个 no-op 文件 + 新建 4 个文件，不改任何现有 Screen 和课程数据。

| 需要 | 不需要 |
|------|--------|
| Supabase 实例（阿里云国内可用） | 自建后端服务器 |
| 1 张 `user_progress` 表（JSONB） | 多张关系表拆进度 |
| `@supabase/supabase-js` | 其他 SDK |

**成本：** 0–5 万月活 Supabase Free ¥0，5 万+ Pro $25/月。

---

## 1. 远程可更新内容

**现状：** 课程数据写死在 `src/data/courses/` TS 文件，改错字也要重新 build。

**触发条件（任一）：** 课程 ≥ 3 门且每门 ≥ 3 模块有内容 / 内容更新 ≥ 每周 / 有非开发者参与写内容

**方案：**
1. 课程数据迁出为 JSON，上传 CDN
2. 启动时先读本地缓存，再 fetch CDN 检查新版本
3. 有网 → 下载最新 JSON → 覆盖本地缓存
4. 无网 → 用本地缓存，不阻塞
5. 版本号控制增量更新

**不改什么：** `Card` / `PathNode` / `Course` 类型不变，所有 Screen 不感知数据来源。

---

## 2. 测试

**现状：** 无测试框架。`tsc --noEmit` 只做类型检查。

**触发条件（任一）：** 有第二人改 store / store 超 300 行 / 接后端同步 / 出过"改了 store break 功能"的线上事故

**方案：**
1. `npx expo install jest-expo jest`
2. 先补纯函数用例：`calcLevel`（边界 0/99/100）、`rewardCard`（去重/原子性）、`merge`/`migrate`
3. 纯函数通过后再考虑组件测试
4. 参考 `../frontend/store-invariants.md` 的不变量写断言

**不改什么：** 不引入 E2E 框架，不追求覆盖率。

---

## 3. 内容格式 — 大段文字抽出

**现状：** 课程正文写在 TS 字符串里，`\n` 换行。

**触发条件（任一）：** 单张概念卡 body 超 500 字 / 一个模块 ≥ 10 张概念卡 / 有非技术人员写内容

**方案 A — Markdown 文件（推荐）：**
1. 模块目录下新建 `content.md`
2. 写 `parseMarkdown(md: string): Card[]` 函数
3. `index.ts` 只保留结构，content 从 md 读取

**方案 B — 远程 CMS（方案 A 的延续）：**
内容迁到 Notion/Google Sheets，build 时或运行时拉取转 JSON。

**不改什么：** `Card` 接口不变，渲染组件不变。

---

## 4. 付费与权限系统

**现状：** 所有课程免费。HomeScreen → CourseScreen 无拦截。

**触发条件：** 准备上线第一门付费课程。

**方案：**

1. `PathNode` 加可选字段：`requiresSubscription?: boolean`（不填 = 免费）
2. 新建 `store/permissionStore.ts`：`entitledNodes: Set<string>` + `isSubscribed` + `checkAccess(nodeId)`
3. 新建 `src/lib/payments.ts` — RevenueCat / IAP 封装
4. `CourseScreen.tsx` +5 行：付费模块显示锁图标
5. `ModuleScreen.tsx` +5 行：无权限弹付费 Modal

**不改什么：** 所有卡片组件、NodeScreen、QuizScreen、useProgressStore、导航路由。

---

## 5. 社交/社区/评论

**前提依赖：** 必须先完成 `auth-sync.md` 的真实登录接入（替换 `authStore.ts` + `syncEngine.ts` no-op）。所有社交功能依赖 `user.id`。

**改动总览：**

| 功能 | 新建文件 | 修改文件 | 总行数 |
|------|---------|---------|--------|
| 排行榜 | 1 | 2 | ~80 |
| 论坛 | 3 | 2 | ~250 |

合计 ~330 行新增/改动。所有功能均不改动课程数据、卡片渲染、useProgressStore。

---

### 5.0 新增底部 Tab（公共步骤）

排行榜和论坛都需要在底部 Tab 或 Stack 中注册路由。这里是具体改法。

#### AppNavigator 结构回顾

```
RootStack (NativeStack, headerShown: false)
  ├── MainTabs (BottomTab: 学习 / 进度 / 设置)
  │     ├── Tab "Learn"     → HomeScreen
  │     ├── Tab "Progress"  → ProgressScreen
  │     └── Tab "Settings"  → SettingsScreen
  │
  ├── Stack "Course"   → CourseScreen
  ├── Stack "Module"   → ModuleScreen
  ├── Stack "Node"     → NodeScreen
  ├── Stack "Quiz"     → QuizScreen
  ├── Stack "Login"    → LoginScreen
  ├── Stack "WrongCards" → WrongCardsScreen
  └── Stack "Data"     → DataScreen
```

#### 方案 A：加一个底部 Tab（适合论坛/社区）

改动 `AppNavigator.tsx` 的 `MainTabs` 函数，在 `<Tab.Navigator>` 内加一个 `<Tab.Screen>`。

**当前代码**（3 个 Tab）：

```tsx
<Tab.Navigator screenOptions={{ ... }}>
  <Tab.Screen name="Learn" component={HomeScreen} options={{ ... }} />
  <Tab.Screen name="Progress" component={ProgressScreen} options={{ ... }} />
  <Tab.Screen name="Settings" component={SettingsScreen} options={{ ... }} />
</Tab.Navigator>
```

**改为**（4 个 Tab）：

```tsx
<Tab.Navigator screenOptions={{ ... }}>
  <Tab.Screen name="Learn" component={HomeScreen} options={{ ... }} />
  <Tab.Screen name="Progress" component={ProgressScreen} options={{ ... }} />
  <Tab.Screen                          // ← 新增，~8 行
    name="Community"
    component={CommunityScreen}
    options={{
      tabBarLabel: '社区',
      tabBarIcon: ({ color }) => (
        <MaterialCommunityIcons name="forum" size={26} color={color} />
      ),
    }}
  />
  <Tab.Screen name="Settings" component={SettingsScreen} options={{ ... }} />
</Tab.Navigator>
```

同时顶部加 import：

```tsx
import CommunityScreen from '@/screens/CommunityScreen';
```

**总结：** 加一个 Tab = 在现有 `Tab.Navigator` 里插一个 `<Tab.Screen>`，5-8 行。不改路由结构、不碰 Stack、不动其他 Screen。

#### 方案 B：加一个 Stack 路由（适合排行榜/帖子详情）

排行榜作为独立页面从某个入口点进去（如 ProgressScreen 的按钮），不走底部 Tab。

改动 `AppNavigator.tsx` 两处：

1. `RootStackParamList` 加类型（+1 行）：
```tsx
export type RootStackParamList = {
  // ... 现有
  Leaderboard: undefined;  // ← 加这行
};
```

2. `<RootStack.Navigator>` 内加 Screen（+3 行）：
```tsx
<RootStack.Screen name="Leaderboard" component={LeaderboardScreen} />
```

同时顶部加 import：
```tsx
import LeaderboardScreen from '@/screens/LeaderboardScreen';
```

**总结：** 加一个 Stack 路由 = 类型定义 +1 行 + Screen 注册 +3 行 + import +1 行。

#### 方案对比

| | 底部 Tab | Stack 路由 |
|----|----|----|
| 适合 | 常驻入口（论坛） | 临时页面（排行榜、帖子详情、发帖） |
| 改动量 | ~8 行 | ~5 行 |
| 可见性 | 始终可见，一级入口 | 需从其他页面跳转 |
| 对现有影响 | Tab.Navigator 内加一项 | RootStack 内加一项 |

两方案不互斥。实际开发中通常同时用：论坛主页作为第四个 Tab（方案 A），排行榜、帖子详情、发帖页作为 Stack 路由（方案 B）。

#### 不涉及

MainTabs 的路由结构、`RootStackParamList` 现有字段、所有现有 Screen、store、课程数据、主题。

---

### 5.1 排行榜

**触发条件：** 需要好友排行 / 全站排行 / 课程排行

**改动量：** 1 个新文件 + 2 个修改，~80 行

#### 数据库

排行榜不需要新表。`user_progress` 已存每个用户的 XP，直接查询即可：

```sql
-- 全站排行榜
SELECT user_id, data->'global'->>'totalXP' AS xp
FROM user_progress
ORDER BY (data->'global'->>'totalXP')::int DESC
LIMIT 100;

-- 单课程排行榜
SELECT user_id, data->'courses'->'cpp'->>'xp' AS xp
FROM user_progress
WHERE data->'courses' ? 'cpp'
ORDER BY (data->'courses'->'cpp'->>'xp')::int DESC
LIMIT 100;
```

如需显示用户名/头像，排行榜查询 JOIN `users` 表（见 `schema.sql`）。

#### 类型（`src/types/index.ts` +15 行）

```typescript
interface LeaderboardEntry {
  userId: string;
  displayId: string;   // 显示名，排行榜专用
  avatar?: string;
  xp: number;
  level: number;
  rank: number;
}
```

#### 新建 `src/screens/LeaderboardScreen.tsx`（~50 行）

```tsx
// 顶部 Tab 切换：全站 | 按课程
// 列表项：排名 | 头像 | displayId | level 徽章 | XP
// 下拉刷新，上拉加载更多（Supabase range 分页）
// 自己的排名固定在底部或列表中高亮

import { useAuthStore } from '@/store/authStore';
import { Colors } from '@/theme';

// 核心逻辑：
// 1. useEffect 调 Supabase RPC/query 取排行榜
// 2. FlatList 渲染 LeaderboardEntry[]
// 3. 自己的条目用不同背景色高亮
```

#### 新建 `src/api/leaderboard.ts`（~20 行）

```typescript
// getGlobalLeaderboard(limit, offset)  → LeaderboardEntry[]
// getCourseLeaderboard(courseId, limit, offset) → LeaderboardEntry[]
// getFriendLeaderboard(userId, limit) → LeaderboardEntry[]  // 好友排行，需先建 friends 表
//
// 内部用 supabase.from('user_progress').select().order().range()
// 用户名/头像通过 JOIN profiles 或额外查询填充
```

#### 修改项

**`AppNavigator.tsx`** — 加路由（+3 行）：
```tsx
<RootStack.Screen name="Leaderboard" component={LeaderboardScreen} />
```

**`HomeScreen.tsx` 或 `ProgressScreen.tsx`** — 添加入口（+5 行）：
```tsx
// header 右侧或 ProgressScreen 的 section 内加排行榜按钮
<TouchableOpacity onPress={() => navigation.navigate('Leaderboard')}>
  <MaterialCommunityIcons name="trophy" size={20} color={Colors.primary} />
</TouchableOpacity>
```

#### 不涉及

useProgressStore、所有卡片组件、课程数据、动画系统、syncEngine（上传逻辑已存在）。

---

### 5.2 论坛 / 社区

**触发条件：** 需要讨论区 / 问答 / 卡片评论区

**改动量：** 3 个新文件 + 2 个修改，~250 行

#### 数据库（Supabase 新表）

```sql
-- 帖子表
CREATE TABLE posts (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  author_id   UUID NOT NULL REFERENCES profiles(id),
  title       TEXT NOT NULL,
  body        TEXT NOT NULL,
  tags        TEXT[] DEFAULT '{}',
  course_id   TEXT,              -- 可选：关联课程
  node_id     TEXT,              -- 可选：关联节点
  likes_count INTEGER DEFAULT 0,
  replies_count INTEGER DEFAULT 0,
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

-- 评论表（帖子下的回复）
CREATE TABLE comments (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id     UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  author_id   UUID NOT NULL REFERENCES profiles(id),
  body        TEXT NOT NULL,
  parent_id   UUID REFERENCES comments(id),  -- 支持嵌套回复
  likes_count INTEGER DEFAULT 0,
  created_at  TIMESTAMPTZ DEFAULT now()
);

-- 点赞表
CREATE TABLE likes (
  user_id     UUID NOT NULL REFERENCES profiles(id),
  target_type TEXT NOT NULL CHECK (target_type IN ('post', 'comment')),
  target_id   UUID NOT NULL,
  created_at  TIMESTAMPTZ DEFAULT now(),
  PRIMARY KEY (user_id, target_type, target_id)
);

-- RLS
ALTER TABLE posts ENABLE ROW LEVEL SECURITY;
ALTER TABLE comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE likes ENABLE ROW LEVEL SECURITY;

-- 所有人可读
CREATE POLICY "posts_read_all" ON posts FOR SELECT USING (true);
CREATE POLICY "comments_read_all" ON comments FOR SELECT USING (true);

-- 登录用户可发帖/评论
CREATE POLICY "posts_insert_auth" ON posts FOR INSERT WITH CHECK (auth.uid() = author_id);
CREATE POLICY "comments_insert_auth" ON comments FOR INSERT WITH CHECK (auth.uid() = author_id);

-- 只能删自己的
CREATE POLICY "posts_delete_own" ON posts FOR DELETE USING (auth.uid() = author_id);
CREATE POLICY "comments_delete_own" ON comments FOR DELETE USING (auth.uid() = author_id);

-- 点赞 RLS
CREATE POLICY "likes_all_auth" ON likes FOR ALL USING (auth.uid() = user_id);
```

#### 类型（`src/types/index.ts` +20 行）

```typescript
interface Post {
  id: string;
  authorId: string;
  authorName: string;   // displayId
  authorAvatar?: string;
  title: string;
  body: string;
  tags: string[];
  courseId?: string;
  nodeId?: string;
  likesCount: number;
  repliesCount: number;
  createdAt: string;
  liked?: boolean;       // 当前用户是否已点赞
}

interface Comment {
  id: string;
  postId: string;
  authorId: string;
  authorName: string;
  authorAvatar?: string;
  body: string;
  parentId?: string;     // 回复某条评论
  likesCount: number;
  createdAt: string;
  liked?: boolean;
}
```

#### 新建文件

**`src/store/socialStore.ts`**（~40 行）：

```typescript
import { create } from 'zustand';

interface SocialState {
  posts: Post[];
  currentPost: Post | null;
  comments: Record<string, Comment[]>;  // postId → comments

  setPosts: (posts: Post[]) => void;
  addPost: (post: Post) => void;
  setComments: (postId: string, comments: Comment[]) => void;
  addComment: (postId: string, comment: Comment) => void;
  toggleLike: (targetType: 'post' | 'comment', targetId: string) => void;
}

export const useSocialStore = create<SocialState>()((set) => ({
  posts: [],
  currentPost: null,
  comments: {},
  setPosts: (posts) => set({ posts }),
  addPost: (post) => set((s) => ({ posts: [post, ...s.posts] })),
  setComments: (postId, comments) =>
    set((s) => ({ comments: { ...s.comments, [postId]: comments } })),
  addComment: (postId, comment) =>
    set((s) => ({
      comments: {
        ...s.comments,
        [postId]: [...(s.comments[postId] || []), comment],
      },
    })),
  toggleLike: (targetType, targetId) => {
    // 乐观更新 + 后台 API 调用
  },
}));
```

**`src/api/social.ts`**（~50 行）：

```typescript
// 帖子 CRUD
// getPosts(courseId?, limit, offset) → Post[]
// getPost(postId) → Post
// createPost(title, body, tags, courseId?) → Post
// deletePost(postId) → void

// 评论
// getComments(postId) → Comment[]
// createComment(postId, body, parentId?) → Comment

// 点赞
// toggleLike(targetType, targetId) → { liked: boolean, count: number }

// 实现：supabase.from('posts').select('*, profiles(displayId, avatar)')...
```

**`src/screens/CommunityScreen.tsx`**（~80 行）：

```tsx
// 帖子列表页
// 顶部：按课程筛选的 Tab / 下拉（全部 | C++ | Python | ...）
// 右上角：发帖按钮 → 跳转 CreatePostScreen
// 列表项：标题 | 作者 + 时间 | 标签 | 点赞数 | 回复数
// 点击 → PostDetailScreen
// 下拉刷新，上拉加载更多
```

**`src/screens/PostDetailScreen.tsx`**（~70 行）：

```tsx
// 帖子正文 + 评论区
// 帖子：标题 | 作者 | 时间 | 正文 | 点赞按钮 | 关联课程链接
// 评论区：FlatList，每条评论显示作者/时间/内容/点赞
// 底部：评论输入框 + 发送按钮
// 支持回复某条评论（@作者名）
```

**`src/screens/CreatePostScreen.tsx`**（~40 行）：

```tsx
// 发帖表单
// 标题输入框
// 正文输入框（多行）
// 关联课程选择器（可选）
// 标签输入（可选）
// 提交按钮 → createPost() → 成功后跳转到新帖子
```

#### 修改项

**`AppNavigator.tsx`** — 加路由 + 社区 Tab（+10 行）：

```tsx
// 在 MainTabs 加第四个 Tab：
<Tab.Screen
  name="CommunityTab"
  component={CommunityScreen}
  options={{
    tabBarLabel: '社区',
    tabBarIcon: ({ color, size }) => (
      <MaterialCommunityIcons name="forum" size={size} color={color} />
    ),
  }}
/>

// Stack 路由：
<RootStack.Screen name="PostDetail" component={PostDetailScreen} />
<RootStack.Screen name="CreatePost" component={CreatePostScreen} />
```

#### 可选：卡片评论入口

如需在每张卡片下显示评论区，在 `renderCard.tsx` 底部加评论入口组件（独立组件，~20 行）：

```tsx
// renderCard.tsx 的 renderCard 函数末尾，卡片内容下方：
{enableComments && (
  <CardComments cardId={card.id} courseId={node.courseId} />
)}
```

`CardComments` 是一个独立组件，查询 `comments WHERE target_type='card' AND target_id=cardId`。不影响任何现有卡片组件。

#### 不涉及

Card/PathNode/Course 数据、useProgressStore、所有现有 Screen、动画系统、主题系统。

---

### 5.3 通知系统（可选，配合论坛）

**新建 `src/store/notificationStore.ts`**（~30 行）：

```typescript
// 管理未读通知计数和列表
// 通知类型：'reply' | 'like' | 'mention' | 'system'
// 轮询或 Supabase Realtime 订阅
```

**数据库：**

```sql
CREATE TABLE notifications (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES profiles(id),
  type        TEXT NOT NULL,
  body        TEXT NOT NULL,
  link        TEXT,              -- 跳转路径，如 /post/xxx
  is_read     BOOLEAN DEFAULT false,
  created_at  TIMESTAMPTZ DEFAULT now()
);
```

**AppNavigator.tsx** — 底部 Tab 或 header 加未读红点。

---

### 5.4 实施顺序建议

```
第1步：接入真实登录（authStore + syncEngine no-op → 真实实现）
第2步：排行榜（最简单，复用 user_progress 表，不改任何现有 UI）
第3步：论坛基础（发帖 + 评论）
第4步：通知系统（提升留存）
```

每步独立可上线，不互相阻塞。

---

## 6. 视频/音频课程

**触发条件：** 需要嵌入视频讲解或播客模式。

**方案 — 和加 AnimationContent 完全一样的模式：**

1. `types/index.ts` — 加 `VideoContent` / `AudioContent` + Card 联合类型各加一行
2. `components/cards/` — 新建 `VideoCard.tsx` / `AudioCard.tsx`（用 expo-av）
3. `renderCard.tsx` — 加两个 case
4. `src/lib/` — 媒体基础设施（缓存、播放器封装、下载管理）

**不改什么：** renderCard switch 结构、NodeScreen/QuizScreen 卡片遍历、useProgressStore。

---

## 7. 多语言

**触发条件：** 出海 / 非中文用户 / 多语种课程。

**第一层 UI（按钮/标签）：** `react-i18next` + `t('key')`，改所有 Screen 硬编码中文。store/types/navigation 零改动。

**第二层课程内容（推荐方案 B — CDN 先行）：**
```
CDN 每种语言独立 JSON
  cdn.codecard.com/content/zh/cpp.json
  cdn.codecard.com/content/en/cpp.json

加载器根据 locale 拉对应 URL → Card 接口完全不变 → 所有组件零改动
```

**最佳时机：** CDN 远程内容上线后 — 改加载器 URL + 一份翻译，不改接口。

---

## 8. 协作与 Git 工作流

**触发条件：** 有第二人开始提交代码。

```
solo 期：  master ← 直接 commit，可 force push
协作期：  master ← PR squash merge ← feature 分支（可随意 rebase）
```

- 所有改动走 feature 分支，不在 master 直接 commit
- GitHub "Squash and merge" 合并
- 禁止 force push 到 master
- feature 分支可自由 rebase/squash

冲突处理：`git merge master` on feature branch → 解决 → push → PR 自动刷新。

---

## 9. 动画 Registry 类型安全

**现状：** `src/data/animations/index.ts` 所有 Component 通过 `as ComponentType<{ scenario: AnimScenario; step: number }>` 强制转换。Scenario 的实际类型（如 `ScopeCodeScenario`）和 Component 期望的 props 类型之间没有编译期约束。

```ts
// 当前 — 类型擦除，配错不报错
'scope-lifecycle': {
  scenario: scopeLifecycleScenario,          // ScopeCodeScenario
  Component: ScopeCodePlayer as ComponentType<...>,  // 手工保证匹配
},
// 如果把 BranchPlayer 配给 scopeLifecycleScenario，编译不报错，运行时才崩
```

**触发条件（任一）：** 动画类型 ≥ 8 / 有多人同时加动画 / 出过一次配错 scenario/Component 的线上事故

**方案：** 加一个类型安全的 `defineAnimation` 辅助函数，让编译器自动检查 scenario 和 Component 的类型兼容性，消除 `as` 强制转换。

```ts
// src/data/animations/index.ts

// 类型安全的注册辅助
function defineAnimation<S extends AnimScenario>(
  scenario: S,
  Component: React.ComponentType<{ scenario: S; step: number }>,
): AnimationEntry {
  return { scenario, Component };
}

// 使用 — Component 的 scenario 类型必须和传入的 scenario 兼容
export const animationRegistry = {
  'scope-lifecycle': defineAnimation(scopeLifecycleScenario, ScopeCodePlayer),
  'variable-storage':  defineAnimation(variableStorageScenario, MemoryBox),
  'if-else-branch':    defineAnimation(ifElseBranchScenario, BranchPlayer),
  'for-loop':          defineAnimation(forLoopScenario, LoopPlayer),
  'break-continue':    defineAnimation(breakContinueScenario, BreakContinuePlayer),
  'while-vs-dowhile':  defineAnimation(whileDoWhileScenario, WhileDoWhilePlayer),
  // 如果故意配错：defineAnimation(scopeLifecycleScenario, BranchPlayer)
  // → TypeScript 报错！BranchPlayer 需要 BranchScenario，不是 ScopeCodeScenario
};
```

**改动量：** 只改 `src/data/animations/index.ts` 一个文件 — 加 4 行辅助函数 + 改 registry 条目的写法（去掉 `as ComponentType<...>`，包一层 `defineAnimation()`）。

**不改什么：** `AnimationEntry` 接口不变、`getAnimScenario`/`getAnimComponent` 查找函数不变、所有 Scenario 文件不变、所有 Component 文件不变。

**权衡：** 当前 6 个动画类型，手工维护配错概率低（registry 里 scenario 和 Component 同一个 import 块，一眼可见）。solo 开发时改这个收益不大。但加这个辅助函数只需要 4 行代码 + 改写 6 个条目，改动极小，如果哪天顺手也可以提前做掉。

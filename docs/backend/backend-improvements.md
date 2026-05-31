# 后端三个短期改进方案

> 2026-05-30

---

## 一、加 Swagger API 文档 ✅ 已完成 (2026-05-30)

### 已改

`pom.xml` 加 `springdoc-openapi-starter-webmvc-ui:2.7.0`，`application.yml` 加 `springdoc` 配置，`SecurityConfig.java` 加白名单。

### 原方案

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.7.0</version>
</dependency>
```

**application.yml** 加配置：

```yaml
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
```

完事。启动后端后浏览器打开 `http://localhost:8080/swagger-ui.html`，所有 Controller 的接口自动列出来，还能在线调。

### 效果

```
┌─────────────────────────────────┐
│  Swagger UI                     │
│  ┌─ Auth ──────────────────────┐│
│  │ POST /api/v1/auth/register  ││
│  │ POST /api/v1/auth/login     ││
│  │ ...                         ││
│  └─────────────────────────────┘│
│  ┌─ Progress ──────────────────┐│
│  │ GET  /api/v1/progress       ││
│  │ PUT  /api/v1/progress       ││
│  └─────────────────────────────┘│
│  Try it out → 直接在页面发请求    │
└─────────────────────────────────┘
```

不需要手写任何文档，Controller 代码就是文档。

---

## 二、关掉 open-in-view ✅ 已完成 (2026-05-30)

已加 `spring.jpa.open-in-view: false`，已验证无懒加载在事务外的场景。

### 原方案：问题是什么

Spring Boot 默认 `spring.jpa.open-in-view = true`。意思是：

> 从请求进来到返回，数据库连接一直不还。

翻译成人话：你去餐厅点了菜，但筷子从进门到结账都占着，别人没法用。

目前单用户开发没影响。但以后多用户上线后，一个请求卡住 → 连接池被占满 → 所有人都连不上数据库。

### 要改什么

**1 个文件**：`application.yml`，加一行：

```yaml
spring:
  jpa:
    open-in-view: false
```

### 有没有副作用

改了之后，如果 Service 方法外面（比如 Controller 或 View 层）试图访问懒加载的关联数据，会报 `LazyInitializationException`。

检查了 CodeCard 的代码：**所有数据库操作都在 `@Transactional` Service 方法内完成，没有在事务外访问懒加载数据的场景。** 所以零副作用，放心关。

---

## 三、进度同步版本冲突策略

### 问题是什么

当前 `syncProgress` 的逻辑：

```
客户端发来数据 → 服务端有旧数据？→ 返回旧数据，让客户端自己合并
                → 服务端没数据？→ 直接存
```

服务端不会帮客户端合并，所有冲突甩给客户端处理。等于两台设备同时改了进度 → 其中一个人的数据可能丢。

### 当前数据格式

客户端传来的进度数据（`user_progress.data` JSONB）：

```json
{
  "global": { "totalXP": 1500, "level": 15 },
  "courses": {
    "cpp": {
      "completedCards": { "cpp-01-hello-c1": true, "cpp-01-hello-c2": true },
      "wrongCards": { "cpp-01-hello-c3": true },
      "xp": 300,
      "quizScores": { "cpp-01-quiz": 80 },
      "nodePositions": { "cpp-01": 2 }
    }
  }
}
```

### 方案：乐观锁 + 服务端深度合并

#### 核心思路

```
设备A 读到 version=5 → 离线刷了 3 张卡 → 上传时带 version=5
  ├─ 服务端 version 还是 5 → 没冲突 → 直接存，version 变 6 ✅
  └─ 服务端 version 已经是 7 → 有冲突 → 服务端帮你合并 ✅
```

#### 服务端合并规则（按字段）

| 字段 | 合并规则 | 举例 |
|------|---------|------|
| `completedCards` | **并集** — 两边完成的都算完成 | A 完成了 c1，B 完成了 c2 → 合并后 c1+c2 都完成 |
| `wrongCards` | **并集** — 两边错过的都记录 | 同理 |
| `xp` / `totalXP` | **取最大值** | A 攒了 500，B 攒了 300 → 取 500 |
| `level` | **取最大值** | 由 xp 计算得出，同步后客户端重新算 |
| `quizScores` | **取最高分** | A 考了 80，B 考了 90 → 取 90 |
| `nodePositions` | **取最大值（读到哪了）** | A 读到第 3 张，B 读到第 5 张 → 取 5 |

#### 新 sync 流程

```
POST /api/v1/progress/sync
  Request:  { data: {...}, version: 5 }
              ↓
         服务端当前 version = 7（有人先更新了）
              ↓
         → 深度合并服务端 data + 客户端 data（按上面规则）
         → version = 8（自增）
         → 返回合并后的数据给客户端
              ↓
  Response: { data: {...合并后...}, version: 8, merged: true }
              ↓
         客户端用返回的数据覆盖本地 store
```

#### 要改什么

只改 **1 个文件**：`ProgressService.java`，重写 `syncProgress` 方法。

新增一个私有方法 `mergeProgress`，实现上面的合并规则：

```java
@Transactional
public ProgressSyncResponse syncProgress(UUID userId, ProgressSyncRequest req) {
    return progressRepo.findById(userId)
            .map(remote -> {
                // 版本号相同 → 无冲突，直接更新
                if (req.getVersion() >= remote.getVersion()) {
                    remote.setData(req.getData());
                } else {
                    // 客户端版本落后 → 服务端合并
                    Map<String, Object> merged = mergeData(remote.getData(), req.getData());
                    remote.setData(merged);
                }
                remote.setVersion(remote.getVersion() + 1);
                remote.setUpdatedAt(Instant.now());
                progressRepo.save(remote);

                ProgressSyncResponse resp = toResponse(remote);
                resp.setMerged(true);
                return resp;
            })
            .orElseGet(() -> {
                ProgressSyncResponse resp = upsertProgress(userId, req);
                resp.setMerged(false);
                return resp;
            });
}

@SuppressWarnings("unchecked")
private Map<String, Object> mergeData(Map<String, Object> server, Map<String, Object> client) {
    // 顶层：global + courses
    Map<String, Object> global = deepMerge(
            (Map<String, Object>) server.getOrDefault("global", Map.of()),
            (Map<String, Object>) client.getOrDefault("global", Map.of())
    );
    Map<String, Object> courses = deepMergeCourses(
            (Map<String, Object>) server.getOrDefault("courses", Map.of()),
            (Map<String, Object>) client.getOrDefault("courses", Map.of())
    );

    Map<String, Object> result = new HashMap<>();
    result.put("global", global);
    result.put("courses", courses);
    return result;
}
```

具体的 `deepMerge` 和 `deepMergeCourses` 按上面的合并规则逐字段处理。

#### 为什么不用复杂的 CRDT

CRDT（无冲突数据类型）是分布式协同编辑用的，CodeCard 就两台设备偶尔同步，不值得。

**显式合并规则**的好处：
- 合并逻辑一目了然，出 bug 能看懂是哪个字段合并错了
- ~50 行代码，不需要引入新依赖
- 用户看到 `merged: true` 就知道服务端做了合并

---

## 总结

| # | 问题 | 改几个文件 | 预计工作量 | 上线风险 |
|---|------|-----------|-----------|---------|
| 1 | 缺 Swagger 文档 | 2（pom.xml + yml） | 5 分钟 | 零 |
| 2 | open-in-view 开着 | 1（yml） | 1 分钟 | 零（已验证无懒加载在事务外） |
| 3 | 进度同步无版本冲突 | 1（ProgressService） | 1 小时 | 需测多设备同步场景 |

**建议顺序**：1 → 2 → 3。前两个改配置，第三个改逻辑。

# 数据库操作入门

> 写给初学者，一步步跟着做就行

---

## 一、数据库是什么

你可以把它理解成一个**超级 Excel 表格**，只不过：

- Excel 是你手动填数据
- 数据库是 App 和后端程序自动往里写数据、读数据

你的学习进度、账号密码、登录状态，都存在这里面。

---

## 二、数据库在哪

你的数据库装在 `G:\CodeCard\posterSQL\`，数据存在 `G:\CodeCard\posterSQL\data\`。

它平时是**睡觉状态**（不运行），需要你手动叫醒。

---

## 三、怎么启动数据库

打开终端（Win+R → 输入 `powershell` → 回车），输入：

```powershell
pg_ctl start
```

看到 `server started` 就说明启动了。

**你不需要一直盯着它**，它启动后在后台运行，你可以关掉终端。

---

## 四、怎么停止数据库

```powershell
pg_ctl stop
```

**一般不主动停**。除非电脑特别卡、或者要重装数据库。

---

## 五、怎么启动后端（让 App 能同步数据）

数据库启动后，后端才能连上它。

```powershell
cd G:\CodeCard\backend
mvn spring-boot:run
```

看到 `Started CodeCardApplication` 就说明后端在运行了。

**重要**：
- 数据库和后端**都要开着**，App 才能登录、同步进度
- 两个终端窗口：一个启动数据库（前台也可以），一个跑后端（不能关）
- 数据库一直在后台运行一次就行，后端可以随用随启

---

## 六、怎么查看数据库里有什么

### 方法一：命令行（快，但不太好看）

```powershell
psql -U codecard -d codecard
```

进去后，常用命令：

```
\dt          — 看看有哪些表
\d users     — 看看 users 表的结构（有哪些列）
SELECT * FROM users;         — 查看所有注册用户
SELECT * FROM user_progress;  — 查看所有人的学习进度
\q           — 退出
```

### 方法二：DBeaver（图形化，像 Excel 一样）

你的电脑上已经装了 DBeaver。

1. 打开 DBeaver
2. 左上角点 **新建连接** 图标（一个插座 + 加号）
3. 选 **PostgreSQL**
4. 填写：
   - Host: `localhost`
   - Port: `5432`
   - Database: `codecard`
   - Username: `codecard`
   - Password: `codecard`
5. 点 **测试连接** → 点 **完成**
6. 左边出现 `codecard` → 点开 → `public` → `表` → 双击表名就能看数据了

---

## 七、账号和密码说明

| 用途 | 用户名 | 密码 |
|------|--------|------|
| 超级管理员（安装时设的） | `postgres` | `fwj827296` |
| 项目专用用户（后端连库用） | `codecard` | `codecard` |

平时只用 `codecard` 这个。`postgres` 是装数据库时你设的那个密码。

---

## 八、每次开机后怎么操作

顺序不能反：

```
1. 启动数据库    →  pg_ctl start
2. 启动后端      →  cd G:\CodeCard\backend  →  mvn spring-boot:run
3. App 连接后端  →  后端地址 http://localhost:8080
```

数据库开一次就行（关机会自动停），后端每次开发时手动跑。

---

## 九、安全：SQL 注入是什么、怎么防

### 一句话解释

黑客在输入框里塞代码，骗数据库执行不该执行的命令。

### 举个例子

你登录时输入邮箱 `abc@test.com`，后端本该生成：

```sql
SELECT * FROM users WHERE email = 'abc@test.com';
```

但如果代码是**用户输入直接拼 SQL**（危险写法）：

```java
String sql = "SELECT * FROM users WHERE email = '" + email + "'";
```

黑客在邮箱框输入：

```
' OR 1=1 --
```

拼出来的 SQL 变成：

```sql
SELECT * FROM users WHERE email = '' OR 1=1 --';
```

`OR 1=1` 永远成立 → 跳过密码验证，直接登录任意账号。

### CodeCard 怎么防的

核心原则：**永远不让用户输入和 SQL 混在一起**。

```java
// 安全写法：参数化查询（CodeCard 全部用这种）
@Query("SELECT u FROM User u WHERE u.email = :email")
Optional<User> findByEmail(@Param("email") String email);
```

用户的恶意输入在这里被当成**普通文字**，不是代码。输入 `' OR 1=1 --` 只会去数据库里找"邮箱字段真的等于这个字符串"的行，找不到就返回空。

```java
// 更省事的写法：Spring Data 自动生成 SQL
Optional<User> findByEmail(String email);   // 自动参数化
userRepo.existsByEmail(email);              // 自动参数化
```

### 永远不要做

```java
// ❌ 字符串拼接
String sql = "SELECT * FROM users WHERE email = '" + email + "'";

// ❌ String.format
String sql = String.format("SELECT * FROM users WHERE email = '%s'", email);
```

记住一句话：**框架的 `@Param` / `?` 占位符是防弹衣，字符串拼接是裸奔。** CodeCard 全穿了防弹衣，以后写新功能保持这个习惯。

### PreparedStatement 是什么，我需要手写吗

**不需要。** PreparedStatement 是 JDBC 底层的参数化写法，长这样：

```java
// 底层 JDBC 写法（CodeCard 不需要写这种代码）
PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE email = ?");
ps.setString(1, email);    // 第 1 个 ? 填 email
ps.executeQuery();
```

Spring Data JPA 在底层已经自动帮你做了这件事。`:email` 参数 → JPA 内部转成 `?` → JDBC PreparedStatement → 安全查询。

你平时写 `@Query("... WHERE u.email = :email")` 或 `findByEmail(...)` 就够了。只有当你离开 Spring 框架、裸写 JDBC 时才需要手动 PreparedStatement——CodeCard 项目不存在这种情况。

---

## 十、常见错误

| 错误提示 | 原因 | 解决 |
|----------|------|------|
| `Connection refused` | 数据库没启动 | 先 `pg_ctl start` |
| `角色不存在` | 没指定用户名 | 加 `-U codecard` |
| `JWT_SECRET is not configured` | 缺环境变量 | 已修复，不会再出现 |
| `Schema-validation: missing column` | 数据库结构太旧 | 已修复，不会再出现 |
| `port 8080 already in use` | 上一个后端没关 | 关掉旧终端或重启电脑 |

---

## 十一、一句话总结

```
开机 → pg_ctl start → mvn spring-boot:run → 搞定
想看数据 → 打开 DBeaver → 双击表 → 像 Excel 一样看
```

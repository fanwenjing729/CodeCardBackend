# Nginx + HTTPS + 环境变量分层 设计方案

> 日期：2026-05-29  
> 状态：待实施

---

## 一、整体架构

```
┌─────────────────────────┐
│  React Native (Expo)    │
│  Android / iOS / Web    │
└───────┬─────────────────┘
        │  HTTPS (TLS 1.3)
        ▼
┌─────────────────────────┐
│  Nginx (:443 / :8443)   │  ← SSL 终止、限流、日志
│  nginx.conf             │
└───────┬─────────────────┘
        │  HTTP (内网)
        ▼
┌─────────────────────────┐
│  Spring Boot (:8080)    │  ← 只处理业务逻辑
│  server.port: 8080      │
└─────────────────────────┘
```

---

## 二、环境变量分层

### 2.1 环境矩阵

| 场景 | 运行位置 | 访问地址 | 协议 |
|------|----------|----------|------|
| Android 模拟器 | 本机 | `http://10.0.2.2:8080` | HTTP（开发） |
| iOS 模拟器 | 本机 | `http://localhost:8080` | HTTP（开发） |
| Web 开发 | 浏览器 localhost | `http://localhost:8080` | HTTP（开发） |
| 真机调试 (Dev) | 局域网 | `http://192.168.x.x:8080` | HTTP（开发） |
| 真机调试 (Dev) | 局域网 + Nginx | `https://192.168.x.x:8443` | HTTPS（开发） |
| 预发布 (Staging) | 公网 | `https://staging.codecard.app` | HTTPS |
| 生产 (Prod) | 公网 | `https://api.codecard.app` | HTTPS |

### 2.2 前端代码改造

```ts
// src/lib/api.ts

import { Platform } from 'react-native';

function getBaseUrl(): string {
  // 1. 生产环境：强制使用环境变量
  const prodUrl = process.env.EXPO_PUBLIC_API_URL;
  if (prodUrl) return prodUrl;

  // 2. 开发环境：按平台自动选择
  if (__DEV__) {
    // Android 模拟器
    if (Platform.OS === 'android') {
      return 'http://10.0.2.2:8080/api/v1';
    }
    // iOS 模拟器 / Web
    return 'http://localhost:8080/api/v1';
  }

  // 3. 安全兜底：非开发模式必须配环境变量
  throw new Error(
    'EXPO_PUBLIC_API_URL must be set in production. ' +
    'Example: https://api.codecard.app/api/v1'
  );
}

const BASE_URL = getBaseUrl();
```

### 2.3 各场景打包命令

```bash
# 开发（默认 localhost）
npx expo start

# 真机调试（局域网 IP）
EXPO_PUBLIC_API_URL=http://192.168.1.100:8080/api/v1 npx expo start

# Staging 构建
EXPO_PUBLIC_API_URL=https://staging.codecard.app/api/v1 eas build --profile staging

# 生产构建
EXPO_PUBLIC_API_URL=https://api.codecard.app/api/v1 eas build --profile production
```

### 2.4 环境变量文件（可选）

```bash
# .env.development
EXPO_PUBLIC_API_URL=http://localhost:8080/api/v1

# .env.staging
EXPO_PUBLIC_API_URL=https://staging.codecard.app/api/v1

# .env.production
EXPO_PUBLIC_API_URL=https://api.codecard.app/api/v1
```

---

## 三、Nginx + HTTPS 配置

### 3.1 本地开发环境

只用自签名证书，验证 HTTPS 链路是否正常。

**生成自签名证书：**
```bash
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout localhost.key \
  -out localhost.crt \
  -subj "/CN=localhost" \
  -addext "subjectAltName=IP:127.0.0.1,IP:192.168.1.100"
```

**nginx.conf（开发版）：**
```nginx
worker_processes auto;

events {
    worker_connections 1024;
}

http {
    include       mime.types;
    default_type  application/json;

    # 关闭 Nginx 版本号暴露
    server_tokens off;

    # 限流：每个 IP 每秒最多 10 个请求
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=10r/s;

    # 日志格式（含 traceId 透传）
    log_format main '$remote_addr - [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_trace_id"';

    server {
        listen 8443 ssl;
        server_name localhost;

        ssl_certificate     /path/to/localhost.crt;
        ssl_certificate_key /path/to/localhost.key;

        # 仅允许 TLS 1.2+
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers HIGH:!aNULL:!MD5;

        # 请求限流
        limit_req zone=api_limit burst=20 nodelay;

        # API 代理
        location /api/ {
            proxy_pass http://127.0.0.1:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;

            # 超时
            proxy_connect_timeout 10s;
            proxy_read_timeout 30s;
        }

        # Actuator 健康检查（不暴露外部）
        location /actuator/health {
            proxy_pass http://127.0.0.1:8080;
            allow 127.0.0.1;
            deny all;
        }

        # 拒绝其他路径
        location / {
            return 404;
        }
    }
}
```

**启动：**
```bash
# 1. 启动后端
cd G:\CodeCard\backend
mvn spring-boot:run

# 2. 启动 Nginx（需要先安装 Nginx for Windows）
nginx -c /path/to/nginx.conf

# 3. 真机调试用
EXPO_PUBLIC_API_URL=https://192.168.1.100:8443/api/v1 npx expo start
```

### 3.2 生产部署（Linux 服务器）

**nginx.conf（生产版）：**
```nginx
worker_processes auto;

events {
    worker_connections 2048;
}

http {
    include       mime.types;
    default_type  application/json;
    server_tokens off;

    # 限流
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=20r/s;
    limit_req_zone $binary_remote_addr zone=login_limit:10m rate=5r/m;

    # Gzip 压缩
    gzip on;
    gzip_types application/json;

    # HTTP → HTTPS 重定向
    server {
        listen 80;
        server_name api.codecard.app;
        return 301 https://$host$request_uri;
    }

    server {
        listen 443 ssl http2;
        server_name api.codecard.app;

        # Let's Encrypt 证书（通过 certbot 自动管理）
        ssl_certificate     /etc/letsencrypt/live/api.codecard.app/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/api.codecard.app/privkey.pem;

        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers HIGH:!aNULL:!MD5;
        ssl_prefer_server_ciphers on;
        ssl_session_cache shared:SSL:10m;

        # 全局限流
        limit_req zone=api_limit burst=30 nodelay;

        # 登录接口严格限流
        location /api/v1/auth/login {
            limit_req zone=login_limit burst=3 nodelay;
            proxy_pass http://127.0.0.1:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        location /api/ {
            proxy_pass http://127.0.0.1:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_connect_timeout 10s;
            proxy_read_timeout 30s;
        }

        location /actuator/health {
            proxy_pass http://127.0.0.1:8080;
            allow 127.0.0.1;
            deny all;
        }

        location / {
            return 404;
        }
    }
}
```

### 3.3 Let's Encrypt 证书自动续期

```bash
# Ubuntu/Debian 安装 certbot
sudo apt install certbot python3-certbot-nginx

# 首次签发
sudo certbot --nginx -d api.codecard.app

# certbot 会自动添加定时任务：
# /etc/cron.d/certbot 每天检查，到期前自动续期
```

---

## 四、正式证书申请指南

### 4.1 你需要什么

| 条件 | 说明 | 开发阶段 | 生产阶段 |
|------|------|----------|----------|
| 域名 | 如 `codecard.app` | ❌ 不需要 | ✅ 必需 |
| 公网服务器 | 有固定 IP | ❌ 不需要 | ✅ 必需 |
| DNS 解析 | 域名 → 服务器 IP | ❌ 不需要 | ✅ 必需 |

### 4.2 域名购买（推荐 Cloudflare）

**为什么用 Cloudflare：**
- 域名价格最低（不加价，按注册局成本卖）
- 白送 CDN + DDoS 防护
- 白送 DNS 管理，一键配 Let's Encrypt
- 不需要备案（.app 等国际域名）

**购买流程：**
1. 注册 [cloudflare.com](https://cloudflare.com)
2. 搜索 `codecard.app` → 加入购物车 → 结账（约 ¥70/年）
3. 不需要买任何附加服务，基础价格即可

> **备选：** 阿里云（万网）、腾讯云（DNSPod）也可以，但 `.app` 域名可能需要备案才能在国内服务器用。Cloudflare + 香港/海外服务器免备案。

### 4.3 服务器选购

| 方案 | 配置 | 价格 | 推荐场景 |
|------|------|------|----------|
| 阿里云轻量云 | 2C2G | ¥68/月 | 国内用户，懂备案 |
| 腾讯云轻量云 | 2C2G | ¥68/月 | 同上 |
| 香港轻量云 | 2C2G | ¥34/月 | **免备案，推荐** |
| AWS Lightsail | 2C512M | $5/月 | 海外用户 |

> **推荐：** 香港轻量云（阿里云/腾讯云），¥34/月，免备案，国内访问延迟也低。

### 4.4 证书签发（Let's Encrypt）

开发阶段用自签名证书跑通，生产环境换正式证书。申请完全免费，一条命令：

```bash
# 1. SSH 登录服务器
ssh root@你的服务器IP

# 2. 安装 certbot
sudo apt update
sudo apt install certbot python3-certbot-nginx -y

# 3. 确保 Nginx 已运行且域名已解析到服务器
#    （/etc/nginx/sites-enabled/ 下有你的配置）

# 4. 一键签发 + 自动配置 Nginx
sudo certbot --nginx -d api.codecard.app

# 5. 测试自动续期是否正常
sudo certbot renew --dry-run
```

**之后什么都不用管：**
- certbot 每天检查证书到期时间
- 到期前 30 天自动续期
- Nginx 自动加载新证书，零停机

### 4.5 证书类型对比

| 类型 | 费用 | 有效期 | 信任度 | 适用 |
|------|------|--------|--------|------|
| **自签名** | 免费 | 自己定 | ❌ 浏览器警告 | 本地开发 |
| **Let's Encrypt** | 免费 | 90 天（自动续）| ✅ 全平台信任 | 生产环境 |
| **商业证书** | ¥1000-5000/年 | 1 年 | ✅ 全平台信任 | 只有需要 EV（地址栏绿色）才买 |

**结论：Let's Encrypt 就够了**，99% 的 App/网站用的都是它。

### 4.6 操作顺序（上线前）

```
1. 买域名（Cloudflare，10 分钟）      → codecard.app
2. 买服务器（香港轻量云，5 分钟）       → 获得 IP
3. DNS 解析（Cloudflare 面板）         → codecard.app → 服务器 IP
4. SSH 登录服务器装 Nginx（10 分钟）    → apt install nginx
5. certbot 签发证书（2 分钟）          → HTTPS 生效
6. 部署 Spring Boot jar（5 分钟）       → 后端上线
7. App 配环境变量打出生产包              → EXPO_PUBLIC_API_URL=https://api.codecard.app/api/v1
```

总耗时约 1 小时，总费用约 ¥104（域名 ¥70 + 服务器首月 ¥34）。

---

## 五、Spring Boot 适配

### 5.1 只需要改 Nginx 代理相关配置

`application.yml` 无需大改，补充一点：

```yaml
server:
  port: ${PORT:8080}
  # 告诉 Spring 它在代理后面（Tomcat 不直接暴露）
  forward-headers-strategy: framework

  # 如果直接对外暴露 HTTPS（无 Nginx），可以加：
  # ssl:
  #   key-store: classpath:keystore.p12
  #   key-store-password: ${SSL_KEY_PASSWORD:}
```

Spring Boot 会自动处理 `X-Forwarded-*` 头，生成正确的重定向 URL。

---

## 六、Android 真机调试注意

Android 7+ 默认不信任用户安装的 CA 证书。自签名证书在真机上会报 SSL 错误。

**解决方案：**

1. **开发阶段**：App 配置网络安全性例外
```xml
<!-- android/app/src/main/res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <!-- 开发时的局域网调试 -->
        <domain includeSubdomains="false">192.168.1.100</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

2. **生产构建不包含此配置** — 通过 EAS Build Profile 区分

---

## 七、实施计划

| 阶段 | 内容 | 预估时间 | 前提 |
|------|------|----------|------|
| **Phase 1** | 前端环境变量分层 | 15 分钟 | ~~无~~ ✅ 已完成（2026-05-29）|
| **Phase 2** | 本地 Nginx + 自签名证书 | 30 分钟 | 安装 Nginx for Windows |
| **Phase 3** | 真机 HTTPS 联调 | 20 分钟 | Phase 2 完成 |
| **Phase 4** | 生产服务器 Nginx + Let's Encrypt | 30 分钟 | 有公网服务器 + 域名 |

**当前可以不做的：**
- Phase 4 需要公网服务器和域名，部署前再做
- Phase 3 如果只用模拟器开发也可以跳过
- Phase 1 + Phase 2 可以现在做，验证整套链路

---

## 八、成本评估

| 组件 | 费用 |
|------|------|
| Nginx | 免费 |
| Let's Encrypt 证书 | 免费 |
| 域名 `codecard.app` | ~¥70/年 |
| 服务器（2C4G） | ¥50-100/月（阿里云/腾讯云轻量）|

最简部署方案：一台轻量云服务器 ≈ ¥68/月，域名 ¥70/年。

# Agent Platform

Agent Platform 是一个自托管移动端 Agent 系统。后端负责用户、会话、模型编排和设备连接；Android 客户端通过 WebSocket 上报工具清单并执行工具调用；Web UI 通过 SSE 展示模型输出、工具调用过程和工具结果。

## 功能

- 用户注册、登录和设备绑定
- Web 聊天会话与历史消息
- Android 设备在线状态和动态工具清单
- 大模型工具调用编排
- 相册读取、图片读取、语义相册检索和 UI 自动化工具
- 长期记忆、会话摘要和工具结果工作集
- PostgreSQL / pgvector 存储会话、记忆和照片索引
- Codex Responses 兼容端点与 Anthropic Messages provider 配置

## 架构

```text
Web UI (React + SSE)
        |
        v
Spring Cloud Gateway -- auth-service
        |
        v
agent-service -- chat-service
        |
        v
device-hub-service -- WebSocket / JSON-RPC -- Android foreground service
```

Android 客户端连接后发送 `tool.manifest`。`agent-service` 在会话请求中读取在线设备工具，并把工具定义注入给模型。模型返回工具调用后，`device-hub-service` 通过 WebSocket 转发给 Android 客户端执行。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.6、Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0 |
| 前端 | React、Vite、TypeScript、Tailwind、TanStack Query |
| Android | Kotlin、Jetpack Compose、OkHttp、Foreground Service、Accessibility Service |
| 数据库 | PostgreSQL 16、pgvector、Flyway |
| 实时通信 | WebSocket、JSON-RPC 2.0、SSE |
| 部署 | Docker Compose、GHCR |

## 目录结构

```text
.
|-- pom.xml
|-- docker-compose.yml
|-- docker-compose.ghcr.yml
|-- Dockerfile.spring
|-- common/
|-- auth-service/
|-- gateway/
|-- device-hub-service/
|-- agent-service/
|-- chat-service/
|-- web/
|-- android/
|-- photo-embedding-sidecar/
|-- infra/
`-- docs/
```

## 运行环境

- Docker 24+
- JDK 21
- Node.js 20+
- Corepack / pnpm 9
- Android SDK 和 adb
- 可用的大模型 provider key

## 配置

复制环境变量模板：

```bash
cp .env.example .env
```

主要配置项：

| 配置 | 用途 |
| --- | --- |
| `JWT_SECRET` | 用户 token 和设备 token 签名 |
| `INTERNAL_API_TOKEN` | 服务间接口鉴权 |
| `POSTGRES_PASSWORD` | PostgreSQL 超级用户密码 |
| `AUTH_DB_PASSWORD` | `auth` schema 用户密码 |
| `CHAT_DB_PASSWORD` | `chat` schema 用户密码 |
| `HUB_DB_PASSWORD` | `hub` schema 用户密码 |
| `CODEX_API_KEY` / `OPENAI_API_KEY` | Codex Responses 兼容 provider |
| `ANTHROPIC_API_KEY` | Anthropic Messages provider |
| `MEMORY_EMBEDDING_*` | 长期记忆 embedding |
| `PHOTO_EMBEDDING_*` | 照片语义索引 embedding |
| `SERVER_PUBLIC_URL` | 网关公网地址 |
| `WEB_PUBLIC_URL` | Web UI 公网地址 |

## 启动

启动完整服务栈：

```bash
docker compose --profile default up -d
docker compose --profile default ps
```

Web UI：

```text
http://localhost:3000
```

Gateway：

```text
http://localhost:8080
```

启动本地照片 embedding sidecar：

```bash
docker compose --profile default --profile photo-local up -d
```

## 首次使用

1. 打开 Web UI。
2. 注册账号并登录。
3. 在设备页创建设备绑定令牌。
4. 安装 Android APK。
5. 在 Android 客户端填写服务器地址和绑定令牌，或扫描二维码。
6. 授权客户端所需的通知、媒体读取、前台服务和无障碍权限。
7. 回到 Web UI，确认设备在线后开始聊天。

## Android

构建调试 APK：

```bash
cd android
./gradlew :app:assembleDebug
```

安装 APK：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

新增 Android 工具的入口：

```text
android/app/src/main/java/com/agentplatform/android/tools/
```

工具注册位置：

```text
android/app/src/main/java/com/agentplatform/android/service/AgentForegroundService.kt
```

## 本地开发

启动基础设施：

```bash
docker compose up -d postgres nacos
```

构建后端：

```bash
./mvnw clean package -DskipTests
```

运行后端测试：

```bash
./mvnw test
```

启动 Web 开发服务器：

```bash
cd web
corepack enable
pnpm install
pnpm dev
```

构建 Web：

```bash
cd web
pnpm build
```

## GHCR 镜像

GitHub Actions 工作流：

```text
.github/workflows/publish-ghcr.yml
```

镜像：

```text
ghcr.io/yizhiakuya/agent-platform-auth-service
ghcr.io/yizhiakuya/agent-platform-gateway
ghcr.io/yizhiakuya/agent-platform-device-hub-service
ghcr.io/yizhiakuya/agent-platform-agent-service
ghcr.io/yizhiakuya/agent-platform-chat-service
ghcr.io/yizhiakuya/agent-platform-web
ghcr.io/yizhiakuya/agent-platform-photo-embedding-sidecar
```

使用 GHCR 镜像启动：

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u yizhiakuya --password-stdin
docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr.yml pull
docker compose --profile default -f docker-compose.yml -f docker-compose.ghcr.yml up -d --no-build
```

## 健康检查

Gateway：

```bash
curl -fsS http://localhost:8080/actuator/health
```

容器内服务：

```bash
docker exec agent-platform-auth curl -fsS http://localhost:8081/actuator/health
docker exec agent-platform-agent curl -fsS http://localhost:8082/actuator/health
docker exec agent-platform-hub curl -fsS http://localhost:8083/actuator/health
docker exec agent-platform-chat curl -fsS http://localhost:8084/actuator/health
```

## 服务端口

| 服务 | 默认端口 |
| --- | --- |
| Web UI | `3000` |
| Gateway | `8080` |
| auth-service | `8081` |
| agent-service | `8082` |
| device-hub-service | `8083` |
| chat-service | `8084` |
| PostgreSQL | `5432` |
| Nacos | `8848` |

## 安全

- `.env` 不提交到仓库。
- `.env.example` 只包含占位值。
- 生产环境使用独立的 `JWT_SECRET` 和 `INTERNAL_API_TOKEN`。
- 对公网暴露时配置 HTTPS 和 CORS。
- 设备工具涉及真实手机内容，高风险动作需要用户确认。

## 已知限制

| 项目 | 状态 |
| --- | --- |
| device hub 多实例 | 未实现 |
| FCM push 唤醒 | 未实现 |
| 设备 token 加密存储 | 当前使用 SharedPreferences |
| Playwright + adb 端到端测试 | 部分覆盖 |
| 流式输出中途 provider failover | 未实现 |

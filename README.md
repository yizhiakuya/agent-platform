# Agent Platform

Agent Platform 是一个自托管的移动端 Agent 平台。你可以在自己的服务器或本地机器上运行后端与 Web UI，在 Android 手机上安装客户端，然后通过浏览器和大模型对话，让模型经由 WebSocket 调用手机侧工具，例如相册检索、图片读取、界面观察和受控 UI 操作。

当前产品方向是“服务端大模型编排 + Android 设备工具”。

## 核心能力

- 自托管部署：后端、数据库、Web UI、设备网关都可以运行在自己的环境中。
- 动态工具注册：Android 客户端连接后上报 `tool.manifest`，在线设备的工具会按会话动态注入给大模型。
- 手机工具调用：通过 JSON-RPC 2.0 over WebSocket 执行照片、截图、UI 自动化等设备能力。
- 会话与记忆：聊天记录、工具调用结果、长期记忆和照片索引保存在 PostgreSQL / pgvector 中。
- 多 provider 路由：支持 Codex Responses 兼容端点优先、Anthropic Messages 兜底的 provider 池。
- 语义相册检索：支持通过文本语义、OCR、图片标签和向量索引查找手机相册内容。
- Web 实时体验：React Web UI 通过 SSE 展示模型输出、工具调用过程和工具结果。

## 架构概览

```text
Web UI (React + SSE)
        |
        v
Spring Cloud Gateway -- auth-service
        |
        v
agent-service -- chat-service (会话、消息、记忆、照片索引)
        |
        v
device-hub-service -- WebSocket / JSON-RPC -- Android 前台服务
```

工具是动态的：每个 Android 客户端连接后会发送 `tool.manifest`。`agent-service` 在每一轮对话中把当前在线设备暴露的工具加入模型请求。新增一个手机能力通常只需要在 Android 侧实现一个 `Tool` 类，并在 `AgentForegroundService` 中注册；服务端不需要为每个设备工具手写一个对应接口。

## 技术栈

| 层级 | 技术选择 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.6、Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0 |
| 大模型 | Codex Responses 兼容端点优先，Anthropic Messages fallback |
| 数据库 | PostgreSQL 16 + pgvector，按服务拆分 schema：`auth`、`chat`、`hub` |
| 实时设备通道 | JSON-RPC 2.0 over WebSocket |
| 前端 | React、Vite、TypeScript、Tailwind、TanStack Query |
| Android | Kotlin、Jetpack Compose、OkHttp、前台服务、无障碍/屏幕工具 |
| 部署 | Docker Compose、GHCR 镜像，可选本地照片 embedding sidecar |

## 前置要求

- Docker 24+
- JDK 21
- Node 20+
- Android Studio 或已配置好的 Android SDK，用于构建 APK
- 至少一个可用的大模型 provider key，写入 `.env`

## 快速启动

```bash
cp .env.example .env
```

编辑 `.env`，至少填好下面几类配置：

- `JWT_SECRET`：用于签发用户和设备 token，生产环境必须使用随机强密钥。
- 数据库密码：`POSTGRES_PASSWORD`、`AUTH_DB_PASSWORD`、`CHAT_DB_PASSWORD`、`HUB_DB_PASSWORD`。
- 大模型 provider：`CODEX_API_KEY` / `OPENAI_API_KEY` 或 `ANTHROPIC_API_KEY`。
- 记忆和语义检索 embedding：`MEMORY_EMBEDDING_*`，如果启用照片语义索引，还需要配置 `PHOTO_EMBEDDING_*`。

启动完整栈：

```bash
docker compose up -d
docker compose ps
```

打开 Web UI：

```text
http://localhost:3000
```

首次使用流程：

1. 在 Web UI 注册账号并登录。
2. 在设备页创建一个设备绑定令牌。
3. 安装 Android APK。
4. 在 Android 客户端填写服务器地址和绑定令牌，或扫描 Web UI 里的二维码。
5. 授权通知、媒体读取、前台服务、无障碍等权限。
6. 回到聊天页，确认设备在线后开始对话。

## Android 客户端

在本地构建调试 APK：

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动时，客户端会要求输入服务器地址和 enrollment token。绑定成功后，Android 前台服务会连接 `/ws/device`，并暴露已注册的工具，例如相册工具、图片读取工具和 UI 自动化工具。

Android 端新增工具的基本流程：

1. 在 `android/app/src/main/java/com/agentplatform/android/tools/<domain>/` 下实现 `Tool`。
2. 在 `AgentForegroundService` 中注册该工具。
3. 如果需要新权限，更新 `AndroidManifest.xml` 和绑定页权限提示。
4. 重新构建并安装 APK，设备重连后会自动上报新的 `tool.manifest`。

## 大模型、记忆与 Embedding

`agent-service` 从 `agent-platform.agent.providers` 读取 provider 池。当前默认策略是：

- Codex Responses 兼容端点：`CODEX_API_KEY`、`CODEX_BASE_URL`、`CODEX_CHAT_MODEL`
- Anthropic Messages 端点：`ANTHROPIC_API_KEY`、`ANTHROPIC_BASE_URL`、`LLM_CHAT_MODEL`

空 key 或占位 key 会被跳过。如果所有 provider 都不可用，`agent-service` 会启动失败，避免静默切换到假模型。

长期记忆和语义照片检索使用 embedding 端点：

- `MEMORY_EMBEDDING_*`：用于长期记忆的写入和召回。
- `PHOTO_EMBEDDING_*`：用于照片语义索引。
- `photo-embedding-sidecar`：可选本地多模态 embedding 服务，仅在 `photo-local` Compose profile 下运行。

## 本地开发

后端是 Maven 多模块项目。推荐用本地 JDK/Maven 构建，不把 Docker 当成依赖下载和编译环境。

```bash
./mvnw clean package -DskipTests
```

只启动基础设施，然后从 IDE 或命令行运行 Spring 服务：

```bash
docker compose up -d postgres nacos
```

Web UI 本地开发：

```bash
cd web
npm install
npm run dev
```

Vite dev server 会把 API 请求代理到 gateway。

常用构建命令：

| 命令 | 说明 |
| --- | --- |
| `./mvnw clean package -DskipTests` | 构建后端 Maven 多模块 |
| `./mvnw test` | 运行后端测试 |
| `cd web && npm run build` | 构建 Web UI |
| `cd android && ./gradlew :app:assembleDebug` | 构建 Android 调试 APK |

## GHCR 镜像部署

`.github/workflows/publish-ghcr.yml` 会在推送 `main`、版本 tag 或手动触发时发布镜像。

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u yizhiakuya --password-stdin
docker compose -f docker-compose.yml -f docker-compose.ghcr.yml pull
docker compose -f docker-compose.yml -f docker-compose.ghcr.yml up -d --no-build
```

镜像命名：

```text
ghcr.io/yizhiakuya/agent-platform-auth-service
ghcr.io/yizhiakuya/agent-platform-gateway
ghcr.io/yizhiakuya/agent-platform-device-hub-service
ghcr.io/yizhiakuya/agent-platform-agent-service
ghcr.io/yizhiakuya/agent-platform-chat-service
ghcr.io/yizhiakuya/agent-platform-web
```

实际生产部署推荐先在本地完成 Maven / Web 构建，再把已经构建好的 `target/*.jar` 或 `web/dist` 打进运行镜像。这样可以避免 Docker 构建阶段重复下载依赖，也更容易定位环境问题。

## 健康检查

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8081/actuator/health
curl -fsS http://localhost:8082/actuator/health
curl -fsS http://localhost:8083/actuator/health
curl -fsS http://localhost:8084/actuator/health
```

端到端验证建议直接使用 Web UI：配置可用的大模型 provider，绑定一台 Android 设备，然后在聊天中触发一次真实设备工具调用。无设备 mock 只适合开发和 hub 层测试，不能替代完整聊天链路。

## 仓库结构

```text
.
|-- pom.xml
|-- docker-compose.yml
|-- docker-compose.ghcr.yml
|-- Dockerfile.spring
|-- common/
|   |-- common-api/
|   |-- common-protocol/
|   `-- common-security/
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

## 安全注意事项

- 不要提交 `.env`、私钥、证书、真实 API key 或生产数据库密码。
- `.env.example` 只能放占位值和示例值。
- `JWT_SECRET`、`INTERNAL_API_TOKEN` 和 provider key 在生产环境必须使用独立强密钥。
- 对外公开部署时，应通过反向代理配置 HTTPS、CORS、上传大小限制和必要的访问控制。
- 设备工具可能操作真实手机内容。删除、发送、支付、提交订单等高风险动作必须保留明确确认链路。

## 已知限制

| 项目 | 状态 |
| --- | --- |
| device hub 多实例 | 暂未完成；生产环境默认单实例或依赖粘性路由 |
| FCM push 唤醒 | 暂未完成；Android 依赖前台服务和 WebSocket 重连 |
| 设备 token 加密存储 | 目前仍是普通 SharedPreferences |
| Playwright + adb 端到端测试 | 只有部分覆盖 |
| 流式输出中途 provider failover | 已有启动阶段 failover，流中错误 failover 尚未实现 |

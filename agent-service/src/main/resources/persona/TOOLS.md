# TOOLS

本文件描述当前用户的设备和工具环境。这部分是环境特化的本地知识 — 用户后续可在 web 端覆写本文件以反映自己实际的设备命名和工具偏好。

## Bound Devices

用户绑定了若干 Android 设备,设备 alias 见 device-hub manifest(每个 device 有 `deviceId` 和可选 `alias`,例如 "主力机" / "备用机" / "平板")。

- 用户**没指定**设备时:选第一个 online 的设备,在简短的总结里提一下用了哪台("已用主力机查询"),不要每次都问。
- 用户**指定了**设备(用 alias 或描述,例如 "用平板查"):匹配 alias;如果 alias 不存在,告诉用户当前 online 的设备列表,让用户选。
- **没有 online 设备**:直接说"当前没有 online 的设备",不要假装能调。

## Tool Conventions

- **工具名格式** `domain.action`(例如 `photos.list_recent`、`screen.capture`、`apps.list_installed`)。
- Anthropic API 不允许工具名带点,服务端会自动把 `.` 转成 `_` 再发给 LLM,LLM 看到的是 `photos_list_recent`。**你按服务端给你的工具名调用就行**,内部转换不用管。
- **大二进制字段约定**:返回值里以 `_b64` 结尾的字段(`image_b64`、`thumbnail_b64`、`audio_b64`)是 base64 编码的二进制,**前端会直接渲染**。你的文本回复里:
  - 不要复述、不要引用、不要"展开看"。
  - 可以说"已附上 3 张缩略图",但不要把 base64 字符串本身或它的片段贴出来。
- **参数 schema 严格校验**:不在 schema 里的字段服务端会拒绝。不确定参数怎么填先调最小集合,再按返回 / 报错调整。

## Photo Tool Routing(优先级从精到粗)

相册类工具有 6 个,**先选最精准的**而不是无脑用 list_recent + 过滤。决策树:

| 用户说什么 | 优先用 | 不要用 |
|---|---|---|
| "看截图 / Screenshot / 小黑盒的图" | `photos.recent_screenshots` | `photos.list_recent + name_contains` |
| "我有哪些相册 / 微信相册多少张" | `photos.list_albums` | `list_recent` 然后从文件名瞎猜 |
| "看微信相册的图 / 抖音保存的图" | `photos.list_by_album`(先 list_albums 拿 bucket_id) | `name_contains` |
| "这张是哪儿拍的 / 什么相机 / 几点拍的" | `photos.get_metadata` | 编造元数据 |
| "看清这张 / 放大 / 这张是什么内容" | `photos.get_full(id)` | 沿用之前的小缩略图猜 |
| "我录了什么视频 / 视频列表" | `videos.list_recent` | (没工具,不要瞎答) |
| "最近的图 / 给我看相册" | `photos.list_recent` | 这是兜底 |

## Photo Tool Specifics

- **Android 截图文件名带源 app 包名**(例 `Screenshot_..._com.xiaoheihe.SnsBus.jpg`)。但优先用 `photos.recent_screenshots`,它已经按相册 + 文件名前缀复合过滤,中文 ROM 兼容性更好。
- 系统相机拍的图文件名一般是 `IMG_yyyymmdd_HHMMSS.jpg` 不带 app 标识。
- `limit` 默认保守(5–10),除非用户说"全部 / 都列出来"。
- **时间过滤**用 UNIX 毫秒时间戳(`date_after_ms` / `date_before_ms`),不是 ISO 字符串。"今天"、"昨天"先在自己脑子里转换成毫秒再传。
- **多步组合**:相册场景常常是"先 list_albums 看分组 → 让用户挑(或自己决定)→ list_by_album 拉具体图"。一次拉 50 张是流量浪费。
- **vision 已开**:tool 返回的缩略图你能直接看到内容(渲染成多模态 tool_result),所以可以基于图判断,不用让用户自己描述。但回复里**仍然不要贴 base64 字符串**。

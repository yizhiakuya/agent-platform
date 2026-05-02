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
| "看清这张 / 放大 / 这张是什么内容" | `photos.get_full(id)`(返回 2048px 高清,你能直接看清细节) | 沿用列表里的小缩略图瞎猜内容 |
| "我录了什么视频 / 视频列表" | `videos.list_recent` | (没工具,不要瞎答) |
| "最近的图 / 给我看相册" | `photos.list_recent` | 这是兜底 |

## Tool Call Discipline(每次调 photo / video tool 前过一遍)

1. **过滤一次到位**。用户说"今天 / 昨天 / 本周 / 6 月以来"等时间约束时,**必须**算出对应的 UNIX 毫秒时间戳传 `date_after_ms` / `date_before_ms`,**不要**先拉 10 张再事后筛。拉多了既浪费用户上行带宽,也让你的回复看起来啰嗦("拉了 10 张,但其实只有 1 张是今天的")。

   - 服务器当前北京时间已经在 system 里了,基于此算"今天 0:00"的毫秒戳。

2. **关键 id 写进回复**。每次 tool 返回 photos / videos / albums 后,如果用户**有可能 follow-up 提到**(典型:"放大那张"、"删除第二张"、"那个相册里都有什么"),**在你回复的文本里把对应 id 用反引号写出来**。例:

   > 今天只有 1 张:`id 1000031568`,凌晨 00:55 的小黑盒帖子。

   原因:**你看不到上一轮 tool_result 的原始 JSON 数据**(只看到自己说过的话 + 工具返回的图)。下一轮要 `photos.get_full(id)` / `photos.list_by_album(bucket_id)` 之类,只能从你自己上一回合的文本里捞 id — 文本里没就只能重新调列表,白烧一次 token。

3. **不要无故重复列表调用**。同一会话内,前一轮已经 list_recent / recent_screenshots 过的范围(同 user / 同时间段 / 同 name_contains),如果用户的新问题指向的是同一批图(例:"那张放大看"、"第二张是啥"),**直接调 get_full / get_metadata**,**不要**先重列一遍。

## Photo Tool Specifics

- **Android 截图文件名带源 app 包名**(例 `Screenshot_..._com.xiaoheihe.SnsBus.jpg`)。但优先用 `photos.recent_screenshots`,它已经按相册 + 文件名前缀复合过滤,中文 ROM 兼容性更好。
- 系统相机拍的图文件名一般是 `IMG_yyyymmdd_HHMMSS.jpg` 不带 app 标识。
- `limit` 默认保守(5–10),除非用户说"全部 / 都列出来"。
- **时间过滤**用 UNIX 毫秒时间戳(`date_after_ms` / `date_before_ms`),不是 ISO 字符串。"今天"、"昨天"先在自己脑子里转换成毫秒再传。
- **多步组合**:相册场景常常是"先 list_albums 看分组 → 让用户挑(或自己决定)→ list_by_album 拉具体图"。一次拉 50 张是流量浪费。
- **vision 已开**:tool 返回的缩略图你能直接看到内容(渲染成多模态 tool_result),所以可以基于图判断,不用让用户自己描述。但回复里**仍然不要贴 base64 字符串**。
- **图片分层(关键)**:
  - 列表工具(`list_recent` / `list_by_album` / `recent_screenshots`)只返回 256px **缩略图**(`thumb_b64`),你能粗判主体但看不清细节,**不要硬猜**截图里的具体文字 / 表格内容 / 小图标。
  - 用户问"这张是什么内容 / 放大看 / 看清楚" → 调 `photos.get_full(id)`,返回 **2048px 高清版**(`vision_b64`),你能直接读屏幕里的文字、识别表格、看清表情。**这是分层设计**:列表轻、单张重,你按需要切。
  - 你**不会**同时收到一张图的两份 b64 — 系统已自动把高清版优先给你看,thumb 只去 web 端的缩略图列表。所以放心调 `get_full`,不会重复消耗 token。

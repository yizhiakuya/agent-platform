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

## Photo Tool Specifics

`photos.list_recent` 当前是主力 tool,几个本地知识:

- **Android 截图文件名带源 app 包名**(例如 `Screenshot_20251020-143012_com.xiaoheihe.SnsBus.jpg`)。所以"找小黑盒的截图"用 `name_contains=xiaoheihe` 通常能直接命中,比 `date_after` 之类时间过滤精度更高。
- 系统相机拍的图文件名一般是 `IMG_yyyymmdd_HHMMSS.jpg` 不带 app 标识。要按相机/截图区分,用 `name_contains=Screenshot` 或 `name_contains=IMG_`。
- `limit` 默认保守(5–10),除非用户说"全部 / 都列出来"。
- 时间过滤(`date_after` / `date_before`)接 ISO 8601 字符串。"今天"、"昨天"这种相对时间要先在自己脑子里转换成具体日期再传。

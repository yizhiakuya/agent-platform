# TOOLS

本文件描述当前用户的**设备和工具环境** — 设备别名、动态工具清单、用户可覆写的本地知识。

行为纪律(怎么调、什么时候调、出错怎么办)在 `AGENTS.md`。每个工具的具体用途在工具自身的 description 里。这里**只**记环境事实。

## Bound Devices

用户通过扫码绑定 Android 设备。每个 device 有 `deviceId` 和可选 `alias`(例 `phone1`、`tablet`)。

- 没指定就选第一个 online 的设备;只在多设备场景才提别名。
- 没有 online 设备就直说,不要假装能调。

## Tool Naming

工具名格式 `domain.action`(例 `photos.list_recent`)。Anthropic API 不允许 `.`,服务端把 `.` 转成 `_` 再发给你 — 按 manifest 给的名字调即可。

## Binary Fields

字段名以 `_b64` 结尾的是 base64 二进制。系统:
- 渲染给用户(web 端缩略图);
- 给你看(vision-aware Claude 直接收到图片内容)。

回复不要复述、引用或截取 base64 字符串。

## Time

服务器实时时钟在系统消息的 `# CURRENT TIME` 段(Asia/Shanghai 墙钟 + 今天/明天 0 点的 UNIX 毫秒)。需要时间相关过滤时,把它当作时间锚点;具体参数名、单位、边界包含关系、排序规则按对应工具 schema、description 或 loaded skill body,不要凭印象算。

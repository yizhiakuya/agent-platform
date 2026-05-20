---
name: photos-search
description: Best practices for searching photos via photos.semantic_search and list-style photo tools. Trigger when user asks for photos by app, date, visible text, or content keyword.
---

# Photo management intent routing

Do not treat "整理相册 / 管理相册 / 看看最近图片 / 帮我整理一下" as a
delete-or-cleanup request by default. Default photo management is
non-destructive: browse, group, summarize, suggest albums, rename/move/copy
plans, and ask a clarifying question if the next action is ambiguous.

Only discuss "recommended deletion", "trash", or "cleanup candidates" when the
user explicitly asks for deletion or cleanup intent, for example "删除",
"清理垃圾图", "哪些可以删", "释放空间", "重复图", or "废图". Previous memories
about photo cleanup must not broaden a neutral organization request into a
delete workflow.

For neutral organization requests:

1. Narrow the scope with `photos.semantic_search`, `photos.list_recent`,
   `photos.recent_screenshots`, or album/date/name filters.
2. If several photos need visual judgment, call `photos.get_full_batch` with
   the ids and inspect them together.
3. Report non-destructive organization output: categories, suggested albums,
   noteworthy photos, duplicates to review, or "needs user choice". Do not say
   "不建议删除" unless the user asked about deletion.
4. Use `media.selection.create` only when the user or agent has selected a
   working set for a follow-up action. The selection itself is generic state,
   not a cleanup classifier.

For explicit cleanup/delete requests:

1. Narrow the scope with the same search/list tools. Do not show a huge raw
   grid unless the user explicitly wants to browse all photos.
2. Use `photos.get_full_batch` to inspect multiple candidates together; do not
   fetch one image per tool call unless batch fetch fails or the set is larger
   than the batch limit.
3. Decide in the agent layer which images look disposable, and explain the
   visible candidates by display order and short reasons. The tool layer should
   not hide this judgment inside a business-specific cleanup API.
4. Create a stable working set with `media.selection.create`, including the
   selected ids, display indexes, reasons, and intended action such as `trash`.
5. After the user confirms, call `photos.trash` with the returned
   `selection_id` (or pass `ids` directly for older clients).

# Skill: photos-search

## 什么时候用

用户按 app、日期、文件名、截图文字、画面内容、自然语言描述找相册图片时，优先加载本 skill。

典型请求：

- “找一下猫的照片”
- “找学超发我的聊天截图”
- “找有付款码/二维码/菜单/订单/文档的图”
- “找昨天/上周/5 月 1 号的照片”
- “找小黑盒、微信、B 站、知乎的截图”

## 当前架构

`photos.semantic_search` 现在是索引优先：

1. Android 前台服务后台扫描 MediaStore，上传图片元数据和约 512px JPEG 缩略图到 chat-service。
2. 原图不上传，仍然留在手机上。需要看清细节时再用 `photos.get_full` 按 id 从手机取原图。
3. agent-service 后台把缩略图送到多模态 embedding provider/sidecar，写入 Postgres pgvector。
4. 搜索时先把文本 query 转成同一向量空间的 embedding，在服务端索引里查 top-K。
5. 如果索引为空、embedding provider 不可用，才回退到旧的手机实时扫描 `photos.semantic_candidates`。

不要把 OCR 当成主方案。OCR 只适合截图/文字图；自然照片应靠多模态图片向量索引。

## 工具选择

- 内容语义搜索：用 `photos.semantic_search`。
- 明确要最近照片、指定相册、文件名/app 包名、日期窗口：用 list 工具，范围越窄越好。
- 搜到候选后，若用户问某张图的具体内容、文字、数字、页面细节、图表、聊天原文：必须对对应 `id` 调 `photos.get_full`，让视觉模型看原图后再回答。
- 搜到多张候选并且需要逐张判断内容/垃圾图/重复图时：优先一次调用 `photos.get_full_batch`，传 `ids` 数组和 `max_dim: 1024`，不要连续一张一张调 `photos.get_full`。只有批量工具不可用、失败，或超过批量上限时，才分批/单张补看。

`photos.semantic_search` 的 `photos` 字段只包含最终选中的结果，数量等于 `limit`；正常 `confirmed_only` 搜索不暴露内部候选。只有显式调试/浏览候选时才使用 `display: show_candidates`，这时 `review_candidates` 也只能是不带图片二进制的审计信息。单张搜索优先使用返回的 `primary_image`，如果没有 `primary_image`，按 `next.args.id` 再调 `photos.get_full`。

## 参数原则

- `limit` 是用户最终想要的数量，必须由 agent 按任务显式选择。用户说“一张/那张/最近那张”时用 `limit: 1`；不确定但只需要浏览少量结果时用 `3-5`；用户明确要很多张时再放大。
- `candidate_k` 是语义召回池大小，也由 agent 按任务显式选择。普通精确搜索可以接近 `limit`；“最新/最近 X”、先语义召回再按时间排序、或需要更稳妥比较时，主动放大到足够覆盖可能结果，例如 `20-50`。
- `review_limit` 只控制内部审计/调试候选，不是展示数量；只有需要打开 `display: show_candidates` 调试或浏览候选时才设置。普通搜索不要依赖它让模型看多张图。
- `scan_limit` 只影响旧实时扫描 fallback。索引命中时不依赖手机在线扫描。
- 有 app、相册、日期等线索时传过滤条件，不要无脑放大 `limit`。

示例：

```json
{"query":"猫 宠物 小猫 照片","limit":1,"candidate_k":5}
```

```json
{"query":"学超 微信 聊天记录 消息 截图","name_contains":"mm","limit":3,"candidate_k":12}
```

```json
{"query":"付款码 二维码 收款码 截图","limit":1,"candidate_k":8}
```

## 截图文件名线索

Android 截图文件名常包含来源包名，例如 `Screenshot_<timestamp>_<package>.jpg`。

常见 `name_contains`：

| 用户说法 | name_contains |
| --- | --- |
| 微信 | `mm` |
| QQ | `qq` |
| 小黑盒 | `xiaoheihe` |
| 知乎 | `zhihu` |
| B 站 / bilibili | `bilibili` |
| 抖音 | `douyin` |
| 淘宝 | `taobao` |

不确定包名时，先用显著英文/拼音子串；空结果再放宽。

## 回答规则

- 不要在文本里复述任何 `*_b64` 字段。
- 只把 `photos` / `primary_image` 当作可展示结果；默认不会有 `review_candidates`，即使显式调试时出现，也不要包装成用户结果。
- 搜索空结果时，说明正在放宽条件或索引可能还没完成，不要说相册里一定没有。
- 如果索引还在建立，搜索可能回退到旧实时扫描；这时结果可靠性更低，应谨慎确认。

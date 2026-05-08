---
name: photos-search
description: Best practices for searching photos via photos.semantic_search and list-style photo tools. Trigger when user asks for photos by app, date, visible text, or content keyword.
---

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

`photos.semantic_search` 的 `photos` 字段只包含最终选中的结果，数量等于 `limit`；正常 `confirmed_only` 搜索不暴露内部候选。只有显式调试/浏览候选时才使用 `display: show_candidates`，这时 `review_candidates` 也只能是不带图片二进制的审计信息。单张搜索优先使用返回的 `primary_image`，如果没有 `primary_image`，按 `next.args.id` 再调 `photos.get_full`。

## 参数原则

- `limit` 是用户最终想要的数量。用户说“一张/那张/最近那张”时用 `limit: 1`。
- `review_limit` 只控制内部召回/审计候选，不是展示数量；普通搜索不要依赖它让模型看多张图。
- `scan_limit` 只影响旧实时扫描 fallback。索引命中时不依赖手机在线扫描。
- 有 app、相册、日期等线索时传过滤条件，不要无脑放大 `limit`。

示例：

```json
{"query":"猫 宠物 小猫 照片","limit":1,"review_limit":8}
```

```json
{"query":"学超 微信 聊天记录 消息 截图","name_contains":"mm","limit":3,"review_limit":8}
```

```json
{"query":"付款码 二维码 收款码 截图","limit":1,"review_limit":8}
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

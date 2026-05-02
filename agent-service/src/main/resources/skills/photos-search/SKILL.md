---
name: photos-search
description: Best practices for searching photos via the photos.list_recent tool. Trigger when user asks for photos by app, by date, or by content keyword.
---

# Skill: photos-search — 照片搜索 best practice

## 何时触发

用户说类似下面这些话时加载本 skill:

- "找一下小黑盒的图 / 看看 xx app 的截图"
- "把上周拍的照片列出来 / 5 月 1 号那天的照片"
- "我前两天截的那张图在哪"
- "翻一下相册里所有 xx 相关的图"

简单说:**任何按 app / 按日期 / 按文件名关键字找照片的请求**。

## 执行原则:先窄后广

`photos.list_recent` 支持的过滤参数(以 device 上当前 schema 为准):

- `name_contains`(string)— 文件名子串匹配,**最有用**
- `date_after` / `date_before`(ISO date,如 `2026-04-25`)— 时间窗
- `limit`(int,默认 10,**别超过 10 除非用户明确要全部**)

**第一次查询永远用最窄的 filter**,失败了再放宽。不要一开始就 `limit: 50` 把大量元数据塞进 context。

## Android 截图命名约定

Android 系统截图文件名通常是 `Screenshot_<timestamp>_<package>.jpg`,末段是源 app 的包名。所以"哪个 app 的截图"等价于按包名子串匹配。

常见映射:

| 用户说 | 用 name_contains |
|--------|------------------|
| 小黑盒 | `xiaoheihe` |
| 微信 | `mm`(包名 `com.tencent.mm`)|
| 知乎 | `zhihu` |
| B站 / bilibili | `bilibili` |
| 抖音 | `douyin` |
| Twitter / X | `twitter` |
| 淘宝 | `taobao` |

不确定包名时,先用最显著的英文/拼音子串试一次,空结果再问用户或换关键字。

## 示例:用户 prompt → tool args

| 用户说 | tool args |
|--------|-----------|
| "找一下小黑盒的图" | `{"name_contains": "xiaoheihe", "limit": 10}` |
| "5 月 1 号截的图" | `{"date_after": "2026-05-01", "date_before": "2026-05-02", "limit": 10}` |
| "上周的微信截图" | `{"name_contains": "mm", "date_after": "2026-04-25", "date_before": "2026-05-02", "limit": 10}` |
| "最近的 5 张" | `{"limit": 5}`(不需要 filter)|
| "把所有 b 站截图都列出来" | `{"name_contains": "bilibili", "limit": 30}`(用户明确要"所有",才放宽 limit)|

## 调完之后

- tool 返回的图片字段(`image_b64`、`thumbnail_b64`)前端会直接渲染给用户。**不要在文本里复述这些字段**。
- 用一两句简短总结:"找到 N 张,从 X 到 Y。" 不要给每张图写一段描述。
- 如果窄查询返回空,再考虑放宽(去掉 `name_contains` 或扩大日期范围)— 但要明确告诉用户你在做什么。

---
name: meituan-takeout
description: Operate Meituan Waimai with accessibility-tree-first navigation, stopping before order submission or payment.
---

# Skill: meituan-takeout

Use this skill when the user asks to browse, search, compare, or prepare a Meituan Waimai order.

## Packages

Known installed packages on the user's phone:

- Preferred Waimai app: `com.sankuai.meituan.takeoutnew`
- Fallback Meituan app: `com.sankuai.meituan`
- Related but not for takeout ordering by default: `com.sankuai.youxuan`

Do not assume `ui.open_app` lands on the home page. Android may restore the last foreground activity. On the sampled phone, opening `com.sankuai.meituan.takeoutnew` restored `GlobalSearchActivity` because the user had previously left the app on a search page.

## Operating Model

Prefer accessibility tree navigation over screenshots.

1. Open `com.sankuai.meituan.takeoutnew`.
2. Call `ui.dump_tree` and identify the current page from package, activity hints, text, resource ids, clickable nodes, and scrollable lists.
3. Choose the next action from the tree. Tap the center of the target node bounds.
4. After every navigation, tap, type, or swipe, call `ui.dump_tree` again to confirm state changed.
5. Use `ui.screen_capture` only when the tree is sparse, custom-rendered, or the visible text you need is absent from the tree.

Never use fixed coordinates without first deriving them from the current tree or current screenshot.

## Safety Boundaries

The agent may search, compare merchants, inspect menus, add items to the cart, and navigate to the order confirmation page.

The agent must stop and ask for explicit user confirmation before:

- Submitting an order
- Paying
- Changing delivery address
- Applying a coupon that changes final price in a surprising way
- Buying an item outside the user's stated preference or budget

Before asking for confirmation, summarize merchant, items, quantity, final visible price, delivery fee, estimated delivery time, and delivery address if visible.

## Page Recognition

Recognize pages by stable node patterns, not by a single label.

Search results page:

- Package: `com.sankuai.meituan.takeoutnew`
- Often Activity: `GlobalSearchActivity`
- Common ids/text observed:
  - `com.sankuai.meituan.takeoutnew:id/result_view_pager`
  - `com.sankuai.meituan.takeoutnew:id/txt_search_keyword`
  - `com.sankuai.meituan.takeoutnew:id/img_clear` with desc/text like clear
  - `com.sankuai.meituan.takeoutnew:id/search_change_location`
  - `com.sankuai.meituan.takeoutnew:id/list_poiSearch_poiList`
  - `com.sankuai.meituan.takeoutnew:id/btn_global_cart`
  - Text such as `全部商家`, `广告`, `进店`, `月售`, `起送`, `分钟`, `km`

Search input/action bar:

- Look for editable text node or an `EditText` with id similar to `txt_search_keyword`.
- If the field is not focused, tap its bounds first, then call `ui.type_text` with the full query.
- If there is a clear button (`img_clear`, desc `清除`), clear the old query before typing a new one.

Merchant result cards:

- Prefer cards containing merchant name text plus rating/monthly sales, starting price, delivery fee, distance, and ETA.
- Do not tap ads unless the user explicitly asks for ads/promoted results.
- If a visible card has a child text like `进店`, tap the parent/card or the `进店` button bounds.

Cart:

- A global cart button may appear as id `btn_global_cart`.
- Treat cart actions as potentially consequential. Inspect cart contents and price before proceeding.

## Search And Selection Strategy

When the user gives a food or merchant request:

1. Search the literal user query first.
2. If results are too broad, use tree-visible filters such as distance, sales, delivery time, price, or rating.
3. Compare candidates from visible tree text. Prefer non-ad organic results unless the user asks otherwise.
4. If the user has a budget, keep total visible price under budget including delivery fee where visible.
5. If exact requested item is unavailable, propose the closest alternatives and ask.

For ambiguous requests like "点个午饭" or "来杯奶茶", present 2-3 options instead of silently ordering.

## Recovery

If the current page is not recognized:

- Call `ui.dump_tree` with a larger depth.
- Use `ui.global` back once only if the tree indicates a transient dialog or unexpected detail page.
- If the tree remains sparse, call `ui.screen_capture` and reason visually.
- If locked or the app is in background and cannot be brought forward, tell the user to unlock/open the phone.

If popups appear:

- Close marketing popups when a close/cancel node is visible.
- Do not accept address changes, membership offers, payment prompts, or permission prompts without user confirmation.

## Current Known Limitation

The sampled search page came from Android restoring the user's previous app state, not a cold start from the Waimai home page. Always detect the current page after opening and adapt from there.

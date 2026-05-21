import { expect, test } from './fixtures';
import type { Route } from '@playwright/test';

test.describe('Spatial canvas chat shell', () => {
  test('renders the replicated APP visual preview without login', async ({ page }) => {
    await page.goto('/chat');

    await expect(page.getByText('Preview').first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Agent Session Preview' })).toBeVisible();
    await expect(page.getByRole('textbox')).toBeVisible();
    await expect(page.getByRole('heading', { name: '图片结果' }).last()).toBeVisible({ timeout: 3000 });
  });

  test('loads real session chrome when authenticated', async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem('agent.token', 'test-token');
    });

    await page.route('**/api/sessions', async route => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 's1',
          userId: 'u1',
          title: '测试会话',
          createdAt: '2026-05-15T00:00:00.000Z',
          lastMessageAt: '2026-05-15T00:00:00.000Z'
        }])
      });
    });

    await page.route('**/api/me/devices', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 'd1',
          name: 'Pixel 7 Pro',
          model: 'Pixel 7 Pro',
          osVersion: '14',
          lastSeenAt: '2026-05-15T00:00:00.000Z',
          createdAt: '2026-05-15T00:00:00.000Z'
        }])
      });
    });

    await page.route('**/api/devices/online-status', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          deviceId: 'd1',
          online: true,
          connectedAt: '2026-05-15T00:00:00.000Z',
          toolCount: 12
        }])
      });
    });

    await page.route('**/api/sessions/s1/messages', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'm1',
            sessionId: 's1',
            role: 'USER',
            content: '打开小黑盒',
            createdAt: '2026-05-15T00:01:00.000Z'
          },
          {
            id: 'm2',
            sessionId: 's1',
            role: 'ASSISTANT',
            content: '我会先检查当前页面，然后调用工具确认包名。',
            metadata: { durationMs: 1200 },
            createdAt: '2026-05-15T00:02:00.000Z'
          }
        ])
      });
    });

    await page.goto('/chat');

    await expect(page.getByText('Pixel 7 Pro').first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Agent Session' })).toBeVisible();
    await expect(page.getByRole('textbox')).toBeVisible();
    await page.getByRole('button', { name: 'New session' }).click();
    const sessionButton = page.getByRole('button', { name: /测试会话/ });
    await expect(sessionButton).toBeVisible();
    await sessionButton.hover();
    const preview = page.locator('#session-preview-s1');
    await expect(preview).toBeVisible();
    const rowBox = await sessionButton.locator('xpath=ancestor::div[contains(@class,"group/session")]').boundingBox();
    const previewBox = await preview.boundingBox();
    if (!rowBox || !previewBox) throw new Error('Session preview layout boxes were unavailable');
    expect(previewBox.x).toBeGreaterThan(rowBox.x + rowBox.width);
    expect(rowBox.width / rowBox.height).toBeLessThan(6);
    await expect(preview.locator('> div')).toHaveCSS('background-image', /linear-gradient/);
    await expect(page.getByText('预览记录')).toBeVisible();
    await expect(page.getByText('打开小黑盒')).toBeVisible();
  });

  test('keeps the session list narrow and shows the preview on the right', async ({ page }) => {
    await page.setViewportSize({ width: 1920, height: 920 });
    await page.addInitScript(() => {
      window.localStorage.setItem('agent.token', 'test-token');
      window.localStorage.setItem('agent-platform.activeSessionId', 'wide-1');
    });

    await page.route('**/api/sessions', async route => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'wide-1',
            userId: 'u1',
            title: 'Open app and then inspect the foreground package with a very long title that must truncate',
            createdAt: '2026-05-21T06:42:00.000Z',
            lastMessageAt: '2026-05-21T06:42:00.000Z'
          },
          {
            id: 'wide-2',
            userId: 'u1',
            title: 'Another long historical session title that previously stretched the entire modal row',
            createdAt: '2026-05-21T05:50:00.000Z',
            lastMessageAt: '2026-05-21T05:50:00.000Z'
          }
        ])
      });
    });

    await page.route('**/api/me/devices', async route => {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) });
    });
    await page.route('**/api/devices/online-status', async route => {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) });
    });
    await page.route('**/api/sessions/wide-1/messages', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 'm1', sessionId: 'wide-1', role: 'USER', content: 'Open the app', createdAt: '2026-05-21T06:42:01.000Z' },
          { id: 'm2', sessionId: 'wide-1', role: 'ASSISTANT', content: 'Done.', createdAt: '2026-05-21T06:42:09.000Z' }
        ])
      });
    });

    await page.goto('/chat');

    await page.getByRole('button', { name: /Open app/ }).click();
    const sessionButton = page.locator('button[aria-describedby="session-preview-wide-1"]');
    await expect(sessionButton).toBeVisible();
    await sessionButton.hover();
    const preview = page.locator('#session-preview-wide-1');
    await expect(preview).toBeVisible();

    const rowBox = await sessionButton.locator('xpath=ancestor::div[contains(@class,"session-star-row")]').boundingBox();
    const listBox = await page.locator('.session-star-list').boundingBox();
    const previewBox = await preview.boundingBox();
    if (!rowBox || !listBox || !previewBox) throw new Error('Session preview layout boxes were unavailable');
    expect(listBox.width).toBeLessThanOrEqual(500);
    expect(rowBox.width).toBeLessThanOrEqual(500);
    expect(rowBox.width / rowBox.height).toBeLessThan(6);
    expect(previewBox.x).toBeGreaterThan(rowBox.x + rowBox.width);
    await expect(preview.locator('> div')).toHaveCSS('background-image', /linear-gradient/);
  });

  test('keeps tool progress out of the final assistant bubble', async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem('agent.token', 'test-token');
      window.localStorage.setItem('agent-platform.activeSessionId', 's2');
    });

    await page.route('**/api/sessions', async route => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 's2',
          userId: 'u1',
          title: '你好',
          createdAt: '2026-05-21T05:13:46.000Z',
          lastMessageAt: '2026-05-21T05:14:29.000Z'
        }])
      });
    });

    await page.route('**/api/me/devices', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 'd1',
          name: 'phone',
          model: 'Pixel',
          osVersion: '14',
          lastSeenAt: '2026-05-21T05:14:29.000Z',
          createdAt: '2026-05-21T05:13:46.000Z'
        }])
      });
    });

    await page.route('**/api/devices/online-status', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{ deviceId: 'd1', online: true, connectedAt: '2026-05-21T05:14:29.000Z', toolCount: 33 }])
      });
    });

    await page.route('**/api/sessions/s2/messages', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 'm1', sessionId: 's2', role: 'USER', content: '关闭小黑盒', createdAt: '2026-05-21T05:13:58.000Z' },
          {
            id: 'm2',
            sessionId: 's2',
            role: 'TOOL_CALL',
            content: 'calling ui.run_steps',
            metadata: { tool: 'ui.run_steps', args: { steps: [{ action: 'global', global_action: 'RECENTS' }] } },
            createdAt: '2026-05-21T05:14:15.000Z'
          },
          {
            id: 'm3',
            sessionId: 's2',
            role: 'TOOL_RESULT',
            content: 'tool ui.run_steps returned',
            metadata: { tool: 'ui.run_steps', result: { ok: true, results: [{ action: 'global' }] } },
            createdAt: '2026-05-21T05:14:17.000Z'
          },
          {
            id: 'm4',
            sessionId: 's2',
            role: 'ASSISTANT',
            content: '我先确认小黑盒的包名和关闭方式。我会从最近任务里划掉小黑盒。小黑盒在最近任务里，划掉它。已从最近任务关闭小黑盒。',
            metadata: { durationMs: 31271 },
            createdAt: '2026-05-21T05:14:29.000Z'
          }
        ])
      });
    });

    await page.goto('/chat');

    await expect(page.getByText('已从最近任务关闭小黑盒。')).toBeVisible();
    await expect(page.getByText(/我先确认小黑盒的包名和关闭方式.*已从最近任务关闭小黑盒。/)).not.toBeVisible();
  });

  test('keeps short duplicated action text out of the live final bubble', async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem('agent.token', 'test-token');
      window.localStorage.setItem('agent-platform.activeSessionId', 's3');
    });

    await page.route('**/api/sessions', async route => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 's3',
          userId: 'u1',
          title: '打开小黑盒',
          createdAt: '2026-05-21T05:50:38.000Z',
          lastMessageAt: '2026-05-21T05:50:52.000Z'
        }])
      });
    });

    await page.route('**/api/me/devices', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 'd1',
          name: 'phone',
          model: 'Pixel',
          osVersion: '14',
          lastSeenAt: '2026-05-21T05:50:52.000Z',
          createdAt: '2026-05-21T05:50:38.000Z'
        }])
      });
    });

    await page.route('**/api/devices/online-status', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{ deviceId: 'd1', online: true, connectedAt: '2026-05-21T05:50:52.000Z', toolCount: 33 }])
      });
    });

    let streamCompleted = false;
    let heldMessagesRoute: Route | null = null;
    const duplicated = '打开小黑盒。 已打开小黑盒。';
    const final = '已打开小黑盒。';
    const persistedMessages = JSON.stringify([
      { id: 'm1', sessionId: 's3', role: 'USER', content: '打开小黑盒', createdAt: '2026-05-21T05:50:38.000Z' },
      {
        id: 'm2',
        sessionId: 's3',
        role: 'TOOL_CALL',
        content: 'calling ui.open_app',
        metadata: { tool: 'ui.open_app', args: { package: 'com.max.xiaoheihe' } },
        createdAt: '2026-05-21T05:50:48.000Z'
      },
      {
        id: 'm3',
        sessionId: 's3',
        role: 'TOOL_RESULT',
        content: 'tool ui.open_app returned',
        metadata: { tool: 'ui.open_app', result: { ok: true, opened: true, display_policy: 'debug_only' } },
        createdAt: '2026-05-21T05:50:50.000Z'
      },
      {
        id: 'm4',
        sessionId: 's3',
        role: 'ASSISTANT',
        content: final,
        metadata: { durationMs: 13178 },
        createdAt: '2026-05-21T05:50:52.000Z'
      }
    ]);

    await page.route('**/api/sessions/s3/messages', async route => {
      if (streamCompleted) {
        heldMessagesRoute = route;
        return;
      }
      await route.fulfill({
        contentType: 'application/json',
        body: '[]'
      });
    });

    await page.route('**/api/chat/stream', async route => {
      streamCompleted = true;
      await route.fulfill({
        contentType: 'text/event-stream',
        body: [
          `event: tool_call_started\ndata: ${JSON.stringify({ tool: 'ui.open_app', args: { package: 'com.max.xiaoheihe' } })}\n\n`,
          `event: tool_call_result\ndata: ${JSON.stringify({ tool: 'ui.open_app', result: { ok: true, opened: true, display_policy: 'debug_only' } })}\n\n`,
          `event: assistant_message\ndata: ${JSON.stringify({ content: duplicated })}\n\n`
        ].join('')
      });
    });

    await page.goto('/chat');

    await page.getByRole('textbox').fill('打开小黑盒');
    const sendButton = page.getByRole('button', { name: '发送' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect.poll(() => Boolean(heldMessagesRoute)).toBe(true);
    await expect(page.getByText(final)).toBeVisible();
    await expect(page.getByText(duplicated)).not.toBeVisible();
    await heldMessagesRoute?.fulfill({
      contentType: 'application/json',
      body: persistedMessages
    });
  });
});

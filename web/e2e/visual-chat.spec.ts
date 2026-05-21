import { expect, test } from './fixtures';

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
    await page.getByRole('button', { name: '会话历史' }).first().click();
    const sessionButton = page.getByRole('button', { name: /测试会话/ });
    await expect(sessionButton).toBeVisible();
    await sessionButton.hover();
    await expect(page.getByText('预览记录')).toBeVisible();
    await expect(page.getByText('打开小黑盒')).toBeVisible();
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
});

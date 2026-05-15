import { expect, test } from './fixtures';

test.describe('Chat composer', () => {
  test('keeps input usable and queues the next turn while a session is streaming', async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem('agent.token', 'test-token');
      window.localStorage.setItem('agent-platform.activeSessionId', 's1');
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

    await page.route('**/api/sessions/s1/messages', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: '[]'
      });
    });

    const streamBodies: Array<{ message?: string }> = [];
    let releaseFirst!: () => void;
    const firstMayClose = new Promise<void>(resolve => {
      releaseFirst = resolve;
    });

    await page.route('**/api/chat/stream', async route => {
      const body = JSON.parse(route.request().postData() || '{}') as { message?: string };
      streamBodies.push(body);
      if (streamBodies.length === 1) await firstMayClose;
      await route.fulfill({
        contentType: 'text/event-stream',
        body: `event: assistant_message\ndata: ${JSON.stringify({ content: `收到 ${body.message}` })}\n\n`
      });
    });

    await page.goto('/chat');

    const textbox = page.getByRole('textbox');
    await expect(textbox).toBeEnabled();

    await textbox.fill('第一条');
    await page.getByRole('button', { name: '发送' }).click();

    await expect.poll(() => streamBodies.length).toBe(1);
    await expect(textbox).toBeEnabled();

    await textbox.fill('第二条');
    const queueButton = page.getByRole('button', { name: '排队' });
    await expect(queueButton).toBeEnabled();
    await queueButton.click();

    await expect(page.getByText('已排队 1 条，当前回复结束后自动发送')).toBeVisible();

    releaseFirst();

    await expect.poll(() => streamBodies.length).toBe(2);
    expect(streamBodies.map(body => body.message)).toEqual(['第一条', '第二条']);
  });
});

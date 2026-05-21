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

    await page.goto('/chat/classic');

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

  test('starts a new assistant segment after tool events while streaming', async ({ page }) => {
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

    let streamCompleted = false;
    const progress = '我先确认小黑盒。';
    const final = '已从最近任务关闭小黑盒。';

    await page.route('**/api/sessions/s1/messages', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: streamCompleted ? JSON.stringify([
          { id: 'm1', sessionId: 's1', role: 'USER', content: '关闭小黑盒', createdAt: '2026-05-21T05:13:58.000Z' },
          {
            id: 'm2',
            sessionId: 's1',
            role: 'TOOL_CALL',
            content: 'calling ui.run_steps',
            metadata: { tool: 'ui.run_steps', args: { steps: [{ action: 'global' }] } },
            createdAt: '2026-05-21T05:14:15.000Z'
          },
          {
            id: 'm3',
            sessionId: 's1',
            role: 'TOOL_RESULT',
            content: 'tool ui.run_steps returned',
            metadata: { tool: 'ui.run_steps', result: { ok: true } },
            createdAt: '2026-05-21T05:14:17.000Z'
          },
          {
            id: 'm4',
            sessionId: 's1',
            role: 'ASSISTANT',
            content: `${progress}${final}`,
            metadata: { durationMs: 1200 },
            createdAt: '2026-05-21T05:14:29.000Z'
          }
        ]) : '[]'
      });
    });

    await page.route('**/api/chat/stream', async route => {
      streamCompleted = true;
      await route.fulfill({
        contentType: 'text/event-stream',
        body: [
          `event: assistant_message\ndata: ${JSON.stringify({ content: progress })}\n\n`,
          `event: tool_call_started\ndata: ${JSON.stringify({ tool: 'ui.run_steps', args: { steps: [{ action: 'global' }] } })}\n\n`,
          `event: tool_call_result\ndata: ${JSON.stringify({ tool: 'ui.run_steps', result: { ok: true } })}\n\n`,
          `event: assistant_message\ndata: ${JSON.stringify({ content: final })}\n\n`
        ].join('')
      });
    });

    await page.goto('/chat/classic');

    await page.getByRole('textbox').fill('关闭小黑盒');
    const sendButton = page.getByRole('button', { name: '发送' });
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect(page.getByText(final)).toBeVisible();
    await expect(page.getByText(`${progress}${final}`)).not.toBeVisible();
  });
});

import { expect, test } from './fixtures';
import { Buffer } from 'node:buffer';

const tinyImage = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=';
const tinyImageBody = Buffer.from(tinyImage.split(',')[1], 'base64');
const originalImage = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR42mNk+M9QzwAEjQH/WOq6wwAAAABJRU5ErkJggg==';

test.describe('media gallery result', () => {
  test.beforeEach(async ({ page }) => {
    page.on('dialog', dialog => dialog.accept());
  });

  test('renders album covers and opens a gallery entry in place', async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem('agent.token', 'test-token');
      window.localStorage.setItem('agent-platform.activeSessionId', 'gallery-1');
    });

    await page.route('**/api/sessions', async route => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 'gallery-1',
          userId: 'u1',
          title: '查看相册',
          createdAt: '2026-05-22T00:00:00.000Z',
          lastMessageAt: '2026-05-22T00:01:00.000Z'
        }])
      });
    });

    await page.route('**/api/me/devices', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 'd1',
          name: 'Pixel',
          model: 'Pixel',
          osVersion: '15',
          lastSeenAt: '2026-05-22T00:01:00.000Z',
          createdAt: '2026-05-22T00:00:00.000Z'
        }])
      });
    });

    await page.route('**/api/devices/online-status', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([{ deviceId: 'd1', online: true, connectedAt: '2026-05-22T00:01:00.000Z', toolCount: 34 }])
      });
    });

    await page.route('**/api/sessions/gallery-1/messages', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 'm1', sessionId: 'gallery-1', role: 'USER', content: '查看相册', createdAt: '2026-05-22T00:00:10.000Z' },
          {
            id: 'm2',
            sessionId: 'gallery-1',
            role: 'TOOL_RESULT',
            content: 'tool media.gallery.browse returned',
            metadata: {
              tool: 'media.gallery.browse',
              result: albumsResult()
            },
            createdAt: '2026-05-22T00:00:20.000Z'
          }
        ])
      });
    });

    let browsePayload: any = null;
    let originalPayload: any = null;
    let uploadCount = 0;
    let streamPayload: any = null;
    let trashPayload: any = null;
    let originalDelayMs = 0;
    let originalCompleted = false;
    let lastThumbnailUrl = '';
    await page.route('**/api/chat/media-gallery/browse', async route => {
      browsePayload = JSON.parse(route.request().postData() || '{}');
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(albumGridResult())
      });
    });
    await page.route('**/api/chat/media-gallery/thumbnail**', async route => {
      lastThumbnailUrl = route.request().url();
      await route.fulfill({
        contentType: 'image/png',
        body: tinyImageBody
      });
    });
    await page.route('**/api/chat/media-gallery/original', async route => {
      originalPayload = JSON.parse(route.request().postData() || '{}');
      originalCompleted = false;
      if (originalDelayMs > 0) {
        await new Promise(resolve => setTimeout(resolve, originalDelayMs));
      }
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(originalPhotoResult())
      });
      originalCompleted = true;
    });
    await page.route('**/api/uploads/photos', async route => {
      uploadCount += 1;
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          imageUrl: '/api/uploads/photos/uploaded-1',
          assetId: 'uploaded-1',
          contentType: 'image/png',
          bytes: tinyImageBody.length
        })
      });
    });
    await page.route('**/api/uploads/photos/uploaded-1', async route => {
      await route.fulfill({
        contentType: 'image/png',
        body: tinyImageBody
      });
    });
    await page.route('**/api/chat/stream', async route => {
      streamPayload = JSON.parse(route.request().postData() || '{}');
      await route.fulfill({
        contentType: 'text/event-stream',
        body: 'event: assistant_message\ndata: {"content":"收到"}\n\n'
      });
    });
    let trashShouldFail = true;
    await page.route('**/api/chat/media-gallery/trash', async route => {
      trashPayload = JSON.parse(route.request().postData() || '{}');
      if (trashShouldFail) {
        await route.fulfill({
          status: 502,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Android did not confirm that the selected media was moved to trash.' })
        });
        return;
      }
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          ok: true,
          affected_count: 1,
          trashed: true,
          items: [{ media_type: 'photo', id: '101', media_ref: 'media://photo/101' }]
        })
      });
    });

    await page.goto('/chat/classic');

    const cameraButton = page.getByRole('button', { name: '打开相机' }).first();
    await expect(cameraButton).toBeVisible();
    const cover = cameraButton.getByRole('img', { name: '相机' });
    await expect(cover).toBeVisible();
    await expect.poll(async () => cover.evaluate(img => (img as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
    await expect(page.getByText('browse_args')).not.toBeVisible();

    await cameraButton.click();

    await expect.poll(() => browsePayload?.args?.category).toBe('camera');
    await expect.poll(() => browsePayload?.deviceId).toBeUndefined();
    await expect(page.getByText('IMG_0001.jpg')).toBeVisible();
    await expect(page.getByRole('button', { name: '返回' })).toBeVisible();
    await expect(page.getByRole('button', { name: '下一页' })).toHaveCount(0);
    await expect(page.getByText(/每页/)).not.toBeVisible();
    await expect(page.getByRole('button', { name: '附加选中' })).toHaveCount(0);
    await expect(page.getByTestId('media-gallery-masonry')).toHaveCSS('display', 'grid');
    await expect(page.getByTestId('media-gallery-tile').first()).toHaveCSS('align-self', 'start');

    const tile = page.getByTestId('media-gallery-tile').first();
    originalDelayMs = 350;
    const openStarted = Date.now();
    const openPromise = tile.click();
    const overlay = page.locator('.fixed.inset-0').first();
    await expect(overlay).toBeVisible();
    expect(Date.now() - openStarted).toBeLessThan(300);
    expect(originalCompleted).toBe(false);
    const preview = page.getByRole('img', { name: '预览' });
    await expect(preview).toBeVisible();
    const previewBoxBefore = await preview.boundingBox();
    if (!previewBoxBefore) throw new Error('lightbox preview did not render');
    expect(Math.abs(previewBoxBefore.width / previewBoxBefore.height - 1536 / 2048)).toBeLessThan(0.01);
    await openPromise;
    await expect.poll(() => originalPayload?.id).toBe('101');
    await expect.poll(() => originalPayload?.maxDim).toBe(2048);
    await expect.poll(() => originalPayload?.dateModifiedSec).toBe(1716336000);
    await expect.poll(() => originalPayload?.sizeBytes).toBe(345678);
    await expect.poll(() => originalCompleted).toBe(true);
    await expect.poll(async () => preview.evaluate(img => (img as HTMLImageElement).naturalWidth)).toBe(2);
    const previewBoxAfter = await preview.boundingBox();
    if (!previewBoxAfter) throw new Error('lightbox preview disappeared after original load');
    expect(Math.abs(previewBoxAfter.width - previewBoxBefore.width)).toBeLessThan(1);
    expect(Math.abs(previewBoxAfter.height - previewBoxBefore.height)).toBeLessThan(1);
    await page.locator('.fixed.inset-0').click({ position: { x: 8, y: 8 } });
    originalDelayMs = 0;

    originalPayload = null;
    await tile.click({ button: 'right', position: { x: 80, y: 80 } });
    await expect(page.getByTestId('media-gallery-context-menu')).toBeVisible();
    await page.getByRole('button', { name: '引用图片' }).click();
    await expect(page.getByRole('textbox')).toHaveValue('');
    await expect(page.locator('form').getByRole('button', { name: '移除图片' })).toBeVisible();
    expect(originalPayload).toBeNull();
    await page.locator('form').getByRole('button', { name: /预览图片 IMG_0001\.jpg/ }).click();
    const attachedPreview = page.getByRole('img', { name: '预览' });
    await expect(attachedPreview).toBeVisible();
    await expect.poll(async () => attachedPreview.evaluate(img => (img as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
    await page.locator('.fixed.inset-0').click({ position: { x: 8, y: 8 } });
    expect(originalPayload).toBeNull();

    await page.getByTestId('media-gallery-tile').nth(1).click();
    const videoPreview = page.getByRole('img', { name: '预览' });
    await expect(videoPreview).toBeVisible();
    const videoBox = await videoPreview.boundingBox();
    if (!videoBox) throw new Error('video preview did not render');
    expect(Math.abs(videoBox.width / videoBox.height - 1920 / 1080)).toBeLessThan(0.01);
    expect(videoBox.width).toBeLessThanOrEqual(650);
    expect(originalPayload).toBeNull();
    await expect.poll(() => lastThumbnailUrl).toContain('mediaType=video');
    await expect.poll(() => lastThumbnailUrl).toContain('maxDim=640');
    await expect.poll(() => lastThumbnailUrl).toContain('v=1716336001-456789');
    await page.locator('.fixed.inset-0').click({ position: { x: 8, y: 8 } });

    const tileBox = await tile.boundingBox();
    const surfaceBox = await page.getByTestId('media-gallery-selection-surface').boundingBox();
    if (!tileBox || !surfaceBox) throw new Error('gallery tile or selection surface is not visible');
    const leftEdgeStartX = tileBox.x - 10;
    await page.mouse.move(leftEdgeStartX, tileBox.y + 40);
    await page.mouse.down();
    await page.mouse.move(tileBox.x + 6, tileBox.y + tileBox.height - 6, { steps: 6 });
    await expect(page.getByTestId('media-gallery-selection-box')).toBeVisible();
    await page.mouse.up();
    await expect(tile).toHaveAttribute('data-selected', 'true');
    await tile.click({ button: 'right', position: { x: 80, y: 80 } });
    await page.getByRole('button', { name: '清空选择 1' }).click();
    await expect(tile).toHaveAttribute('data-selected', 'false');

    const secondTile = page.getByTestId('media-gallery-tile').nth(1);
    await tile.click({ modifiers: ['Control'] });
    await expect(tile).toHaveAttribute('data-selected', 'true');
    await expect(page.getByRole('img', { name: '预览' })).toHaveCount(0);
    await secondTile.click({ modifiers: ['Control'] });
    await expect(secondTile).toHaveAttribute('data-selected', 'true');
    await tile.click({ button: 'right', position: { x: 80, y: 80 } });
    await page.getByRole('button', { name: '清空选择 2' }).click();
    await expect(tile).toHaveAttribute('data-selected', 'false');
    await expect(secondTile).toHaveAttribute('data-selected', 'false');

    originalPayload = null;
    await tile.click({ button: 'right', position: { x: 80, y: 80 } });
    await page.getByRole('button', { name: '查看图片' }).click();
    await expect.poll(() => originalPayload?.id).toBe('101');
    await page.locator('.fixed.inset-0').click({ position: { x: 8, y: 8 } });

    await tile.click({ button: 'right', position: { x: 80, y: 80 } });
    await page.getByRole('button', { name: '选择图片' }).click();
    await expect(tile).toHaveAttribute('data-selected', 'true');
    await tile.click({ button: 'right', position: { x: 80, y: 80 } });
    await page.getByRole('button', { name: '移到回收站' }).click();
    await expect.poll(() => trashPayload?.args?.items?.[0]?.id).toBe('101');
    await expect(page.getByText('Android did not confirm that the selected media was moved to trash.')).toBeVisible();
    await expect(page.getByTestId('media-gallery-tile')).toHaveCount(2);

    trashShouldFail = false;
    await tile.click({ button: 'right', position: { x: 80, y: 80 } });
    await page.getByRole('button', { name: '移到回收站' }).click();
    await expect(page.getByText('已移到回收站 1 项。')).toBeVisible();
    await expect(page.getByTestId('media-gallery-tile')).toHaveCount(1);

    originalPayload = null;
    await page.getByRole('button', { name: '发送' }).click();
    await expect.poll(() => originalPayload?.id).toBe('101');
    await expect.poll(() => originalPayload?.dateModifiedSec).toBe(1716336000);
    await expect.poll(() => originalPayload?.sizeBytes).toBe(345678);
    await expect.poll(() => uploadCount).toBe(1);
    await expect.poll(() => streamPayload?.attachments?.[0]?.mediaId).toBe('101');
    await expect.poll(() => streamPayload?.attachments?.[0]?.source).toBe('media_gallery');
    await expect(page.locator('form').getByRole('button', { name: '移除图片' })).toHaveCount(0);
  });
});

function albumsResult() {
  return {
    ok: true,
    result_type: 'results',
    display_policy: 'show_gallery',
    view: 'albums',
    title: '影集',
    count: 2,
    sections: [{
      title: '常用',
      entries: [{
        entry_type: 'category',
        key: 'category:camera',
        title: '相机',
        count: 2,
        category: 'camera',
        cover_thumb_url: '/api/chat/media-gallery/thumbnail?mediaType=photo&id=101&maxDim=256&deviceId=d1',
        browse_args: { view: 'category', category: 'camera', limit: 40, offset: 0, max_dim: 640 }
      }]
    }]
  };
}

function albumGridResult() {
  return {
    ok: true,
    result_type: 'results',
    display_policy: 'show_gallery',
    view: 'category',
    title: '相机',
    category: 'camera',
    count: 2,
    items: [
      {
        media_type: 'photo',
        id: '101',
        name: 'IMG_0001.jpg',
        bucket_name: 'Camera',
        width: 1536,
        height: 2048,
        date_modified_sec: 1716336000,
        size_bytes: 345678,
        thumb_url: '/api/chat/media-gallery/thumbnail?mediaType=photo&id=101&maxDim=256&deviceId=d1',
        media_ref: 'media://photo/101'
      },
      {
        media_type: 'video',
        id: '202',
        name: 'VID_0001.mp4',
        bucket_name: 'Camera',
        width: 1920,
        height: 1080,
        date_modified_sec: 1716336001,
        size_bytes: 456789,
        duration_ms: 9000,
        thumb_url: '/api/chat/media-gallery/thumbnail?mediaType=video&id=202&maxDim=256&deviceId=d1',
        media_ref: 'media://video/202'
      }
    ],
    pagination: {
      offset: 0,
      limit: 40,
      returned_count: 2,
      start_index: 1,
      end_index: 2,
      has_more: false
    }
  };
}

function originalPhotoResult() {
  return {
    ok: true,
    result_type: 'confirmed',
    display_policy: 'show_primary',
    id: '101',
    name: 'IMG_0001.jpg',
    image_url: originalImage,
    image_width: 2048,
    image_height: 1536
  };
}

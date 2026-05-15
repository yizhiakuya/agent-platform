import { expect, test } from './fixtures';

test.describe('Login', () => {
  test('shows the login form by default', async ({ page }) => {
    await page.goto('/');

    await expect(page.getByRole('heading', { name: '登录' })).toBeVisible();
    await expect(page.getByLabel('用户名')).toHaveValue('admin');
    await expect(page.getByLabel('密码')).toBeVisible();
    await expect(page.getByRole('button', { name: '登录' })).toBeVisible();
  });

  test('can switch to registration mode', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: '还没有账号?去注册' }).click();

    await expect(page.getByRole('heading', { name: '注册账号' })).toBeVisible();
    await expect(page.getByRole('button', { name: '注册账号' })).toBeVisible();
  });
});

import { expect, test } from '@playwright/test'

test('retired playground redirects to the dashboard', async ({ page }) => {
  await page.route('**/zixuan/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname.replace(/^.*\/zixuan/, '')
    const json = (data: unknown) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 0, msg: 'success', data }),
    })
    if (path === '/user/info') return json({ id: 7, username: 'demo', superAdmin: 0, status: 1 })
    return json([])
  })
  await page.addInitScript(() => localStorage.setItem('companion-console.session', JSON.stringify({ token: 'browser-token' })))
  await page.goto('/playground')
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByText('操练场')).toHaveCount(0)
  await expect(page.getByTitle('陪伴对话')).toHaveCount(0)
})

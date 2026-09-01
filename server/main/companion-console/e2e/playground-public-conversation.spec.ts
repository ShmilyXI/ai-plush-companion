import { expect, test } from '@playwright/test'

test('playground embeds the standalone companion web app', async ({ page }, testInfo) => {
  await page.route('**/xiaozhi/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname.replace(/^.*\/xiaozhi/, '')
    const json = (data: unknown) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 0, msg: 'success', data }),
    })
    if (path === '/user/info') return json({ id: 7, username: 'demo', superAdmin: 0, status: 1 })
    if (path === '/api/v1/web-sessions/bootstrap' && route.request().method() === 'POST') return json({ code: 'bootstrap-1', expiresIn: 300 })
    return json([])
  })
  await page.route('http://127.0.0.1:8010/**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>Companion</title><p>独立陪伴应用</p>' })
  })
  await page.addInitScript(() => localStorage.setItem('companion-console.session', JSON.stringify({ token: 'browser-token' })))
  await page.goto('/playground')
  await expect(page.getByRole('heading', { name: '操练场' })).toBeVisible()
  await expect(page.getByTitle('陪伴对话')).toBeVisible()
  await expect(page.getByTitle('陪伴对话')).toHaveAttribute('allow', /microphone/)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy()
  await page.screenshot({ path: testInfo.outputPath(`playground-${testInfo.project.name}.png`), fullPage: true })
})

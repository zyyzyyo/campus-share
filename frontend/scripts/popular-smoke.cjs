// Use an isolated demo backend. Creates two accounts and two items.
const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const baseURL = process.env.FRONTEND_URL || 'http://127.0.0.1:15174'
const output = process.env.SMOKE_OUTPUT || path.resolve(__dirname, '../../backend/target/browser-tests')

async function main() {
  fs.mkdirSync(output, { recursive: true })
  const http = await request.newContext({ baseURL })
  const stamp = Date.now().toString(36)
  async function api(url, token, method = 'GET', data) {
    const response = await http.fetch('/api' + url, { method, data, headers: token ? { Authorization: `Bearer ${token}` } : {} })
    assert.ok(response.ok(), `${method} ${url}: ${response.status()} ${await response.text()}`)
    return (await response.json()).data
  }
  async function account(role) {
    const username = `${role}_${stamp}`
    await api('/auth/register', null, 'POST', { username, password: 'CampusPass123!', nickname: role })
    return api('/auth/login', null, 'POST', { username, password: 'CampusPass123!' })
  }
  const owner = await account('popularowner'), viewer = await account('popularviewer')
  const itemBody = title => ({ title, description: '热榜联调物品，功能完好', categoryId: 1, campus: '东校区', pickupLocation: '图书馆' })
  const camera = await api('/items', owner.accessToken, 'POST', itemBody('热门测试相机'))
  const lamp = await api('/items', owner.accessToken, 'POST', itemBody('热门测试台灯'))
  const browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const errors = []
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  page.on('pageerror', error => errors.push(error.message))
  try {
    await page.goto(baseURL); await page.waitForLoadState('networkidle')
    await page.getByText('今天还没有热门物品，', { exact: false }).waitFor()
    await api(`/items/${camera.id}`, owner.accessToken)
    assert.equal((await api('/items/popular')).items.length, 0, 'Owner view must not count')
    await api(`/items/${camera.id}`, viewer.accessToken)
    await api(`/items/${camera.id}`, viewer.accessToken)
    await api(`/items/${camera.id}`)
    await api(`/items/${lamp.id}`, viewer.accessToken)
    const ranking = await api('/items/popular')
    assert.equal(ranking.items[0].item.id, camera.id)
    assert.equal(ranking.items[0].views, 2, 'Repeated viewer must be deduplicated')
    await page.getByRole('button', { name: '刷新热榜' }).click()
    const first = page.locator('.popular-card').first()
    await first.getByText(camera.title, { exact: true }).waitFor()
    await first.click()
    await page.getByRole('dialog').getByRole('heading', { name: camera.title, exact: true }).waitFor()
    await page.getByRole('button', { name: '关闭弹窗' }).click()
    await api(`/items/${camera.id}`, owner.accessToken, 'PUT', itemBody('修改后的热门相机'))
    const updated = await api(`/items/${camera.id}`, viewer.accessToken)
    assert.equal(updated.title, '修改后的热门相机', 'Cache must be invalidated after edit')
    await page.getByRole('button', { name: '刷新热榜' }).click()
    await first.getByText('修改后的热门相机', { exact: true }).waitFor()
    await page.screenshot({ path: path.join(output, 'popular-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: path.join(output, 'popular-mobile.png'), fullPage: true })
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Mobile horizontal overflow')

    await api(`/items/${lamp.id}/status`, owner.accessToken, 'PATCH', { status: 'OFF_SHELF' })
    await page.getByRole('button', { name: '刷新热榜' }).click()
    await page.waitForFunction(() => document.querySelectorAll('.popular-card').length === 1)
    assert.equal((await api(`/items/${lamp.id}`, viewer.accessToken)).status, 'OFF_SHELF')

    // Simulate only the optional hot-list service being unavailable.
    await page.route('**/api/items/popular?*', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', data: { available: false, items: [] } }) }))
    await page.getByRole('button', { name: '刷新热榜' }).click()
    await page.getByText('热榜暂时不可用，请稍后重试。', { exact: false }).waitFor()
    await page.unroute('**/api/items/popular?*')
    await page.locator('.popular-notice').getByRole('button', { name: '重试' }).click()
    await first.getByText('修改后的热门相机', { exact: true }).waitFor()
    assert.deepEqual(errors, [])
    console.log('PASS: empty list, owner exclusion, visitor deduplication, sorted ranking, cache invalidation, detail dialog, off-shelf filtering, unavailable/retry, desktop and mobile; no browser errors')
  } catch (error) {
    await page.screenshot({ path: path.join(output, 'popular-failure.png'), fullPage: true }).catch(() => {})
    throw error
  } finally { await browser.close(); await http.dispose() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })

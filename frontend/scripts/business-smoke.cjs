// Run against an isolated demo server. Creates test users and items.
// PLAYWRIGHT_MODULE may point to a separately installed Playwright package.
const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
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
  const owner = await account('owner'), borrower = await account('borrower')
  const browser = await chromium.launch({ headless: true, channel: 'msedge' })
  const errors = []
  try {
    async function pageFor(account) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
      await context.addInitScript(token => sessionStorage.setItem('campus-access-token-v2', token), account.accessToken)
      const page = await context.newPage()
      page.on('pageerror', error => errors.push(error.message))
      page.on('dialog', dialog => dialog.accept())
      await page.goto(baseURL); await page.waitForLoadState('networkidle')
      return page
    }
    const lender = await pageFor(owner), student = await pageFor(borrower)
    async function nav(page, label) { await page.locator('nav').getByRole('button', { name: label }).click(); await page.waitForLoadState('networkidle') }
    async function createItem(title) {
      return api('/items', owner.accessToken, 'POST', { title, description: '浏览器联调物品', categoryId: 1, campus: '东校区', pickupLocation: '图书馆' })
    }
    async function apply(item, startTime, endTime) {
      return api('/orders', borrower.accessToken, 'POST', { itemId: item.id, startTime: startTime.toISOString(), endTime: endTime.toISOString() })
    }
    const now = Date.now(), camera = await createItem('浏览器续期相机')
    const order = await apply(camera, new Date(now + 3600000), new Date(now + 7200000))
    await nav(lender, '借出管理')
    await lender.getByRole('button', { name: '同意申请', exact: true }).click()
    await lender.getByRole('button', { name: '确认领取', exact: true }).click()
    await nav(student, '我的借用')
    await student.getByRole('button', { name: '申请续期', exact: true }).click()
    const future = new Date(now + 10800000)
    const localInput = new Date(future.getTime() - future.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
    await student.getByLabel('希望归还的时间').fill(localInput)
    await student.getByRole('button', { name: '确认提交', exact: true }).click()
    await student.getByText('续期待审核：', { exact: false }).waitFor()
    await lender.getByRole('button', { name: '刷新订单' }).click()
    await lender.getByRole('button', { name: '同意续期', exact: true }).click()
    await lender.locator('.order-card').filter({ hasText: camera.title }).getByText('续期已通过：', { exact: false }).waitFor()
    assert.equal((await api(`/orders/${order.id}`, borrower.accessToken)).renewalStatus, 'APPROVED')
    await nav(student, '消息通知')
    await student.locator('.notification-card').filter({ hasText: '续期申请已通过' }).getByRole('button', { name: '查看订单' }).click()
    await student.getByRole('dialog').getByText('续期已通过：', { exact: false }).waitFor()
    await student.getByRole('button', { name: '关闭弹窗' }).click()

    const lamp = await createItem('浏览器取消台灯')
    await apply(lamp, new Date(now + 3600000), new Date(now + 7200000))
    await lender.getByRole('button', { name: '刷新订单' }).click()
    await lender.locator('.order-card').filter({ hasText: lamp.title }).getByRole('button', { name: '取消并说明原因' }).click()
    await lender.getByLabel('取消原因').fill('台灯需要维修，暂时不能交付')
    await lender.getByRole('button', { name: '确认提交', exact: true }).click()
    await lender.getByText('发布者取消原因：台灯需要维修，暂时不能交付').waitFor()
    await student.getByRole('button', { name: '刷新消息' }).click()
    await student.getByText('发布者已取消订单，原因：台灯需要维修，暂时不能交付').waitFor()

    const ball = await createItem('浏览器逾期篮球'), shortNow = Date.now()
    const overdue = await apply(ball, new Date(shortNow + 4000), new Date(shortNow + 12000))
    await api(`/orders/${overdue.id}/actions/approve`, owner.accessToken, 'PATCH')
    await api(`/orders/${overdue.id}/actions/pickup`, owner.accessToken, 'PATCH')
    await nav(student, '我的借用')
    await student.waitForTimeout(Math.max(0, shortNow + 12500 - Date.now()))
    await student.getByRole('button', { name: '刷新订单' }).click()
    const heldCard = student.locator('.order-card').filter({ hasText: ball.title })
    await heldCard.getByText('已逾期', { exact: false }).waitFor()
    await lender.getByRole('button', { name: '刷新订单' }).click()
    await lender.locator('.order-card').filter({ hasText: ball.title }).getByRole('button', { name: '提醒归还' }).click()
    await lender.getByText('已提醒归还，每天最多发送一次').waitFor()
    await nav(student, '消息通知')
    await student.getByText('发布者提醒你尽快归还：' + ball.title).waitFor()
    await student.getByRole('button', { name: '全部已读' }).click()
    assert.equal(await api('/notifications/unread-count', borrower.accessToken), 0)
    await student.setViewportSize({ width: 390, height: 844 })
    await student.screenshot({ path: path.join(output, 'notifications-mobile.png'), fullPage: true })
    assert.ok(await student.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Mobile horizontal overflow')
    await student.setViewportSize({ width: 1440, height: 1000 })
    await nav(student, '我的借用')
    await heldCard.getByRole('button', { name: '申请归还' }).click()
    await lender.getByRole('button', { name: '刷新订单' }).click()
    await lender.locator('.order-card').filter({ hasText: ball.title }).getByRole('button', { name: '确认归还' }).click()
    await lender.locator('.order-card').filter({ hasText: ball.title }).getByText('已完成', { exact: true }).waitFor()
    assert.equal((await api(`/orders/${overdue.id}`, borrower.accessToken)).overdue, false)
    await lender.screenshot({ path: path.join(output, 'orders-desktop.png'), fullPage: true })
    assert.deepEqual(errors, [])
    console.log('PASS: renewal, notifications, cancellation reason, overdue reminders, return, mobile layout; no browser errors')
  } catch (error) {
    for (const [index, context] of browser.contexts().entries()) {
      const page = context.pages()[0]
      if (page) await page.screenshot({ path: path.join(output, `failure-${index}.png`), fullPage: true }).catch(() => {})
    }
    throw error
  } finally { await browser.close(); await http.dispose() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })

// Optional browser check: npm install --no-save --package-lock=false playwright
// Run with the Vite server running: node scripts/smoke.mjs
import { chromium } from 'playwright'
import assert from 'node:assert/strict'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  // External images and fonts are cosmetic; test the UI without network dependency.
  await page.route('https://**', route => route.abort())
  await page.goto('http://127.0.0.1:5173')
  await page.waitForLoadState('networkidle')
  await page.locator('.item-card').first().waitFor()
  assert.equal(await page.locator('.item-card').count(), 6)
  await page.getByRole('button', { name: '运动装备', exact: true }).click()
  assert.equal(await page.locator('.item-card').count(), 2)
  await page.getByRole('button', { name: '全部物品', exact: true }).click()
  await page.getByRole('textbox', { name: '搜索物品' }).fill('不存在的物品')
  await page.getByRole('button', { name: '清除筛选', exact: true }).click()
  assert.equal(await page.locator('.item-card').count(), 6)
  await page.getByRole('button', { name: '收藏物品', exact: true }).first().click()
  await page.getByRole('button', { name: '我的收藏', exact: true }).click()
  assert.equal(await page.locator('.item-card').count(), 1)
  await page.getByRole('button', { name: '登录 / 注册', exact: true }).click()
  await page.getByLabel('你的昵称').fill('测试同学')
  await page.getByRole('button', { name: '进入体验', exact: true }).click()
  await page.getByRole('button', { name: '发现物品', exact: true }).click()
  await page.getByRole('button', { name: '去看看', exact: true }).first().click()
  function localInput(date) {
    return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}T${String(date.getHours()).padStart(2,'0')}:${String(date.getMinutes()).padStart(2,'0')}`
  }
  await page.getByLabel('开始时间').fill(localInput(new Date(Date.now() + 86400000)))
  await page.getByLabel('结束时间').fill(localInput(new Date(Date.now() + 90000000)))
  await page.getByRole('button', { name: '提交预约申请', exact: true }).click()
  await page.locator('.order-card').waitFor()
  assert.equal(await page.locator('.order-status').textContent(), '待审核')
  await page.getByRole('button', { name: '取消申请', exact: true }).click()
  assert.equal(await page.locator('.order-status').textContent(), '已取消')
  await page.getByRole('button', { name: '发布闲置', exact: true }).click()
  await page.getByLabel('物品名称').fill('测试台灯')
  await page.getByLabel('物品描述').fill('功能正常，含充电线')
  await page.getByRole('button', { name: '发布物品', exact: true }).click()
  assert.equal(await page.locator('.item-card').count(), 1)
  await page.getByRole('button', { name: '下架', exact: true }).click()
  assert.equal(await page.locator('.status-tag').textContent(), '暂不可借')
  await page.reload()
  await page.waitForLoadState('networkidle')
  assert.equal(await page.locator('.item-card').count(), 7)
  if (process.env.QA_SCREENSHOTS) await page.screenshot({ path: `${process.env.QA_SCREENSHOTS}/desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'mobile must not overflow')
  if (process.env.QA_SCREENSHOTS) await page.screenshot({ path: `${process.env.QA_SCREENSHOTS}/mobile.png`, fullPage: true })
  await page.getByRole('button', { name: '展开导航', exact: true }).click()
  await page.getByRole('button', { name: '我的借用', exact: true }).click()
  assert.equal(await page.locator('.order-status').textContent(), '已取消')
  assert.deepEqual(errors, [])
  console.log('PASS: filters, search, favorites, login, booking, cancellation, publishing, unlisting, persistence, mobile layout; no runtime errors.')
} finally {
  await browser.close()
}

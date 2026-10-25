"""Real frontend/backend browser smoke test; start both servers before running.

pip install playwright
python scripts/smoke.py
Uses installed Microsoft Edge; creates two uniquely named test accounts/items.
"""
import base64
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

from playwright.sync_api import sync_playwright, expect

BASE_URL = os.environ.get("FRONTEND_URL", "http://127.0.0.1:5173")
PREFIX = uuid4().hex[:10]
TITLE = f"联调相机 {PREFIX}"
PASSWORD = "CampusPass123!"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")


def navigate(page, label):
    if page.viewport_size["width"] <= 700:
        page.get_by_role("button", name="展开导航", exact=True).click()
    page.locator(".sidebar nav").get_by_role("button", name=label).click()


def register(page, username):
    page.goto(BASE_URL)
    page.wait_for_load_state("networkidle")
    page.get_by_role("button", name="登录 / 注册", exact=True).click()
    page.get_by_role("button", name="注册账号", exact=True).click()
    page.get_by_label("账号", exact=True).fill(username)
    page.get_by_label("昵称", exact=True).fill("同名同学")
    page.get_by_label("密码", exact=True).fill(PASSWORD)
    page.get_by_role("button", name="注册并登录", exact=True).click()
    expect(page.locator(".dialog")).to_have_count(0)
    expect(page.locator(".user-button")).to_contain_text("同名同学")


with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    errors = []
    try:
        owner_context = browser.new_context(viewport={"width": 1440, "height": 1000}, timezone_id="Asia/Shanghai")
        borrower_context = browser.new_context(viewport={"width": 1440, "height": 1000}, timezone_id="Asia/Shanghai")
        owner, borrower = owner_context.new_page(), borrower_context.new_page()
        for page in (owner, borrower):
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.route("https://fonts.googleapis.com/**", lambda route: route.abort())

        register(owner, f"owner_{PREFIX}")
        owner.get_by_role("button", name="发布闲置", exact=True).click()
        owner.get_by_label("物品名称", exact=True).fill(TITLE)
        owner.get_by_label("领取位置", exact=True).fill("图书馆一楼")
        owner.get_by_label("物品描述", exact=True).fill("真实接口联调，附充电器与相机包")
        owner.locator('input[type="file"]').set_input_files({"name": "camera.png", "mimeType": "image/png", "buffer": PNG})
        owner.get_by_role("button", name="发布物品", exact=True).click()
        owner_card = owner.locator(".item-card").filter(has_text=TITLE)
        expect(owner_card).to_be_visible()
        owner.reload()
        owner.wait_for_load_state("networkidle")
        navigate(owner, "我的发布")
        expect(owner_card).to_be_visible()
        expect(owner_card.locator("img")).to_have_attribute("src", __import__("re").compile(r"/api/media/.*\.png"))

        register(borrower, f"borrower_{PREFIX}")
        search = borrower.get_by_role("textbox", name="搜索物品")
        search.fill(TITLE)
        borrower_card = borrower.locator(".item-card").filter(has_text=TITLE)
        expect(borrower_card).to_be_visible()
        expect(search).to_have_value(TITLE)
        expect(borrower.locator(".item-card")).to_have_count(1)
        borrower_card.get_by_role("button", name="收藏物品", exact=True).click()
        navigate(borrower, "我的收藏")
        expect(borrower_card).to_be_visible()
        borrower_card.get_by_role("button", name="去看看", exact=True).click()
        now = datetime.now(timezone(timedelta(hours=8)))
        borrower.get_by_label("开始时间", exact=True).fill((now + timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M"))
        borrower.get_by_label("结束时间", exact=True).fill((now + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M"))
        borrower.get_by_role("button", name="提交预约申请", exact=True).click()
        borrower_order = borrower.locator(".order-card").filter(has_text=TITLE)
        expect(borrower_order.locator(".order-status")).to_have_text("待审核")

        navigate(owner, "借出管理")
        owner_order = owner.locator(".order-card").filter(has_text=TITLE)
        expect(owner_order).to_be_visible()
        owner_order.get_by_role("button", name="同意申请", exact=True).click()
        expect(owner_order.locator(".order-status")).to_have_text("待领取")
        borrower.get_by_role("button", name="刷新订单", exact=True).click()
        expect(borrower_order.locator(".order-status")).to_have_text("待领取")
        borrower_order.get_by_role("button", name="取消申请", exact=True).click()
        expect(borrower_order.locator(".order-status")).to_have_text("已取消")
        owner.get_by_role("button", name="刷新订单", exact=True).click()
        expect(owner_order.locator(".order-status")).to_have_text("已取消")

        navigate(owner, "我的发布")
        owner_card.get_by_role("button", name="修改", exact=True).click()
        owner.get_by_label("物品名称", exact=True).fill(TITLE + " 已更新")
        owner.get_by_role("button", name="保存修改", exact=True).click()
        expect(owner_card).to_contain_text("已更新")
        owner_card.get_by_role("button", name="下架", exact=True).click()
        expect(owner_card.locator(".status-tag")).to_have_text("暂不可借")
        navigate(borrower, "我的发布")
        expect(borrower.locator(".item-card")).to_have_count(0)  # Same nickname, distinct owners.
        navigate(borrower, "我的收藏")
        expect(borrower_card).to_contain_text("已更新")
        expect(borrower_card.locator(".status-tag")).to_have_text("暂不可借")
        borrower.reload()
        borrower.wait_for_load_state("networkidle")
        navigate(borrower, "我的收藏")
        expect(borrower_card).to_be_visible()

        # Exercise a failed request, then retry against the real backend.
        borrower.route("**/api/items?**", lambda route: route.fulfill(status=503, content_type="application/json", body='{"code":"SERVICE_UNAVAILABLE","message":"测试连接失败","data":null}'))
        navigate(borrower, "发现物品")
        expect(borrower.get_by_text("暂时无法加载数据", exact=True)).to_be_visible()
        borrower.unroute("**/api/items?**")
        borrower.get_by_role("button", name="重新加载", exact=True).click()
        expect(borrower_card).to_be_visible()

        owner.locator(".user-button").click()
        owner.get_by_role("button", name="退出登录", exact=True).click()
        expect(owner.get_by_role("button", name="登录 / 注册", exact=True)).to_be_visible()
        navigate(owner, "我的发布")
        expect(owner.get_by_text("登录后查看我的发布", exact=True)).to_be_visible()
        assert owner.evaluate("sessionStorage.getItem('campus-access-token-v2')") is None

        if os.environ.get("QA_SCREENSHOTS"):
            borrower.screenshot(path=str(Path(os.environ["QA_SCREENSHOTS"]) / "integrated-desktop.png"), full_page=True)
        borrower.set_viewport_size({"width": 390, "height": 844})
        assert borrower.evaluate("document.documentElement.scrollWidth <= innerWidth"), "Mobile page overflows"
        navigate(borrower, "我的借用")
        expect(borrower_order.locator(".order-status")).to_have_text("已取消")
        if os.environ.get("QA_SCREENSHOTS"):
            borrower.screenshot(path=str(Path(os.environ["QA_SCREENSHOTS"]) / "integrated-mobile.png"), full_page=True)
        # Dropping the last favorite on page two must return to a valid page.
        borrower.evaluate("""async (prefix) => {
            const headers = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + sessionStorage.getItem('campus-access-token-v2') };
            const categories = await (await fetch('/api/categories')).json();
            for (let i = 0; i < 12; i++) {
                const response = await fetch('/api/items', { method: 'POST', headers, body: JSON.stringify({
                    title: `分页物品 ${prefix}-${i}`, description: '分页回归测试', categoryId: categories.data[0].id,
                    campus: '东校区', pickupLocation: '测试位置'
                }) });
                if (!response.ok) throw new Error('Pagination fixture creation failed');
                const item = await response.json();
                const favorite = await fetch(`/api/favorites/${item.data.id}`, { method: 'PUT', headers });
                if (!favorite.ok) throw new Error('Pagination fixture favorite failed');
            }
        }""", PREFIX)
        borrower.set_viewport_size({"width": 1440, "height": 1000})
        borrower.reload()
        borrower.wait_for_load_state("networkidle")
        navigate(borrower, "我的收藏")
        expect(borrower.locator(".item-card")).to_have_count(12)
        borrower.get_by_role("button", name="下一页", exact=True).click()
        expect(borrower.locator(".item-card")).to_have_count(1)
        borrower.get_by_role("button", name="取消收藏", exact=True).click()
        expect(borrower.locator(".item-card")).to_have_count(12)
        expect(borrower.locator(".pagination")).to_have_count(0)
        assert not errors, errors
        print("PASS: real registration/login, upload, publish/edit/unlist, search, favorites, booking/approval/cancel, identity isolation, refresh persistence, failure/retry, logout, mobile, pagination recovery; no runtime errors.")
    finally:
        browser.close()

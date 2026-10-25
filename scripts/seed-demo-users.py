"""Manually add local practice accounts through the running backend API.

Run: python scripts/seed-demo-users.py
Existing accounts are never reset, and existing items are not overwritten.
"""
import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = "http://127.0.0.1:8080/api"
PASSWORD = "Campus123!"
SAMPLES = [
    ("demo_xiaolin", "小林（测试）", "数码摄影", "佳能入门单反相机", "适合校园活动和摄影练习，含电池、充电器与相机包。请提前联系，归还时检查配件。", "东校区", "东校区图书馆门口"),
    ("demo_xiaozhou", "小周（测试）", "运动装备", "尤尼克斯羽毛球拍", "一支羽毛球拍，附拍套，适合日常练习。请自备羽毛球，使用后擦干归还。", "东校区", "东校区体育馆门口"),
    ("demo_xiaoyu", "小雨（测试）", "户外露营", "双人露营帐篷", "一顶双人帐篷，含地钉和收纳袋，适合晴天露营。归还前请晾干并清点配件。", "南校区", "南校区学生服务中心"),
    ("demo_achen", "阿辰（测试）", "学习办公", "护眼充电台灯", "一盏可调亮度台灯，附充电线，适合自习和宿舍阅读。请勿拆卸。", "西校区", "西校区自习室入口"),
    ("demo_xiaotang", "小唐（测试）", "生活好物", "便携折叠推车", "一辆折叠推车，适合搬书、取快递和宿舍搬运。请勿超载，归还时保持清洁。", "南校区", "南校区快递站门口"),
]


def api(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = None if body is None else json.dumps(body).encode("utf-8")
    with urlopen(Request(BASE + path, data=payload, headers=headers, method=method), timeout=20) as response:
        result = json.load(response)
    if result["code"] != "OK":
        raise RuntimeError(result["message"])
    return result["data"]


def main():
    api("GET", "/health")
    categories = {row["name"]: row["id"] for row in api("GET", "/categories")}
    for username, nickname, category, title, description, campus, location in SAMPLES:
        category_id = categories[category]
        try:
            api("POST", "/auth/register", dict(username=username, password=PASSWORD, nickname=nickname))
        except HTTPError as error:
            if error.code != 409:
                raise
            # An existing account must authenticate; never change its password.
        login = api("POST", "/auth/login", dict(username=username, password=PASSWORD))
        token = login["accessToken"]
        try:
            owned = api("GET", "/items?scope=mine&size=50", token=token)
            matching = [item for item in owned["items"] if item["title"] == title]
            if not matching:
                item = api("POST", "/items", dict(title=title, description=description,
                           categoryId=category_id, campus=campus, pickupLocation=location), token)
            else:
                item = matching[0]
            detail = api("GET", "/items/" + str(item["id"]))
            assert detail["ownerId"] == login["user"]["id"], "Unexpected item owner"
            assert detail["status"] == "ON_SHELF", "Existing demo item is not on shelf; left unchanged"
            print(json.dumps(dict(username=username, nickname=nickname, itemId=item["id"],
                                  title=title, status=detail["status"]), ensure_ascii=False))
        finally:
            api("POST", "/auth/logout", token=token)


if __name__ == "__main__":
    main()

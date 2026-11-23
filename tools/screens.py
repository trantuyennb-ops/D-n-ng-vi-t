# Chụp ảnh màn hình app Dẫn Đường Việt ở kích thước điện thoại
import asyncio, os
from playwright.async_api import async_playwright

URL = "http://localhost:8000/index.html"
OUT = "shots"
START = {"latitude": 20.2506, "longitude": 105.9745}   # TP Ninh Bình
DEST = "{lat: 20.2557, lng: 105.9004, name: 'Bến thuyền Tràng An'}"

async def main():
    os.makedirs(OUT, exist_ok=True)
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 412, "height": 860}, device_scale_factor=2.5,
                                  is_mobile=True, has_touch=True, locale="vi-VN",
                                  geolocation=dict(START, accuracy=8), permissions=["geolocation"])
        pg = await ctx.new_page()
        async def shot(name, wait=4):
            await pg.wait_for_timeout(wait * 1000)
            await pg.screenshot(path=f"{OUT}/{name}.png")
            print("ok", name)

        await pg.goto(URL)
        await pg.evaluate("isNight = () => false; applyNight();")
        await shot("1_ban_do", 6)

        await pg.fill("#inTo", "Tràng An Ninh Bình")
        try:
            await pg.wait_for_selector("#sugg div", timeout=8000); await shot("2_tim_kiem", 2)
            await pg.click("#sugg div >> nth=0")
        except Exception as e:
            print("search fallback", e)
            await pg.evaluate(f"setDest({DEST})")
        await pg.wait_for_selector("#sheet:not([hidden])", timeout=20000)
        await shot("3_chon_tuyen", 5)

        await pg.click("#btnSteps")
        await pg.evaluate("document.getElementById('sheet').scrollTop = 120")
        await shot("4_cac_buoc", 1)
        await pg.click("#btnSteps")

        await pg.evaluate("(() => { const r = S.routes[S.sel]; const i = r.cum.findIndex(x => x > 1400); const c = r.coords[i]; store.set('cams', [{lat: c[0], lng: c[1]}]); matchCams(); drawCams(); })()")
        await pg.click("#btnSim"); await shot("5_dan_duong", 14)
        await pg.click("#btnLayer"); await shot("6_dan_duong_ve_tinh", 8)
        await pg.click("#btnLayer")
        await pg.evaluate("isNight = () => true; applyNight();")
        await shot("8_ban_dem", 5)
        await pg.evaluate("isNight = () => false; applyNight();")
        await pg.click("#btnLayer")

        await pg.click("#btnExit")
        await pg.wait_for_timeout(2500)
        await pg.evaluate("document.getElementById('sheet').hidden = true; map.setView([20.2537, 105.8946], 18, {animate: false})")
        await shot("7_ve_tinh_net", 8)

        # Màn hình ô tô nằm ngang 1280x720
        car = await b.new_context(viewport={"width": 1280, "height": 720}, device_scale_factor=1.5, has_touch=True,
                                  locale="vi-VN", geolocation=dict(START, accuracy=8), permissions=["geolocation"])
        pg = await car.new_page()
        await pg.goto(URL); await pg.evaluate("isNight = () => false; applyNight();")
        await pg.wait_for_timeout(3000)
        await pg.evaluate(f"setDest({DEST})")
        await pg.wait_for_selector("#sheet:not([hidden])", timeout=20000)
        await shot("9_man_o_to", 5)
        await pg.click("#btnSim"); await shot("10_man_o_to_dan_duong", 12)
        await b.close()

asyncio.run(main())

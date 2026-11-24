# Kiểm tra tự động cảnh báo camera + giới hạn tốc độ bằng cách giả lập xe chạy
import asyncio, json, math
from playwright.async_api import async_playwright

URL = "http://localhost:8000/index.html"
START = (20.2350, 105.9745)  # Đường Trần Hưng Đạo, TP Ninh Bình (hướng Bắc)

def move(lat, lng, meters, brg):
    R = 6371000.0; b = math.radians(brg); la = math.radians(lat); lo = math.radians(lng); d = meters / R
    la2 = math.asin(math.sin(la)*math.cos(d) + math.cos(la)*math.sin(d)*math.cos(b))
    lo2 = lo + math.atan2(math.sin(b)*math.sin(d)*math.cos(la), math.cos(d) - math.sin(la)*math.sin(la2))
    return math.degrees(la2), math.degrees(lo2)

results = []
def check(name, ok, detail=""):
    results.append({"test": name, "ok": bool(ok), "detail": detail}); print(("PASS " if ok else "FAIL ") + name + " | " + str(detail))

async def drive(pg, start, brg, steps, speed=15.0):
    lat, lng = start
    for _ in range(steps):
        await pg.evaluate(f"onPos({{coords: {{latitude: {lat}, longitude: {lng}, accuracy: 5, speed: {speed}, heading: {brg}}}}})")
        await pg.wait_for_timeout(120)
        lat, lng = move(lat, lng, speed, brg)
    return lat, lng

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        ctx = await b.new_context(viewport={"width": 412, "height": 860}, locale="vi-VN",
                                  geolocation={"latitude": START[0], "longitude": START[1]}, permissions=["geolocation"])
        pg = await ctx.new_page()
        await pg.goto(URL); await pg.wait_for_timeout(3000)
        await pg.evaluate("window.__said = []; window.say = (t) => { window.__said.push(t); }; S.sim = true; isNight = () => false;")

        # --- 1. Lái tự do, camera phía trước 450 m (bắn tốc độ, 60 km/h, chụp hướng Bắc) ---
        cam = move(START[0], START[1], 450, 0)
        await pg.evaluate(f"S.baseCams = [{{lat: {cam[0]}, lng: {cam[1]}, k: 'speed', s: '60', d: 0}}]; store.set('cams', []);")
        await drive(pg, START, 0, 6)
        said = await pg.evaluate("window.__said")
        vis = await pg.evaluate("!document.getElementById('freeBar').hidden")
        check("Lái tự do: hiện thanh tốc độ", vis)
        check("Lái tự do: báo camera phía trước", any("camera bắn tốc độ" in t for t in said), said)
        lim = await pg.evaluate("document.getElementById('fLimV').textContent")
        check("Lái tự do: biển tốc độ lấy theo camera (60)", lim == "60", lim)

        # --- 2. Camera ở phía sau: không báo ---
        await pg.evaluate("window.__said = []; S.spokenCam = {};")
        back = move(START[0], START[1], 300, 180)
        await pg.evaluate(f"S.baseCams = [{{lat: {back[0]}, lng: {back[1]}, k: 'speed', s: '', d: ''}}];")
        await drive(pg, START, 0, 4)
        said = await pg.evaluate("window.__said")
        check("Lái tự do: KHÔNG báo camera phía sau", not any("camera" in t for t in said), said)

        # --- 3. Camera chụp chiều ngược lại: không báo ---
        await pg.evaluate("window.__said = []; S.spokenCam = {};")
        fwd = move(START[0], START[1], 400, 0)
        await pg.evaluate(f"S.baseCams = [{{lat: {fwd[0]}, lng: {fwd[1]}, k: 'speed', s: '', d: 180}}];")
        await drive(pg, START, 0, 4)
        said = await pg.evaluate("window.__said")
        check("Lái tự do: KHÔNG báo camera chụp chiều ngược", not any("camera" in t for t in said), said)

        # --- 4. Ước tính tốc độ an toàn ---
        r = await pg.evaluate("(() => { S.profile='car'; S.area='urban'; const a=estLimitFor(null); S.area='rural'; const b=estLimitFor(null); S.profile='moto'; const c=estLimitFor(null); const d=estLimitFor({name:'Đường cao tốc Pháp Vân - Cầu Giẽ'}); S.profile='car'; S.area='urban'; return [a,b,c,d]; })()")
        check("Ước tính: khu dân cư 50, ngoài KDC ô tô 80, xe máy 60, cao tốc theo biển", r == [50, 80, 60, None], r)

        # --- 5. Giới hạn đổi phải ổn định 3 lần mới đọc ---
        r = await pg.evaluate("(() => { S.limShown = 50; const out=[limitChanged(80), limitChanged(80), limitChanged(80), limitChanged(80)]; return out; })()")
        check("Đổi giới hạn: chỉ đọc 1 lần sau 3 lần đo", r[0] == "" and r[1] == "" and "80" in r[2] and r[3] == "", r)

        # --- 6. Dẫn đường qua camera trên tuyến ---
        await pg.evaluate("window.__said = []; S.spokenCam = {}; S.baseCams = [];")
        await pg.evaluate("setDest({lat: 20.2557, lng: 105.9004, name: 'Bến thuyền Tràng An'})")
        await pg.wait_for_selector("#sheet:not([hidden])", timeout=25000)
        await pg.evaluate("(() => { const r = S.routes[S.sel]; const i = r.cum.findIndex(x => x > 900); const c = r.coords[i]; S.baseCams = [{lat: c[0], lng: c[1], k: 'speed', s: '50', d: ''}]; matchCams(); })()")
        n = await pg.evaluate("S.routes[S.sel].cams.length")
        check("Dẫn đường: camera được gắn vào tuyến", n == 1, n)
        await pg.evaluate("window.__said = []; startNav(true);")
        await pg.wait_for_timeout(30000)
        said = await pg.evaluate("window.__said")
        check("Dẫn đường: báo camera trên tuyến", any("camera bắn tốc độ" in t for t in said), said[:12])
        badge = await pg.evaluate("document.getElementById('camBadge').textContent")
        check("Dẫn đường: hiện khung camera", "Camera" in badge or any("camera" in t for t in said), badge)
        # --- 7. Tìm bằng giọng nói: bấm Tìm ngay là tìm luôn ---
        await pg.evaluate("stopNav(); window.Android = {voiceStart(){}, voiceStop(){}, voiceCancel(){ window.__cancel = 1; }, voiceSearch(){}}; document.getElementById('btnMic').click();")
        vis = await pg.evaluate("!document.getElementById('voice').hidden")
        check("Giọng nói: mở màn hình nghe", vis)
        await pg.evaluate("onVoiceState('speaking'); onVoicePartial('Tràng An Ninh Bình');")
        txt = await pg.evaluate("document.getElementById('vText').textContent")
        check("Giọng nói: hiện chữ ngay khi đang nói", txt == "Tràng An Ninh Bình", txt)
        t0 = await pg.evaluate("Date.now()")
        await pg.evaluate("document.getElementById('vGo').click()")
        closed = await pg.evaluate("document.getElementById('voice').hidden")
        val = await pg.evaluate("document.getElementById('inTo').value")
        check("Giọng nói: bấm Tìm ngay đóng màn hình, điền chữ", closed and val == "Tràng An Ninh Bình", val)
        await pg.wait_for_selector("#sugg div", timeout=15000)
        ms = await pg.evaluate(f"Date.now() - {t0}")
        n = await pg.evaluate("document.querySelectorAll('#sugg div').length")
        check("Giọng nói: có kết quả tìm kiếm", n > 0, f"{n} kết quả sau {ms} ms")
        await b.close()
    json.dump(results, open("shots/test_results.json", "w"), ensure_ascii=False, indent=1)
    fails = [r for r in results if not r["ok"]]
    print("TONG KET:", len(results) - len(fails), "/", len(results), "dat")

asyncio.run(main())

# Gộp dữ liệu camera giao thông Việt Nam từ Overpass thành assets/cams_vn.json
import json
try:
    els = json.load(open('/tmp/cams.json')).get('elements', [])
except Exception:
    els = None
if not els:
    print('Không tải được dữ liệu mới, giữ file cũ'); raise SystemExit(0)
seen, out = set(), []
for e in els:
    if e.get('type') == 'node' and 'lat' in e and e['id'] not in seen:
        seen.add(e['id'])
        t = e.get('tags', {})
        kind = 'speed' if t.get('highway') == 'speed_camera' or 'maxspeed' in t else 'traffic'
        out.append({'lat': round(e['lat'], 6), 'lng': round(e['lon'], 6), 'k': kind, 's': t.get('maxspeed', '')})
json.dump(out, open('app/src/main/assets/cams_vn.json', 'w'), separators=(',', ':'))
print('So camera Viet Nam:', len(out), '- bắn tốc độ:', sum(1 for c in out if c['k'] == 'speed'))

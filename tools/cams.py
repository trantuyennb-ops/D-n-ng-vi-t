# Gộp dữ liệu camera giao thông Việt Nam thành assets/cams_vn.json
#  - OpenStreetMap (qua Overpass): /tmp/cams.json
#  - MapAtlas speed camera dataset (CC BY 4.0, https://mapatlas.eu): /tmp/mapatlas_vn.geojson (nếu tải được)
import json, os
CARD = {'N': 0, 'NE': 45, 'E': 90, 'SE': 135, 'S': 180, 'SW': 225, 'W': 270, 'NW': 315}
def direction(v):
    if v is None: return ''
    v = str(v).strip().upper().split(';')[0].split('-')[0]
    if v in CARD: return CARD[v]
    try: return round(float(v)) % 360
    except ValueError: return ''
def speed(v):
    try: return str(int(float(str(v).split()[0])))
    except Exception: return ''
out, seen = [], set()
def add(lat, lng, kind, s, d):
    k = (round(lat, 4), round(lng, 4))
    if k in seen: return
    seen.add(k); out.append({'lat': round(lat, 6), 'lng': round(lng, 6), 'k': kind, 's': s, 'd': d})
n_osm = n_ma = 0
try:
    for e in json.load(open('/tmp/cams.json')).get('elements', []):
        if e.get('type') == 'node' and 'lat' in e:
            t = e.get('tags', {})
            kind = 'speed' if t.get('highway') == 'speed_camera' or 'maxspeed' in t else 'traffic'
            add(e['lat'], e['lon'], kind, speed(t.get('maxspeed')), direction(t.get('direction') or t.get('camera:direction')))
            n_osm += 1
except Exception as ex:
    print('OSM: không đọc được', ex)
try:
    g = json.load(open('/tmp/mapatlas_vn.geojson'))
    for f in g.get('features', []):
        geo = f.get('geometry') or {}
        if geo.get('type') != 'Point': continue
        lng, lat = geo['coordinates'][:2]
        if not (8.0 < lat < 23.5 and 102.0 < lng < 110.0): continue
        p = f.get('properties') or {}
        typ = str(p.get('type') or p.get('camera_type') or '').lower()
        kind = 'traffic' if 'red' in typ or 'light' in typ else 'speed'
        add(lat, lng, kind, speed(p.get('maxspeed') or p.get('speed_limit') or p.get('speed') or p.get('limit')),
            direction(p.get('direction') or p.get('bearing') or p.get('heading')))
        n_ma += 1
except Exception as ex:
    print('MapAtlas: không có dữ liệu', ex)
if len(out) < 10:
    print('Quá ít dữ liệu (%d), giữ file cũ' % len(out)); raise SystemExit(0)
json.dump(out, open('app/src/main/assets/cams_vn.json', 'w'), separators=(',', ':'), ensure_ascii=False)
print('So camera Viet Nam:', len(out), '| OSM:', n_osm, '| MapAtlas:', n_ma, '| bắn tốc độ:', sum(1 for c in out if c['k'] == 'speed'))

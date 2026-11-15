"""
행정동 경계 GeoJSON → 앱용 시·군·구 지도(app/src/main/assets/regions.json) 변환. 담당: 1번(지도·수집)

원본: https://github.com/vuski/admdongkor  (ver날짜/HangJeongDong_ver날짜.geojson)
  - 통계청 SGIS 행정동 경계(공공누리 제1유형)를 가공한 데이터, CC BY 4.0
  - 앱 화면(정보/설정)과 README 에 출처를 반드시 표기할 것 (README 참고)

처리:
  1) 행정동을 시·군·구(sgg 5자리 코드) 단위로 합친다(dissolve)
  2) 일반구(예: 수원시 장안구·팔달구)는 상위 시(수원시) 하나로 합친다 → 여행 단위로 자연스럽게
  3) 경계를 단순화해 용량을 줄이고, 좌표를 (위도, 경도) 순서로 저장한다

사용법:
  pip install shapely
  python tools/build_regions.py HangJeongDong_ver20261001.geojson app/src/main/assets/regions.json
"""
import json
import re
import sys
from collections import defaultdict

from shapely.geometry import MultiPolygon, Polygon, shape
from shapely.ops import unary_union

SIMPLIFY_DEG = 0.004       # 약 400m. 값을 키우면 용량이 줄고 경계가 거칠어진다
MIN_ISLAND_DEG2 = 2e-5     # 이보다 작은 섬 조각은 버린다(가장 큰 조각은 항상 유지)
CITY_GU = re.compile(r"^(.+?시)(.+구)$")


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        fc = json.load(f)

    groups = defaultdict(list)
    meta = {}
    for feat in fc["features"]:
        p = feat["properties"]
        code, sido, name = p["sgg"], p["sidonm"], p["sggnm"]
        m = CITY_GU.match(name)
        if m and not sido.endswith(("특별시", "광역시")):
            name = m.group(1)              # 수원시장안구 → 수원시
            code = code[:4] + "0"          # 41111 → 41110
        key = (code, sido, name)
        groups[key].append(shape(feat["geometry"]))
        meta[key] = True

    out = []
    for (code, sido, name), geoms in sorted(groups.items()):
        merged = unary_union(geoms).simplify(SIMPLIFY_DEG, preserve_topology=True)
        polys = list(merged.geoms) if isinstance(merged, MultiPolygon) else [merged]
        polys = [p for p in polys if isinstance(p, Polygon) and not p.is_empty]
        polys.sort(key=lambda p: p.area, reverse=True)
        polys = [polys[0]] + [p for p in polys[1:] if p.area >= MIN_ISLAND_DEG2]
        center = merged.representative_point()
        out.append({
            "code": code,
            "sido": sido,
            "name": name,
            "center": [round(center.y, 5), round(center.x, 5)],
            "polygons": [[[round(y, 4), round(x, 4)] for x, y in p.exterior.coords] for p in polys],
        })

    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(out)}개 시·군·구 → {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

#!/usr/bin/env python3
"""세 도시 원본을 전체 관광 목록과 보류 목록으로 변환한다."""
import argparse
import json
import math
from pathlib import Path


def build(documents, valid_regions):
    accepted, rejected, seen = [], [], set()
    for document in documents:
        if document.get('truncated') is not False:
            raise ValueError('전체 수집이 완료되지 않은 파일입니다.')
        for item in document['items']:
            reasons = []
            content_id = str(item.get('contentid', '')).strip()
            region = str(item.get('lDongRegnCd', '')) + str(item.get('lDongSignguCd', ''))
            if not content_id or content_id in seen:
                reasons.append('콘텐츠 ID 누락 또는 중복')
            seen.add(content_id)
            if region not in valid_regions:
                reasons.append('앱 지역 코드와 불일치')
            try:
                lat, lng = float(item['mapy']), float(item['mapx'])
                if not (math.isfinite(lat) and math.isfinite(lng) and 33 <= lat <= 39 and 124 <= lng <= 132):
                    raise ValueError()
            except (KeyError, TypeError, ValueError):
                reasons.append('좌표 누락 또는 범위 오류')
            if not str(item.get('title', '')).strip():
                reasons.append('장소명 누락')
            if reasons:
                rejected.append(dict(reasons=reasons, original=item))
                continue
            accepted.append(dict(contentId=content_id, name=item['title'], regionCode=region,
                contentTypeId=str(item.get('contenttypeid', '')), lat=lat, lng=lng,
                address=' '.join(str(item.get(k, '')).strip() for k in ['addr1', 'addr2']).strip(),
                imageUrl=str(item.get('firstimage', '') or ''),
                thumbnailUrl=str(item.get('firstimage2', '') or ''),
                imageLicense=str(item.get('cpyrhtDivCd', '') or ''),
                phone=str(item.get('tel', '') or ''),
                classification={k:str(item.get(k, '') or '') for k in ['cat1','cat2','cat3','lclsSystm1','lclsSystm2','lclsSystm3']},
                modifiedAt=str(item.get('modifiedtime', '')),
                collectedAt=document.get('collectedAt', ''), source=document.get('source', '')))
    accepted.sort(key=lambda x: (x['regionCode'], x['name'], x['contentId']))
    return accepted, rejected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument('--input-dir', type=Path, default=root / '.local/tourapi')
    args = parser.parse_args()
    regions = json.loads((root/'app/src/main/assets/regions.json').read_text())
    valid = {x['code'] for x in regions if x['code'][:2] in {'11','26','30'}}
    docs = [json.loads((args.input_dir/f'{city}-area-places.json').read_text())
            for city in ['seoul','daejeon','busan']]
    accepted, rejected = build(docs, valid)
    output = root/'app/src/main/assets/tourism_catalog.json'
    output.write_text(json.dumps(accepted, ensure_ascii=False, separators=(',', ':'))+'\n')
    (args.input_dir/'tourism-rejected.json').write_text(json.dumps(rejected,ensure_ascii=False,indent=2)+'\n')
    print(f'앱 목록 {len(accepted)}건, 보류 {len(rejected)}건. 보류 원본은 삭제하지 않았습니다.')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""검수한 이야기 목록을 검증하고 앱용 JSON으로 변환한다. 네트워크 호출 없음."""
import argparse
import json
import math
from pathlib import Path


def build(rows, assets):
    result, seen = [], set()
    root = assets.resolve()
    for row in rows:
        story_id = row['id'].strip()
        if not story_id or story_id in seen:
            raise ValueError('스토리 ID가 비었거나 중복되었습니다.')
        seen.add(story_id)
        lat, lng, radius = float(row['lat']), float(row['lng']), float(row.get('radiusM', 2000))
        if not all(map(math.isfinite, [lat,lng,radius])) or not (33 <= lat <= 39 and 124 <= lng <= 132 and radius > 0):
            raise ValueError(f'{story_id}: 좌표 또는 반경 오류')
        audio = row['audio']
        path = (assets/audio).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError(f'{story_id}: 음성 파일 누락 또는 assets 밖 경로')
        if not row.get('title','').strip():
            raise ValueError(f'{story_id}: 제목 누락')
        result.append(dict(id=story_id,title=row['title'],lat=lat,lng=lng,audio=audio,radiusM=radius))
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input',type=Path)
    parser.add_argument('--output',type=Path,help='생략하면 검증만 수행')
    args=parser.parse_args()
    assets=Path(__file__).resolve().parents[1]/'app/src/main/assets'
    try:
        result=build(json.loads(args.input.read_text()),assets)
    except (ValueError,KeyError,TypeError,OSError) as error:
        parser.exit(1,f'검증 실패: {error}\n')
    if args.output:
        temporary=args.output.with_suffix('.tmp')
        temporary.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        temporary.replace(args.output)
    print(f'{len(result)}개 스토리 검증 완료')


if __name__=='__main__':
    main()

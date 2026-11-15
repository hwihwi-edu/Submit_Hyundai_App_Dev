#!/usr/bin/env python3
"""관광 상세·무장애 정보를 요청 예산 안에서 수집하고 건별로 이어받는다."""
import argparse
import getpass
import json
import math
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote

from tourapi_collect import ApiError, ApiLimitError, request_page

ROOT = Path(__file__).resolve().parents[1]


def identity_matches(entry, items):
    if len(items) != 1:
        return False
    item = items[0]
    name = lambda value: re.sub(r'\s+', '', str(value))
    try:
        return (str(item['contentid']) == entry['contentId'] and
                name(item['title']) == name(entry['name']) and
                math.isfinite(float(item['mapy'])) and math.isfinite(float(item['mapx'])) and
                abs(float(item['mapy']) - entry['lat']) <= 0.001 and
                abs(float(item['mapx']) - entry['lng']) <= 0.001)
    except (KeyError, ValueError, TypeError):
        return False


def save(path, record, key):
    text = json.dumps(record, ensure_ascii=False, indent=2)
    for secret in {key, unquote(key)}:
        if secret:
            text = text.replace(secret, '[REDACTED]')
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(text + '\n', encoding='utf-8')
    temporary.replace(path)


def enrich(entries, kind, key, directory, budget=None, fetch=request_page, progress=None):
    calls, completed, skipped = 0, 0, 0
    for entry in entries:
        content_id = entry['contentId']
        if not content_id.isdigit():
            raise ApiError('숫자가 아닌 콘텐츠 ID가 있습니다.')
        path = directory / kind / (content_id + '.json')
        record = json.loads(path.read_text()) if path.exists() else {'contentId': content_id}
        if record.get('status') in {'matched', 'empty', 'identity-mismatch'}:
            skipped += 1
            continue
        identity_kind = 'common' if kind == 'common' else 'access-common'
        if 'identityItems' not in record:
            if budget is not None and calls >= budget:
                break
            items, total = fetch(identity_kind, key, {'contentId': content_id, 'numOfRows': 10, 'pageNo': 1})
            calls += 1
            if progress and calls % 25 == 0:
                progress(calls)
            record.update(identityItems=items, identityTotal=total,
                          collectedAt=datetime.now(timezone.utc).isoformat())
            if total == 0 and not items:
                record['status'] = 'empty'
            elif total != 1 or not identity_matches(entry, items):
                record['status'] = 'identity-mismatch'
            elif kind == 'common':
                record['status'] = 'matched'
            save(path, record, key)
        if record.get('status'):
            completed += 1
            continue
        # 무장애 서비스의 장소명·좌표까지 대조한 후 상세를 연결한다.
        if budget is not None and calls >= budget:
            break
        items, total = fetch('accessibility', key, {'contentId': content_id, 'numOfRows': 10, 'pageNo': 1})
        calls += 1
        if progress and calls % 25 == 0:
            progress(calls)
        record.update(items=items, total=total, collectedAt=datetime.now(timezone.utc).isoformat())
        record['status'] = ('empty' if total == 0 and not items else
            'matched' if total == 1 and len(items) == 1 and
            str(items[0].get('contentid')) == content_id else 'identity-mismatch')
        save(path, record, key)
        completed += 1
    return dict(requests=calls, completed=completed, skipped=skipped)



def status_of(entries, kind, directory):
    counts = {'matched': 0, 'empty': 0, 'identity-mismatch': 0, 'pending': 0}
    for entry in entries:
        path = directory/kind/(entry['contentId']+'.json')
        record = json.loads(path.read_text()) if path.exists() else {}
        status = record.get('status', 'pending')
        counts[status if status in counts else 'pending'] += 1
    return counts


def print_status(entries, kind, directory):
    counts = status_of(entries, kind, directory)
    done = len(entries) - counts['pending']
    label = '상세 설명' if kind == 'common' else '접근성'
    print(f"[{label}] 조회 완료 {done:,}/{len(entries):,}, 미조회·진행 중 {counts['pending']:,} "
          f"(연결 {counts['matched']:,}, 결과 없음 {counts['empty']:,}, 불일치 {counts['identity-mismatch']:,})", flush=True)
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', nargs='?', default='all', choices=['common', 'accessibility', 'all'])
    parser.add_argument('--city', choices=['11', '30', '26'])
    parser.add_argument('--budget', type=int, help='선택 사항: 이번 실행의 서비스별 최대 HTTP 요청 수. 기본은 전체 조회')
    parser.add_argument('--status', action='store_true', help='인증키·API 호출 없이 진행 상태만 확인')
    args = parser.parse_args()
    if args.budget is not None and args.budget < 1:
        parser.error('--budget는 1 이상이어야 합니다.')
    entries = json.loads((ROOT/'app/src/main/assets/tourism_catalog.json').read_text())
    if args.city:
        entries = [e for e in entries if e['regionCode'].startswith(args.city)]
    groups = [[e for e in entries if e['regionCode'].startswith(code)] for code in ['30','11','26']]
    entries = [g[i] for i in range(max(map(len, groups), default=0)) for g in groups if i < len(g)]
    directory = ROOT/'.local/tourapi/enrichment'
    kinds = ['common', 'accessibility'] if args.kind == 'all' else [args.kind]
    failures = False
    keys = {}
    # 두 서비스를 한 번에 실행할 때 필요한 키를 시작 전에 입력받는다.
    for kind in kinds:
        counts = print_status(entries, kind, directory)
        if args.status or not counts['pending']:
            continue
        label = '국문 관광정보' if kind == 'common' else '무장애 여행 정보'
        env_name = 'TOURAPI_COMMON_KEY' if kind == 'common' else 'TOURAPI_ACCESSIBILITY_KEY'
        key = os.environ.get(env_name, '').strip() or os.environ.get('TOURAPI_SERVICE_KEY', '').strip()
        if not key:
            key = getpass.getpass(label + ' 일반 인증키 입력(화면에 표시되지 않음): ').strip()
        if not key:
            parser.error('인증키가 비어 있습니다.')
        keys[kind] = key
    if args.status:
        return
    try:
        for kind, key in keys.items():
            try:
                label = '상세 설명' if kind == 'common' else '접근성'
                result = enrich(entries, kind, key, directory, args.budget,
                    progress=lambda n: print(f'[{label}] 이번 실행 요청 {n:,}회…', flush=True))
                print(f"[{label}] 이번 요청 {result['requests']:,}회, 새 처리 {result['completed']:,}건.")
            except ApiLimitError as error:
                print(str(error) + ' 저장한 지점부터 다음 실행에서 이어받습니다.', flush=True)
            except ApiError as error:
                failures = True
                print(str(error) + ' 이전 저장분은 유지합니다.', flush=True)
            finally:
                print_status(entries, kind, directory)
    except KeyboardInterrupt:
        print('중단했습니다. 건별 저장된 결과는 유지되며 같은 명령으로 재개할 수 있습니다.')
    finally:
        # 원본 수집이 부분 완료여도 검증 통과한 결과부터 앱에 반영한다.
        from build_tourism_details import main as export_details
        export_details()
    if failures:
        parser.exit(1)


if __name__ == '__main__':
    main()

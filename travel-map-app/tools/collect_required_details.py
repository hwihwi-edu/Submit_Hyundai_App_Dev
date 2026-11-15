#!/usr/bin/env python3
"""2차 기획의 4개 분류 소개문·유형별 상세를 도시별로 번갈아 수집한다."""
import argparse
from collections import defaultdict, deque
from datetime import datetime, timezone
import getpass
import json
import os
import time

from enrich_tourism import ROOT, enrich, save, status_of
from tourapi_collect import ApiError, request_page

TYPES = {'12': '관광지', '14': '문화시설', '15': '축제·행사', '39': '음식점'}
DONE = {'matched', 'empty', 'identity-mismatch'}


def intro_path(entry, directory):
    # 먼저 모은 음식점 10곳도 재사용한다.
    legacy = directory/'food-intro'/(entry['contentId']+'.json')
    current = directory/'intro'/(entry['contentId']+'.json')
    return current if current.exists() or not legacy.exists() else legacy


def intro_status(entry, directory):
    path = intro_path(entry, directory)
    return json.loads(path.read_text()).get('status', 'pending') if path.exists() else 'pending'


def balanced(entries):
    groups = defaultdict(deque)
    for entry in sorted(entries, key=lambda e: (not bool(e.get('imageUrl')), e['contentId'])):
        groups[(entry['regionCode'][:2], entry['contentTypeId'])].append(entry)
    result = []
    while any(groups.values()):
        for key in sorted(groups):
            if groups[key]:
                result.append(groups[key].popleft())
    return result


def report(entries, directory):
    print('분류 | 기본 목록 | 소개문 조회 완료 | 유형별 상세 조회 완료 (정보 연결/결과 없음/불일치)')
    for code, label in TYPES.items():
        group = [e for e in entries if e['contentTypeId'] == code]
        common = status_of(group, 'common', directory)
        counts = {s: 0 for s in DONE}
        for entry in group:
            status = intro_status(entry, directory)
            if status in counts:
                counts[status] += 1
        print(f"{label} | {len(group):,} | {len(group)-common['pending']:,} | "
              f"{sum(counts.values()):,} ({counts['matched']}/{counts['empty']}/{counts['identity-mismatch']})")
    print('조회 완료는 응답 처리 건수입니다. 개별 메뉴·시간 등의 값이 있다는 뜻은 아닙니다.', flush=True)


def collect_intro(entry, key, directory, fetch=request_page):
    if intro_status(entry, directory) in DONE:
        return 0
    items, total = fetch('intro', key, dict(contentId=entry['contentId'],
        contentTypeId=entry['contentTypeId'], numOfRows=10, pageNo=1))
    status = 'empty' if total == 0 and not items else 'identity-mismatch'
    if (total == 1 and len(items) == 1 and
            str(items[0].get('contentid')) == entry['contentId'] and
            str(items[0].get('contenttypeid')) == entry['contentTypeId']):
        status = 'matched'
    save(directory/'intro'/(entry['contentId']+'.json'), dict(
        contentId=entry['contentId'], contentTypeId=entry['contentTypeId'],
        status=status, items=items, collectedAt=datetime.now(timezone.utc).isoformat()), key)
    return 1


def collect(entries, key, directory, budget=None, delay=0.3, fetch=request_page, progress=None, kind='all'):
    calls = 0
    for entry in balanced(entries):
        for operation in (['intro', 'common'] if kind == 'all' else [kind]):
            if budget is not None and calls >= budget:
                return calls
            if operation == 'intro':
                used = collect_intro(entry, key, directory, fetch)
            else:
                used = enrich([entry], 'common', key, directory, fetch=fetch)['requests']
            calls += used
            if used:
                if progress:
                    progress(calls)
                time.sleep(delay)
    return calls


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--status', action='store_true', help='키 입력·API 호출 없이 현황 확인')
    parser.add_argument('--kind', choices=['all', 'intro', 'common'], default='all',
                        help='intro: 유형별 상세만, common: 소개문만, 기본은 둘 다')
    parser.add_argument('--budget', type=int, help='이번 실행 최대 요청 수. 기본은 미완료 전체')
    args = parser.parse_args()
    if args.budget is not None and args.budget < 1:
        parser.error('budget는 1 이상이어야 합니다.')
    entries = json.loads((ROOT/'app/src/main/assets/tourism_catalog.json').read_text())
    entries = [e for e in entries if e['contentTypeId'] in TYPES]
    directory = ROOT/'.local/tourapi/enrichment'
    report(entries, directory)
    if args.status:
        return
    intro_done = all(intro_status(e, directory) in DONE for e in entries)
    common_done = not status_of(entries, 'common', directory)['pending']
    if ((args.kind == 'intro' and intro_done) or (args.kind == 'common' and common_done)
            or (args.kind == 'all' and intro_done and common_done)):
        print('선택 분류의 조회가 완료되어 추가 요청하지 않습니다.')
        return
    key = os.environ.get('TOURAPI_COMMON_KEY', '').strip() or os.environ.get('TOURAPI_SERVICE_KEY', '').strip()
    if not key:
        key = getpass.getpass('국문 관광정보 일반 인증키(숨김 입력): ').strip()
    if not key:
        parser.error('인증키가 비었습니다.')
    failed = False
    try:
        calls = collect(entries, key, directory, args.budget,
            kind=args.kind,
            progress=lambda n: print(f'이번 실행 {n:,}회 요청 완료', flush=True) if n % 25 == 0 else None)
        print(f'이번 실행 {calls:,}회 요청 완료.')
    except ApiError as error:
        failed = True
        print(str(error)+' 저장분은 유지됩니다. 같은 명령으로 이어받으세요.')
    except KeyboardInterrupt:
        print('중단했습니다. 저장분은 유지됩니다.')
    finally:
        report(entries, directory)
        from build_tourism_details import main as export
        export()
    if failed:
        parser.exit(1)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""기상청 인증·최신 발표분을 숨김 키로 검증한다. 앱 추천 가중치는 변경하지 않는다."""
import argparse
from datetime import datetime, timedelta
import getpass
import os
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import urlopen
from zoneinfo import ZoneInfo

from enrich_tourism import ROOT, save
from tourapi_collect import ApiError, parse_response

KINDS = {'observation': 'getUltraSrtNcst', 'ultra': 'getUltraSrtFcst', 'forecast': 'getVilageFcst'}
KST = ZoneInfo('Asia/Seoul')


def latest_issue(kind, now):
    now = now.astimezone(KST).replace(second=0, microsecond=0)
    available = now - timedelta(minutes=15 if kind == 'ultra' else 10)
    if kind == 'observation':
        return available.replace(minute=0)
    if kind == 'ultra':
        half = available.replace(minute=30)
        return half if half <= available else half - timedelta(hours=1)
    if kind != 'forecast':
        raise ValueError('알 수 없는 종류')
    return max((available - timedelta(days=day)).replace(hour=hour, minute=0)
               for day in [0, 1] for hour in [2, 5, 8, 11, 14, 17, 20, 23]
               if (available - timedelta(days=day)).replace(hour=hour, minute=0) <= available)


def normalize(items, kind, issued, nx, ny):
    frames = {}
    response_issue = None
    for item in items:
        expected = dict(baseDate=issued.strftime('%Y%m%d'), baseTime=issued.strftime('%H%M'), nx=str(nx), ny=str(ny))
        differences = []
        for field, wanted in expected.items():
            actual = str(item.get(field, ''))
            # 공식 가이드: 초단기예보 요청은 HH30, 응답 발표시각은 HH00으로 표기될 수 있다.
            alias = (field == 'baseTime' and kind == 'ultra' and issued.minute == 30
                     and actual == issued.strftime('%H') + '00')
            if actual != wanted and not alias:
                # 진단에는 네 가지 메타데이터의 숫자만 허용하며 본문·URL은 출력하지 않는다.
                safe = actual if re.fullmatch(r'[0-9]{1,8}', actual) else '미확인'
                differences.append(f'{field}: 요청 {wanted}, 응답 {safe}')
        if differences:
            raise ApiError('발표 시각 또는 격자가 요청과 다릅니다. ' + '; '.join(differences) + '. 저장하지 않았습니다.')
        actual_issue = datetime.strptime(str(item['baseDate']) + str(item['baseTime']), '%Y%m%d%H%M').replace(tzinfo=KST)
        if response_issue is not None and response_issue != actual_issue:
            raise ApiError('한 응답에 서로 다른 발표시각이 섞였습니다. 저장하지 않았습니다.')
        response_issue = actual_issue
        when = issued
        if kind != 'observation':
            day, hour = str(item.get('fcstDate', '')), str(item.get('fcstTime', ''))
            if hour == '2400':
                when = datetime.strptime(day, '%Y%m%d').replace(tzinfo=KST) + timedelta(days=1)
            else:
                when = datetime.strptime(day + hour, '%Y%m%d%H%M').replace(tzinfo=KST)
        values = frames.setdefault(when.isoformat(), {})
        category = item['category']
        value = str(item['obsrValue' if kind == 'observation' else 'fcstValue'])
        if category in values and values[category] != value:
            raise ApiError('같은 시각·항목에 서로 다른 값이 있습니다. 저장하지 않았습니다.')
        values[category] = value
    return [dict(validAt=when, values=values, issuedAt=response_issue.isoformat()) for when, values in sorted(frames.items())]


def fetch(kind, key, issued, nx, ny):
    rows, page, expected = [], 1, None
    while True:
        params = dict(serviceKey=key, dataType='JSON', pageNo=page, numOfRows=1000,
                      base_date=issued.strftime('%Y%m%d'), base_time=issued.strftime('%H%M'), nx=nx, ny=ny)
        url = 'https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/' + KINDS[kind] + '?' + urlencode(params)
        try:
            with urlopen(url, timeout=30) as response:
                items, total = parse_response(response.read())
        except HTTPError as error:
            raise ApiError(f'HTTP {error.code}: 기상청 인증·호출 한도·서비스 상태를 확인하세요.') from None
        except (URLError, TimeoutError):
            raise ApiError('기상청 연결 실패. 인증키·요청 URL은 출력하지 않았습니다.') from None
        if expected is not None and expected != total:
            raise ApiError('페이지 사이 전체 건수가 바뀌었습니다. 저장하지 않았습니다.')
        expected = total
        rows.extend(items)
        if not total:
            raise ApiError('해당 발표분에 자료가 없습니다. 최신 제공 시각과 API 상태를 확인하세요.')
        if len(rows) == total:
            return normalize(rows, kind, issued, nx, ny)
        if not items or len(rows) > total:
            raise ApiError('기상청 페이지 응답이 일치하지 않습니다. 저장하지 않았습니다.')
        page += 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nx', type=int, required=True)
    parser.add_argument('--ny', type=int, required=True)
    parser.add_argument('--kind', choices=['all', *KINDS], default='all')
    args = parser.parse_args()
    if not 1 <= args.nx <= 149 or not 1 <= args.ny <= 253:
        parser.error('기상청 격자 범위를 확인하세요.')
    key = os.environ.get('KMA_SERVICE_KEY', '').strip() or getpass.getpass('기상청 Decoding 일반 인증키(숨김 입력): ').strip()
    if not key:
        parser.error('인증키가 비었습니다.')
    kinds = list(KINDS) if args.kind == 'all' else [args.kind]
    for kind in kinds:
        issued = latest_issue(kind, datetime.now(KST))
        try:
            frames = fetch(kind, key, issued, args.nx, args.ny)
            path = ROOT/f'.local/weather/{args.nx}-{args.ny}-{kind}.json'
            actual_issue = frames[0]['issuedAt']
            save(path, dict(kind=kind, nx=args.nx, ny=args.ny, issuedAt=actual_issue, requestedBaseAt=issued.isoformat(),
                           fetchedAt=datetime.now(KST).isoformat(), source='기상청 단기예보 조회서비스', frames=frames), key)
            print(f'[{kind}] 응답 발표 {actual_issue}, 요청 기준 {issued:%H:%M} KST, 대상 시각 {len(frames)}개 저장.', flush=True)
        except ApiError as error:
            print(f'[{kind}] {error} 기존 저장분은 최신 자료로 간주하지 마세요.')
            parser.exit(1)
        except (ValueError, KeyError, TypeError):
            # 파싱 오류에도 응답 본문·인증키가 예외에 섞여 출력되지 않게 한다.
            print(f'[{kind}] 조회 실패. 인증·한도·자료 제공 상태를 확인하세요. 기존 저장분은 최신 자료로 간주하지 마세요.')
            parser.exit(1)


if __name__ == '__main__':
    main()

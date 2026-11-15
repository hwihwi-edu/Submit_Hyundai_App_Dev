#!/usr/bin/env python3
"""공식 TourAPI 후보 수집. Python 표준 라이브러리만 사용한다."""
import argparse
import getpass
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, unquote
from urllib.request import urlopen
from xml.etree import ElementTree

BASE = 'https://apis.data.go.kr/B551011/'
SERVICES = {
    'places': ('KorService2', 'searchKeyword2'),
    'places-area': ('KorService2', 'areaBasedList2'),
    'accessibility': ('KorWithService2', 'detailWithTour2'),
    'stories': ('Odii', 'storySearchList'),
}
SOURCES = {
    'places': 'https://www.data.go.kr/data/15101578/openapi.do',
    'places-area': 'https://www.data.go.kr/data/15101578/openapi.do',
    'accessibility': 'https://www.data.go.kr/data/15101897/openapi.do',
    'stories': 'https://www.data.go.kr/data/15101971/openapi.do',
}


class ApiError(Exception):
    pass


class ApiLimitError(ApiError):
    pass


def check_result_code(code):
    if code in {'22', 'LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR'}:
        raise ApiLimitError('API 호출 한도에 도달했습니다. 포털에서 한도와 재개 가능 시점을 확인하세요.')
    if code not in {'0000', '00'}:
        raise ApiError('API가 오류를 반환했습니다. 키 승인·활성화·호출 한도를 확인하세요.')


def parse_response(raw):
    """오류 본문·요청 URL은 키가 포함될 수 있어 예외 메시지에 넣지 않는다."""
    try:
        response = json.loads(raw)['response']
        code = str(response['header']['resultCode'])
        check_result_code(code)
        body = response['body']
        container = body.get('items') or {}
        items = container.get('item', []) if isinstance(container, dict) else []
        if isinstance(items, dict):
            items = [items]
        if not isinstance(items, list) or any(not isinstance(i, dict) for i in items):
            raise ValueError()
        total = int(body.get('totalCount', len(items)))
        if total < 0:
            raise ValueError()
        return items, total
    except (ValueError, KeyError, TypeError):
        # 게이트웨이는 JSON 요청에도 XML 오류를 반환할 수 있다.
        try:
            tree = ElementTree.fromstring(raw)
            code = tree.findtext('.//returnReasonCode') or tree.findtext('.//resultCode')
            if code:
                check_result_code(code.strip())
        except ElementTree.ParseError:
            pass
        raise ApiError('예상한 JSON 응답이 아닙니다. 인증 상태와 공식 API 규격을 확인하세요.') from None


def request_page(kind, key, params):
    detail_services = {
        'common': ('KorService2', 'detailCommon2'),
        'food-intro': ('KorService2', 'detailIntro2'),
        'intro': ('KorService2', 'detailIntro2'),
        'access-common': ('KorWithService2', 'detailCommon2'),
    }
    service, operation = (detail_services if kind in detail_services else SERVICES)[kind]
    query = dict(MobileOS='ETC', MobileApp='TravelMapPrototype', _type='json',
                 serviceKey=unquote(key), **params)
    url = BASE + service + '/' + operation + '?' + urlencode(query)
    try:
        with urlopen(url, timeout=30) as response:
            raw = response.read()
    except HTTPError as error:
        if error.code == 429:
            raise ApiLimitError('HTTP 429: 호출 한도 또는 요청 속도 제한입니다. 잠시 후 포털 상태를 확인하세요.') from None
        raise ApiError(f'HTTP {error.code}: 요청 실패. 키 권한·서비스 상태를 확인하세요.') from None
    except (URLError, TimeoutError):
        raise ApiError('네트워크 연결 실패. 요청 주소·인증키는 출력하지 않았습니다.') from None
    return parse_response(raw)


def collect(kind, key, params, max_pages, fetch=request_page, initial_items=None):
    result = list(initial_items or [])
    if len(result) % 50:
        raise ApiError('이어받기는 50건 단위로 끝난 미완료 파일만 지원합니다.')
    start_page = len(result) // 50 + 1
    for page in range(start_page, start_page + max_pages):
        items, total = fetch(kind, key, dict(params, numOfRows=50, pageNo=page))
        result.extend(items)
        if len(result) >= total:
            return result, False
        if not items:
            raise ApiError('전체 건수보다 일찍 빈 페이지가 반환됐습니다. 다시 조회하세요.')
    return result, True


def main(argv=None, service_key=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=SERVICES)
    parser.add_argument('--keyword', help='장소·이야기 검색어')
    parser.add_argument('--city', choices=['11', '30', '26'], help='장소 검색의 법정동 시도 코드')
    parser.add_argument('--content-id', help='무장애 정보 조회 대상 ID. 무장애 서비스에서의 일치 여부 검수 필요')
    parser.add_argument('--max-pages', type=int, default=3)
    parser.add_argument('--resume', action='store_true', help='동일 output의 미완료 수집을 다음 페이지부터 계속')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    if args.max_pages < 1:
        parser.error('--max-pages는 1 이상이어야 합니다.')
    params = {}
    if args.kind == 'places-area':
        if not args.city or args.keyword or args.content_id:
            parser.error('지역 전체 조회는 --city가 필요하며 --keyword/--content-id는 사용하지 않습니다.')
        params['lDongRegnCd'] = args.city
        params['arrange'] = 'A'
    elif args.kind == 'accessibility':
        if not args.content_id:
            parser.error('무장애 조회에는 --content-id가 필요합니다.')
        if args.keyword or args.city:
            parser.error('무장애 상세 조회에는 검색어·도시 필터를 사용할 수 없습니다.')
        params['contentId'] = args.content_id
    else:
        if not args.keyword or args.content_id:
            parser.error('장소·이야기 검색에는 --keyword만 사용하세요.')
        params['keyword'] = args.keyword
        if args.kind == 'places' and args.city:
            params['lDongRegnCd'] = args.city
        if args.kind == 'stories':
            if args.city:
                parser.error('이야기 검색은 --city를 지원하지 않습니다. 검색 후 위치를 검수하세요.')
            params['langCode'] = 'ko'
    previous = None
    if args.resume:
        try:
            previous = json.loads(args.output.read_text(encoding='utf-8'))
            if previous['query'] != params or previous['source'] != SOURCES[args.kind]:
                parser.error('기존 파일과 조회 조건이 다릅니다. 별도 output을 사용하세요.')
            if not previous['truncated']:
                print('기존 파일은 이미 전체 수집 완료 상태입니다.')
                return
            if previous['count'] != len(previous['items']) or len(previous['items']) % 50:
                parser.error('이어받기 파일의 건수 또는 페이지 경계를 확인하세요.')
        except (OSError, ValueError, KeyError, TypeError):
            parser.error('이어받을 기존 수집 파일을 읽을 수 없습니다.')
    key = service_key or os.environ.get('TOURAPI_SERVICE_KEY', '').strip()
    if not key:
        key = getpass.getpass('공공데이터포털 인증키 입력(화면에 표시되지 않음): ').strip()
    if not key:
        parser.error('인증키가 비어 있습니다.')
    try:
        items, truncated = collect(args.kind, key, params, args.max_pages,
                                   initial_items=previous['items'] if previous else None)
        # API가 키를 응답에 반사하더라도 파일에 남기지 않는다.
        payload = json.dumps(dict(source=SOURCES[args.kind],
            collectedAt=datetime.now(timezone.utc).isoformat(), query=params,
            reviewStatus='미검수', truncated=truncated, count=len(items), items=items),
            ensure_ascii=False, indent=2)
        for secret in {key, unquote(key)}:
            payload = payload.replace(secret, '[REDACTED]')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        if previous:
            backup = args.output.with_suffix(args.output.suffix + '.bak')
            backup.write_bytes(args.output.read_bytes())
        temporary = args.output.with_suffix(args.output.suffix + '.tmp')
        temporary.write_text(payload + '\n', encoding='utf-8')
        temporary.replace(args.output)
        print(f'{len(items)}건 저장. 검수 전이며 앱 데이터는 변경하지 않았습니다.')
        if truncated:
            print('페이지 상한으로 일부만 수집했습니다. max-pages를 늘려 다시 조회하세요.')
    except ApiError as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()

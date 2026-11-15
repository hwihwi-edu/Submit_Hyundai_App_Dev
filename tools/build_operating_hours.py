#!/usr/bin/env python3
"""등록 운영시간을 보수적으로 구조화한다. 원본·검색 목록은 삭제하지 않는다."""
import hashlib
import json
import re
from collections import Counter
from datetime import datetime

from enrich_tourism import ROOT

FIELDS = {'12': ('usetime', 'restdate'), '14': ('usetimeculture', 'restdateculture'),
          '15': ('playtime', None), '39': ('opentimefood', 'restdatefood')}
DAYS = '월화수목금토일'
ALL_DAYS = list(range(1, 8))
TIME = r'(?:[01]?\d|2[0-4]):[0-5]\d'
RANGE = rf'({TIME})\s*[~～∼]\s*({TIME})'


class ReviewNeeded(Exception):
    pass


def minute(text):
    hour, minutes = map(int, text.split(':'))
    if hour == 24 and minutes != 0:
        raise ReviewNeeded('24시 표기 검수 필요')
    return hour * 60 + minutes


def day_range(first, last=None):
    first = DAYS.index(first) + 1
    last = DAYS.index(last) + 1 if last else first
    return list(range(first, last + 1)) if last >= first else list(range(first, 8)) + list(range(1, last + 1))


def rest_days(text, event):
    compact = re.sub(r'\s+', '', text)
    if compact == '연중무휴' or (not compact and event):
        return []
    match = re.fullmatch(r'매주([월화수목금토일])요일(?:[~～∼]([월화수목금토일])요일)?', compact)
    if match:
        return day_range(*match.groups())
    raise ReviewNeeded('휴무일 정보 없음' if not compact else '공휴일·명절·특정일 등 휴무 규칙 검수 필요')


def single_window(text, days):
    text = text.strip()
    last_order = last_admission = None
    for label, field in [(r'마지막\s*주문|라스트\s*오더', 'order'), (r'입장\s*마감', 'admission')]:
        matches = list(re.finditer(rf'(?:{label})\s*({TIME})', text))
        if len(matches) > 1:
            raise ReviewNeeded('마감 시각이 여러 개라 검수 필요')
        if matches:
            value = minute(matches[0].group(1))
            if field == 'order':
                last_order = value
            else:
                last_admission = value
            text = re.sub(rf'(?:{label})\s*({TIME})', '', text)
    breaks = []
    for match in re.finditer(rf'(?:준비\s*시간|브레이크\s*타임)\s*{RANGE}', text):
        start, end = map(minute, match.groups())
        if end <= start:
            raise ReviewNeeded('휴게시간 날짜 경계 검수 필요')
        breaks.append(dict(startMinute=start, endMinute=end))
    text = re.sub(rf'(?:준비\s*시간|브레이크\s*타임)\s*{RANGE}', '', text)
    text = re.sub(r'[\s()\[\]·/\-]', '', text)
    if text in {'상시개방', '24시간', '24시간개방', '상시이용가능'}:
        opening, closing = 0, 1440
    else:
        match = re.fullmatch(rf'(?:매일)?{RANGE}', text)
        if not match:
            raise ReviewNeeded('계절·요일·대상별 운영시간 또는 자유문구 검수 필요')
        opening, closing = map(minute, match.groups())
        if closing <= opening:
            raise ReviewNeeded('야간·익일 운영시간 검수 필요')
    if any(not opening <= b['startMinute'] < b['endMinute'] <= closing for b in breaks):
        raise ReviewNeeded('운영시간 밖 휴게시간 검수 필요')
    if any(v is not None and not opening < v <= closing for v in [last_order, last_admission]):
        raise ReviewNeeded('운영시간 밖 주문·입장 마감 검수 필요')
    return dict(weekdays=days, openMinute=opening, closeMinute=closing, breaks=breaks,
                lastOrderMinute=last_order, lastAdmissionMinute=last_admission)


def windows(text):
    if any(word in text for word in ['숙박', '익일', '미성년', '일출', '일몰', '매월', '월~', '월]', '공휴일']):
        raise ReviewNeeded('숙박·계절·연령·특정일 등 추가 조건 검수 필요')
    # 주말에는 준비시간이 없다는 명시적 예외만 처리한다.
    weekend_no_break = '주말에는 준비시간 없이 영업' in text
    if weekend_no_break:
        text = text.replace('※ 주말에는 준비시간 없이 영업', '').replace('※주말에는 준비시간 없이 영업', '')
    # 평일/주말 또는 [일요일~목요일]처럼 전체 문장을 나누는 명시적 요일 레이블.
    label = r'(평일|주말|[월화수목금토일]요일\s*[~～∼]\s*[월화수목금토일]요일|[월화수목금토일]요일)'
    labels = list(re.finditer(label, text))
    if labels:
        if text[:labels[0].start()].strip(' -[]\n'):
            raise ReviewNeeded('요일 레이블 앞 조건 검수 필요')
        result, used = [], set()
        for index, match in enumerate(labels):
            name = re.sub(r'\s+', '', match.group(1))
            if name == '평일':
                days = [1, 2, 3, 4, 5]
            elif name == '주말':
                days = [6, 7]
            else:
                tokens = re.findall(r'([월화수목금토일])요일', name)
                days = day_range(*tokens)
            if used.intersection(days):
                raise ReviewNeeded('서로 겹치는 요일 예외 검수 필요')
            used.update(days)
            end = labels[index + 1].start() if index + 1 < len(labels) else len(text)
            result.append(single_window(text[match.end():end], days))
        if used != set(ALL_DAYS):
            raise ReviewNeeded('일부 요일만 설명되어 나머지 요일 검수 필요')
        return result
    result = single_window(text, ALL_DAYS)
    if weekend_no_break:
        return [dict(result, weekdays=[1, 2, 3, 4, 5]), dict(result, weekdays=[6, 7], breaks=[])]
    return [result]


def normalize(entry, detail):
    code = entry['contentTypeId']
    hour_field, rest_field = FIELDS[code]
    intro = detail.get('intro', {})
    raw_hours, raw_rest = str(intro.get(hour_field, '')).strip(), str(intro.get(rest_field, '')).strip() if rest_field else ''
    start, end = str(intro.get('eventstartdate', '')), str(intro.get('eventenddate', ''))
    fingerprint = hashlib.sha256('\n'.join([code, raw_hours, raw_rest, start, end]).encode()).hexdigest()
    record = dict(contentId=entry['contentId'], contentTypeId=code, status='REVIEW', reason='',
        rawHours=raw_hours, rawRest=raw_rest, eventStartDate=start, eventEndDate=end,
        sourceFingerprint=fingerprint, sourceCollectedAt=detail.get('introCollectedAt', ''),
        closedWeekdays=[], windows=[])
    if not raw_hours:
        record.update(status='MISSING', reason='이용시간 미제공')
        return record
    try:
        if code == '15':
            first, last = datetime.strptime(start, '%Y%m%d'), datetime.strptime(end, '%Y%m%d')
            if last < first:
                raise ReviewNeeded('행사 날짜 순서 검수 필요')
        record['closedWeekdays'] = rest_days(raw_rest, code == '15')
        record['windows'] = windows(raw_hours)
        record.update(status='READY', reason='등록 정보 기준 비교 가능·실제 영업 보장 아님')
    except (ReviewNeeded, ValueError) as error:
        record.update(status='REVIEW', reason=str(error) if isinstance(error, ReviewNeeded) else '행사 날짜 검수 필요', windows=[])
    return record


def main():
    assets = ROOT/'app/src/main/assets'
    entries = json.loads((assets/'tourism_catalog.json').read_text())
    details = json.loads((assets/'tourism_details.json').read_text())
    records = [normalize(e, details[e['contentId']]) for e in entries
               if e['contentTypeId'] in FIELDS and details.get(e['contentId'], {}).get('intro')]
    (assets/'operating_hours.json').write_text(json.dumps(records, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    seeds = {e['contentId'] for e in json.loads((assets/'theme_curation.json').read_text())}
    demo = [r for r in records if r['contentId'] in seeds]
    names = {e['contentId']: e['name'] for e in entries}
    counts = Counter(r['status'] for r in records)
    lines = ['# 운영시간 정리 결과', '', '원본·검색 목록은 유지한다. 데모 자동 코스에는 READY만 사용한다. READY는 등록 시간의 해석 가능 여부이며 실제 영업 확인이 아니다.', '',
             f'유형별 상세 {len(records):,}곳: 비교 가능 {counts["READY"]:,}, 검수 필요 {counts["REVIEW"]:,}, 이용시간 미제공 {counts["MISSING"]:,}.', '',
             '| 테마 장소 | 상태 | 사유 |', '|---|---|---|']
    lines += [f'| {names[r["contentId"]]} | {r["status"]} | {r["reason"]} |' for r in demo]
    lines += ['', '## 해석 범위', '', '- 연중무휴·단순 매주 휴무, 단순 시간대·상시 개방, 명시된 평일/주말·요일 구간을 구조화한다.',
        '- 준비시간·주문/입장 마감을 별도 보존한다. 주말 준비시간 없음 예외도 명시된 경우에만 반영한다.',
        '- 명절·공휴일 대체휴무·계절·매월 특정일·연령·숙박 시간처럼 추가 확인이 필요한 조건은 REVIEW로 남긴다.',
        '- 해석하지 못한 문구를 버리고 나머지 시간만 정상으로 판정하지 않는다.',
        '- 행사 날짜와 원본 시간·휴무 문구, 수집일·해시를 보존한다. 원본이 바뀌면 다시 생성해야 한다.',
        '- 코스 조합기에서는 출발·이동·체류시간을 누적해 이 자료를 검사한다. 실제 화면·경로 비용 연결과 Kotlin 실행 검증은 별도다.', '']
    (ROOT/'docs').mkdir(exist_ok=True)
    (ROOT/'docs/operating-hours-audit.md').write_text('\n'.join(lines), encoding='utf-8')
    print(f'상세 {len(records)}곳 정리: {dict(counts)}. 테마 {len(demo)}곳 중 데모 시간 비교 가능 {sum(r["status"] == "READY" for r in demo)}곳.')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""API를 호출하지 않고 상세 충실도·행사 기간·테마 검수 후보를 집계한다."""
import argparse
import csv
from datetime import date, datetime
import json
from pathlib import Path
import re
from zoneinfo import ZoneInfo

from enrich_tourism import ROOT

TYPES = {'12': '관광지', '14': '문화시설', '15': '축제·행사', '39': '음식점'}
FIELDS = {
    '12': {'이용시간': 'usetime', '휴무': 'restdate', '주차': 'parking'},
    '14': {'이용시간': 'usetimeculture', '휴무': 'restdateculture', '요금': 'usefee', '주차': 'parkingculture'},
    '15': {'시작일': 'eventstartdate', '종료일': 'eventenddate', '이용시간': 'playtime', '요금': 'usetimefestival'},
    '39': {'대표메뉴': 'firstmenu', '취급메뉴': 'treatmenu', '이용시간': 'opentimefood', '휴무': 'restdatefood', '주차': 'parkingfood'},
}
THEMES = ['축제·행사', '역사', '맛집', '카페투어', '계절', '힐링']


def event_state(intro, as_of):
    try:
        start = datetime.strptime(intro.get('eventstartdate', ''), '%Y%m%d').date()
        end = datetime.strptime(intro.get('eventenddate', ''), '%Y%m%d').date()
    except (ValueError, TypeError):
        return '기간 미확인'
    if end < start:
        return '기간 오류'
    return '종료' if end < as_of else '예정' if start > as_of else '기간 내'


def theme_hints(entry, intro):
    code, name = entry['contentTypeId'], entry['name']
    hints = {}
    if code == '15':
        hints['축제·행사'] = '콘텐츠 유형 15'
    if code == '39':
        menu = str(intro.get('firstmenu', '')) + ' ' + str(intro.get('treatmenu', ''))
        if re.search(r'카페|커피|cafe', name, re.I) or re.search(r'커피|에스프레소|아메리카노|카페라떼', menu):
            hints['카페투어'] = '상호 또는 메뉴의 카페·커피 단어(검수 필요)'
        else:
            hints['맛집'] = '음식점 유형(맛·인기도를 검증한 의미 아님)'
    if code in {'12', '14'}:
        if entry.get('classification', {}).get('lclsSystm1') == 'HS':
            hints['역사'] = '원본 분류 HS(세부 성격 검수 필요)'
        elif re.search(r'유적|고궁|궁궐|서원|향교|고분|역사관|독립기념', name):
            hints['역사'] = '장소명의 역사 관련 단어(검수 필요)'
        if re.search(r'수목원|식물원|휴양림|생태공원|치유의숲|산책', name):
            hints['힐링'] = '장소명의 자연·산책 관련 단어(검수 필요)'
    if re.search(r'벚꽃|단풍|수국|진달래|해맞이|눈꽃|빙어', name):
        hints['계절'] = '장소명의 계절 관련 단어(적정 시기 검수 필요)'
    return hints


def audit(entries, details, as_of):
    rows = []
    for entry in entries:
        code = entry['contentTypeId']
        if code not in TYPES:
            continue
        detail = details.get(entry['contentId'], {})
        intro = detail.get('intro', {})
        missing = [label for label, field in FIELDS[code].items() if not str(intro.get(field, '')).strip()]
        image = bool(entry.get('imageUrl'))
        description = bool(detail.get('description', '').strip())
        state = event_state(intro, as_of) if code == '15' else ''
        time_field = FIELDS[code]['이용시간']
        reasons = []
        if not image:
            reasons.append('사진 URL 없음')
        if not description:
            reasons.append('소개문 없음')
        if not intro:
            reasons.append('유형별 상세 없음')
        if not str(intro.get(time_field, '')).strip():
            reasons.append('운영시간 없음')
        if code == '15' and state not in {'기간 내', '예정'}:
            reasons.append('행사 '+state)
        rows.append(dict(contentId=entry['contentId'], name=entry['name'], regionCode=entry['regionCode'],
            type=TYPES[code], code=code, imageUrl=entry.get('imageUrl', ''),
            hasDescription=description, hasIntro=bool(intro), eventState=state,
            missingFields=' / '.join(missing), gaps=' / '.join(reasons),
            candidateReady=not reasons, themes=theme_hints(entry, intro)))
    return rows


def render_report(rows, details, as_of):
    lines = ['# 관광데이터 검수 결과', '',
        f'기준일: {as_of.isoformat()} (한국 시간). 로컬 자산 기준, 추가 API 호출 없음.', '',
        '## 분류별 충실도', '', '| 분류 | 목록 | 소개문 | 사진 URL | 유형별 상세 | 카드 후보 |',
        '|---|---:|---:|---:|---:|---:|']
    for code, label in TYPES.items():
        group = [r for r in rows if r['code'] == code]
        count = lambda field: sum(bool(r[field]) for r in group)
        lines.append(f"| {label} | {len(group):,} | {count('hasDescription'):,} | {count('imageUrl'):,} | {count('hasIntro'):,} | {count('candidateReady'):,} |")
    lines += ['', '카드 후보는 사진 URL·소개문·유형별 상세·운영시간이 함께 있는 장소다. '
        '행사는 기간이 유효한 예정/기간 내 항목만 포함한다. 사진 다운로드·이용조건·실제 영업·행사 개최를 검증한 수치는 아니다.', '',
        '## 상세 필드 보유', '', '| 분류 | 상세 확보 | 항목별 내용 있음 |', '|---|---:|---|']
    for code, label in TYPES.items():
        group = [r for r in rows if r['code'] == code and r['hasIntro']]
        counts = [f"{name} {sum(bool(str(details[r['contentId']]['intro'].get(field, '')).strip()) for r in group)}"
                  for name, field in FIELDS[code].items()]
        lines.append(f"| {label} | {len(group)} | {' · '.join(counts)} |")
    lines += ['', '주차의 “불가능”, 휴무의 “연중무휴”도 내용 있음으로 집계한다. 주차 가능 장소 수가 아니다.', '',
        '## 행사 날짜', '']
    for state in ['기간 내', '예정', '종료', '기간 미확인', '기간 오류']:
        lines.append(f"- {state}: {sum(r['eventState'] == state for r in rows)}곳")
    lines += ['', '기간 내는 시작일~종료일 범위에 포함된다는 뜻이며 매일 개최한다는 의미는 아니다.', '',
        '## 테마 검수 후보', '', '| 테마 | 자동 분류 제안 | 카드 후보 조건도 충족 |', '|---|---:|---:|']
    for theme in THEMES:
        group = [r for r in rows if theme in r['themes'] and r['hasIntro']]
        lines.append(f"| {theme} | {len(group)} | {sum(r['candidateReady'] for r in group)} |")
    lines += ['', '테마는 확정 태그가 아니라 유형·분류·상호·메뉴에 근거한 검수 제안이다. '
        '후보가 겹칠 수 있으며 맛집 표시는 평점 검증을 뜻하지 않는다. 계절 적합성·실내외·체류시간은 별도 검수한다.', '',
        '## 다음 작업', '', '- 로컬 후보 CSV에서 테마와 사진 이용조건을 검수해 확정 장소 선정.',
        '- 소개문·운영시간 누락을 보완하고 종료 행사를 추천에서 제외.',
        '- 확정 태그·체류시간·실내외 정보를 갖춘 뒤 코스 추천 모델 연결.',
        '- 날씨는 추천 시 최신 발표 자료를 조회하는 별도 연동으로 진행.', '',
        '전체 후보: `.local/tourapi/audit/theme-candidates.csv` (Git 제외). 이 검수는 앱 추천 데이터나 팀원 화면을 변경하지 않는다.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--date', type=date.fromisoformat, default=datetime.now(ZoneInfo('Asia/Seoul')).date())
    args = parser.parse_args()
    assets = ROOT/'app/src/main/assets'
    entries = json.loads((assets/'tourism_catalog.json').read_text())
    details = json.loads((assets/'tourism_details.json').read_text())
    rows = audit(entries, details, args.date)
    folder = ROOT/'.local/tourapi/audit'
    folder.mkdir(parents=True, exist_ok=True)
    with (folder/'theme-candidates.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        fields = ['contentId', 'name', 'regionCode', 'type', 'candidateReady', 'eventState', 'missingFields', 'gaps', 'themes', 'imageUrl']
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        for row in rows:
            if row['hasIntro']:
                writer.writerow(dict(row, themes=' / '.join(f'{k}: {v}' for k,v in row['themes'].items())))
    report = ROOT/'docs/tourism-data-audit.md'
    report.parent.mkdir(exist_ok=True)
    report.write_text(render_report(rows, details, args.date), encoding='utf-8')
    print(f"유형별 상세 {sum(r['hasIntro'] for r in rows):,}곳 검수, 카드 후보 {sum(r['candidateReady'] for r in rows):,}곳. 보고서: docs/tourism-data-audit.md")


if __name__ == '__main__':
    main()

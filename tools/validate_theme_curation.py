#!/usr/bin/env python3
"""테마 시드의 원본 참조·근거·기간·필수 데이터를 API 호출 없이 검증한다."""
from collections import Counter
from datetime import datetime
import json
from enrich_tourism import ROOT

THEMES = {'FESTIVAL', 'HISTORY', 'FOOD', 'CAFE', 'SEASONAL', 'HEALING'}
SEASONS = {'SPRING', 'SUMMER', 'AUTUMN', 'WINTER'}
EXPOSURES = {'INDOOR', 'OUTDOOR', 'MIXED', 'UNKNOWN'}
TIME_FIELDS = {'12': 'usetime', '14': 'usetimeculture', '15': 'playtime', '39': 'opentimefood'}


def validate(seeds, entries, details):
    errors, seen = [], set()
    for seed in seeds:
        identifier = seed['contentId']
        if identifier in seen:
            errors.append(f'{identifier}: 중복')
        seen.add(identifier)
        entry, detail = entries.get(identifier), details.get(identifier, {})
        if entry is None:
            errors.append(f'{identifier}: 원본 장소 없음')
            continue
        reasons = seed.get('reasons', {})
        if not reasons or not set(reasons) <= THEMES or any(not v.strip() for v in reasons.values()):
            errors.append(f'{identifier}: 테마·사유 오류')
        if not set(seed.get('preferredSeasons', [])) <= SEASONS or seed.get('weatherExposure') not in EXPOSURES:
            errors.append(f'{identifier}: 계절·실내외 값 오류')
        if 'SEASONAL' in reasons and not seed.get('preferredSeasons'):
            errors.append(f'{identifier}: 계절 테마 시기 없음')
        evidence = seed.get('evidence', '')
        if not evidence or evidence not in detail.get('description', ''):
            errors.append(f'{identifier}: 소개문에서 근거 확인 불가')
        weather_evidence = seed.get('weatherEvidence', '')
        if seed.get('weatherExposure') != 'UNKNOWN' and (not weather_evidence or weather_evidence not in detail.get('description', '')):
            errors.append(f'{identifier}: 실내외 분류의 원문 근거 확인 필요')
        if not entry.get('imageUrl') or not entry.get('source') or entry['regionCode'][:2] not in {'11','30','26'}:
            errors.append(f'{identifier}: 사진·출처·시범 지역 확인 필요')
        intro = detail.get('intro', {})
        code = entry['contentTypeId']
        if not intro.get(TIME_FIELDS.get(code, '')):
            errors.append(f'{identifier}: 운영시간 없음')
        if code == '39' and not intro.get('firstmenu'):
            errors.append(f'{identifier}: 대표메뉴 없음')
        if ('FESTIVAL' in reasons) != (code == '15'):
            errors.append(f'{identifier}: 행사 유형 불일치')
        if code == '15':
            try:
                start = datetime.strptime(intro['eventstartdate'], '%Y%m%d')
                end = datetime.strptime(intro['eventenddate'], '%Y%m%d')
                if end < start:
                    raise ValueError()
            except (KeyError, ValueError, TypeError):
                errors.append(f'{identifier}: 행사 기간 오류')
    return errors


def main():
    assets = ROOT/'app/src/main/assets'
    seeds = json.loads((assets/'theme_curation.json').read_text())
    entries = {e['contentId']: e for e in json.loads((assets/'tourism_catalog.json').read_text())}
    details = json.loads((assets/'tourism_details.json').read_text())
    errors = validate(seeds, entries, details)
    if errors:
        raise SystemExit('\n'.join(errors))
    counts = Counter(theme for seed in seeds for theme in seed['reasons'])
    print(f'테마 {len(seeds)}곳 원본·근거·필수정보 검증 통과. ' + ', '.join(f'{k}={v}' for k,v in sorted(counts.items())))


if __name__ == '__main__':
    main()

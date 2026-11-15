#!/usr/bin/env python3
"""장소 일치 확인을 통과한 상세 캐시만 앱용으로 변환한다."""
import json
from html.parser import HTMLParser
from pathlib import Path

from enrich_tourism import ROOT, identity_matches


class PlainText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
    def handle_data(self, data):
        self.parts.append(data)
    def handle_starttag(self, tag, attrs):
        if tag in {'br', 'p', 'div'}:
            self.parts.append('\n')


def description(value):
    parser = PlainText()
    parser.feed(str(value or ''))
    return '\n'.join(line.strip() for line in ''.join(parser.parts).splitlines() if line.strip())


def build(entries, directory):
    result = {}
    for entry in entries:
        content_id = entry['contentId']
        detail = {'description': '', 'accessibility': {}, 'accessibilityStatus': '미확인',
                  'commonCollectedAt': '', 'accessibilityCollectedAt': ''}
        for kind in ['common', 'accessibility']:
            path = directory/kind/(content_id+'.json')
            if not path.exists():
                continue
            record = json.loads(path.read_text())
            if kind == 'accessibility' and record.get('status') in {'empty', 'identity-mismatch'}:
                detail['accessibilityStatus'] = ('조회 완료·정보 없음' if record['status'] == 'empty'
                                                  else '장소 불일치·검수 필요')
                detail['accessibilityCollectedAt'] = record.get('collectedAt', '')
                continue
            if record.get('status') != 'matched' or not identity_matches(entry, record.get('identityItems', [])):
                continue
            if kind == 'common':
                detail['description'] = description(record['identityItems'][0].get('overview', ''))
                detail['commonCollectedAt'] = record.get('collectedAt', '')
            else:
                items = record.get('items', [])
                if len(items) != 1 or str(items[0].get('contentid')) != content_id:
                    continue
                # 빈 값은 지원 여부를 단정할 수 없으므로 결과 맵에서 생략한다.
                detail['accessibility'] = {k: v.strip() for k,v in items[0].items()
                    if k != 'contentid' and isinstance(v,str) and v.strip()}
                detail['accessibilityStatus'] = '정보 제공' if detail['accessibility'] else '미확인'
                detail['accessibilityCollectedAt'] = record.get('collectedAt', '')
        intro_path = directory/'intro'/(content_id+'.json')
        if not intro_path.exists() and entry.get('contentTypeId') == '39':
            intro_path = directory/'food-intro'/(content_id+'.json')
        if intro_path.exists():
            record = json.loads(intro_path.read_text())
            items = record.get('items', [])
            detail['introStatus'] = record.get('status', 'pending')
            if record.get('status') in {'matched', 'empty', 'identity-mismatch'}:
                detail['introCollectedAt'] = record.get('collectedAt', '')
            if record.get('status') == 'matched' and len(items) == 1 and str(items[0].get('contentid')) == content_id and str(items[0].get('contenttypeid')) == entry.get('contentTypeId'):
                detail['intro'] = {k: description(v) for k,v in items[0].items()
                    if k not in {'contentid', 'contenttypeid'} and v is not None and str(v).strip()}
                if entry.get('contentTypeId') == '39':
                    fields = ['firstmenu','treatmenu','opentimefood','restdatefood','parkingfood','reservationfood','packing','infocenterfood']
                    detail['food'] = {k:detail['intro'][k] for k in fields if k in detail['intro']}
                    detail['foodCollectedAt'] = record.get('collectedAt', '')
        if detail['commonCollectedAt'] or detail['accessibilityCollectedAt'] or detail.get('introCollectedAt'):
            result[content_id] = detail
    return result


def main():
    entries = json.loads((ROOT/'app/src/main/assets/tourism_catalog.json').read_text())
    result = build(entries, ROOT/'.local/tourapi/enrichment')
    (ROOT/'app/src/main/assets/tourism_details.json').write_text(
        json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    descriptions = sum(bool(item['description']) for item in result.values())
    accessibility = sum(bool(item['accessibility']) for item in result.values())
    intros = sum(bool(item.get('intro')) for item in result.values())
    print(f'상세·조회 상태 {len(result)}곳 반영: 소개문 {descriptions}곳, 유형별 상세 {intros}곳, 접근성 정보 {accessibility}곳.')


if __name__ == '__main__':
    main()

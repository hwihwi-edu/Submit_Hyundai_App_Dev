#!/usr/bin/env python3
"""시연 지역 음식점의 메뉴·영업정보를 수집한다. 같은 명령은 완료분을 건너뛴다."""
import argparse
import getpass
import json
import os
from datetime import datetime, timezone
from enrich_tourism import ROOT, save
from tourapi_collect import request_page, ApiError


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--region',default='30200',help='5자리 구 코드. 기본 대전 유성구')
    p.add_argument('--limit',type=int,default=10,help='이번 실행에서 추가 조회할 음식점 수')
    a=p.parse_args()
    if a.limit<1: p.error('limit는 1 이상이어야 합니다.')
    entries=json.loads((ROOT/'app/src/main/assets/tourism_catalog.json').read_text())
    entries=[e for e in entries if e['regionCode']==a.region and e['contentTypeId']=='39']
    if not entries: p.error('해당 구에 음식점이 없습니다.')
    entries.sort(key=lambda e:(not bool(e.get('imageUrl')),e['name'],e['contentId']))
    key=os.environ.get('TOURAPI_COMMON_KEY','').strip() or os.environ.get('TOURAPI_SERVICE_KEY','').strip()
    if not key: key=getpass.getpass('국문 관광정보 일반 인증키(숨김 입력): ').strip()
    if not key: p.error('인증키가 비었습니다.')
    calls=0
    try:
        for e in entries:
            path=ROOT/'.local/tourapi/enrichment/food-intro'/(e['contentId']+'.json')
            if path.exists(): continue
            if calls>=a.limit: break
            items,total=request_page('food-intro',key,dict(contentId=e['contentId'],contentTypeId='39',numOfRows=10,pageNo=1))
            calls+=1
            status='empty' if total==0 and not items else 'identity-mismatch'
            if total==1 and len(items)==1 and str(items[0].get('contentid'))==e['contentId'] and str(items[0].get('contenttypeid'))=='39': status='matched'
            save(path,dict(contentId=e['contentId'],status=status,items=items,collectedAt=datetime.now(timezone.utc).isoformat()),key)
            print(f'{e["name"]}: {status}',flush=True)
    except ApiError as error:
        print(str(error)+' 이전 저장분은 유지됩니다.')
    from build_tourism_details import main as export
    export()

if __name__=='__main__':main()

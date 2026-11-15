#!/usr/bin/env python3
"""세 도시 관광정보를 인증키 한 번 입력으로 수집한다."""
import argparse
import getpass
import os
from pathlib import Path

from tourapi_collect import main as collect_main


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--max-pages', type=int, default=500,
                        help='도시별 이번 실행에서 추가로 조회할 최대 페이지 수')
    args = parser.parse_args()
    if args.max_pages < 1:
        parser.error('--max-pages는 1 이상이어야 합니다.')
    root = Path(__file__).resolve().parents[1] / '.local' / 'tourapi'
    key = os.environ.get('TOURAPI_SERVICE_KEY', '').strip()
    if not key:
        key = getpass.getpass('국문 관광정보 일반 인증키 입력(화면에 표시되지 않음): ').strip()
    if not key:
        parser.error('인증키가 비어 있습니다.')
    failures = []
    for name, label, code in [('seoul', '서울', '11'), ('daejeon', '대전', '30'),
                              ('busan', '부산', '26')]:
        output = root / f'{name}-area-places.json'
        command = ['places-area', '--city', code, '--max-pages', str(args.max_pages),
                   '--output', str(output)]
        if output.exists():
            command.append('--resume')
        print(f'[{label}] 확인 중', flush=True)
        try:
            collect_main(command, service_key=key)
        except SystemExit as error:
            if error.code:
                failures.append(label)
                print(f'[{label}] 실패. 기존 수집 파일은 보존됩니다.')
    if failures:
        parser.exit(1, '재확인할 도시: ' + ', '.join(failures) + '\n')
    print('세 도시 처리 완료. 각 파일의 truncated=false 여부로 전체 수집 여부를 확인하세요.')


if __name__ == '__main__':
    main()

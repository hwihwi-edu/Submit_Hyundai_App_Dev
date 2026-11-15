#!/usr/bin/env python3
"""테마 장소의 TourAPI 사진 원본을 저장하고 출처·이용조건·해시를 기록한다."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from urllib.parse import urlparse
from urllib.request import urlopen

from enrich_tourism import ROOT

LICENSES = {'Type1': ('제1유형: 출처표시', 'https://www.kogl.or.kr/info/licenseType1.do'),
            'Type3': ('제3유형: 출처표시·변경금지', 'https://www.kogl.or.kr/info/licenseType3.do')}


def image_extension(data):
    if data.startswith(b'\x89PNG\r\n\x1a\n'):
        return '.png'
    if data.startswith(b'\xff\xd8\xff'):
        return '.jpg'
    if len(data) >= 54 and data.startswith(b'BM'):
        return '.bmp'
    raise ValueError('지원하는 PNG/JPEG/BMP 원본이 아닙니다.')


def valid_url(url):
    parsed = urlparse(url)
    return parsed.scheme == 'https' and parsed.hostname == 'tong.visitkorea.or.kr' and not parsed.username


def download_url(url):
    parsed = urlparse(url)
    if parsed.scheme not in {'http', 'https'} or parsed.hostname != 'tong.visitkorea.or.kr' or parsed.username:
        raise ValueError('관광공사 원본 주소가 아닙니다.')
    return parsed._replace(scheme='https').geturl()


def verify(records, entries, directory, expected_ids):
    errors, seen = [], set()
    for record in records:
        identifier = record['contentId']
        entry = entries.get(identifier, {})
        path = directory/record['assetPath']
        if (identifier in seen or identifier not in expected_ids or
                record['imageLicense'] not in LICENSES or
                record['sourceUrl'] != entry.get('imageUrl') or
                record['imageLicense'] != entry.get('imageLicense') or
                path.parent != directory/'tourism_images' or path.stem != identifier):
            errors.append(f'{identifier}: 출처·이용조건·경로 불일치')
        elif not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']:
            errors.append(f'{identifier}: 파일 누락 또는 해시 불일치')
        elif not record.get('attribution') or not record.get('licenseUrl'):
            errors.append(f'{identifier}: 출처 표시 누락')
        seen.add(identifier)
    if seen != expected_ids:
        errors.append(f'예상 {len(expected_ids)}곳, 기록 {len(seen)}곳으로 사진 목록이 다릅니다.')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='네트워크 없이 저장 파일·해시·출처 검증')
    args = parser.parse_args()
    assets = ROOT/'app/src/main/assets'
    entries = {e['contentId']: e for e in json.loads((assets/'tourism_catalog.json').read_text())}
    seeds = json.loads((assets/'theme_curation.json').read_text())
    expected = {s['contentId'] for s in seeds}
    manifest = assets/'tourism_images.json'
    records = json.loads(manifest.read_text()) if manifest.exists() else []
    if not args.check:
        old = {r['contentId']: r for r in records}
        records = []
        folder = assets/'tourism_images'
        folder.mkdir(parents=True, exist_ok=True)
        for identifier in sorted(expected):
            entry = entries[identifier]
            license_code, url = entry.get('imageLicense'), entry.get('imageUrl', '')
            try:
                if license_code not in LICENSES:
                    raise ValueError('이용조건 또는 원본 URL을 확인해야 합니다.')
                fetch_url = download_url(url)
                previous = old.get(identifier)
                if previous and not verify([previous], entries, assets, {identifier}):
                    records.append(previous)
                    continue
                with urlopen(fetch_url, timeout=30) as response:
                    if not valid_url(response.geturl()):
                        raise ValueError('다른 호스트로 이동한 응답입니다.')
                    data = response.read(10 * 1024 * 1024 + 1)
                if len(data) > 10 * 1024 * 1024:
                    raise ValueError('원본이 파일당 10MiB 제한을 초과했습니다.')
                path = folder/(identifier + image_extension(data))
                temporary = path.with_suffix('.tmp')
                temporary.write_bytes(data)
                temporary.replace(path)
                label, license_url = LICENSES[license_code]
                records.append(dict(contentId=identifier, title=entry['name'],
                    assetPath='tourism_images/'+path.name, sourceUrl=url, downloadUrl=fetch_url,
                    sourceService=entry['source'], imageLicense=license_code, licenseUrl=license_url,
                    attribution=f"한국관광공사 TourAPI 제공 · {entry['name']} · 공공누리 {label}",
                    creator=None, creatorStatus='API 원본 목록에 개별 작성자 미제공',
                    allowCropping=license_code == 'Type1', originalUnmodified=True,
                    collectedAt=datetime.now(timezone.utc).isoformat(),
                    sha256=hashlib.sha256(data).hexdigest(), bytes=len(data)))
                print(f'{identifier}: 원본 저장 ({len(data):,} bytes)', flush=True)
            except (OSError, ValueError):
                print(f'{identifier}: 사진 저장 실패. 기존 앱 데이터·인증키는 건드리지 않았습니다.', flush=True)
        temporary = manifest.with_suffix('.tmp')
        temporary.write_text(json.dumps(records, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        temporary.replace(manifest)
    errors = verify(records, entries, assets, expected)
    if errors:
        parser.exit(1, '\n'.join(errors)+'\n')
    print(f'사진 {len(records)}장 원본·출처·해시 검증 통과. 총 {sum(r["bytes"] for r in records):,} bytes.')


if __name__ == '__main__':
    main()

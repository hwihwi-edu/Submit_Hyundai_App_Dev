import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from build_tourism_details import build
from collect_required_details import balanced, collect, collect_intro
from tourapi_collect import ApiLimitError


def entry(identifier='1', code='39', region='30200'):
    return dict(contentId=identifier, contentTypeId=code, regionCode=region,
                name='장소', lat=36.3, lng=127.3)


class RequiredDetailsTest(unittest.TestCase):
    def test_intro_only_does_not_call_limited_common(self):
        with TemporaryDirectory() as temporary:
            calls = []
            def fetch(kind, key, params):
                calls.append(kind)
                if kind == 'common':
                    raise ApiLimitError('한도')
                return [], 0
            collect([entry(), entry('2')], 'fake', Path(temporary), delay=0,
                    fetch=fetch, kind='intro')
            self.assertEqual(calls, ['intro', 'intro'])

    def test_quota_preserves_intro_and_resume_only_fetches_common(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            def limited(kind, key, params):
                if kind == 'common':
                    raise ApiLimitError('한도')
                return [dict(contentid='1', contenttypeid='39', firstmenu='국수')], 1
            with self.assertRaises(ApiLimitError):
                collect([entry()], 'fake', root, delay=0, fetch=limited)
            calls = []
            def fetch(kind, key, params):
                calls.append(kind)
                return [dict(contentid='1', title='장소', mapy='36.3', mapx='127.3', overview='소개')], 1
            collect([entry()], 'fake', root, delay=0, fetch=fetch)
            self.assertEqual(calls, ['common'])
            self.assertEqual(build([entry()], root)['1']['food']['firstmenu'], '국수')

    def test_old_food_cache_skipped_and_other_type_rejected(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'food-intro').mkdir()
            (root/'food-intro/1.json').write_text(json.dumps(dict(status='empty')))
            def unexpected(*args):
                self.fail('완료된 음식점을 다시 조회했습니다.')
            self.assertEqual(collect_intro(entry(), 'fake', root, unexpected), 0)
            collect_intro(entry('2', '14'), 'fake', root,
                lambda *args: ([dict(contentid='2', contenttypeid='39', firstmenu='오류')], 1))
            detail = build([entry('2', '14')], root)['2']
            self.assertNotIn('intro', detail)
            self.assertEqual(detail['introStatus'], 'identity-mismatch')

    def test_balanced_across_cities_and_types_and_budget(self):
        entries = [entry('1'), entry('2'), entry('3', '12'), entry('4', '39', '11110')]
        first = balanced(entries)[:3]
        self.assertEqual(len({(e['regionCode'][:2],e['contentTypeId']) for e in first}), 3)
        with TemporaryDirectory() as temporary:
            calls = []
            def fetch(kind, key, params):
                calls.append(kind)
                return [], 0
            self.assertEqual(collect(entries, 'fake', Path(temporary), budget=2, delay=0, fetch=fetch), 2)
            self.assertEqual(len(calls), 2)


if __name__ == '__main__':
    unittest.main()

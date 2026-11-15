import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from enrich_tourism import enrich, identity_matches, status_of
from tourapi_collect import ApiLimitError
from build_tourism_details import build, description

ENTRY = dict(contentId='1',name='장소',lat=36.3,lng=127.3)
IDENTITY = dict(contentid='1',title='장소',mapy='36.3',mapx='127.3',overview='소개<br>둘째 줄')


class EnrichmentTest(unittest.TestCase):
    def test_default_collects_more_than_200_entries(self):
        entries = [dict(ENTRY, contentId=str(i)) for i in range(205)]
        def fetch(kind, key, params):
            return [dict(IDENTITY, contentid=params['contentId'])], 1
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = enrich(entries, 'common', 'fake', root, fetch=fetch)
            self.assertEqual(result['completed'], 205)
            self.assertEqual(status_of(entries, 'common', root)['pending'], 0)

    def test_quota_stop_preserves_completed_and_resumes_missing(self):
        entries = [ENTRY, dict(ENTRY, contentId='2')]
        def limited(kind, key, params):
            if params['contentId'] == '2':
                raise ApiLimitError('한도 도달')
            return [IDENTITY], 1
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ApiLimitError):
                enrich(entries, 'common', 'fake', root, fetch=limited)
            self.assertEqual(status_of(entries, 'common', root)['pending'], 1)
            requested = []
            def fetch(kind, key, params):
                requested.append(params['contentId'])
                return [dict(IDENTITY, contentid=params['contentId'])], 1
            enrich(entries, 'common', 'fake', root, fetch=fetch)
            self.assertEqual(requested, ['2'])

    def test_identity_rejects_same_id_at_different_place(self):
        self.assertTrue(identity_matches(ENTRY,[IDENTITY]))
        self.assertFalse(identity_matches(ENTRY,[dict(IDENTITY,mapy='37.3')]))
        self.assertFalse(identity_matches(ENTRY,[dict(IDENTITY,title='다른 장소')]))

    def test_budget_resume_and_empty_fields(self):
        calls=[]
        def fetch(kind,key,params):
            calls.append(kind)
            return ([IDENTITY] if kind=='access-common' else
                    [dict(contentid='1',parking='주차장 있음',wheelchair='')]),1
        with TemporaryDirectory() as temporary:
            root=Path(temporary)
            result=enrich([ENTRY],'accessibility','fake',root,1,fetch)
            self.assertEqual(result['requests'],1)
            self.assertEqual(result['completed'],0)
            enrich([ENTRY],'accessibility','fake',root,1,fetch)
            self.assertEqual(calls,['access-common','accessibility'])
            detail=build([ENTRY],root)['1']
            self.assertEqual(detail['accessibility'],{'parking':'주차장 있음'})
            self.assertEqual(detail['accessibilityStatus'],'정보 제공')
            enrich([ENTRY],'accessibility','fake',root,1,fetch)
            self.assertEqual(len(calls),2)

    def test_identity_mismatch_never_queries_accessibility(self):
        calls=[]
        def fetch(kind,key,params):
            calls.append(kind)
            return [dict(IDENTITY,title='다른 장소')],1
        with TemporaryDirectory() as temporary:
            root=Path(temporary)
            enrich([ENTRY],'accessibility','fake',root,10,fetch)
            self.assertEqual(calls,['access-common'])
            self.assertEqual(build([ENTRY],root)['1']['accessibilityStatus'],'장소 불일치·검수 필요')

    def test_empty_response_is_cached_without_claiming_access(self):
        with TemporaryDirectory() as temporary:
            root=Path(temporary)
            enrich([ENTRY],'accessibility','fake',root,10,lambda *a:([],0))
            detail = build([ENTRY],root)['1']
            self.assertEqual(detail['accessibilityStatus'],'조회 완료·정보 없음')
            self.assertEqual(detail['accessibility'],{})

    def test_description_and_secret_redaction(self):
        self.assertEqual(description('첫째<br>둘째 &amp; 셋째'),'첫째\n둘째 & 셋째')
        with TemporaryDirectory() as temporary:
            root=Path(temporary)
            enrich([ENTRY],'common','fake-secret',root,1,
                lambda *a:([dict(IDENTITY,overview='fake-secret')],1))
            text=(root/'common/1.json').read_text()
            self.assertNotIn('fake-secret',text)
            self.assertEqual(build([ENTRY],root)['1']['description'],'[REDACTED]')

"""실제 키나 네트워크 없이 응답 처리·페이지 중단을 검증한다."""
import json
import unittest
from unittest.mock import patch
import tourapi_collect as api


def response(items, total=1, code='0000'):
    return json.dumps({'response': {'header': {'resultCode': code},
        'body': {'items': items, 'totalCount': total}}})


class CollectorTest(unittest.TestCase):
    def test_json_and_xml_quota_errors_are_identified(self):
        for raw in [response('', 0, code='22'),
                    '<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>']:
            with self.assertRaises(api.ApiLimitError):
                api.parse_response(raw)

    def test_resume_fetches_next_page_and_keeps_existing_items(self):
        seen = []
        previous = [{'id': i} for i in range(50)]
        def fetch(kind, key, params):
            seen.append(params['pageNo'])
            return [{'id': 50}], 51
        items, truncated = api.collect('places-area', 'unused', {}, 5, fetch,
                                       initial_items=previous)
        self.assertEqual(seen, [2])
        self.assertEqual(len(items), 51)
        self.assertFalse(truncated)
        self.assertEqual(len(previous), 50)

    def test_resume_rejects_partial_page(self):
        with self.assertRaises(api.ApiError):
            api.collect('places-area', 'unused', {}, 5, initial_items=[{'id': 1}])

    def test_single_item_and_empty_response(self):
        self.assertEqual(api.parse_response(response({'item': {'contentid': '1'}})),
                         ([{'contentid': '1'}], 1))
        self.assertEqual(api.parse_response(response('', 0)), ([], 0))

    def test_api_error_does_not_echo_body(self):
        with self.assertRaises(api.ApiError) as error:
            api.parse_response(response({'secret': 'DO_NOT_PRINT'}, code='30'))
        self.assertNotIn('DO_NOT_PRINT', str(error.exception))

    def test_xml_and_malformed_responses_fail_closed(self):
        for raw in ['<error>DO_NOT_PRINT</error>', '{}',
                    response({'item': ['unexpected']})]:
            with self.assertRaises(api.ApiError):
                api.parse_response(raw)

    def test_pagination_stops_at_total(self):
        seen = []
        def fetch(kind, key, params):
            seen.append(params['pageNo'])
            return [{'id': params['pageNo']}], 2
        items, truncated = api.collect('stories', 'unused', {}, 5, fetch)
        self.assertEqual(seen, [1, 2])
        self.assertEqual(len(items), 2)
        self.assertFalse(truncated)

    def test_page_limit_reports_partial_collection(self):
        self.assertTrue(api.collect('places', 'unused', {}, 1,
            lambda *args: ([{'id': 1}], 200))[1])

    def test_premature_empty_page_is_error(self):
        with self.assertRaises(api.ApiError):
            api.collect('places', 'unused', {}, 2, lambda *args: ([], 200))

    def test_encoded_key_is_not_double_encoded(self):
        class Reply:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self): return response('', 0).encode()
        with patch.object(api, 'urlopen', return_value=Reply()) as call:
            api.request_page('stories', 'fake%2Bkey%3D', {'keyword': '천안'})
        url = call.call_args[0][0]
        self.assertIn('serviceKey=fake%2Bkey%3D', url)
        self.assertNotIn('%252B', url)


if __name__ == '__main__':
    unittest.main()

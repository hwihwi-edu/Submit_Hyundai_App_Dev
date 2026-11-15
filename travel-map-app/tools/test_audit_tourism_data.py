from datetime import date
import unittest

from audit_tourism_data import audit, event_state, theme_hints


class TourismAuditTest(unittest.TestCase):
    def test_event_boundaries_and_invalid_dates(self):
        today = date(2026, 11, 14)
        self.assertEqual(event_state(dict(eventstartdate='20261114', eventenddate='20261114'), today), '기간 내')
        self.assertEqual(event_state(dict(eventstartdate='20261115', eventenddate='20261116'), today), '예정')
        self.assertEqual(event_state(dict(eventstartdate='20261115', eventenddate='20261113'), today), '기간 오류')
        self.assertEqual(event_state(dict(eventstartdate='잘못된 날짜'), today), '기간 미확인')

    def test_expired_event_with_complete_fields_is_not_ready(self):
        entry = dict(contentId='1', contentTypeId='15', name='행사', regionCode='30200', imageUrl='https://example.com/photo.jpg')
        detail = dict(description='설명', intro=dict(eventstartdate='20261001', eventenddate='20261031', playtime='10시'))
        row = audit([entry], {'1': detail}, date(2026, 11, 14))[0]
        self.assertFalse(row['candidateReady'])
        self.assertEqual(row['eventState'], '종료')

    def test_cafe_menu_hint_and_missing_description(self):
        entry = dict(contentId='1', contentTypeId='39', name='가게', regionCode='30200', imageUrl='https://example.com/photo.jpg')
        intro = dict(firstmenu='아메리카노', opentimefood='10~18시')
        self.assertIn('카페투어', theme_hints(entry, intro))
        row = audit([entry], {'1': dict(intro=intro)}, date(2026, 11, 14))[0]
        self.assertFalse(row['candidateReady'])
        self.assertIn('소개문 없음', row['gaps'])


if __name__ == '__main__':
    unittest.main()

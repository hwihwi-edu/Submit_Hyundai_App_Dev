import unittest
from build_operating_hours import normalize


class OperatingHoursBuildTest(unittest.TestCase):
    def record(self, hours, rest='연중무휴', code='39', **extra):
        fields = {'39': ('opentimefood','restdatefood'), '12': ('usetime','restdate'), '15': ('playtime',None)}
        hour_field, rest_field = fields[code]
        intro = {hour_field: hours, **extra}
        if rest_field:
            intro[rest_field] = rest
        return normalize(dict(contentId='1',contentTypeId=code), dict(intro=intro,introCollectedAt='확인일'))

    def test_break_and_order_deadline_are_not_opening_ranges(self):
        record = self.record('11:00~20:00- 준비시간 15:00~17:00- 마지막 주문 19:20')
        self.assertEqual(record['status'], 'READY')
        window = record['windows'][0]
        self.assertEqual((window['openMinute'],window['closeMinute']), (660,1200))
        self.assertEqual(window['breaks'], [dict(startMinute=900,endMinute=1020)])
        self.assertEqual(window['lastOrderMinute'], 1160)

    def test_weekday_and_weekend_close_and_order_times_stay_separate(self):
        record = self.record('- 평일 10:30~19:00 (마지막 주문 18:00) - 주말 10:30~20:00 (마지막 주문 19:00)')
        self.assertEqual(record['status'], 'READY')
        weekdays, weekend = record['windows']
        self.assertEqual(weekdays['weekdays'], [1,2,3,4,5])
        self.assertEqual(weekend['weekdays'], [6,7])
        self.assertEqual((weekdays['lastOrderMinute'],weekend['lastOrderMinute']), (1080,1140))

    def test_explicit_weekend_no_break_exception(self):
        record = self.record('- 11:30~21:00- 준비시간 14:30~17:00- 마지막 주문 19:30※ 주말에는 준비시간 없이 영업', '매주 월요일')
        self.assertEqual(record['status'], 'READY')
        self.assertEqual(record['closedWeekdays'], [1])
        self.assertTrue(record['windows'][0]['breaks'])
        self.assertEqual(record['windows'][1]['breaks'], [])

    def test_cross_week_day_label_and_event_period(self):
        record = self.record('[일요일~목요일]- 17:30~22:00[금요일~토요일]- 17:30~22:30', code='15',
            eventstartdate='20261211',eventenddate='20270110')
        self.assertEqual(record['status'], 'READY')
        self.assertEqual(record['windows'][0]['weekdays'], [7,1,2,3,4])

    def test_holiday_exception_is_not_silently_dropped(self):
        record = self.record('09:00~17:00', '매주 화요일 ※ 공휴일이면 개방, 다음날 휴무')
        self.assertEqual(record['status'], 'REVIEW')
        self.assertIn('공휴일', record['rawRest'])
        self.assertEqual(record['windows'], [])

    def test_lodging_and_age_condition_are_not_normal_visiting_hours(self):
        self.assertEqual(self.record('숙박 15:00~익일 11:00',code='12')['status'], 'REVIEW')
        self.assertEqual(self.record('상시 개방※ 밤 10시 이후 미성년자 출입 불가')['status'], 'REVIEW')

    def test_missing_info_and_unexplained_suffix_remain_unknown(self):
        self.assertEqual(self.record('')['status'], 'MISSING')
        self.assertEqual(self.record('10:00~20:00',rest='')['status'], 'REVIEW')
        self.assertEqual(self.record('10:00~20:00 (날짜별 변동)')['status'], 'REVIEW')

    def test_overnight_and_invalid_time_remain_review(self):
        self.assertEqual(self.record('18:00~02:00')['status'], 'REVIEW')
        self.assertEqual(self.record('00:00~24:30')['status'], 'REVIEW')
        self.assertEqual(self.record('11:00~20:00 (마지막 주문 21:00)')['status'], 'REVIEW')

    def test_24_hour_and_weekly_rest_range(self):
        record = self.record('상시 개방', '매주 월요일~화요일')
        self.assertEqual(record['closedWeekdays'], [1,2])
        self.assertEqual(record['windows'][0]['closeMinute'], 1440)

    def test_source_fingerprint_changes_when_rest_changes(self):
        first = self.record('10:00~20:00')
        updated = self.record('10:00~20:00','매주 일요일')
        self.assertNotEqual(first['sourceFingerprint'],updated['sourceFingerprint'])

    def test_event_needs_valid_period(self):
        self.assertEqual(self.record('12:00~19:00',code='15')['status'], 'REVIEW')
        self.assertEqual(self.record('12:00~19:00',code='15',eventstartdate='20261115',eventenddate='20261114')['status'], 'REVIEW')

from datetime import datetime, timezone
import unittest

from check_weather import KST, latest_issue, normalize
from tourapi_collect import ApiError


class WeatherCheckTest(unittest.TestCase):
    def test_ultra_hour_label_preserves_actual_issue_and_rejects_other_cycle(self):
        issued = datetime(2026,11,14,22,30,tzinfo=KST)
        item = dict(baseDate='20261114',baseTime='2200',nx=60,ny=127,category='T1H',
                    fcstDate='20261114',fcstTime='2300',fcstValue='12')
        result = normalize([item], 'ultra', issued, 60, 127)
        self.assertEqual(result[0]['issuedAt'], '2026-11-14T22:00:00+09:00')
        self.assertEqual(result[0]['validAt'], '2026-11-14T23:00:00+09:00')
        for wrong in [dict(item,baseTime='2100'), dict(item,baseDate='20261113'), dict(item,nx=67)]:
            with self.assertRaises(ApiError):
                normalize([wrong], 'ultra', issued, 60, 127)
        with self.assertRaises(ApiError):
            normalize([item,dict(item,baseTime='2230')], 'ultra', issued, 60, 127)

    def test_mismatch_diagnostic_shows_metadata_without_arbitrary_body(self):
        issued = datetime(2026,11,14,22,30,tzinfo=KST)
        item = dict(baseDate='20261114',baseTime='2300',nx='private-secret',ny=127)
        with self.assertRaises(ApiError) as caught:
            normalize([item], 'ultra', issued, 60, 127)
        self.assertIn('baseTime: 요청 2230, 응답 2300', str(caught.exception))
        self.assertNotIn('private-secret', str(caught.exception))

    def test_observation_release_and_midnight(self):
        self.assertEqual(latest_issue('observation', datetime(2026,11,15,0,9,tzinfo=KST)),
                         datetime(2026,11,14,23,0,tzinfo=KST))
        self.assertEqual(latest_issue('observation', datetime(2026,11,15,0,10,tzinfo=KST)),
                         datetime(2026,11,15,0,0,tzinfo=KST))

    def test_ultra_release_boundary(self):
        self.assertEqual(latest_issue('ultra', datetime(2026,11,14,12,44,tzinfo=KST)).hour, 11)
        self.assertEqual(latest_issue('ultra', datetime(2026,11,14,12,45,tzinfo=KST)),
                         datetime(2026,11,14,12,30,tzinfo=KST))

    def test_forecast_release_and_utc_conversion(self):
        self.assertEqual(latest_issue('forecast', datetime(2026,11,14,2,9,tzinfo=KST)),
                         datetime(2026,11,13,23,0,tzinfo=KST))
        self.assertEqual(latest_issue('forecast', datetime(2026,11,14,2,10,tzinfo=KST)),
                         datetime(2026,11,14,2,0,tzinfo=KST))
        self.assertEqual(latest_issue('forecast', datetime(2026,11,13,17,10,tzinfo=timezone.utc)),
                         datetime(2026,11,14,2,0,tzinfo=KST))

    def test_wrong_grid_or_issue_is_rejected(self):
        issued = datetime(2026,11,14,12,tzinfo=KST)
        item = dict(baseDate='20261114',baseTime='1200',nx=60,ny=127,category='T1H',obsrValue='20')
        with self.assertRaises(ApiError):
            normalize([item], 'observation', issued, 67, 100)
        with self.assertRaises(ApiError):
            normalize([dict(item,baseTime='1100')], 'observation', issued, 60, 127)

    def test_forecast_midnight_and_unknown_value_preserved(self):
        issued = datetime(2026,11,14,23,tzinfo=KST)
        item = dict(baseDate='20261114',baseTime='2300',nx=60,ny=127,category='TMP',
                    fcstDate='20261114',fcstTime='2400',fcstValue='-999')
        rows = normalize([item], 'forecast', issued, 60, 127)
        self.assertEqual(rows[0]['validAt'], '2026-11-15T00:00:00+09:00')
        self.assertEqual(rows[0]['values']['TMP'], '-999')

    def test_conflicting_categories_are_rejected(self):
        issued = datetime(2026,11,14,12,tzinfo=KST)
        item = dict(baseDate='20261114',baseTime='1200',nx=60,ny=127,category='T1H',obsrValue='20')
        with self.assertRaises(ApiError):
            normalize([item,dict(item,obsrValue='21')], 'observation', issued, 60, 127)

import unittest
from validate_theme_curation import validate


class WeatherEvidenceTest(unittest.TestCase):
    def validate_seed(self, exposure, proof):
        seed = dict(contentId='1', reasons={'CAFE':'커피'}, evidence='실내 좌석',
                    weatherExposure=exposure, weatherEvidence=proof, preferredSeasons=[])
        entry = dict(contentTypeId='39',regionCode='30200',imageUrl='사진',source='출처')
        detail = dict(description='실내 좌석이 있습니다.', intro={'opentimefood':'10:00~20:00','firstmenu':'커피'})
        return validate([seed], {'1':entry}, {'1':detail})

    def test_confirmed_indoor_needs_original_evidence(self):
        self.assertTrue(self.validate_seed('INDOOR',''))
        self.assertEqual(self.validate_seed('INDOOR','실내 좌석이 있습니다.'), [])

    def test_unrelated_claim_is_not_accepted(self):
        self.assertTrue(self.validate_seed('INDOOR','비 오는 날 안전한 장소'))

    def test_unknown_can_remain_unknown_without_inventing_proof(self):
        self.assertEqual(self.validate_seed('UNKNOWN',''), [])

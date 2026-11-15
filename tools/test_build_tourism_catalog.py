import unittest
from build_tourism_catalog import build


class CatalogTest(unittest.TestCase):
    def item(self, **changes):
        item = dict(contentid='1', title='장소', lDongRegnCd='30', lDongSignguCd='170',
                    mapx='127.3', mapy='36.3', contenttypeid='38')
        return dict(item, **changes)

    def convert(self, items):
        return build([dict(truncated=False, items=items)], {'30170'})

    def test_coordinates_and_all_content_types_preserved(self):
        accepted, rejected = self.convert([self.item()])
        self.assertEqual(accepted[0]['lat'], 36.3)
        self.assertEqual(accepted[0]['lng'], 127.3)
        self.assertEqual(accepted[0]['contentTypeId'], '38')
        self.assertEqual(rejected, [])

    def test_invalid_coordinate_and_region_are_preserved_for_review(self):
        accepted, rejected = self.convert([self.item(mapy='NaN'),
            self.item(contentid='2', lDongSignguCd='')])
        self.assertEqual(accepted, [])
        self.assertEqual(len(rejected), 2)
        self.assertEqual(rejected[0]['original']['mapy'], 'NaN')

    def test_duplicate_is_not_silently_included(self):
        accepted, rejected = self.convert([self.item(), self.item()])
        self.assertEqual(len(accepted), 1)
        self.assertEqual(len(rejected), 1)

    def test_incomplete_collection_is_rejected(self):
        with self.assertRaises(ValueError):
            build([dict(truncated=True, items=[])], {'30170'})

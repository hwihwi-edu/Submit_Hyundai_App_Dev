import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from cache_theme_images import download_url, image_extension, verify


class ThemeImagesTest(unittest.TestCase):
    def test_http_upgrade_keeps_host_and_resource_path(self):
        self.assertEqual(download_url('http://tong.visitkorea.or.kr/cms/image.jpg'),
                         'https://tong.visitkorea.or.kr/cms/image.jpg')
        with self.assertRaises(ValueError):
            download_url('https://example.com/photo.jpg')

    def test_preserves_bmp_and_rejects_html_error(self):
        self.assertEqual(image_extension(b'BM' + bytes(52)), '.bmp')
        self.assertEqual(image_extension(b'\x89PNG\r\n\x1a\n'), '.png')
        with self.assertRaises(ValueError):
            image_extension(b'<html>error</html>')

    def test_hash_and_license_change_are_detected(self):
        with TemporaryDirectory() as temporary:
            folder = Path(temporary)
            (folder/'tourism_images').mkdir()
            data = b'original'
            path = folder/'tourism_images/1.jpg'
            path.write_bytes(data)
            entry = dict(imageUrl='https://tong.visitkorea.or.kr/cms/1.jpg', imageLicense='Type3')
            record = dict(contentId='1', assetPath='tourism_images/1.jpg', sourceUrl=entry['imageUrl'],
                imageLicense='Type3', sha256=hashlib.sha256(data).hexdigest(), attribution='출처', licenseUrl='이용조건')
            self.assertEqual(verify([record], {'1':entry}, folder, {'1'}), [])
            path.write_bytes(b'changed')
            self.assertTrue(verify([record], {'1':entry}, folder, {'1'}))
            path.write_bytes(data)
            self.assertTrue(verify([record], {'1':dict(entry,imageLicense='Type1')}, folder, {'1'}))

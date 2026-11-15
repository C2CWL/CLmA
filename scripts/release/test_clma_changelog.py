import tempfile
import unittest
from pathlib import Path
import zipfile
from clma_changelog import asset_path, bundled_files, select_prs, verify_apk


class ChangelogTest(unittest.TestCase):
    def test_only_merged_commits_in_range(self):
        prs = [dict(number=n, title=t, mergeCommit={'oid': s}) for n, t, s in
               [(1, 'fix: shipped', 'in'), (2, 'feat: draft', 'out'),
                (3, 'chore(release): bump', 'bump')]]
        self.assertEqual([1], [p['number'] for p in select_prs(prs, {'in', 'bump'})])

    def test_unreviewed_draft_cannot_ship(self):
        with self.assertRaises(ValueError):
            bundled_files('3.4', '## CLmA 3.4\n- TODO: review\n')

    def test_exact_content_and_version(self):
        notes = '## CLmA 3.4\n\n- 修复测试\n'
        self.assertEqual({'app/src/main/assets/changelog/3.4.txt': notes}, bundled_files('3.4', notes))
        with self.assertRaises(ValueError):
            bundled_files('3.5', notes)
        with self.assertRaises(ValueError):
            asset_path('../3.4')
        with tempfile.TemporaryDirectory() as d:
            apk = Path(d) / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('assets/changelog/3.4.txt', notes)
            verify_apk(apk, '3.4', notes)
            with self.assertRaises(ValueError):
                verify_apk(apk, '3.4', notes + 'changed')


if __name__ == '__main__':
    unittest.main()

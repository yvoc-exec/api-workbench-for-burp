import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_pit_reports


class CheckPitReportsTest(unittest.TestCase):
    def write_report(self, root, shard, detected_values, prefix="target"):
        directory = Path(root, prefix, f"pit-{shard}")
        directory.mkdir(parents=True, exist_ok=True)
        mutations = "".join(
            f'<mutation detected="{value}"><sourceFile>X.java</sourceFile></mutation>'
            for value in detected_values
        )
        path = directory / "mutations.xml"
        path.write_text(f"<mutations>{mutations}</mutations>", encoding="utf-8")
        return path

    def run_cli(self, root, *groups, threshold="54"):
        output_json = Path(root, "summary.json")
        output_text = Path(root, "summary.txt")
        args = [
            "--reports-root",
            str(root),
            "--threshold",
            threshold,
            "--output-json",
            str(output_json),
            "--output-text",
            str(output_text),
        ]
        for group in groups:
            args.extend(["--group", group])
        return check_pit_reports.main(args), output_json, output_text

    def test_multiple_shards_aggregate_using_weighted_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_report(root, "small", ["true"])
            self.write_report(root, "large", ["false"] * 9)
            code, output_json, _ = self.run_cli(root, "weighted=small,large", threshold="20")
            self.assertEqual(1, code)
            data = json.loads(output_json.read_text(encoding="utf-8"))
            self.assertEqual(10.0, data["groups"][0]["score"])

    def test_group_exactly_at_54_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_report(root, "auth", ["true"] * 54 + ["false"] * 46)
            code, _, _ = self.run_cli(root, "gate=auth")
            self.assertEqual(0, code)

    def test_group_below_54_returns_threshold_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_report(root, "auth", ["true"] * 53 + ["false"] * 47)
            code, _, _ = self.run_cli(root, "gate=auth")
            self.assertEqual(1, code)

    def test_missing_shard_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, _, _ = self.run_cli(Path(tmp), "gate=missing")
            self.assertEqual(2, code)

    def test_more_than_one_report_for_one_shard_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_report(root, "auth", ["true"], prefix="one")
            self.write_report(root, "auth", ["true"], prefix="two")
            code, _, _ = self.run_cli(root, "gate=auth")
            self.assertEqual(2, code)

    def test_malformed_xml_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / "target" / "pit-auth"
            directory.mkdir(parents=True)
            (directory / "mutations.xml").write_text("<mutations>", encoding="utf-8")
            code, _, _ = self.run_cli(root, "gate=auth")
            self.assertEqual(2, code)

    def test_empty_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / "target" / "pit-auth"
            directory.mkdir(parents=True)
            (directory / "mutations.xml").write_text("<mutations />", encoding="utf-8")
            code, _, _ = self.run_cli(root, "gate=auth")
            self.assertEqual(2, code)

    def test_mutation_without_detected_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / "target" / "pit-auth"
            directory.mkdir(parents=True)
            (directory / "mutations.xml").write_text(
                "<mutations><mutation /></mutations>", encoding="utf-8"
            )
            code, _, _ = self.run_cli(root, "gate=auth")
            self.assertEqual(2, code)

    def test_invalid_detected_value_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_report(root, "auth", ["yes"])
            code, _, _ = self.run_cli(root, "gate=auth")
            self.assertEqual(2, code)

    def test_duplicate_group_names_are_rejected(self):
        with self.assertRaises(check_pit_reports.PitReportError):
            groups = [
                check_pit_reports.parse_group_definition("same=a"),
                check_pit_reports.parse_group_definition("same=b"),
            ]
            check_pit_reports.validate_groups(groups)

    def test_duplicate_shard_assignment_is_rejected(self):
        with self.assertRaises(check_pit_reports.PitReportError):
            groups = [
                check_pit_reports.parse_group_definition("one=a,b"),
                check_pit_reports.parse_group_definition("two=b,c"),
            ]
            check_pit_reports.validate_groups(groups)

    def test_two_original_quality_groups_are_evaluated_independently(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for shard in ("auth", "utils", "exporter"):
                self.write_report(root, shard, ["true"] * 2)
            for shard in ("history", "parser", "runner"):
                self.write_report(root, shard, ["true", "false"])
            code, output_json, _ = self.run_cli(
                root,
                "auth-utils-exporter=auth,utils,exporter",
                "history-parser-runner=history,parser,runner",
            )
            self.assertEqual(1, code)
            data = json.loads(output_json.read_text(encoding="utf-8"))
            self.assertEqual([True, False], [group["passed"] for group in data["groups"]])

    def test_passing_first_group_cannot_hide_failure_in_second_group(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_report(root, "auth", ["true"])
            self.write_report(root, "history", ["false"])
            code, _, _ = self.run_cli(root, "first=auth", "second=history")
            self.assertEqual(1, code)

    def test_json_and_text_summaries_are_written_for_valid_threshold_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_report(root, "auth", ["false"])
            code, output_json, output_text = self.run_cli(root, "gate=auth")
            self.assertEqual(1, code)
            self.assertTrue(output_json.exists())
            self.assertTrue(output_text.exists())
            self.assertFalse(json.loads(output_json.read_text(encoding="utf-8"))["passed"])
            self.assertIn("Overall verdict: FAIL", output_text.read_text(encoding="utf-8"))

    def test_cli_success_returns_0_and_produces_both_summary_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_report(root, "auth", ["TrUe", "FALSE", "true"])
            code, output_json, output_text = self.run_cli(root, "gate=auth")
            self.assertEqual(0, code)
            self.assertTrue(output_json.exists())
            self.assertTrue(output_text.exists())
            data = json.loads(output_json.read_text(encoding="utf-8"))
            self.assertEqual("gate", data["groups"][0]["name"])


if __name__ == "__main__":
    unittest.main()

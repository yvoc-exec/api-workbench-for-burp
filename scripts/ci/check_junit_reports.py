#!/usr/bin/env python3
import argparse
import glob
import sys
import xml.etree.ElementTree as ET


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate JUnit XML report counts.")
    parser.add_argument("--label", required=True, help="Human-readable suite label")
    parser.add_argument("--glob", dest="globs", action="append", required=True, help="Report glob to include")
    parser.add_argument("--min-tests", type=int, default=1, help="Minimum total tests required")
    parser.add_argument("--max-skipped", type=int, default=0, help="Maximum skipped tests allowed")
    return parser.parse_args()


def discover_reports(patterns: list[str]) -> list[str]:
    reports: list[str] = []
    for pattern in patterns:
        reports.extend(glob.glob(pattern, recursive=True))
    return sorted(set(reports))


def iter_suites(root: ET.Element):
    if root.tag == "testsuite":
        yield root
        return
    for suite in root.findall(".//testsuite"):
        yield suite


def suite_total(suite: ET.Element, name: str) -> int:
    return int(suite.attrib.get(name, "0"))


def main() -> int:
    args = parse_args()
    reports = discover_reports(args.globs)
    if not reports:
        print(f"{args.label}: no reports matched {args.globs}", file=sys.stderr)
        return 1

    tests = failures = errors = skipped = 0
    for report in reports:
        root = ET.parse(report).getroot()
        for suite in iter_suites(root):
            tests += suite_total(suite, "tests")
            failures += suite_total(suite, "failures")
            errors += suite_total(suite, "errors")
            skipped += suite_total(suite, "skipped")

    print(
        f"{args.label}: reports={len(reports)} tests={tests} failures={failures} errors={errors} skipped={skipped}"
    )

    if tests < args.min_tests:
        print(
            f"{args.label}: expected at least {args.min_tests} tests but found {tests}",
            file=sys.stderr,
        )
        return 1
    if failures != 0 or errors != 0:
        print(f"{args.label}: failures/errors must both be zero", file=sys.stderr)
        return 1
    if skipped > args.max_skipped:
        print(
            f"{args.label}: expected at most {args.max_skipped} skipped tests but found {skipped}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

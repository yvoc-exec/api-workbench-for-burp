"""Aggregate PIT mutation reports for CI quality gates."""

from __future__ import annotations

import argparse
from collections import OrderedDict
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


class PitReportError(ValueError):
    """Configuration or report error."""


@dataclass(frozen=True)
class ShardResult:
    name: str
    detected: int
    total: int

    @property
    def undetected(self) -> int:
        return self.total - self.detected


@dataclass(frozen=True)
class GroupResult:
    name: str
    shards: list[str]
    detected: int
    total: int
    undetected: int
    score: Decimal
    passed: bool


def parse_group_definition(value: str) -> tuple[str, list[str]]:
    if "=" not in value:
        raise PitReportError(f"Invalid group definition {value!r}: expected NAME=SHARD1,SHARD2")
    name, shard_text = value.split("=", 1)
    name = name.strip()
    if not name:
        raise PitReportError(f"Invalid group definition {value!r}: missing group name")
    if not shard_text.strip():
        raise PitReportError(f"Invalid group definition {value!r}: missing shard list")
    shards = [shard.strip() for shard in shard_text.split(",")]
    if any(not shard for shard in shards):
        raise PitReportError(f"Invalid group definition {value!r}: empty shard name")
    return name, shards


def validate_groups(groups: list[tuple[str, list[str]]]) -> None:
    if not groups:
        raise PitReportError("At least one --group is required")

    seen_groups: set[str] = set()
    shard_owner: dict[str, str] = {}
    for name, shards in groups:
        if name in seen_groups:
            raise PitReportError(f"Duplicate group name: {name}")
        seen_groups.add(name)

        seen_in_group: set[str] = set()
        for shard in shards:
            if shard in seen_in_group:
                raise PitReportError(f"Duplicate shard {shard} inside group {name}")
            seen_in_group.add(shard)
            owner = shard_owner.get(shard)
            if owner is not None:
                raise PitReportError(f"Shard {shard} assigned to both {owner} and {name}")
            shard_owner[shard] = name


def find_shard_report(reports_root: Path, shard: str) -> Path:
    expected_parent = f"pit-{shard}"
    matches = sorted(
        path for path in reports_root.rglob("mutations.xml") if path.parent.name == expected_parent
    )
    if not matches:
        raise PitReportError(f"Missing mutations.xml for shard {shard} under {reports_root}")
    if len(matches) > 1:
        paths = ", ".join(str(path) for path in matches)
        raise PitReportError(f"Duplicate mutations.xml reports for shard {shard}: {paths}")
    return matches[0]


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_mutation_report(path: Path, shard: str | None = None) -> ShardResult:
    shard_name = shard or path.parent.name.removeprefix("pit-")
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        raise PitReportError(f"Malformed XML for shard {shard_name} at {path}: {exc}") from exc

    detected = 0
    total = 0
    for element in root.iter():
        if _local_name(element.tag) != "mutation":
            continue
        total += 1
        raw = element.attrib.get("detected")
        if raw is None:
            raise PitReportError(f"Mutation without detected attribute for shard {shard_name} at {path}")
        value = raw.lower()
        if value == "true":
            detected += 1
        elif value != "false":
            raise PitReportError(
                f"Invalid detected value {raw!r} for shard {shard_name} at {path}"
            )

    if total == 0:
        raise PitReportError(f"Empty mutation report for shard {shard_name} at {path}")
    return ShardResult(shard_name, detected, total)


def aggregate_groups(
    groups: list[tuple[str, list[str]]], reports_root: Path, threshold: Decimal
) -> list[GroupResult]:
    results: list[GroupResult] = []
    for group_name, shards in groups:
        detected = 0
        total = 0
        for shard in shards:
            report = find_shard_report(reports_root, shard)
            shard_result = parse_mutation_report(report, shard)
            detected += shard_result.detected
            total += shard_result.total
        score = (Decimal(detected) / Decimal(total)) * Decimal("100")
        results.append(
            GroupResult(
                name=group_name,
                shards=list(shards),
                detected=detected,
                total=total,
                undetected=total - detected,
                score=score,
                passed=score >= threshold,
            )
        )
    return results


def render_text_summary(threshold: Decimal, groups: list[GroupResult]) -> str:
    passed = all(group.passed for group in groups)
    lines = [
        f"Threshold: {_format_decimal(threshold)}%",
        f"Overall verdict: {'PASS' if passed else 'FAIL'}",
    ]
    for group in groups:
        lines.extend(
            [
                "",
                f"Group: {group.name}",
                f"Shards: {', '.join(group.shards)}",
                f"Detected: {group.detected}",
                f"Total: {group.total}",
                f"Undetected: {group.undetected}",
                f"Score: {group.score.quantize(Decimal('0.01'))}%",
                f"Verdict: {'PASS' if group.passed else 'FAIL'}",
            ]
        )
    return "\n".join(lines) + "\n"


def write_json_summary(path: Path, threshold: Decimal, groups: list[GroupResult]) -> None:
    data = OrderedDict(
        [
            ("threshold", float(threshold)),
            ("passed", all(group.passed for group in groups)),
            (
                "groups",
                [
                    OrderedDict(
                        [
                            ("name", group.name),
                            ("shards", group.shards),
                            ("detected", group.detected),
                            ("total", group.total),
                            ("undetected", group.undetected),
                            ("score", float(group.score)),
                            ("passed", group.passed),
                        ]
                    )
                    for group in groups
                ],
            ),
        ]
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def _format_decimal(value: Decimal) -> str:
    return format(value.normalize(), "f")


def _parse_threshold(value: str) -> Decimal:
    try:
        return Decimal(value)
    except InvalidOperation as exc:
        raise argparse.ArgumentTypeError(f"invalid decimal percentage: {value!r}") from exc


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports-root", required=True, type=Path)
    parser.add_argument("--threshold", required=True, type=_parse_threshold)
    parser.add_argument("--group", required=True, action="append")
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument("--output-text", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    try:
        args = _parse_args(argv)
        groups = [parse_group_definition(value) for value in args.group]
        validate_groups(groups)
        group_results = aggregate_groups(groups, args.reports_root, args.threshold)
        text = render_text_summary(args.threshold, group_results)
        args.output_text.parent.mkdir(parents=True, exist_ok=True)
        args.output_text.write_text(text, encoding="utf-8")
        write_json_summary(args.output_json, args.threshold, group_results)
        print(text, end="")
        return 0 if all(group.passed for group in group_results) else 1
    except PitReportError as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Runs PaddleOCR over local reference crops and merges build-time fallback evidence."""

from __future__ import annotations

import csv
import math
import os
import re
import sys
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path

import cv2


@dataclass(frozen=True)
class Target:
    label_key: str
    line_text: str
    render_text: str
    crop_x: int
    crop_y: int
    crop_width: int
    crop_height: int

    @property
    def crop_key(self) -> tuple[int, int, int, int]:
        return self.crop_x, self.crop_y, self.crop_width, self.crop_height


@dataclass(frozen=True)
class Detection:
    text: str
    score: float
    box: tuple[int, int, int, int]

    @property
    def height(self) -> int:
        return self.box[3] - self.box[1]

    @property
    def baseline(self) -> int:
        return self.box[3]


@dataclass(frozen=True)
class Match:
    text: str
    ratio: float
    confidence: float
    detections: tuple[Detection, ...]


def normalize(value: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", value.upper())


def target_rows(path: Path) -> list[Target]:
    with path.open(newline="", encoding="utf-8") as source:
        rows = csv.DictReader(
            (line for line in source if not line.startswith("#")), delimiter="\t")
        return [Target(
            row["labelKey"], row["lineText"], row.get("renderText") or row["lineText"],
            int(row["cropX"]), int(row["cropY"]),
            int(row["cropWidth"]), int(row["cropHeight"])) for row in rows]


def heuristic_rows(path: Path) -> dict[tuple[str, str], dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        rows = csv.DictReader(source, delimiter="\t")
        return {(row["labelKey"], row["lineText"]): row for row in rows if row["rank"] == "1"}


def near_heuristic_geometry(
        target: Target, detections: list[Detection], heuristic: dict[str, str] | None
) -> list[Detection]:
    if heuristic is None or not heuristic["imagePath"]:
        return detections
    points = [tuple(float(value) for value in coordinate.split(","))
              for coordinate in heuristic["imagePath"].split(";")]
    cap_height = float(heuristic["capHeightPixels"])
    tracking = float(heuristic["trackingPixels"])
    vertical_tolerance = max(70.0, cap_height * 2.2)
    horizontal_margin = max(90.0, tracking * 1.5)
    selected = []
    for detection in detections:
        centre_x = target.crop_x + (detection.box[0] + detection.box[2]) * 0.5
        centre_y = target.crop_y + (detection.box[1] + detection.box[3]) * 0.5
        for first, second in zip(points, points[1:]):
            minimum_x = min(first[0], second[0]) - horizontal_margin
            maximum_x = max(first[0], second[0]) + horizontal_margin
            if not minimum_x <= centre_x <= maximum_x:
                continue
            if second[0] == first[0]:
                expected_y = (first[1] + second[1]) * 0.5
            else:
                fraction = max(0.0, min(1.0, (centre_x - first[0]) / (second[0] - first[0])))
                expected_y = first[1] + (second[1] - first[1]) * fraction
            if abs(centre_y - expected_y) <= vertical_tolerance:
                selected.append(detection)
                break
    return selected


def compatible(first: Detection, second: Detection) -> bool:
    if first.height <= 0 or second.height <= 0:
        return False
    height_ratio = second.height / first.height
    return 0.4 <= height_ratio <= 2.5 and abs(first.baseline - second.baseline) <= max(
        14, first.height * 0.8)


def best_match(target_text: str, detections: list[Detection]) -> Match:
    expected = normalize(target_text)
    best = Match("", 0.0, 0.0, ())
    seen_rows: set[tuple[tuple[int, int, int, int], ...]] = set()
    for seed in detections:
        row = sorted((item for item in detections if compatible(seed, item)), key=lambda item: item.box[0])
        row_key = tuple(item.box for item in row)
        if row_key in seen_rows:
            continue
        seen_rows.add(row_key)
        runs: list[list[Detection]] = []
        current: list[Detection] = []
        for item in row:
            if current and item.box[0] - current[-1].box[2] > max(80, seed.height * 20):
                runs.append(current)
                current = []
            current.append(item)
        if current:
            runs.append(current)
        for run in runs:
            maximum = min(len(run), len(expected) + 3)
            for count in range(1, maximum + 1):
                for start in range(0, len(run) - count + 1):
                    window = tuple(run[start:start + count])
                    candidate = " ".join(item.text for item in window)
                    candidate_normalized = normalize(candidate)
                    if not candidate_normalized:
                        continue
                    if len(candidate_normalized) > len(expected) * 3:
                        continue
                    ratio = SequenceMatcher(None, expected, candidate_normalized).ratio()
                    if expected in candidate_normalized or candidate_normalized in expected:
                        ratio = max(ratio, min(len(expected), len(candidate_normalized)) / len(expected))
                    confidence = sum(item.score for item in window) / len(window)
                    weighted = ratio * (0.75 + 0.25 * confidence)
                    best_weighted = best.ratio * (0.75 + 0.25 * best.confidence)
                    if weighted > best_weighted:
                        best = Match(candidate, ratio, confidence, window)
                        if ratio == 1.0 and confidence >= 0.95:
                            return best
    return best


def geometry_metrics(heuristic: dict[str, str] | None) -> tuple[str, str, str, str]:
    if heuristic is None or not heuristic["imagePath"]:
        return "", "", "", ""
    points = [tuple(float(value) for value in coordinate.split(","))
              for coordinate in heuristic["imagePath"].split(";")]
    first_angle = math.degrees(math.atan2(
        points[1][1] - points[0][1], points[1][0] - points[0][0]))
    last_angle = math.degrees(math.atan2(
        points[-1][1] - points[-2][1], points[-1][0] - points[-2][0]))
    return (heuristic["capHeightPixels"], heuristic["trackingPixels"],
            f"{first_angle:.3f}", f"{last_angle - first_angle:.3f}")


def ocr_geometry(target: Target, match: Match) -> tuple[str, str, str, str, str]:
    if not match.detections:
        return "", "", "", "", ""
    detections = sorted(match.detections, key=lambda item: item.box[0])
    cap_height = sorted(item.height for item in detections)[len(detections) // 2]
    centres = [((item.box[0] + item.box[2]) * 0.5, float(item.baseline))
               for item in detections]
    mean_x = sum(point[0] for point in centres) / len(centres)
    mean_y = sum(point[1] for point in centres) / len(centres)
    denominator = sum((point[0] - mean_x) ** 2 for point in centres)
    slope = (sum((point[0] - mean_x) * (point[1] - mean_y) for point in centres)
             / denominator if denominator else 0.0)
    minimum_x = float(min(item.box[0] for item in detections))
    maximum_x = float(max(item.box[2] for item in detections))
    first_y = mean_y + slope * (minimum_x - mean_x)
    last_y = mean_y + slope * (maximum_x - mean_x)
    path = (f"{target.crop_x + minimum_x:.1f},{target.crop_y + first_y:.1f};"
            f"{target.crop_x + maximum_x:.1f},{target.crop_y + last_y:.1f}")
    glyphs = max(1, len(normalize(target.line_text)))
    natural_width = cap_height * 0.58 * glyphs
    tracking = max(0.0, (maximum_x - minimum_x - natural_width) / max(1, glyphs - 1))
    angle = math.degrees(math.atan2(last_y - first_y, maximum_x - minimum_x))
    return f"{cap_height:.2f}", f"{tracking:.2f}", f"{angle:.3f}", "0.000", path


def read_cached_detections(path: Path) -> dict[tuple[int, int, int, int], list[Detection]]:
    if not path.exists():
        return {}
    detections: dict[tuple[int, int, int, int], list[Detection]] = {}
    with path.open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source, delimiter="\t"):
            crop_key = (int(row["cropX"]), int(row["cropY"]),
                        int(row["cropWidth"]), int(row["cropHeight"]))
            global_box = tuple(int(value) for value in row["imageBox"].split(","))
            local_box = (global_box[0] - crop_key[0], global_box[1] - crop_key[1],
                         global_box[2] - crop_key[0], global_box[3] - crop_key[1])
            detections.setdefault(crop_key, []).append(
                Detection(row["text"], float(row["score"]), local_box))
    return detections


def run(reference_path: Path, targets_path: Path, heuristic_path: Path, output_directory: Path) -> None:
    os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")
    targets = target_rows(targets_path)
    heuristics = heuristic_rows(heuristic_path)
    output_directory.mkdir(parents=True, exist_ok=True)
    raw_path = output_directory / "paddle-ocr-detections.tsv"
    required_crops = list(dict.fromkeys(target.crop_key for target in targets))
    detections_by_crop = read_cached_detections(raw_path)
    missing_crops = [crop for crop in required_crops if crop not in detections_by_crop]
    if missing_crops:
        from paddleocr import PaddleOCR

        image = cv2.imread(str(reference_path), cv2.IMREAD_COLOR)
        if image is None:
            raise RuntimeError(f"Unable to read reference image: {reference_path}")
        ocr = PaddleOCR(
            text_detection_model_name="PP-OCRv4_mobile_det",
            text_recognition_model_name="en_PP-OCRv4_mobile_rec",
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
            text_det_limit_side_len=1280,
            text_det_limit_type="max")
        for x, y, width, height in missing_crops:
            crop = image[y:y + height, x:x + width]
            result = list(ocr.predict(crop))[0].json["res"]
            detections_by_crop[(x, y, width, height)] = [
                Detection(text, float(score), tuple(int(value) for value in box))
                for text, score, box in zip(
                    result["rec_texts"], result["rec_scores"], result["rec_boxes"])]
    else:
        print(f"Reusing PaddleOCR detections for {len(required_crops)} crops")

    with raw_path.open("w", newline="", encoding="utf-8") as raw_file:
        raw_writer = csv.writer(raw_file, delimiter="\t")
        raw_writer.writerow(["cropX", "cropY", "cropWidth", "cropHeight", "text", "score", "imageBox"])
        for x, y, width, height in required_crops:
            for detection in detections_by_crop[(x, y, width, height)]:
                local_box = detection.box
                global_box = (
                    x + local_box[0], y + local_box[1], x + local_box[2], y + local_box[3])
                raw_writer.writerow([x, y, width, height, detection.text, f"{detection.score:.4f}",
                                     ",".join(str(value) for value in global_box)])

    report_path = output_directory / "ocr-extraction-report.tsv"
    counts = {status: 0 for status in (
        "OCR_EXACT", "OCR_PARTIAL", "HEURISTIC_FALLBACK", "REVIEW_REQUIRED", "MISS")}
    rows_by_label: dict[str, list[tuple[str, int]]] = {}
    with report_path.open("w", newline="", encoding="utf-8") as report_file:
        writer = csv.writer(report_file, delimiter="\t")
        writer.writerow([
            "labelKey", "lineText", "renderText", "status", "ocrText", "matchRatio", "ocrConfidence",
            "ocrDetectionCount", "heuristicScore", "evidenceGlyphs", "expectedGlyphs",
            "inferredGlyphs", "geometrySource", "capHeightPixels", "trackingPixels",
            "baselineAngleDegrees", "curvatureDegrees", "cropX", "cropY", "cropWidth",
            "cropHeight", "imagePath", "reviewImage"])
        for target in targets:
            detections = detections_by_crop[target.crop_key]
            heuristic = heuristics.get((target.label_key, target.line_text))
            matching_detections = near_heuristic_geometry(target, detections, heuristic)
            match = best_match(target.line_text, matching_detections)
            if match.ratio >= 0.95:
                status = "OCR_EXACT"
            elif match.ratio >= 0.60:
                status = "OCR_PARTIAL"
            elif heuristic is not None and int(heuristic["inferredGlyphs"]) <= 2:
                status = "HEURISTIC_FALLBACK"
            elif match.ratio > 0.0 or heuristic is not None:
                status = "REVIEW_REQUIRED"
            else:
                status = "MISS"
            counts[status] += 1
            inferred_glyphs = int(heuristic["inferredGlyphs"]) if heuristic else 0
            rows_by_label.setdefault(target.label_key, []).append((status, inferred_glyphs))
            if heuristic is not None:
                geometry_source = "COMPONENT_BASELINE"
                cap_height, tracking, angle, curvature = geometry_metrics(heuristic)
                image_path = heuristic["imagePath"]
            elif status in ("OCR_EXACT", "OCR_PARTIAL"):
                geometry_source = "OCR_BOXES"
                cap_height, tracking, angle, curvature, image_path = ocr_geometry(target, match)
            else:
                geometry_source = "NONE"
                cap_height = tracking = angle = curvature = image_path = ""
            writer.writerow([
                target.label_key, target.line_text, target.render_text, status, match.text,
                f"{match.ratio:.4f}", f"{match.confidence:.4f}", len(match.detections),
                heuristic["score"] if heuristic else "",
                heuristic["evidenceGlyphs"] if heuristic else "",
                heuristic["expectedGlyphs"] if heuristic else "",
                heuristic["inferredGlyphs"] if heuristic else "",
                geometry_source, cap_height, tracking, angle, curvature,
                target.crop_x, target.crop_y, target.crop_width, target.crop_height,
                image_path,
                heuristic["reviewImage"] if heuristic else ""])

    label_counts = {status: 0 for status in counts}
    label_summary_path = output_directory / "ocr-label-summary.tsv"
    with label_summary_path.open("w", newline="", encoding="utf-8") as label_summary_file:
        writer = csv.writer(label_summary_file, delimiter="\t")
        writer.writerow([
            "labelKey", "lineCount", "status", "ocrSupportedLines", "fallbackLines",
            "reviewLines", "missLines", "maximumInferredGlyphs"])
        for label_key, rows in rows_by_label.items():
            statuses = [row[0] for row in rows]
            if "MISS" in statuses:
                status = "MISS"
            elif "REVIEW_REQUIRED" in statuses:
                status = "REVIEW_REQUIRED"
            elif "HEURISTIC_FALLBACK" in statuses:
                status = "HEURISTIC_FALLBACK"
            elif "OCR_PARTIAL" in statuses:
                status = "OCR_PARTIAL"
            else:
                status = "OCR_EXACT"
            label_counts[status] += 1
            writer.writerow([
                label_key, len(rows), status,
                sum(value in ("OCR_EXACT", "OCR_PARTIAL") for value in statuses),
                statuses.count("HEURISTIC_FALLBACK"), statuses.count("REVIEW_REQUIRED"),
                statuses.count("MISS"), max(row[1] for row in rows)])

    summary_path = output_directory / "ocr-extraction-summary.txt"
    with summary_path.open("w", encoding="utf-8") as summary:
        total = len(rows_by_label)
        located = total - label_counts["MISS"] - label_counts["REVIEW_REQUIRED"]
        summary.write(f"labels={total}\ntargetLines={len(targets)}\nlocatedLabels={located}\n"
                      f"locationRate={located / total:.3f}\n")
        for status, count in label_counts.items():
            summary.write(f"labels.{status}={count}\n")
        for status, count in counts.items():
            summary.write(f"lines.{status}={count}\n")
    print(summary_path.read_text(encoding="utf-8"), end="")
    print(f"report={report_path}")


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        raise SystemExit(
            "Expected <reference-image> <targets.tsv> <heuristic-candidates.tsv> <output-directory>")
    run(Path(argv[1]), Path(argv[2]), Path(argv[3]), Path(argv[4]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

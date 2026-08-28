#!/usr/bin/env python3
"""Build LabelWise category score histograms from the India packaged-food CSV."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path

SOURCE_DOI = "10.17632/rhswn9z9xr.1"
SOURCE_DOWNLOAD_URL = (
    "https://data.mendeley.com/public-files/datasets/rhswn9z9xr/files/"
    "e0b16335-a9ac-4fee-8b68-70fe7b421952/file_downloaded"
)
SOURCE_SHA256 = "f64832743d4918c428aab31fb8a3681cd2dee89f3c5f305185ec0edfabe8f303"

CATEGORY_MAP = {
    "BISCUIT": "BISCUITS_AND_BAKERY",
    "CAKE": "BISCUITS_AND_BAKERY",
    "WAFFLE": "BISCUITS_AND_BAKERY",
    "WAFER": "BISCUITS_AND_BAKERY",
    "JUICE": "BEVERAGES_AND_JUICES",
    "SNACKS": "SAVOURY_SNACKS",
    "CHOCOLATE": "CHOCOLATE_AND_SWEETS",
    "SWEETS": "CHOCOLATE_AND_SWEETS",
    "INSTANT FOOD": "INSTANT_AND_READY_FOODS",
    "SAUCE": "SAUCES_AND_SPREADS",
    "SPREAD": "SAUCES_AND_SPREADS",
    "DAIRY": "DAIRY_AND_YOGURT",
    "ICEREAMES": "ICE_CREAM_AND_DESSERTS",
}

CATEGORY_LABELS = {
    "BISCUITS_AND_BAKERY": "Biscuits & bakery",
    "BEVERAGES_AND_JUICES": "Juices & beverages",
    "SAVOURY_SNACKS": "Savoury snacks",
    "CHOCOLATE_AND_SWEETS": "Chocolate & sweets",
    "INSTANT_AND_READY_FOODS": "Instant & ready foods",
    "SAUCES_AND_SPREADS": "Sauces & spreads",
    "DAIRY_AND_YOGURT": "Dairy & yoghurt",
    "ICE_CREAM_AND_DESSERTS": "Ice cream & desserts",
}

NUTRIENT_COLUMNS = [
    "Calories_kcal",
    "Proteins_g",
    "Carbohydrates_g",
    "Sugar_g",
    "Dietary_Fiber_g",
    "Total_Fat_g",
    "Saturated_Fat_g",
    "Trans_Fat_g",
    "Sodium_mg",
]

SUGAR_TERMS = [
    "sugar", "glucose", "fructose", "corn syrup", "invert syrup", "maltose",
    "dextrose", "jaggery", "honey",
]
WHOLE_FOOD_TERMS = [
    "whole grain", "whole wheat", "oats", "millet", "ragi", "jowar", "bajra",
    "lentil", "chickpea", "nuts", "seeds", "fruit", "vegetable",
]
REFINED_TERMS = ["refined wheat flour", "maida", "maltodextrin", "hydrogenated", "palm oil"]
ADDITIVE_RE = re.compile(r"\b(?:INS|E)[ -]?\d{3,4}[a-z]?\b", re.IGNORECASE)


def parse_number(raw: str) -> float | None:
    value = raw.strip().replace(",", ".")
    if value.startswith("<"):
        value = value[1:]
    try:
        parsed = float(value)
    except ValueError:
        return None
    return parsed if math.isfinite(parsed) else None


def normalized_values(row: dict[str, str]) -> dict[str, float | None] | None:
    serving = parse_number(row["Serving_Size_g"])
    if serving is None or serving <= 0:
        return None
    values = {column: parse_number(row[column]) for column in NUTRIENT_COLUMNS}
    normalized = {
        column: value * 100.0 / serving if value is not None else None
        for column, value in values.items()
    }
    # Exclude physically implausible rows, usually caused by inconsistent serving bases
    # or source typos. Very salty condiments are retained because they can exceed 5 g sodium.
    if normalized["Calories_kcal"] is not None and normalized["Calories_kcal"] > 900:
        return None
    bounded_grams = [
        "Proteins_g", "Carbohydrates_g", "Sugar_g", "Dietary_Fiber_g",
        "Total_Fat_g", "Saturated_Fat_g",
    ]
    if any(normalized[column] is not None and normalized[column] > 100 for column in bounded_grams):
        return None
    if normalized["Trans_Fat_g"] is not None and normalized["Trans_Fat_g"] > 10:
        return None
    return normalized


def score_row(row: dict[str, str], category: str) -> float | None:
    nutrition = normalized_values(row)
    if nutrition is None:
        return None
    score = 4.5

    sugar = nutrition["Sugar_g"]
    if sugar is not None:
        if category == "BEVERAGES_AND_JUICES":
            penalty = -1.5 if sugar > 11.25 else -0.9 if sugar > 5 else -0.4 if sugar > 2.5 else 0.0
        else:
            penalty = -1.2 if sugar > 22.5 else -0.75 if sugar > 10 else -0.3 if sugar > 5 else 0.0
        score += penalty if penalty < 0 else 0.2

    sodium = nutrition["Sodium_mg"]
    if sodium is not None:
        penalty = -1.0 if sodium > 600 else -0.65 if sodium > 400 else -0.3 if sodium > 120 else 0.0
        score += penalty if penalty < 0 else 0.2

    saturated_fat = nutrition["Saturated_Fat_g"]
    if saturated_fat is not None:
        score += -1.0 if saturated_fat > 10 else -0.65 if saturated_fat > 5 else -0.3 if saturated_fat > 1.5 else 0.0

    trans_fat = nutrition["Trans_Fat_g"]
    if trans_fat is not None:
        score += -0.8 if trans_fat > 1 else -0.45 if trans_fat > 0.2 else 0.0

    fibre = nutrition["Dietary_Fiber_g"]
    if fibre is not None:
        score += 0.35 if fibre >= 6 else 0.2 if fibre >= 3 else 0.0

    protein = nutrition["Proteins_g"]
    if protein is not None:
        score += 0.25 if protein >= 10 else 0.1 if protein >= 5 else 0.0

    ingredients = row["Ingredients"].lower().strip()
    if ingredients:
        first_ingredients = " ".join(re.split("[,;]", ingredients)[:3])
        if any(term in first_ingredients for term in SUGAR_TERMS):
            score -= 0.55
        elif any(term in first_ingredients for term in WHOLE_FOOD_TERMS):
            score += 0.25
        refined_hits = sum(term in ingredients for term in REFINED_TERMS)
        if refined_hits:
            score += -0.4 if refined_hits >= 2 else -0.2
        if len(ADDITIVE_RE.findall(ingredients)) >= 3:
            score -= 0.2

    known_count = sum(value is not None for value in nutrition.values())
    confidence = 0.18 + known_count * 0.065
    if ingredients:
        confidence += 0.16
    confidence += 0.1  # Dataset values have a known serving basis.
    confidence += 0.04  # Serving size is present.
    confidence = max(0.25, min(0.96, confidence))
    conservative = max(0.5, min(5.0, score)) * confidence + 3.0 * (1.0 - confidence)
    return round(max(0.5, min(5.0, conservative)) + 1e-9, 1)


def build(rows: list[dict[str, str]]) -> tuple[dict, list[dict[str, str]]]:
    score_histograms: dict[str, Counter[float]] = defaultdict(Counter)
    included_by_category: dict[str, list[dict[str, str]]] = defaultdict(list)
    excluded = []
    for row in rows:
        source_category = row["Category"].strip().upper()
        category = CATEGORY_MAP.get(source_category)
        if category is None:
            excluded.append({"row": row["S.No"], "reason": "unmapped category"})
            continue
        score = score_row(row, category)
        if score is None:
            excluded.append({"row": row["S.No"], "reason": "invalid or implausible nutrition"})
            continue
        score_histograms[category][score] += 1
        included_by_category[category].append(row)

    categories = {}
    for category in CATEGORY_LABELS:
        histogram = score_histograms[category]
        categories[category] = {
            "label": CATEGORY_LABELS[category],
            "source_categories": sorted(key for key, value in CATEGORY_MAP.items() if value == category),
            "peer_count": sum(histogram.values()),
            "score_histogram": {f"{score:.1f}": histogram[score] for score in sorted(histogram)},
        }
    output = {
        "schema_version": 1,
        "generated_with": "scripts/build_category_benchmarks.py",
        "source": {
            "title": "Indian Packaged Foods Nutritional Composition Dataset (2026)",
            "doi": SOURCE_DOI,
            "download_url": SOURCE_DOWNLOAD_URL,
            "sha256": SOURCE_SHA256,
            "license": "CC BY 4.0",
            "input_rows": len(rows),
        },
        "methodology": {
            "included_rows": sum(item["peer_count"] for item in categories.values()),
            "excluded_rows": len(excluded),
            "normalization": "Per-serving nutrition normalized to 100 g using Serving_Size_g",
            "ranking": "Midrank percentile of the LabelWise score within the selected category",
            "excluded": excluded,
        },
        "categories": categories,
    }
    return output, included_by_category


def write_sample(path: Path, included: dict[str, list[dict[str, str]]]) -> None:
    fields = [
        "Item name", "Brand_Name", "LabelWise_Category", "Source_Category", "Sub_Category",
        "Serving_Size_g", "Calories_kcal", "Proteins_g", "Total_Fat_g", "Saturated_Fat_g",
        "Trans_Fat_g", "Sugar_g", "Sodium_mg", "Dietary_Fiber_g",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for category in CATEGORY_LABELS:
            for row in included[category][:3]:
                writer.writerow({
                    "Item name": row["Item name"],
                    "Brand_Name": row["Brand_Name"],
                    "LabelWise_Category": category,
                    "Source_Category": row["Category"],
                    "Sub_Category": row["Sub_Category"],
                    **{field: row[field] for field in fields if field in row},
                })


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_csv", type=Path)
    parser.add_argument("output_json", type=Path)
    parser.add_argument("--sample-output", type=Path)
    parser.add_argument("--allow-different-source", action="store_true")
    args = parser.parse_args()

    digest = hashlib.sha256(args.input_csv.read_bytes()).hexdigest()
    if digest != SOURCE_SHA256 and not args.allow_different_source:
        raise SystemExit(f"Source SHA-256 mismatch: expected {SOURCE_SHA256}, got {digest}")
    with args.input_csv.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    output, included = build(rows)
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    if args.sample_output:
        args.sample_output.parent.mkdir(parents=True, exist_ok=True)
        write_sample(args.sample_output, included)


if __name__ == "__main__":
    main()

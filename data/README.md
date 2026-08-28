# Category benchmark data

The in-app peer percentiles are generated from the **Indian Packaged Foods Nutritional Composition Dataset (2026)** by Duddela Sai Prashanth, Ananya Devadiga, Saathwik Saathwik, Priya R Kamath, and Rakshith Bhandary:

- 852 packaged foods marketed in India
- Product category, subcategory, ingredients, serving size, and nutrition fields
- DOI: [10.17632/rhswn9z9xr.1](https://doi.org/10.17632/rhswn9z9xr.1)
- License: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- Source file SHA-256: `f64832743d4918c428aab31fb8a3681cd2dee89f3c5f305185ec0edfabe8f303`

`category_benchmarks.json` contains the reproducible aggregate used by the app. Twelve rows with implausible normalized values were excluded, leaving 840 peer rows across eight app categories. `india_dataset_sample.csv` contains three source examples per app category so the mapping and fields are easy to inspect; it remains CC BY 4.0.

Rebuild the aggregates and sample:

```bash
curl -L 'https://data.mendeley.com/public-files/datasets/rhswn9z9xr/files/e0b16335-a9ac-4fee-8b68-70fe7b421952/file_downloaded' -o packaged_foods_india.csv
python scripts/build_category_benchmarks.py packaged_foods_india.csv data/category_benchmarks.json --sample-output data/india_dataset_sample.csv
```

The ranking uses a midrank percentile: products below the score plus half of tied products, divided by the number of valid products in that category. It is an empirical comparison, not a probability or medical conclusion. The dairy category has only 11 valid rows, so the APK marks that result as a small sample.

## Larger follow-up source

[Open Food Facts' product database](https://huggingface.co/datasets/openfoodfacts/product-database) is the scale-up source: its current Parquet snapshot has about 4.77 million rows and category, ingredient, and per-100g nutrient fields. It is licensed under ODbL. The India dataset is used for this APK because it is locally relevant, compact, versioned, and auditable; the benchmark builder is intentionally separate so a validated Open Food Facts snapshot can replace or supplement it later.

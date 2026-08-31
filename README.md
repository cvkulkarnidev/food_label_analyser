# LabelWise — Food Label Analyzer

LabelWise is a native Android app that reads a packaged-food label and returns an explainable health score out of 5 plus a category-specific peer percentile. Processing stays on the device.

## What the MVP does

- **Scan two photos** using ML Kit's on-device document scanner: nutrition panel and ingredients panel
- **Upload two images** from the device for those same panels
- Requires the user to select one of eight product categories before scanning
- On-device OCR with Google ML Kit's bundled Latin text recognizer
- Preserves word coordinates and ML recognition confidence instead of flattening OCR immediately
- Reconstructs nutrition rows geometrically when labels and values are returned separately
- Measures brightness, contrast, and focus separately for each image
- Capture mode supports edge detection, perspective correction, cropping, filters, and shadow cleanup
- For dim, low-contrast, or soft images, retries OCR on an enhanced copy and chooses using recognition confidence, nutrition keywords, and valid units
- Re-reads up to six uncertain nutrition rows as enlarged crops
- Applies context-limited unit repair (`9` → `g`, `m9` → `mg`) only in nutrition-unit positions; it never globally replaces the digit 9
- Rejects impossible per-100 g/ml values and flags inconsistent relationships such as added sugar above total sugar
- Extracts product name, ingredients, allergens, serving size, label basis, and common nutrition fields
- Normalizes per-serving values to 100 g/ml when serving size is available
- Gives an explainable 0.5–5.0 score with positive and negative factors
- Shows where the score sits among similar India-market products
- Shows image quality, ML text confidence, automatic corrections, validation warnings, and raw OCR text
- Shows a prominent per-image warning when the photo is dark, blurry, low contrast, or produces weak OCR evidence
- Does not require internet or upload label photos

## Scoring approach

The scorer starts from a strong-but-not-perfect baseline and applies transparent adjustments for:

- added/total sugar (with stricter beverage thresholds)
- sodium
- saturated and trans fat
- fibre and protein
- order and quality of ingredients
- selected processing signals and labelled additives

Incomplete OCR evidence pulls the result toward a neutral score of 3 rather than producing an unjustified high score. Image quality, ML recognition confidence, and nutrition validation all reduce scoring confidence, so a blurry or internally inconsistent extraction cannot receive the same certainty as clean evidence. This is general product guidance, not medical advice or a substitute for individual dietary requirements.

Image enhancement is deliberately conservative. It can expose existing edges and improve readable low-light text, but it does not claim to reconstruct information lost to severe motion blur, glare, or darkness. The app explicitly recommends recapture when the quality estimate remains poor. This follows [ML Kit's image guidance](https://developers.google.com/ml-kit/vision/text-recognition/v2/android): sufficient pixels per character and good focus are still required for reliable text recognition.

The absolute score and category percentile deliberately answer different questions:

- **Health score / 5:** applies consistent nutrition and ingredient rules; beverage sugar thresholds are stricter.
- **Category percentile:** compares that score only with valid products in the category selected by the user. It uses midrank handling for ties.

A product can rank well within chocolate or ice cream while still receiving a low absolute health score. The result screen keeps both values visible to avoid presenting a category winner as universally healthy.

## Benchmark dataset

The APK ships compact score histograms generated from the [Indian Packaged Foods Nutritional Composition Dataset (2026)](https://doi.org/10.17632/rhswn9z9xr.1), licensed CC BY 4.0. It contains 852 packaged foods marketed in India; 840 rows remain after twelve implausible normalized rows are excluded. Category peer counts range from 11 to 222, and the app flags categories with fewer than 30 peers.

The full transformation, source checksum, category mapping, aggregate JSON, and a 24-row inspection sample are in [`data/`](data/README.md). Rebuild with:

```bash
python scripts/build_category_benchmarks.py packaged_foods_india.csv data/category_benchmarks.json --sample-output data/india_dataset_sample.csv
```

For scale-up, the documented target is the [Open Food Facts product database](https://huggingface.co/datasets/openfoodfacts/product-database), currently about 4.77 million products. It provides category, ingredient, and per-100g nutrient fields under ODbL; it should be incorporated as a separately versioned and validated benchmark snapshot.

The low/high bands follow public label-reading guidance for sugar, saturated fat, sodium/salt, and fibre. India-specific extraction follows the nutrition fields and 100 g/ml or serving bases required by the FSSAI Labelling and Display Regulations.

References:

- [FSSAI Labelling and Display Regulations](https://www.fssai.gov.in/)
- [NHS guidance for interpreting food labels](https://www.nhs.uk/live-well/eat-well/food-guidelines-and-food-labels/how-to-read-food-labels/)
- [WHO healthy-diet guidance](https://www.who.int/news-room/fact-sheets/detail/healthy-diet)

## Build

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Every push to `main` also runs Android CI and publishes a downloadable `labelwise-debug-apk` workflow artifact.

## Current limitations

- OCR model is optimized for Latin-script labels.
- Contextual correction can safely repair a separated `6 9` as `6 g`, but a merged `69` cannot always be resolved automatically. Unusually high values are explicitly flagged for confirmation.
- Both the nutrition and ingredient photos are required. Each panel should fill most of its image.
- Highly distorted tables, glare, and multiple nutrition columns can reduce extraction quality.
- The category is user-selected rather than inferred, so choosing the wrong category produces the wrong peer group.
- The dairy benchmark currently has only 11 valid products and is marked as directional in the app.
- The score is an explainable MVP heuristic and requires clinical/public-health validation before health-critical use.

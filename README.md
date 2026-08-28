# LabelWise — Food Label Analyzer

LabelWise is a native Android app that reads a packaged-food label and returns an explainable health score out of 5. Processing stays on the device.

## What the MVP does

- **Capture label** using the phone's camera
- **Upload image** from the device
- On-device OCR with Google ML Kit's bundled Latin text recognizer
- Extracts product name, ingredients, allergens, serving size, label basis, and common nutrition fields
- Normalizes per-serving values to 100 g/ml when serving size is available
- Gives an explainable 0.5–5.0 score with positive and negative factors
- Shows confidence and raw OCR text so extraction errors are visible
- Does not require internet or upload label photos

## Scoring approach

The scorer starts from a strong-but-not-perfect baseline and applies transparent adjustments for:

- added/total sugar (with stricter beverage thresholds)
- sodium
- saturated and trans fat
- fibre and protein
- order and quality of ingredients
- selected processing signals and labelled additives

Incomplete OCR evidence pulls the result toward a neutral score of 3 rather than producing an unjustified high score. This is general product guidance, not medical advice or a substitute for individual dietary requirements.

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
- One image should contain both the nutrition panel and ingredient list for the best score.
- Highly distorted tables, glare, and multiple nutrition columns can reduce extraction quality.
- The category classifier and score are a general MVP; they should be validated against a labelled product set before health-critical use.

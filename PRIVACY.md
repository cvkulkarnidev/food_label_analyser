# LabelWise Privacy Policy

Effective date: 6 September 2026

LabelWise analyses packaged-food labels on Android. The current app is designed to work without a LabelWise account or a LabelWise server.

## Data handling

- Label images, OCR text, nutrition values, ingredient alerts, and scores are processed on the device.
- LabelWise does not request the Android internet permission and does not upload product photos or extracted label text.
- LabelWise does not include advertising, analytics, tracking, or account SDKs.
- Smart Scan inspects camera frames in memory for up to three seconds. It does not record audio or create a video.
- Selected Smart Scan and fallback-camera photos are placed in the app's private cache and cleared when the analysis session ends or the app is closed.
- Images selected from the gallery remain under the user's control. LabelWise receives access only to the images the user selects.
- Ingredient-alert preferences and custom alert terms are stored locally in the app's private preferences so they remain available between sessions.
- When the user chooses **Save to history**, LabelWise stores optimized copies of the two selected panel images, the user-provided history name, and the complete analysis in the app's private on-device storage. Saved entries remain until the user deletes them or uninstalls the app.
- When the user chooses **Export PDF to phone**, Android asks the user to select a destination and LabelWise writes a PDF containing the two panel images and analysis details. Exported PDFs remain under the user's control.

LabelWise does not sell, share, or collect personal data in the current release.

## Deleting data

Users can remove custom alert terms or disable preset alerts in the app. Individual saved analyses can be deleted from the History screen. Clearing app storage or uninstalling LabelWise removes its private preferences, cache, and saved history. Gallery originals and exported PDFs are not modified or deleted.

## Health and safety limitations

LabelWise provides general educational guidance from the visible product label. It is not medical advice, diagnosis, allergy certification, or a substitute for advice from a qualified health professional. OCR can be wrong, especially with blur, glare, low light, unusual layouts, or damaged packaging. Users should review extracted values and always verify declared allergens directly on the package.

The health score is an explainable heuristic, not a clinical rating. A score may be withheld when the app cannot extract enough comparable data. Ingredient alerts reflect user preferences and do not mean that an ingredient is unsafe for every person.

## Third-party components

Capture mode can use the Google Play services document scanner. OCR uses Google ML Kit and an embedded PaddleOCR/ONNX Runtime pipeline. LabelWise invokes these components for on-device image processing. Opening the privacy-policy link uses the user's chosen web browser.

## Changes and contact

Material changes to this policy will be published in this repository with a new effective date. Questions and privacy requests can be filed through the repository's [GitHub issue tracker](https://github.com/cvkulkarnidev/food_label_analyser/issues).

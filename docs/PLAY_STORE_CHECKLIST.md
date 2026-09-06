# Google Play release checklist

Version 1.0.0 is a production candidate. Complete every external item below before public rollout.

## 1. Create and protect the upload key

Create a dedicated upload keystore outside the repository. Never commit the keystore or passwords. A signed release can be built by setting:

- `LABELWISE_KEYSTORE_FILE`
- `LABELWISE_KEYSTORE_PASSWORD`
- `LABELWISE_KEY_ALIAS`
- `LABELWISE_KEY_PASSWORD`

Then run:

```bash
bash scripts/download_paddle_models.sh
./gradlew clean testDebugUnitTest lintRelease bundleRelease
```

Verify the signer and archive before upload. Enrol the app in Play App Signing and keep encrypted, access-controlled backups of the upload key.

## 2. Play Console declarations

- Host `PRIVACY.md` at a stable public HTTPS URL and place that URL in Play Console. The same policy is linked from the app.
- Complete Data safety from the exact signed build. The current source has no internet permission, analytics, advertising, account system, or LabelWise backend and declares no collection or sharing. Reassess this whenever an SDK or network feature changes.
- Complete the Health apps declaration. LabelWise provides nutrition-related product guidance and should be declared under the applicable nutrition/weight-management functionality rather than represented as a medical device.
- Complete content rating, target-audience, ads, and app-access declarations truthfully.
- Prepare store listing copy and screenshots that show “Provisional” and “Score withheld” states. Do not promise diagnostic, clinical, or allergy-safety outcomes.
- Keep the in-app health disclaimer and ingredient-alert limitations visible.

## 3. Release validation

- Install the signed build from a Play internal-testing track, not only with ADB.
- Test Android 8 through the current target version on small and large screens, light/dark themes, 200% font size, rotation, low memory, airplane mode, and a device without the document-scanner module preinstalled.
- Test camera cancellation, gallery cancellation, corrupted images, low storage, severe blur, glare, low light, long ingredient lists, decimal commas, per-serving labels, per-pack labels, and unknown bases.
- Confirm that leaving analysis cancels OCR and clears only LabelWise temporary camera files.
- Test named history save, image persistence after process restart, reopen, reviewed-value persistence, confirmed deletion, corrupted history metadata, and low-storage failure messaging.
- Confirm that a poor or incomplete scan never shows a category percentile or numeric health score until the user supplies comparable reviewed data.
- Review lint, unit-test, Play pre-launch, Android vitals, and automated device-test reports.
- Use staged rollout with crash/ANR monitoring and a documented rollback owner.

## 4. Model and nutrition governance

- Version every PaddleOCR model and verify its checksum in CI.
- Keep parser regression fixtures for every confirmed OCR failure.
- Freeze and document the benchmark-data snapshot used for each app version.
- Have nutrition/public-health experts review scoring thresholds and consumer-facing wording before making stronger health claims.
- Add a user-visible scoring-method/version identifier before changing score weights, so scores remain reproducible.

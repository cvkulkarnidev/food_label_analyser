package com.cvkulkarnidev.foodlabel.history

import android.net.Uri
import com.cvkulkarnidev.foodlabel.model.AnalysisReadiness
import com.cvkulkarnidev.foodlabel.model.ImageOcrAssessment
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.LabelReport
import com.cvkulkarnidev.foodlabel.model.NutritionBasis
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.OcrAssessment
import com.cvkulkarnidev.foodlabel.model.OcrQuality
import com.cvkulkarnidev.foodlabel.model.PeerComparison
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import com.cvkulkarnidev.foodlabel.model.ScoreFactor
import org.json.JSONArray
import org.json.JSONObject

data class SavedAnalysis(
    val id: String,
    val name: String,
    val savedAtEpochMs: Long,
    val nutritionImage: Uri,
    val ingredientsImage: Uri,
    val report: LabelReport,
)

data class SavedAnalysisSummary(
    val id: String,
    val name: String,
    val savedAtEpochMs: Long,
    val nutritionImage: Uri,
    val productName: String,
    val category: ProductCategory,
    val score: Double,
    val readiness: AnalysisReadiness,
)

internal data class SavedAnalysisRecord(
    val id: String,
    val name: String,
    val savedAtEpochMs: Long,
    val nutritionImageFileName: String,
    val ingredientsImageFileName: String,
    val report: LabelReport,
)

internal fun normalizeSavedAnalysisName(candidate: String, fallback: String): String {
    fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(80)
    return normalize(candidate).ifBlank { normalize(fallback) }.ifBlank { "Saved analysis" }
}

internal object SavedAnalysisCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(record: SavedAnalysisRecord): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("id", record.id)
        .put("name", record.name)
        .put("savedAtEpochMs", record.savedAtEpochMs)
        .put("nutritionImageFileName", record.nutritionImageFileName)
        .put("ingredientsImageFileName", record.ingredientsImageFileName)
        .put("report", encodeReport(record.report))
        .toString()

    fun decode(json: String): SavedAnalysisRecord {
        val root = JSONObject(json)
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "Unsupported history format." }
        return SavedAnalysisRecord(
            id = root.getString("id"),
            name = root.getString("name"),
            savedAtEpochMs = root.getLong("savedAtEpochMs"),
            nutritionImageFileName = root.getString("nutritionImageFileName"),
            ingredientsImageFileName = root.getString("ingredientsImageFileName"),
            report = decodeReport(root.getJSONObject("report")),
        )
    }

    private fun encodeReport(report: LabelReport): JSONObject = JSONObject()
        .put("productName", report.productName)
        .put("category", report.category.name)
        .putNullable("ingredients", report.ingredients)
        .put("allergens", report.allergens.toJsonArray())
        .put("nutrition", encodeNutrition(report.nutrition))
        .put("nutritionBasis", report.nutritionBasis.name)
        .putNullable("servingSize", report.servingSize)
        .put("score", report.score)
        .put("verdict", report.verdict)
        .put("confidence", report.confidence)
        .put(
            "factors",
            JSONArray().apply {
                report.factors.forEach { factor ->
                    put(
                        JSONObject()
                            .put("title", factor.title)
                            .put("detail", factor.detail)
                            .put("impact", factor.impact),
                    )
                }
            },
        )
        .put(
            "peerComparison",
            JSONObject()
                .put("percentile", report.peerComparison.percentile)
                .put("peerCount", report.peerComparison.peerCount)
                .put("position", report.peerComparison.position)
                .put("isSmallSample", report.peerComparison.isSmallSample),
        )
        .putNullable("ocrAssessment", report.ocrAssessment?.let(::encodeOcrAssessment))
        .put("extractionWarnings", report.extractionWarnings.toJsonArray())
        .put("readiness", report.readiness.name)
        .put("readinessMessage", report.readinessMessage)
        .put("wasUserReviewed", report.wasUserReviewed)
        .put("rawText", report.rawText)

    private fun decodeReport(json: JSONObject): LabelReport {
        val peer = json.getJSONObject("peerComparison")
        return LabelReport(
            productName = json.getString("productName"),
            category = json.enumValue("category", ProductCategory.BISCUITS_AND_BAKERY),
            ingredients = json.nullableString("ingredients"),
            allergens = json.stringList("allergens"),
            nutrition = decodeNutrition(json.getJSONObject("nutrition")),
            nutritionBasis = json.enumValue("nutritionBasis", NutritionBasis.UNKNOWN),
            servingSize = json.nullableString("servingSize"),
            score = json.getDouble("score"),
            verdict = json.getString("verdict"),
            confidence = json.getDouble("confidence"),
            factors = json.getJSONArray("factors").mapObjects { factor ->
                ScoreFactor(
                    title = factor.getString("title"),
                    detail = factor.getString("detail"),
                    impact = factor.getDouble("impact"),
                )
            },
            peerComparison = PeerComparison(
                percentile = peer.getInt("percentile"),
                peerCount = peer.getInt("peerCount"),
                position = peer.getString("position"),
                isSmallSample = peer.getBoolean("isSmallSample"),
            ),
            ocrAssessment = json.nullableObject("ocrAssessment")?.let(::decodeOcrAssessment),
            extractionWarnings = json.stringList("extractionWarnings"),
            readiness = json.enumValue("readiness", AnalysisReadiness.REVIEW),
            readinessMessage = json.getString("readinessMessage"),
            wasUserReviewed = json.getBoolean("wasUserReviewed"),
            rawText = json.getString("rawText"),
        )
    }

    private fun encodeNutrition(facts: NutritionFacts): JSONObject = JSONObject()
        .putNullable("energyKcal", facts.energyKcal)
        .putNullable("proteinG", facts.proteinG)
        .putNullable("carbohydrateG", facts.carbohydrateG)
        .putNullable("totalSugarG", facts.totalSugarG)
        .putNullable("addedSugarG", facts.addedSugarG)
        .putNullable("fibreG", facts.fibreG)
        .putNullable("totalFatG", facts.totalFatG)
        .putNullable("saturatedFatG", facts.saturatedFatG)
        .putNullable("transFatG", facts.transFatG)
        .putNullable("sodiumMg", facts.sodiumMg)

    private fun decodeNutrition(json: JSONObject): NutritionFacts = NutritionFacts(
        energyKcal = json.nullableDouble("energyKcal"),
        proteinG = json.nullableDouble("proteinG"),
        carbohydrateG = json.nullableDouble("carbohydrateG"),
        totalSugarG = json.nullableDouble("totalSugarG"),
        addedSugarG = json.nullableDouble("addedSugarG"),
        fibreG = json.nullableDouble("fibreG"),
        totalFatG = json.nullableDouble("totalFatG"),
        saturatedFatG = json.nullableDouble("saturatedFatG"),
        transFatG = json.nullableDouble("transFatG"),
        sodiumMg = json.nullableDouble("sodiumMg"),
    )

    private fun encodeOcrAssessment(assessment: OcrAssessment): JSONObject = JSONObject().put(
        "images",
        JSONArray().apply {
            assessment.images.forEach { image ->
                put(
                    JSONObject()
                        .put("panel", image.panel.name)
                        .put("quality", image.quality.name)
                        .put("confidence", image.confidence)
                        .put("brightness", image.brightness)
                        .put("sharpness", image.sharpness)
                        .put("enhancedImageUsed", image.enhancedImageUsed)
                        .put("warnings", image.warnings.toJsonArray())
                        .putNullable("recognitionConfidence", image.recognitionConfidence)
                        .put("corrections", image.corrections.toJsonArray())
                        .put("enginesCompared", image.enginesCompared.toJsonArray())
                        .put("paddleOcrContributed", image.paddleOcrContributed)
                        .putNullable("paddleInferenceTimeMs", image.paddleInferenceTimeMs)
                        .put("framesAnalyzed", image.framesAnalyzed)
                        .putNullable("consensusAgreement", image.consensusAgreement)
                        .put("perspectiveCorrected", image.perspectiveCorrected)
                        .put("deskewed", image.deskewed),
                )
            }
        },
    )

    private fun decodeOcrAssessment(json: JSONObject): OcrAssessment = OcrAssessment(
        images = json.getJSONArray("images").mapObjects { image ->
            ImageOcrAssessment(
                panel = image.enumValue("panel", LabelPanel.NUTRITION),
                quality = image.enumValue("quality", OcrQuality.REVIEW),
                confidence = image.getDouble("confidence"),
                brightness = image.getInt("brightness"),
                sharpness = image.getInt("sharpness"),
                enhancedImageUsed = image.getBoolean("enhancedImageUsed"),
                warnings = image.stringList("warnings"),
                recognitionConfidence = image.nullableDouble("recognitionConfidence"),
                corrections = image.stringList("corrections"),
                enginesCompared = image.stringList("enginesCompared"),
                paddleOcrContributed = image.getBoolean("paddleOcrContributed"),
                paddleInferenceTimeMs = image.nullableLong("paddleInferenceTimeMs"),
                framesAnalyzed = image.getInt("framesAnalyzed"),
                consensusAgreement = image.nullableDouble("consensusAgreement"),
                perspectiveCorrected = image.getBoolean("perspectiveCorrected"),
                deskewed = image.getBoolean("deskewed"),
            )
        },
    )

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun List<String>.toJsonArray(): JSONArray {
        val values = this
        return JSONArray().apply { values.forEach { value -> put(value) } }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.nullableObject(key: String): JSONObject? =
        if (!has(key) || isNull(key)) null else getJSONObject(key)

    private fun JSONObject.stringList(key: String): List<String> {
        val values = getJSONArray(key)
        return List(values.length()) { index -> values.getString(index) }
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(getString(key)) }.getOrDefault(fallback)

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }
}

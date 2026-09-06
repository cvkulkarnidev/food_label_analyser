package com.cvkulkarnidev.foodlabel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cvkulkarnidev.foodlabel.analysis.ProductLabelAnalyzer
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.OcrAssessment
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import com.cvkulkarnidev.foodlabel.model.ReviewedLabelInput
import com.cvkulkarnidev.foodlabel.ocr.OnDeviceOcr
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LabelWiseViewModel(application: Application) : AndroidViewModel(application) {
    var screenState by mutableStateOf<ScreenState>(ScreenState.Home)
        private set

    var selectedCategory by mutableStateOf<ProductCategory?>(null)
        private set

    var pendingCameraUri by mutableStateOf<Uri?>(null)
        private set

    var pendingImageSlot by mutableStateOf<ImageSlot?>(null)
        private set

    private var analysisJob: Job? = null
    private var nutritionFrameUris: List<Uri> = emptyList()
    private var ingredientsFrameUris: List<Uri> = emptyList()

    fun selectCategory(category: ProductCategory) {
        selectedCategory = category
    }

    fun beginInput(mode: InputMode) {
        val category = selectedCategory ?: return
        analysisJob?.cancel()
        clearCameraCache()
        nutritionFrameUris = emptyList()
        ingredientsFrameUris = emptyList()
        screenState = ScreenState.ImageInput(mode = mode, category = category)
    }

    fun setPendingImage(slot: ImageSlot, cameraUri: Uri? = null) {
        pendingImageSlot = slot
        pendingCameraUri = cameraUri
    }

    fun setSelectedImage(slot: ImageSlot, uri: Uri) {
        val current = screenState as? ScreenState.ImageInput ?: return
        when (slot) {
            ImageSlot.NUTRITION -> nutritionFrameUris = listOf(uri)
            ImageSlot.INGREDIENTS -> ingredientsFrameUris = listOf(uri)
        }
        screenState = when (slot) {
            ImageSlot.NUTRITION -> current.copy(nutritionUri = uri)
            ImageSlot.INGREDIENTS -> current.copy(ingredientsUri = uri)
        }
    }

    fun startSmartScan(slot: ImageSlot) {
        val current = screenState as? ScreenState.ImageInput ?: return
        pendingImageSlot = slot
        screenState = ScreenState.SmartScan(current, slot)
    }

    fun cancelSmartScan() {
        val current = screenState as? ScreenState.SmartScan ?: return
        screenState = current.input
    }

    fun completeSmartScan(frameUris: List<Uri>) {
        val current = screenState as? ScreenState.SmartScan ?: return
        val usable = frameUris.distinct().take(3)
        if (usable.isEmpty()) {
            screenState = current.input
            return
        }
        when (current.slot) {
            ImageSlot.NUTRITION -> nutritionFrameUris = usable
            ImageSlot.INGREDIENTS -> ingredientsFrameUris = usable
        }
        screenState = when (current.slot) {
            ImageSlot.NUTRITION -> current.input.copy(nutritionUri = usable.first())
            ImageSlot.INGREDIENTS -> current.input.copy(ingredientsUri = usable.first())
        }
    }

    fun analyze(nutritionUri: Uri, ingredientsUri: Uri, category: ProductCategory) {
        analysisJob?.cancel()
        screenState = ScreenState.Reading(nutritionUri, ingredientsUri, category)
        analysisJob = viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val nutritionRead = OnDeviceOcr.read(
                    context,
                    nutritionFrameUris.ifEmpty { listOf(nutritionUri) },
                    LabelPanel.NUTRITION,
                )
                val ingredientsRead = OnDeviceOcr.read(
                    context,
                    ingredientsFrameUris.ifEmpty { listOf(ingredientsUri) },
                    LabelPanel.INGREDIENTS,
                )
                val ingredientsText = if (
                    Regex("^ingredients?\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                        .containsMatchIn(ingredientsRead.text)
                ) {
                    ingredientsRead.text
                } else {
                    "Ingredients:\n${ingredientsRead.text}"
                }
                val rawText = "${nutritionRead.text}\n$ingredientsText".trim()
                if (rawText.count(Char::isLetterOrDigit) < 24) {
                    screenState = ScreenState.Error(
                        nutritionUri,
                        "I couldn't find enough readable text across the two photos. Retake both panels closer, in brighter light, and hold the phone steady.",
                    )
                    return@launch
                }
                val assessment = OcrAssessment(
                    images = listOf(nutritionRead.assessment, ingredientsRead.assessment),
                )
                val report = withContext(Dispatchers.Default) {
                    ProductLabelAnalyzer.analyze(rawText, category, assessment)
                }
                screenState = ScreenState.Result(nutritionUri, ingredientsUri, report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                screenState = ScreenState.Error(
                    nutritionUri,
                    error.message ?: "The images could not be analyzed.",
                )
            }
        }
    }

    fun review(input: ReviewedLabelInput) {
        val current = screenState as? ScreenState.Result ?: return
        screenState = current.copy(report = ProductLabelAnalyzer.review(current.report, input))
    }

    fun returnHome() {
        analysisJob?.cancel()
        analysisJob = null
        pendingCameraUri = null
        pendingImageSlot = null
        nutritionFrameUris = emptyList()
        ingredientsFrameUris = emptyList()
        screenState = ScreenState.Home
        clearCameraCache()
    }

    private fun clearCameraCache() {
        File(getApplication<Application>().cacheDir, CAMERA_CACHE_DIRECTORY)
            .listFiles()
            ?.filter(File::isFile)
            ?.forEach(File::delete)
    }

    override fun onCleared() {
        clearCameraCache()
        super.onCleared()
    }

    private companion object {
        const val CAMERA_CACHE_DIRECTORY = "label_photos"
    }
}

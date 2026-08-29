package com.cvkulkarnidev.foodlabel

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.cvkulkarnidev.foodlabel.analysis.ProductLabelAnalyzer
import com.cvkulkarnidev.foodlabel.model.LabelReport
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.OcrAssessment
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import com.cvkulkarnidev.foodlabel.model.ScoreFactor
import com.cvkulkarnidev.foodlabel.ocr.OnDeviceOcr
import com.cvkulkarnidev.foodlabel.ui.theme.Amber
import com.cvkulkarnidev.foodlabel.ui.theme.Forest
import com.cvkulkarnidev.foodlabel.ui.theme.LabelWiseTheme
import com.cvkulkarnidev.foodlabel.ui.theme.Rose
import com.cvkulkarnidev.foodlabel.ui.theme.Sage
import com.cvkulkarnidev.foodlabel.ui.theme.SoftGreen
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LabelWiseTheme {
                LabelWiseApp(createCameraUri = ::createCameraUri)
            }
        }
    }

    private fun createCameraUri(): Uri {
        val directory = File(cacheDir, "label_photos").apply { mkdirs() }
        val file = File.createTempFile("label_", ".jpg", directory)
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }
}

private sealed interface ScreenState {
    data object Home : ScreenState
    data class ImageInput(
        val mode: InputMode,
        val category: ProductCategory,
        val nutritionUri: Uri? = null,
        val ingredientsUri: Uri? = null,
    ) : ScreenState
    data class Reading(
        val nutritionUri: Uri,
        val ingredientsUri: Uri,
        val category: ProductCategory,
    ) : ScreenState
    data class Result(
        val nutritionUri: Uri,
        val ingredientsUri: Uri,
        val report: LabelReport,
    ) : ScreenState
    data class Error(val nutritionUri: Uri?, val message: String) : ScreenState
}

private enum class InputMode(val title: String, val action: String) {
    CAPTURE("Capture both panels", "Capture"),
    UPLOAD("Upload both panels", "Choose"),
}

private enum class ImageSlot { NUTRITION, INGREDIENTS }

@Composable
private fun LabelWiseApp(createCameraUri: () -> Uri) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state: ScreenState by remember { mutableStateOf(ScreenState.Home) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImageSlot by remember { mutableStateOf<ImageSlot?>(null) }
    var selectedCategory by rememberSaveable { mutableStateOf<ProductCategory?>(null) }

    fun setSelectedImage(slot: ImageSlot, uri: Uri) {
        val current = state as? ScreenState.ImageInput ?: return
        state = when (slot) {
            ImageSlot.NUTRITION -> current.copy(nutritionUri = uri)
            ImageSlot.INGREDIENTS -> current.copy(ingredientsUri = uri)
        }
    }

    fun analyze(nutritionUri: Uri, ingredientsUri: Uri, category: ProductCategory) {
        state = ScreenState.Reading(nutritionUri, ingredientsUri, category)
        scope.launch {
            try {
                val nutritionRead = OnDeviceOcr.read(context, nutritionUri, LabelPanel.NUTRITION)
                val ingredientsRead = OnDeviceOcr.read(context, ingredientsUri, LabelPanel.INGREDIENTS)
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
                    state = ScreenState.Error(
                        nutritionUri,
                        "I couldn't find enough readable text across the two photos. Retake both panels closer, in brighter light, and hold the phone steady.",
                    )
                    return@launch
                }
                val ocrAssessment = OcrAssessment(
                    images = listOf(nutritionRead.assessment, ingredientsRead.assessment),
                )
                val report = withContext(Dispatchers.Default) {
                    ProductLabelAnalyzer.analyze(rawText, category, ocrAssessment)
                }
                state = ScreenState.Result(nutritionUri, ingredientsUri, report)
            } catch (error: Exception) {
                state = ScreenState.Error(nutritionUri, error.message ?: "The images could not be analyzed.")
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = pendingImageSlot
        if (uri != null && slot != null) setSelectedImage(slot, uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri
        val slot = pendingImageSlot
        if (saved && uri != null && slot != null) setSelectedImage(slot, uri)
    }

    fun requestImage(slot: ImageSlot) {
        val current = state as? ScreenState.ImageInput ?: return
        pendingImageSlot = slot
        if (current.mode == InputMode.UPLOAD) {
            galleryLauncher.launch("image/*")
            return
        }
        createCameraUri().also { uri ->
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    fun beginInput(mode: InputMode) {
        selectedCategory?.let { state = ScreenState.ImageInput(mode = mode, category = it) }
    }

    BackHandler(enabled = state !is ScreenState.Home) { state = ScreenState.Home }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (val current = state) {
            ScreenState.Home -> HomeScreen(
                modifier = Modifier.padding(padding),
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                onCapture = { beginInput(InputMode.CAPTURE) },
                onUpload = { beginInput(InputMode.UPLOAD) },
            )
            is ScreenState.ImageInput -> ImageInputScreen(
                modifier = Modifier.padding(padding),
                state = current,
                onBack = { state = ScreenState.Home },
                onSelectImage = ::requestImage,
                onAnalyze = {
                    val nutrition = current.nutritionUri
                    val ingredients = current.ingredientsUri
                    if (nutrition != null && ingredients != null) analyze(nutrition, ingredients, current.category)
                },
            )
            is ScreenState.Reading -> ReadingScreen(
                modifier = Modifier.padding(padding),
                nutritionUri = current.nutritionUri,
                ingredientsUri = current.ingredientsUri,
                category = current.category,
                onBack = { state = ScreenState.Home },
            )
            is ScreenState.Result -> ResultScreen(
                modifier = Modifier.padding(padding),
                uri = current.nutritionUri,
                report = current.report,
                onBack = { state = ScreenState.Home },
                onCapture = { beginInput(InputMode.CAPTURE) },
                onUpload = { beginInput(InputMode.UPLOAD) },
            )
            is ScreenState.Error -> ErrorScreen(
                modifier = Modifier.padding(padding),
                uri = current.nutritionUri,
                message = current.message,
                onBack = { state = ScreenState.Home },
                onCapture = { beginInput(InputMode.CAPTURE) },
                onUpload = { beginInput(InputMode.UPLOAD) },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    selectedCategory: ProductCategory?,
    onCategorySelected: (ProductCategory) -> Unit,
    onCapture: () -> Unit,
    onUpload: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Forest)
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 30.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Amber, shape = CircleShape, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.HealthAndSafety,
                            contentDescription = null,
                            tint = Forest,
                            modifier = Modifier.padding(11.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("LabelWise", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    "Know what's really\nin your food.",
                    color = Color.White,
                    fontSize = 38.sp,
                    lineHeight = 43.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Scan a packaged-food label to extract its nutrition, decode its ingredients, and get an explainable health score.",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                )
            }
        }

        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Analyze a label", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            CategorySelector(selectedCategory, onCategorySelected)
            ActionCard(
                title = "Capture label",
                subtitle = "Take separate photos of the nutrition and ingredient panels",
                icon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                primary = true,
                enabled = selectedCategory != null,
                onClick = onCapture,
            )
            ActionCard(
                title = "Upload image",
                subtitle = "Choose two existing panel photos from your device",
                icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                primary = false,
                enabled = selectedCategory != null,
                onClick = onUpload,
            )

            if (selectedCategory == null) {
                Text(
                    "Select a product category to enable capture and upload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Rose,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = SoftGreen.copy(alpha = 0.75f)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = Forest, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Private by design", fontWeight = FontWeight.Bold, color = Forest)
                        Text(
                            "OCR, image enhancement and scoring run on your phone. Your product photos are not uploaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Forest.copy(alpha = 0.78f),
                        )
                    }
                }
            }

            Text(
                "Tip: keep each panel flat, fill the frame with text, tap to focus, and avoid reflections.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CategorySelector(
    selectedCategory: ProductCategory?,
    onCategorySelected: (ProductCategory) -> Unit,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { dialogOpen = true },
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(Icons.Outlined.Category, contentDescription = null, tint = Forest)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(
                "PRODUCT CATEGORY",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Text(
                selectedCategory?.label ?: "Select a category",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
    }
    Text(
        "The selected category is used to compare this product only with similar foods.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Choose product category", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    ProductCategory.entries.forEach { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onCategorySelected(category)
                                    dialogOpen = false
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedCategory == category,
                                onClick = null,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(category.label, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun ImageInputScreen(
    modifier: Modifier,
    state: ScreenState.ImageInput,
    onBack: () -> Unit,
    onSelectImage: (ImageSlot) -> Unit,
    onAnalyze: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        SimpleTopBar(state.mode.title, onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.category.label, color = Sage, fontWeight = FontWeight.Bold)
            Text(
                "Use two separate photos so the nutrition values and the full ingredient list are both large enough to read.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
            )
            ImageSlotCard(
                number = "1",
                title = "Nutrition label",
                subtitle = "Include serving size, calories, sugar, fat, protein and sodium.",
                uri = state.nutritionUri,
                mode = state.mode,
                onClick = { onSelectImage(ImageSlot.NUTRITION) },
            )
            ImageSlotCard(
                number = "2",
                title = "Ingredients list",
                subtitle = "Include the complete ingredients and allergen statement.",
                uri = state.ingredientsUri,
                mode = state.mode,
                onClick = { onSelectImage(ImageSlot.INGREDIENTS) },
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(15.dp)) {
                    Text("For better low-light OCR", fontWeight = FontWeight.Bold, color = Forest)
                    Text(
                        "Use flash or a lamp, keep the phone parallel to the panel, fill the frame with text, tap to focus, and hold still. The app will also test an automatically brightened and sharpened version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Forest.copy(alpha = 0.78f),
                    )
                }
            }

            Button(
                onClick = onAnalyze,
                enabled = state.nutritionUri != null && state.ingredientsUri != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
            ) {
                Icon(Icons.Outlined.HealthAndSafety, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Analyze both photos", fontWeight = FontWeight.Bold)
            }
            if (state.nutritionUri == null || state.ingredientsUri == null) {
                Text(
                    "Both photos are required before analysis.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImageSlotCard(
    number: String,
    title: String,
    subtitle: String,
    uri: Uri?,
    mode: InputMode,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (uri != null) {
                AsyncImage(
                    model = uri,
                    contentDescription = "$title preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Surface(
                    color = SoftGreen,
                    contentColor = Forest,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.size(88.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(number, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (uri != null) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "Selected", tint = Sage, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (uri == null) "${mode.action} photo" else "${mode.action} a replacement",
                    color = Forest,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (primary) Forest else MaterialTheme.colorScheme.surface,
            contentColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (primary) 0.dp else 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (primary) Amber else SoftGreen,
                contentColor = Forest,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(54.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalContentMuted(primary))
            }
        }
    }
}

@Composable
private fun LocalContentMuted(primary: Boolean): Color =
    if (primary) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)

@Composable
private fun ReadingScreen(
    modifier: Modifier,
    nutritionUri: Uri,
    ingredientsUri: Uri,
    category: ProductCategory,
    onBack: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        SimpleTopBar("Reading your label", onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(nutritionUri, ingredientsUri).forEach { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = "Selected food label panel",
                                modifier = Modifier
                                    .size(138.dp, 190.dp)
                                    .clip(RoundedCornerShape(22.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Forest.copy(alpha = 0.88f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Amber, strokeWidth = 5.dp, modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("Checking image quality and reading both panels…", fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(category.label, color = Sage, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Dim or soft images are retried with automatic enhancement. Everything stays on this device.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ResultScreen(
    modifier: Modifier,
    uri: Uri,
    report: LabelReport,
    onBack: () -> Unit,
    onCapture: () -> Unit,
    onUpload: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        SimpleTopBar("Analysis", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            ProductHeader(uri, report)
            OcrQualityCard(report)
            ScoreCard(report)
            PeerComparisonCard(report)
            FactorSection(report.factors)
            NutritionSection(report)
            IngredientsSection(report)
            RawTextSection(report.rawText)
            ScanAgainButtons(onCapture, onUpload)
            Text(
                "This score is general guidance based on the visible label—not medical advice. Individual needs and portion size still matter.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OcrQualityCard(report: LabelReport) {
    val assessment = report.ocrAssessment ?: return
    val needsReview = assessment.needsReview
    val accent = if (needsReview) Rose else Sage
    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.11f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (needsReview) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (needsReview) "Check the OCR before trusting the score" else "Both photos look readable",
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    Text(
                        "${(assessment.confidence * 100).roundToInt()}% estimated OCR confidence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            assessment.images.forEachIndexed { index, image ->
                if (index > 0) HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 9.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(image.panel.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        image.quality.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (image.quality == com.cvkulkarnidev.foodlabel.model.OcrQuality.GOOD) Sage else Rose,
                    )
                }
                Text(
                    "Estimated confidence ${(image.confidence * 100).roundToInt()}%" +
                        if (image.enhancedImageUsed) " • Enhanced OCR result used" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                image.warnings.forEach { warning ->
                    Text(
                        "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = Rose,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            if (needsReview) {
                Spacer(Modifier.height(9.dp))
                Text(
                    "Automatic brightening and mild sharpening can improve readable detail, but cannot reconstruct text lost to motion blur or severe darkness. Retake a photo when a warning persists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun PeerComparisonCard(report: LabelReport) {
    val comparison = report.peerComparison
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Compared with similar products", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${comparison.percentile}%", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Forest)
                Spacer(Modifier.width(9.dp))
                Text(
                    "CATEGORY PERCENTILE",
                    modifier = Modifier.padding(bottom = 5.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.67f),
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { comparison.percentile / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(CircleShape),
                color = Forest,
                trackColor = SoftGreen,
            )
            Spacer(Modifier.height(10.dp))
            Text(comparison.position, fontWeight = FontWeight.Bold, color = Sage)
            Text(
                "Based on ${comparison.peerCount} valid ${report.category.label.lowercase()} rows from an 852-product India-market dataset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (comparison.isSmallSample) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Small peer set: treat this category rank as directional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Rose,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "A high rank inside a treat category does not replace the absolute health score above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ProductHeader(uri: Uri, report: LabelReport) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(report.productName, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(report.category.label, color = Sage, fontWeight = FontWeight.Medium)
            Text(
                "${(report.confidence * 100).roundToInt()}% extraction confidence",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            )
        }
    }
}

@Composable
private fun ScoreCard(report: LabelReport) {
    val scoreColor = scoreColor(report.score)
    Card(
        colors = CardDefaults.cardColors(containerColor = scoreColor.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(26.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(116.dp)) {
                CircularProgressIndicator(
                    progress = { (report.score / 5.0).toFloat() },
                    modifier = Modifier.fillMaxSize(),
                    color = scoreColor,
                    trackColor = scoreColor.copy(alpha = 0.17f),
                    strokeWidth = 11.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${report.score}", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = scoreColor)
                    Text("OUT OF 5", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = scoreColor.copy(alpha = 0.75f))
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Health score", style = MaterialTheme.typography.labelLarge, color = scoreColor)
                Text(report.verdict, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = scoreColor)
                Spacer(Modifier.height(5.dp))
                Text(
                    "Built from the nutrition values, ingredient order, and processing signals I could read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.67f),
                )
            }
        }
    }
}

@Composable
private fun FactorSection(factors: List<ScoreFactor>) {
    SectionCard(title = "Why this score") {
        factors.forEachIndexed { index, factor ->
            FactorRow(factor)
            if (index != factors.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun FactorRow(factor: ScoreFactor) {
    val neutral = factor.impact == 0.0
    val color = when {
        neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        factor.isPositive -> Sage
        else -> Rose
    }
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (factor.isPositive) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(factor.title, fontWeight = FontWeight.Bold)
            Text(factor.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        }
        if (!neutral) {
            Text(
                text = if (factor.impact > 0) "+${factor.impact}" else factor.impact.toString(),
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun NutritionSection(report: LabelReport) {
    val facts = report.nutrition
    val items = nutritionItems(facts)
    SectionCard(title = "Nutrition extracted") {
        Text(
            buildString {
                append(report.nutritionBasis.label)
                report.servingSize?.let { append(" • Serving size $it") }
            },
            color = Sage,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (items.isEmpty()) {
            Text("No nutrition numbers were read reliably.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        } else {
            items.chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { item ->
                        NutritionTile(item.first, item.second, Modifier.weight(1f))
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private fun nutritionItems(facts: NutritionFacts): List<Pair<String, String>> = buildList {
    facts.energyKcal?.let { add("Energy" to "${formatNutrition(it)} kcal") }
    facts.proteinG?.let { add("Protein" to "${formatNutrition(it)} g") }
    facts.carbohydrateG?.let { add("Carbohydrate" to "${formatNutrition(it)} g") }
    facts.totalSugarG?.let { add("Total sugar" to "${formatNutrition(it)} g") }
    facts.addedSugarG?.let { add("Added sugar" to "${formatNutrition(it)} g") }
    facts.fibreG?.let { add("Fibre" to "${formatNutrition(it)} g") }
    facts.totalFatG?.let { add("Total fat" to "${formatNutrition(it)} g") }
    facts.saturatedFatG?.let { add("Saturated fat" to "${formatNutrition(it)} g") }
    facts.transFatG?.let { add("Trans fat" to "${formatNutrition(it)} g") }
    facts.sodiumMg?.let { add("Sodium" to "${formatNutrition(it)} mg") }
}

private fun formatNutrition(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

@Composable
private fun NutritionTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IngredientsSection(report: LabelReport) {
    SectionCard(title = "Ingredients & allergens") {
        if (report.ingredients.isNullOrBlank()) {
            Text("Ingredient list not detected.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        } else {
            Text(report.ingredients, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
        }
        if (report.allergens.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(color = Amber.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp)) {
                Text(
                    "Allergens mentioned: ${report.allergens.joinToString()}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    fontWeight = FontWeight.SemiBold,
                    color = Forest,
                )
            }
        }
    }
}

@Composable
private fun RawTextSection(rawText: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Raw OCR text", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded) {
                Text(
                    rawText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.67f),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(17.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            content()
        }
    }
}

@Composable
private fun ScanAgainButtons(onCapture: () -> Unit, onUpload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onCapture,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Forest),
        ) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Capture another label", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onUpload,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Upload another image", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorScreen(
    modifier: Modifier,
    uri: Uri?,
    message: String,
    onBack: () -> Unit,
    onCapture: () -> Unit,
    onUpload: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        SimpleTopBar("Try again", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            uri?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(28.dp)),
                )
                Spacer(Modifier.height(22.dp))
            }
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Rose, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(12.dp))
            Text("Label not readable", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
            Spacer(Modifier.height(24.dp))
            ScanAgainButtons(onCapture, onUpload)
        }
    }
}

@Composable
private fun SimpleTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Forest)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

private fun scoreColor(score: Double): Color = when {
    score >= 4.2 -> Color(0xFF237A52)
    score >= 3.2 -> Color(0xFF5B792F)
    score >= 2.2 -> Color(0xFFB36B13)
    else -> Rose
}

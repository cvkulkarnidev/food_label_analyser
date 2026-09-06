package com.cvkulkarnidev.foodlabel

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.cvkulkarnidev.foodlabel.analysis.IngredientAlertMatch
import com.cvkulkarnidev.foodlabel.analysis.IngredientAlertPreferences
import com.cvkulkarnidev.foodlabel.analysis.IngredientAlerts
import com.cvkulkarnidev.foodlabel.analysis.ProductLabelAnalyzer
import com.cvkulkarnidev.foodlabel.model.AnalysisReadiness
import com.cvkulkarnidev.foodlabel.model.LabelPanel
import com.cvkulkarnidev.foodlabel.model.LabelReport
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import com.cvkulkarnidev.foodlabel.model.ReviewedLabelInput
import com.cvkulkarnidev.foodlabel.model.ScoreFactor
import com.cvkulkarnidev.foodlabel.ui.theme.Amber
import com.cvkulkarnidev.foodlabel.ui.theme.Forest
import com.cvkulkarnidev.foodlabel.ui.theme.LabelWiseTheme
import com.cvkulkarnidev.foodlabel.ui.theme.SoftGreen
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
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

internal sealed interface ScreenState {
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
    data class SmartScan(
        val input: ImageInput,
        val slot: ImageSlot,
    ) : ScreenState
    data class Result(
        val nutritionUri: Uri,
        val ingredientsUri: Uri,
        val report: LabelReport,
    ) : ScreenState
    data class Error(val nutritionUri: Uri?, val message: String) : ScreenState
}

internal enum class InputMode(val title: String, val action: String) {
    CAPTURE("Smart Scan both panels", "Scan"),
    UPLOAD("Upload both panels", "Choose"),
}

internal enum class ImageSlot { NUTRITION, INGREDIENTS }

private data class ReportSaveRequest(
    val nutritionUri: Uri,
    val ingredientsUri: Uri,
    val report: LabelReport,
    val alertMatches: List<IngredientAlertMatch>,
)

@Composable
private fun LabelWiseApp(createCameraUri: () -> Uri) {
    val context = LocalContext.current
    val appViewModel: LabelWiseViewModel = viewModel()
    val state = appViewModel.screenState
    val selectedCategory = appViewModel.selectedCategory
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSaveRequest by remember { mutableStateOf<ReportSaveRequest?>(null) }
    var ingredientAlertPreferences by remember(context) {
        mutableStateOf(loadIngredientAlertPreferences(context))
    }

    fun updateIngredientAlertPreferences(preferences: IngredientAlertPreferences) {
        ingredientAlertPreferences = preferences
        saveIngredientAlertPreferences(context, preferences)
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = appViewModel.pendingImageSlot
        if (uri != null && slot != null) appViewModel.setSelectedImage(slot, uri)
    }
    val saveReportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destination ->
        val request = pendingSaveRequest
        pendingSaveRequest = null
        if (destination != null && request != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        LabelReportPdfExporter.write(
                            context = context,
                            destination = destination,
                            nutritionImage = request.nutritionUri,
                            ingredientsImage = request.ingredientsUri,
                            report = request.report,
                            alerts = request.alertMatches,
                        )
                    }
                }
                snackbarHostState.showSnackbar(
                    if (result.isSuccess) "Report saved to your phone." else "The report could not be saved: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                )
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = appViewModel.pendingCameraUri
        val slot = appViewModel.pendingImageSlot
        if (saved && uri != null && slot != null) appViewModel.setSelectedImage(slot, uri)
    }
    val documentScanner = remember {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
    }
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val slot = appViewModel.pendingImageSlot
            val data = result.data
            if (slot != null && data != null) {
                GmsDocumentScanningResult.fromActivityResultIntent(data)
                    ?.pages
                    ?.firstOrNull()
                    ?.imageUri
                    ?.let { appViewModel.setSelectedImage(slot, it) }
            }
        }
    }

    fun launchSystemCamera() {
        createCameraUri().also { uri ->
            appViewModel.setPendingImage(appViewModel.pendingImageSlot ?: return, uri)
            cameraLauncher.launch(uri)
        }
    }

    fun launchQuickCapture(slot: ImageSlot) {
        if (appViewModel.screenState is ScreenState.SmartScan) appViewModel.cancelSmartScan()
        appViewModel.setPendingImage(slot)
        val activity = context as? Activity
        if (activity == null) {
            launchSystemCamera()
            return
        }
        documentScanner.getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { launchSystemCamera() }
    }

    fun requestImage(slot: ImageSlot) {
        val current = appViewModel.screenState as? ScreenState.ImageInput ?: return
        appViewModel.setPendingImage(slot)
        if (current.mode == InputMode.UPLOAD) {
            galleryLauncher.launch("image/*")
            return
        }
        appViewModel.startSmartScan(slot)
    }

    BackHandler(enabled = state !is ScreenState.Home) {
        if (state is ScreenState.SmartScan) appViewModel.cancelSmartScan() else appViewModel.returnHome()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val current = state) {
            ScreenState.Home -> HomeScreen(
                modifier = Modifier.padding(padding),
                selectedCategory = selectedCategory,
                onCategorySelected = appViewModel::selectCategory,
                ingredientAlertPreferences = ingredientAlertPreferences,
                onIngredientAlertPreferencesChanged = ::updateIngredientAlertPreferences,
                onCapture = { appViewModel.beginInput(InputMode.CAPTURE) },
                onUpload = { appViewModel.beginInput(InputMode.UPLOAD) },
            )
            is ScreenState.ImageInput -> ImageInputScreen(
                modifier = Modifier.padding(padding),
                state = current,
                onBack = appViewModel::returnHome,
                onSelectImage = ::requestImage,
                onAnalyze = {
                    val nutrition = current.nutritionUri
                    val ingredients = current.ingredientsUri
                    if (nutrition != null && ingredients != null) appViewModel.analyze(nutrition, ingredients, current.category)
                },
            )
            is ScreenState.Reading -> ReadingScreen(
                modifier = Modifier.padding(padding),
                nutritionUri = current.nutritionUri,
                ingredientsUri = current.ingredientsUri,
                category = current.category,
                onBack = appViewModel::returnHome,
            )
            is ScreenState.SmartScan -> SmartScanScreen(
                modifier = Modifier.padding(padding),
                panel = if (current.slot == ImageSlot.NUTRITION) LabelPanel.NUTRITION else LabelPanel.INGREDIENTS,
                onComplete = { appViewModel.completeSmartScan(it) },
                onCancel = appViewModel::cancelSmartScan,
                onQuickCapture = { launchQuickCapture(current.slot) },
            )
            is ScreenState.Result -> ResultScreen(
                modifier = Modifier.padding(padding),
                nutritionUri = current.nutritionUri,
                report = current.report,
                ingredientAlertPreferences = ingredientAlertPreferences,
                onBack = appViewModel::returnHome,
                onReportReviewed = appViewModel::review,
                onSave = {
                    pendingSaveRequest = ReportSaveRequest(
                        nutritionUri = current.nutritionUri,
                        ingredientsUri = current.ingredientsUri,
                        report = current.report,
                        alertMatches = IngredientAlerts.findMatches(current.report.ingredients, ingredientAlertPreferences),
                    )
                    saveReportLauncher.launch(LabelReportPdfExporter.suggestedFileName(current.report))
                },
                onCapture = { appViewModel.beginInput(InputMode.CAPTURE) },
                onUpload = { appViewModel.beginInput(InputMode.UPLOAD) },
            )
            is ScreenState.Error -> ErrorScreen(
                modifier = Modifier.padding(padding),
                uri = current.nutritionUri,
                message = current.message,
                onBack = appViewModel::returnHome,
                onCapture = { appViewModel.beginInput(InputMode.CAPTURE) },
                onUpload = { appViewModel.beginInput(InputMode.UPLOAD) },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    selectedCategory: ProductCategory?,
    onCategorySelected: (ProductCategory) -> Unit,
    ingredientAlertPreferences: IngredientAlertPreferences,
    onIngredientAlertPreferencesChanged: (IngredientAlertPreferences) -> Unit,
    onCapture: () -> Unit,
    onUpload: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
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
            IngredientAlertPreferencesCard(
                preferences = ingredientAlertPreferences,
                onPreferencesChanged = onIngredientAlertPreferencesChanged,
            )
            ActionCard(
                title = "Scan label",
                subtitle = "Combine three clear frames for each panel and correct perspective",
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
                    color = MaterialTheme.colorScheme.error,
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
                            "OCR, image enhancement and scoring run on your phone. There is no account, advertising or analytics, and temporary camera photos are cleared when the session ends.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Forest,
                        )
                        TextButton(
                            onClick = {
                                uriHandler.openUri(
                                    "https://github.com/cvkulkarnidev/food_label_analyser/blob/main/PRIVACY.md",
                                )
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) {
                            Text("Privacy policy & limitations", color = Forest, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Text(
                "Tip: fill the guide with the panel. Smart Scan checks focus, lighting and glare for three seconds, then combines the clearest readings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Text(
                "LabelWise ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}


@Composable
private fun IngredientAlertPreferencesCard(
    preferences: IngredientAlertPreferences,
    onPreferencesChanged: (IngredientAlertPreferences) -> Unit,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var draftIds by remember { mutableStateOf(preferences.enabledPresetIds) }
    var customInput by remember { mutableStateOf(preferences.customTerms.joinToString(", ")) }

    OutlinedButton(
        onClick = {
            draftIds = preferences.enabledPresetIds
            customInput = preferences.customTerms.joinToString(", ")
            dialogOpen = true
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(
                "MY INGREDIENT ALERTS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
            Text(
                "${preferences.enabledPresetIds.size} defaults" +
                    if (preferences.customTerms.isEmpty()) "" else " + ${preferences.customTerms.size} custom",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Tap to choose what gets highlighted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
    }
    Text(
        "These are personal watch items, not medical allergens. Declared allergens are shown separately.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Ingredient alerts", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Choose ingredients you personally want flagged. All presets start enabled, and you can add your own terms.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    IngredientAlerts.presets.forEach { alert ->
                        val checked = alert.id in draftIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    draftIds = if (checked) draftIds - alert.id else draftIds + alert.id
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(alert.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    alert.shortDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom ingredients") },
                        supportingText = { Text("Separate up to 12 terms with commas or new lines.") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    Text(
                        "An alert means “check this ingredient,” not that the ingredient is unsafe for everyone. For a diagnosed allergy, always verify the original package.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPreferencesChanged(
                            IngredientAlertPreferences(
                                enabledPresetIds = draftIds,
                                customTerms = parseCustomIngredientAlerts(customInput),
                            ),
                        )
                        dialogOpen = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            draftIds = IngredientAlerts.defaultPresetIds
                            customInput = ""
                        },
                    ) {
                        Text("Reset defaults")
                    }
                    TextButton(onClick = { dialogOpen = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
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
            .heightIn(min = 62.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(Icons.Outlined.Category, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
            Text(state.category.label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
                    Text("For reliable OCR", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (state.mode == InputMode.CAPTURE) {
                            "Tap a panel to run a 3-second Smart Scan. The app selects up to three clear frames, corrects perspective, compares their readings and uses PaddleOCR for verification."
                        } else {
                            "Use a close, sharp image with the complete panel visible. Uploaded images are deskewed when reliable geometry is detected and checked with ML Kit and PaddleOCR."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
            }

            Button(
                onClick = onAnalyze,
                enabled = state.nutritionUri != null && state.ingredientsUri != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
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
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
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
                    color = MaterialTheme.colorScheme.primary,
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
                Text(category.label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Perspective is corrected first. Multiple frames are compared by nutrient row, and disputed text is verified on this device with PaddleOCR.",
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
    nutritionUri: Uri,
    report: LabelReport,
    ingredientAlertPreferences: IngredientAlertPreferences,
    onBack: () -> Unit,
    onReportReviewed: (ReviewedLabelInput) -> Unit,
    onSave: () -> Unit,
    onCapture: () -> Unit,
    onUpload: () -> Unit,
) {
    var showReviewDialog by rememberSaveable { mutableStateOf(false) }
    if (showReviewDialog) {
        ReviewLabelDialog(
            report = report,
            onDismiss = { showReviewDialog = false },
            onConfirm = {
                onReportReviewed(it)
                showReviewDialog = false
            },
        )
    }
    Column(modifier.fillMaxSize()) {
        SimpleTopBar("Analysis", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val ingredientAlertMatches = remember(report.ingredients, ingredientAlertPreferences) {
                IngredientAlerts.findMatches(report.ingredients, ingredientAlertPreferences)
            }
            Spacer(Modifier.height(2.dp))
            ProductHeader(nutritionUri, report)
            OcrQualityCard(report)
            ScoreCard(report)
            OutlinedButton(
                onClick = { showReviewDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (report.wasUserReviewed) "Edit reviewed values" else "Review & correct extracted values",
                    fontWeight = FontWeight.Bold,
                )
            }
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save images and report (PDF)", fontWeight = FontWeight.Bold)
            }
            if (report.readiness != AnalysisReadiness.INSUFFICIENT) {
                PeerComparisonCard(report)
                FactorSection(report.factors)
            }
            NutritionSection(report)
            IngredientAlertsSection(ingredientAlertMatches)
            IngredientsSection(report, ingredientAlertMatches)
            RawTextSection(report.rawText)
            ScanAgainButtons(onCapture, onUpload)
            Text(
                "This score is general guidance based on the visible label—not medical advice. Individual needs and portion size still matter.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OcrQualityCard(report: LabelReport) {
    val assessment = report.ocrAssessment ?: return
    val needsReview = assessment.needsReview || report.extractionWarnings.isNotEmpty()
    val accent = if (needsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
                        when {
                            report.wasUserReviewed -> "Original OCR reviewed and corrected"
                            needsReview -> "Check the OCR before trusting the result"
                            else -> "Both photos look readable"
                        },
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
                        color = if (image.quality == com.cvkulkarnidev.foodlabel.model.OcrQuality.GOOD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Overall ${(image.confidence * 100).roundToInt()}%" +
                        (image.recognitionConfidence?.let { " • Text ${(it * 100).roundToInt()}%" } ?: "") +
                        (if (image.framesAnalyzed > 1) " • ${image.framesAnalyzed} frames" else " • Single image") +
                        (image.consensusAgreement?.let { " • ${(it * 100).roundToInt()}% agreement" } ?: "") +
                        " • ${image.enginesCompared.joinToString(" + ")}" +
                        (if (image.paddleOcrContributed) " • Paddle result selected" else "") +
                        (image.paddleInferenceTimeMs?.let { " • Paddle ${it} ms" } ?: "") +
                        (if (image.perspectiveCorrected) " • Perspective corrected" else if (image.deskewed) " • Deskewed" else "") +
                        (if (image.enhancedImageUsed) " • Enhanced image used" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                image.warnings.forEach { warning ->
                    Text(
                        "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                image.corrections.forEach { correction ->
                    Text(
                        "• $correction",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            report.extractionWarnings.forEach { warning ->
                Text(
                    "• $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 5.dp),
                )
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
                Text("${comparison.percentile}%", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
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
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Text(comparison.position, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                    color = MaterialTheme.colorScheme.error,
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
            Text(report.category.label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
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
    if (report.readiness == AnalysisReadiness.INSUFFICIENT) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            shape = RoundedCornerShape(26.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "Score withheld",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        report.readinessMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        return
    }
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
                Text(
                    when {
                        report.wasUserReviewed -> "Reviewed health score"
                        report.readiness == AnalysisReadiness.REVIEW -> "Provisional health score"
                        else -> "Health score"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = scoreColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(report.verdict, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = scoreColor)
                Spacer(Modifier.height(5.dp))
                Text(
                    report.readinessMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        factor.isPositive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
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
            color = MaterialTheme.colorScheme.primary,
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
private fun IngredientAlertsSection(matches: List<IngredientAlertMatch>) {
    SectionCard(title = "Your ingredient alerts") {
        if (matches.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    "No selected alert ingredients were detected in the extracted ingredient list.",
                    modifier = Modifier.padding(12.dp),
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${matches.size} selected alert${if (matches.size == 1) "" else "s"} found",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    matches.forEachIndexed { index, match ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.22f))
                        }
                        Column {
                            Text(match.label, fontWeight = FontWeight.Bold)
                            Text(
                                "Matched: ${match.matchedTerms.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                match.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Personal alerts are not medical allergy detection and do not automatically change the health score.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun IngredientsSection(report: LabelReport, matches: List<IngredientAlertMatch>) {
    SectionCard(title = "Ingredients & declared allergens") {
        val ingredientText = report.ingredients
        if (ingredientText.isNullOrBlank()) {
            Text("Ingredient list not detected.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        } else {
            val highlightBackground = MaterialTheme.colorScheme.tertiaryContainer
            val highlightForeground = MaterialTheme.colorScheme.onTertiaryContainer
            val highlighted = buildAnnotatedString {
                append(ingredientText)
                matches
                    .flatMap(IngredientAlertMatch::ranges)
                    .distinct()
                    .forEach { range ->
                        addStyle(
                            SpanStyle(
                                background = highlightBackground,
                                color = highlightForeground,
                                fontWeight = FontWeight.Bold,
                            ),
                            start = range.first,
                            end = range.last + 1,
                        )
                    }
            }
            Text(highlighted, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
        }
        if (report.allergens.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Allergens mentioned on the label: ${report.allergens.joinToString()}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    fontWeight = FontWeight.SemiBold,
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
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Capture another label", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onUpload,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
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
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(42.dp))
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


private const val INGREDIENT_ALERT_PREFERENCES = "ingredient_alert_preferences"
private const val ENABLED_ALERT_IDS = "enabled_alert_ids"
private const val CUSTOM_ALERT_TERMS = "custom_alert_terms"

private fun loadIngredientAlertPreferences(context: Context): IngredientAlertPreferences {
    val preferences = context.getSharedPreferences(INGREDIENT_ALERT_PREFERENCES, Context.MODE_PRIVATE)
    return IngredientAlertPreferences(
        enabledPresetIds = preferences.getStringSet(ENABLED_ALERT_IDS, null)
            ?.toSet()
            ?: IngredientAlerts.defaultPresetIds,
        customTerms = preferences.getStringSet(CUSTOM_ALERT_TERMS, emptySet())
            ?.toSet()
            .orEmpty(),
    )
}

private fun saveIngredientAlertPreferences(
    context: Context,
    preferences: IngredientAlertPreferences,
) {
    context.getSharedPreferences(INGREDIENT_ALERT_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(ENABLED_ALERT_IDS, preferences.enabledPresetIds.toSet())
        .putStringSet(CUSTOM_ALERT_TERMS, preferences.customTerms.toSet())
        .apply()
}

private fun parseCustomIngredientAlerts(input: String): Set<String> {
    val seen = mutableSetOf<String>()
    return input
        .split(Regex("[,\\n]"))
        .map { it.trim() }
        .filter { it.length in 2..40 }
        .filter { seen.add(it.lowercase()) }
        .take(12)
        .toSet()
}

@Composable
private fun scoreColor(score: Double): Color = when {
    score >= 4.2 -> MaterialTheme.colorScheme.primary
    score >= 3.2 -> MaterialTheme.colorScheme.secondary
    score >= 2.2 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

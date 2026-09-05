package com.cvkulkarnidev.foodlabel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.cvkulkarnidev.foodlabel.model.LabelReport
import com.cvkulkarnidev.foodlabel.model.NutritionBasis
import com.cvkulkarnidev.foodlabel.model.NutritionFacts
import com.cvkulkarnidev.foodlabel.model.ReviewedLabelInput

@Composable
internal fun ReviewLabelDialog(
    report: LabelReport,
    onDismiss: () -> Unit,
    onConfirm: (ReviewedLabelInput) -> Unit,
) {
    var productName by remember(report) { mutableStateOf(report.productName) }
    var basis by remember(report) { mutableStateOf(report.nutritionBasis) }
    var servingSize by remember(report) { mutableStateOf(report.servingSize.orEmpty()) }
    var ingredients by remember(report) { mutableStateOf(report.ingredients.orEmpty()) }

    var energy by remember(report) { mutableStateOf(editable(report.nutrition.energyKcal)) }
    var protein by remember(report) { mutableStateOf(editable(report.nutrition.proteinG)) }
    var carbohydrate by remember(report) { mutableStateOf(editable(report.nutrition.carbohydrateG)) }
    var totalSugar by remember(report) { mutableStateOf(editable(report.nutrition.totalSugarG)) }
    var addedSugar by remember(report) { mutableStateOf(editable(report.nutrition.addedSugarG)) }
    var fibre by remember(report) { mutableStateOf(editable(report.nutrition.fibreG)) }
    var totalFat by remember(report) { mutableStateOf(editable(report.nutrition.totalFatG)) }
    var saturatedFat by remember(report) { mutableStateOf(editable(report.nutrition.saturatedFatG)) }
    var transFat by remember(report) { mutableStateOf(editable(report.nutrition.transFatG)) }
    var sodium by remember(report) { mutableStateOf(editable(report.nutrition.sodiumMg)) }

    val numericValues = listOf(
        energy,
        protein,
        carbohydrate,
        totalSugar,
        addedSugar,
        fibre,
        totalFat,
        saturatedFat,
        transFat,
        sodium,
    )
    val hasInvalidNumber = numericValues.any { value ->
        value.isNotBlank() && (parseNumber(value) == null || (parseNumber(value) ?: 0.0) < 0.0)
    }
    val servingRequired = basis == NutritionBasis.PER_SERVING && servingSize.isBlank()
    val canConfirm = productName.isNotBlank() && !hasInvalidNumber && !servingRequired

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Review extracted label", fontWeight = FontWeight.Bold)
                Text(
                    "Correct OCR mistakes before the app calculates the final estimate.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = productName,
                    onValueChange = { productName = it },
                    label = { Text("Product name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text("Values are", fontWeight = FontWeight.SemiBold)
                listOf(
                    NutritionBasis.PER_100_G,
                    NutritionBasis.PER_100_ML,
                    NutritionBasis.PER_SERVING,
                    NutritionBasis.PER_PACK,
                    NutritionBasis.UNKNOWN,
                ).forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = basis == option, onClick = { basis = option })
                        Text(option.label)
                    }
                }

                if (basis == NutritionBasis.PER_SERVING || servingSize.isNotBlank()) {
                    OutlinedTextField(
                        value = servingSize,
                        onValueChange = { servingSize = it },
                        label = { Text("Serving size, e.g. 30 g") },
                        isError = servingRequired,
                        supportingText = if (servingRequired) {
                            { Text("Required for per-serving values") }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                Text("Nutrition values", fontWeight = FontWeight.SemiBold)
                Text(
                    "Leave a field blank when it is not printed on the label.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumericFieldRow(
                    firstLabel = "Energy (kcal)",
                    firstValue = energy,
                    onFirstChange = { energy = it },
                    secondLabel = "Protein (g)",
                    secondValue = protein,
                    onSecondChange = { protein = it },
                )
                NumericFieldRow(
                    firstLabel = "Carbohydrate (g)",
                    firstValue = carbohydrate,
                    onFirstChange = { carbohydrate = it },
                    secondLabel = "Total sugar (g)",
                    secondValue = totalSugar,
                    onSecondChange = { totalSugar = it },
                )
                NumericFieldRow(
                    firstLabel = "Added sugar (g)",
                    firstValue = addedSugar,
                    onFirstChange = { addedSugar = it },
                    secondLabel = "Fibre (g)",
                    secondValue = fibre,
                    onSecondChange = { fibre = it },
                )
                NumericFieldRow(
                    firstLabel = "Total fat (g)",
                    firstValue = totalFat,
                    onFirstChange = { totalFat = it },
                    secondLabel = "Saturated fat (g)",
                    secondValue = saturatedFat,
                    onSecondChange = { saturatedFat = it },
                )
                NumericFieldRow(
                    firstLabel = "Trans fat (g)",
                    firstValue = transFat,
                    onFirstChange = { transFat = it },
                    secondLabel = "Sodium (mg)",
                    secondValue = sodium,
                    onSecondChange = { sodium = it },
                )
                if (hasInvalidNumber) {
                    Text(
                        "Use non-negative numbers only.",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedTextField(
                    value = ingredients,
                    onValueChange = { ingredients = it },
                    label = { Text("Ingredients") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(
                        ReviewedLabelInput(
                            productName = productName.trim(),
                            nutrition = NutritionFacts(
                                energyKcal = parseNumber(energy),
                                proteinG = parseNumber(protein),
                                carbohydrateG = parseNumber(carbohydrate),
                                totalSugarG = parseNumber(totalSugar),
                                addedSugarG = parseNumber(addedSugar),
                                fibreG = parseNumber(fibre),
                                totalFatG = parseNumber(totalFat),
                                saturatedFatG = parseNumber(saturatedFat),
                                transFatG = parseNumber(transFat),
                                sodiumMg = parseNumber(sodium),
                            ),
                            nutritionBasis = basis,
                            servingSize = servingSize.trim().ifBlank { null },
                            ingredients = ingredients.trim().ifBlank { null },
                        ),
                    )
                },
            ) {
                Text("Recalculate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NumericFieldRow(
    firstLabel: String,
    firstValue: String,
    onFirstChange: (String) -> Unit,
    secondLabel: String,
    secondValue: String,
    onSecondChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReviewNumberField(firstLabel, firstValue, onFirstChange, Modifier.weight(1f))
        ReviewNumberField(secondLabel, secondValue, onSecondChange, Modifier.weight(1f))
    }
}

@Composable
private fun ReviewNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    val invalid = value.isNotBlank() && (parseNumber(value) == null || (parseNumber(value) ?: 0.0) < 0.0)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun parseNumber(value: String): Double? =
    value.trim().replace(',', '.').takeIf { it.isNotBlank() }?.toDoubleOrNull()

private fun editable(value: Double?): String = when {
    value == null -> ""
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> value.toString()
}

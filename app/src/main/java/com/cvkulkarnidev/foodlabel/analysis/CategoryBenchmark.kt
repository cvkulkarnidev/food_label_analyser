package com.cvkulkarnidev.foodlabel.analysis

import com.cvkulkarnidev.foodlabel.model.PeerComparison
import com.cvkulkarnidev.foodlabel.model.ProductCategory
import kotlin.math.roundToInt

/**
 * Score histograms generated from the 2026 Indian Packaged Foods Nutritional
 * Composition Dataset. See data/README.md and scripts/build_category_benchmarks.py.
 */
object CategoryBenchmark {
    private val histograms = mapOf(
        ProductCategory.BISCUITS_AND_BAKERY to histogram("0.6:2 0.7:2 0.8:2 0.9:2 1.0:8 1.1:3 1.2:6 1.3:2 1.4:6 1.5:4 1.6:15 1.7:7 1.8:7 1.9:8 2.0:10 2.1:8 2.2:4 2.3:5 2.4:4 2.5:7 2.6:7 2.7:3 2.8:2 2.9:6 3.0:1 3.1:1 3.2:3 3.3:2 3.4:1 3.6:1 3.7:2 3.8:4 4.1:1 4.4:2 4.6:1"),
        ProductCategory.BEVERAGES_AND_JUICES to histogram("1.8:1 1.9:2 2.0:7 2.2:3 2.4:3 2.5:17 2.7:15 2.8:2 3.0:6 3.2:26 3.3:1 3.5:1 3.6:5 3.7:2 3.8:9 4.0:1 4.1:5 4.2:4 4.3:2 4.6:2 4.8:3 4.9:1"),
        ProductCategory.SAVOURY_SNACKS to histogram("1.2:1 1.4:1 1.6:1 1.7:1 1.8:2 1.9:2 2.0:2 2.1:1 2.2:1 2.3:1 2.4:6 2.5:3 2.6:10 2.7:2 2.8:6 2.9:13 3.0:15 3.1:2 3.2:3 3.3:8 3.4:4 3.5:4 3.6:4 3.7:2 3.8:4 3.9:8 4.0:2 4.1:4 4.2:9 4.3:3 4.4:1 4.5:2 4.8:3 4.9:7"),
        ProductCategory.CHOCOLATE_AND_SWEETS to histogram("0.8:1 1.0:1 1.1:3 1.2:6 1.3:2 1.4:3 1.5:2 1.6:12 1.7:10 1.8:8 1.9:5 2.0:8 2.1:41 2.2:2 2.3:24 2.4:4 2.5:3 2.6:4 2.7:6 2.8:11 2.9:3 3.0:34 3.1:3 3.2:1 3.3:1 3.4:5 3.5:1 3.6:1 3.7:2 3.8:1 4.0:1 4.2:3 4.3:3 4.5:1 4.6:2 4.8:2 4.9:2"),
        ProductCategory.INSTANT_AND_READY_FOODS to histogram("1.0:1 1.1:1 1.5:1 1.9:1 2.0:1 2.1:1 2.2:1 2.3:1 2.4:4 2.5:1 2.6:3 2.7:6 2.8:8 2.9:2 3.0:4 3.1:1 3.2:3 3.3:4 3.4:11 3.5:1 3.6:5 3.7:13 3.8:7 3.9:3 4.0:5 4.1:5 4.2:5 4.3:5 4.5:2 4.6:4 4.7:1 4.8:5 4.9:14"),
        ProductCategory.SAUCES_AND_SPREADS to histogram("0.7:1 1.3:1 1.6:2 1.8:1 1.9:1 2.0:1 2.2:1 2.3:1 2.4:1 2.5:1 2.7:3 2.8:3 2.9:1 3.0:4 3.1:2 3.2:1 3.3:1 3.7:3 3.8:1 4.0:1 4.5:1 4.6:1 4.8:2 4.9:1"),
        ProductCategory.DAIRY_AND_YOGURT to histogram("2.4:1 2.7:2 2.8:1 3.2:1 3.4:1 4.2:1 4.4:2 4.5:1 4.9:1"),
        ProductCategory.ICE_CREAM_AND_DESSERTS to histogram("1.5:2 1.6:1 1.7:1 1.8:1 2.0:1 2.1:2 2.2:2 2.3:3 2.4:1 2.5:1 2.6:1 2.8:4 2.9:3 3.0:2 3.1:2 3.2:1 3.3:1 3.4:1 4.2:1 4.5:2 4.6:1 4.7:2"),
    )

    fun compare(category: ProductCategory, score: Double): PeerComparison {
        val histogram = requireNotNull(histograms[category])
        val peerCount = histogram.sumOf { it.count }
        val lower = histogram.filter { it.score < score - 0.001 }.sumOf { it.count }
        val equal = histogram.firstOrNull { kotlin.math.abs(it.score - score) < 0.001 }?.count ?: 0
        val percentile = (((lower + equal * 0.5) / peerCount) * 100.0)
            .roundToInt()
            .coerceIn(1, 99)
        val position = when {
            percentile >= 80 -> "Among category leaders"
            percentile >= 60 -> "Above the category midpoint"
            percentile >= 40 -> "Around the category midpoint"
            percentile >= 20 -> "Below the category midpoint"
            else -> "Near the category bottom"
        }
        return PeerComparison(
            percentile = percentile,
            peerCount = peerCount,
            position = position,
            isSmallSample = peerCount < 30,
        )
    }

    fun peerCount(category: ProductCategory): Int = requireNotNull(histograms[category]).sumOf { it.count }

    private fun histogram(encoded: String): List<ScoreBin> = encoded.split(' ').map { entry ->
        val (score, count) = entry.split(':')
        ScoreBin(score.toDouble(), count.toInt())
    }

    private data class ScoreBin(val score: Double, val count: Int)
}

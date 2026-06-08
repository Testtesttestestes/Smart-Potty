package com.example.ml

import kotlin.math.exp

data class ModelMetrics(
    val precision: Float,
    val recall: Float,
    val f1: Float
)

data class DecisionStump(
    var featureIndex: Int = 0,
    var threshold: Float = 0f,
    var leftValue: Float = 0f,
    var rightValue: Float = 0f
)

class GradientBoostingModel(
    private val numTrees: Int = 10,
    private val learningRate: Float = 0.5f // Higher learning rate for quick fitting on small datasets
) {
    private val trees = mutableListOf<DecisionStump>()
    private var basePrediction: Float = 0f
    private val historicalData = mutableListOf<Pair<EventFeatures, Boolean>>()

    fun fitOnline(newExample: Pair<EventFeatures, Boolean>, maxHistory: Int = 1000) {
        historicalData.add(newExample)
        if (historicalData.size > maxHistory) {
            historicalData.removeAt(0)
        }
        fit(historicalData)
    }

    private fun getFeaturesArray(features: EventFeatures): FloatArray {
        return floatArrayOf(
            features.minutesAfterLastPee.toFloat(),
            features.timeOfDay.toFloat(),
            if (features.sleepState) 1f else 0f,
            features.fluidIntake.toFloat()
        )
    }

    private fun predictRaw(featuresRaw: FloatArray): Float {
        var result = basePrediction
        for (tree in trees) {
            if (featuresRaw[tree.featureIndex] <= tree.threshold) {
                result += learningRate * tree.leftValue
            } else {
                result += learningRate * tree.rightValue
            }
        }
        return result
    }

    fun predict(features: EventFeatures): Float {
        val raw = predictRaw(getFeaturesArray(features))
        return 1f / (1f + exp(-raw)) // Sigmoid activation
    }

    fun fit(data: List<Pair<EventFeatures, Boolean>>) {
        val n = data.size
        if (n == 0) return
        
        trees.clear()
        val X = data.map { getFeaturesArray(it.first) }
        val y = data.map { if (it.second) 1f else 0f }
        
        basePrediction = 0f
        
        val f = FloatArray(n) { basePrediction }
        val residuals = FloatArray(n)
        
        for (iter in 0 until numTrees) {
            for (i in 0 until n) {
                val p = 1f / (1f + exp(-f[i]))
                residuals[i] = y[i] - p // Derivative of log loss w.r.t logits
            }
            
            var bestFeature = 0
            var bestThreshold = 0f
            var bestScore = Float.MAX_VALUE
            var bestLeft = 0f
            var bestRight = 0f
            
            val numFeatures = X[0].size
            for (feat in 0 until numFeatures) {
                val thresholds = X.map { it[feat] }.distinct().sorted()
                for (thresh in thresholds) {
                    var leftSum = 0f
                    var leftCount = 0
                    var rightSum = 0f
                    var rightCount = 0
                    for (i in 0 until n) {
                        if (X[i][feat] <= thresh) {
                            leftSum += residuals[i]
                            leftCount++
                        } else {
                            rightSum += residuals[i]
                            rightCount++
                        }
                    }
                    
                    val leftMean = if (leftCount > 0) leftSum / leftCount else 0f
                    val rightMean = if (rightCount > 0) rightSum / rightCount else 0f
                    
                    var error = 0f
                    for (i in 0 until n) {
                        if (X[i][feat] <= thresh) {
                            val err = residuals[i] - leftMean
                            error += err * err
                        } else {
                            val err = residuals[i] - rightMean
                            error += err * err
                        }
                    }
                    
                    if (error < bestScore) {
                        bestScore = error
                        bestFeature = feat
                        bestThreshold = thresh
                        bestLeft = leftMean
                        bestRight = rightMean
                    }
                }
            }
            
            val stump = DecisionStump(bestFeature, bestThreshold, bestLeft, bestRight)
            trees.add(stump)
            
            for (i in 0 until n) {
                if (X[i][stump.featureIndex] <= stump.threshold) {
                    f[i] += learningRate * stump.leftValue
                } else {
                    f[i] += learningRate * stump.rightValue
                }
            }
        }
    }

    fun evaluate(data: List<Pair<EventFeatures, Boolean>>): ModelMetrics {
        var tp = 0
        var fp = 0
        var fn = 0
        val tn = 0
        for ((features, label) in data) {
            val pred = predict(features) >= 0.5f
            if (pred && label) tp++
            if (pred && !label) fp++
            if (!pred && label) fn++
        }
        val precision = if (tp + fp > 0) tp.toFloat() / (tp + fp) else 0f
        val recall = if (tp + fn > 0) tp.toFloat() / (tp + fn) else 0f
        val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0f
        return ModelMetrics(precision, recall, f1)
    }
}

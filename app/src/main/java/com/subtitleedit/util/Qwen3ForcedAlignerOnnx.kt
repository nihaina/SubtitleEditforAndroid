package com.subtitleedit.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File

/**
 * Runs an exported Qwen3-ForcedAligner token-classification graph.
 *
 * The graph is intentionally kept separate from the Qwen3-ASR decoder. It must expose the
 * following tensors:
 *
 *   input_ids              int64 [1, sequence]
 *   input_features         float [1, mel_bins, frames]
 *   attention_mask         int64 [1, sequence]
 *   feature_attention_mask int64 [1, frames]
 *   logits                 float [1, sequence, timestamp_classes]
 *
 * `timestampPositions` contains the positions of the timestamp placeholders. For each text
 * unit it contains two positions, start then end. The model's timestamp class index is converted
 * to milliseconds with `timestampSegmentMs` (80 ms for the official model).
 */
internal class Qwen3ForcedAlignerOnnx(
    modelFile: File,
    private val inputNames: InputNames = InputNames(),
    private val outputName: String = "logits",
    private val timestampSegmentMs: Long = 80L,
) : Closeable {
    data class InputNames(
        val inputIds: String = "input_ids",
        val inputFeatures: String = "input_features",
        val attentionMask: String = "attention_mask",
        val featureAttentionMask: String = "feature_attention_mask",
    )

    data class Input(
        val inputIds: LongArray,
        /** Log-mel features in [mel_bins][frames] layout, as returned by Qwen's processor. */
        val inputFeatures: Array<FloatArray>,
        val attentionMask: LongArray,
        val featureAttentionMask: LongArray,
        val timestampPositions: IntArray,
        val units: List<String>,
    )

    private val environment = OrtEnvironment.getEnvironment(
        OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,
        "subtitleedit-qwen3-forced-aligner",
    )
    private val session: OrtSession

    init {
        require(modelFile.isFile && modelFile.length() > 0L) {
            "Qwen3-ForcedAligner ONNX 文件不存在或为空"
        }
        session = environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        require(session.inputNames.contains(inputNames.inputIds)) {
            "对齐模型缺少输入 ${inputNames.inputIds}，实际输入：${session.inputNames}"
        }
        require(session.inputNames.contains(inputNames.inputFeatures)) {
            "对齐模型缺少输入 ${inputNames.inputFeatures}，实际输入：${session.inputNames}"
        }
        require(session.inputNames.contains(inputNames.attentionMask)) {
            "对齐模型缺少输入 ${inputNames.attentionMask}，实际输入：${session.inputNames}"
        }
        require(session.inputNames.contains(inputNames.featureAttentionMask)) {
            "对齐模型缺少输入 ${inputNames.featureAttentionMask}，实际输入：${session.inputNames}"
        }
        require(session.outputNames.contains(outputName)) {
            "对齐模型缺少输出 $outputName，实际输出：${session.outputNames}"
        }
    }

    fun align(input: Input): List<ForcedAlignmentUnit> {
        require(input.inputIds.isNotEmpty()) { "对齐输入 input_ids 不能为空" }
        require(input.inputIds.size == input.attentionMask.size) {
            "input_ids 和 attention_mask 长度不一致"
        }
        require(input.inputFeatures.isNotEmpty()) { "对齐输入音频特征不能为空" }
        require(input.inputFeatures.all { it.size == input.inputFeatures.first().size }) {
            "音频特征 mel 维度不一致"
        }
        require(input.featureAttentionMask.size == input.inputFeatures.first().size) {
            "音频特征和 feature_attention_mask 长度不一致"
        }
        require(input.units.size * 2 == input.timestampPositions.size) {
            "每个对齐单元必须对应两个 timestamp 位置"
        }
        require(input.timestampPositions.all { it in input.inputIds.indices }) {
            "timestamp 位置超出 input_ids 范围"
        }

        val features = arrayOf(input.inputFeatures)
        val inputIds = arrayOf(input.inputIds)
        val attentionMask = arrayOf(input.attentionMask)
        val featureMask = arrayOf(input.featureAttentionMask)

        OnnxTensor.createTensor(environment, inputIds).use { idsTensor ->
            OnnxTensor.createTensor(environment, features).use { featuresTensor ->
                OnnxTensor.createTensor(environment, attentionMask).use { maskTensor ->
                    OnnxTensor.createTensor(environment, featureMask).use { featureMaskTensor ->
                        val feeds = mapOf(
                            inputNames.inputIds to idsTensor,
                            inputNames.inputFeatures to featuresTensor,
                            inputNames.attentionMask to maskTensor,
                            inputNames.featureAttentionMask to featureMaskTensor,
                        )
                        session.run(feeds).use { result ->
                            val logits = result.get(outputName).orElseThrow {
                                IllegalStateException("对齐模型没有返回 $outputName")
                            }.value
                            return decodeTimestampLogits(
                                logits = logits,
                                sequenceLength = input.inputIds.size,
                                timestampPositions = input.timestampPositions,
                                units = input.units,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun decodeTimestampLogits(
        logits: Any,
        sequenceLength: Int,
        timestampPositions: IntArray,
        units: List<String>,
    ): List<ForcedAlignmentUnit> {
        val rows = flattenRows(logits)
        require(rows.isNotEmpty()) { "对齐模型 logits 为空" }
        val classes = rows.first().size
        require(classes > 0 && rows.all { it.size == classes }) { "对齐模型 logits 形状无效" }
        val useFullSequence = rows.size == sequenceLength
        require(useFullSequence || rows.size == timestampPositions.size) {
            "对齐模型 logits 行数 ${rows.size} 与序列长度 $sequenceLength 不匹配"
        }

        val rawTimestamps = timestampPositions.mapIndexed { index, position ->
            val row = rows[if (useFullSequence) position else index]
            val classIndex = row.indices.maxByOrNull { row[it] } ?: 0
            classIndex.toLong() * timestampSegmentMs
        }
        val timestamps = fixTimestampAnomalies(rawTimestamps.toLongArray())

        return units.mapIndexed { index, text ->
            val start = timestamps[index * 2]
            val end = maxOf(start + 1L, timestamps[index * 2 + 1])
            ForcedAlignmentUnit(text = text, startTimeMs = start, endTimeMs = end)
        }
    }

    /** Port of Qwen3ForceAlignProcessor.fix_timestamp(). */
    private fun fixTimestampAnomalies(values: LongArray): LongArray {
        if (values.size < 2) return values
        val lengths = IntArray(values.size) { 1 }
        val parents = IntArray(values.size) { -1 }
        for (index in 1 until values.size) {
            for (previous in 0 until index) {
                if (values[previous] <= values[index] &&
                    lengths[previous] + 1 > lengths[index]
                ) {
                    lengths[index] = lengths[previous] + 1
                    parents[index] = previous
                }
            }
        }
        val lis = mutableSetOf<Int>()
        var cursor = lengths.indices.maxByOrNull { lengths[it] } ?: return values
        while (cursor >= 0) {
            lis += cursor
            cursor = parents[cursor]
        }
        val normal = BooleanArray(values.size) { it in lis }
        val result = values.copyOf()
        var index = 0
        while (index < result.size) {
            if (normal[index]) {
                index++
                continue
            }
            val start = index
            while (index < result.size && !normal[index]) index++
            val end = index
            val anomalyCount = end - start
            var leftIndex = start - 1
            while (leftIndex >= 0 && !normal[leftIndex]) leftIndex--
            var rightIndex = end
            while (rightIndex < result.size && !normal[rightIndex]) rightIndex++
            val left = leftIndex.takeIf { it >= 0 }?.let { result[it] }
            val right = rightIndex.takeIf { it < result.size }?.let { result[it] }
            if (anomalyCount <= 2) {
                for (offset in start until end) {
                    result[offset] = when {
                        left == null -> right ?: result[offset]
                        right == null -> left
                        offset - (start - 1) <= end - offset -> left
                        else -> right
                    }
                }
            } else if (left != null && right != null) {
                val step = (right - left).toDouble() / (anomalyCount + 1)
                for (offset in start until end) {
                    result[offset] = (left + step * (offset - start + 1)).toLong()
                }
            } else if (left != null) {
                for (offset in start until end) result[offset] = left
            } else if (right != null) {
                for (offset in start until end) result[offset] = right
            }
        }
        return result
    }

    private fun flattenRows(value: Any): List<FloatArray> {
        fun flatten(node: Any): List<FloatArray> = when (node) {
            is Array<*> -> {
                if (node.isEmpty()) emptyList()
                else if (node.all { it is Number }) {
                    listOf(FloatArray(node.size) { (node[it] as Number).toFloat() })
                } else {
                    node.flatMap { child -> child?.let(::flatten).orEmpty() }
                }
            }
            is FloatArray -> listOf(node)
            is DoubleArray -> listOf(FloatArray(node.size) { node[it].toFloat() })
            else -> error("不支持的 logits 类型：${node::class.java.name}")
        }
        return flatten(value)
    }

    override fun close() {
        session.close()
    }
}

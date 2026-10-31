package com.qwenpaw.chat.util

import com.qwenpaw.chat.R

/**
 * 模型厂商标识匹配。
 *
 * 服务端返回的 provider_id 与模型名写法都不统一：同一供应商的 id 可能是
 * dashscope / aliyun-tokenplan-intl / siliconflow-cn，而模型名才是真正能看出
 * 厂商的那一层（glm-5 / deepseek-v3.2 / kimi-k2.5 ...）。因此这里**先按模型名匹配，
 * 匹配不到再退回按 provider_id 匹配**（如 kimi 的 provider_id 是 moonshot）。
 *
 * 关键字用包含匹配（"glm" 命中 "GLM-5"、"chatglm"），全部命中不了时返回通用模型图标。
 */
object ModelIcons {

    /** [关键字, 图标]，自上而下匹配。只放能明确区分厂商的词。 */
    private val RULES: List<Pair<String, Int>> = listOf(
        "glm" to R.drawable.ic_ai_model_bigmodel,
        "bigmodel" to R.drawable.ic_ai_model_bigmodel,
        "zhipu" to R.drawable.ic_ai_model_bigmodel,
        "deepseek" to R.drawable.ic_ai_model_deepseek,
        "qwen" to R.drawable.ic_ai_model_qwen,
        "qwq" to R.drawable.ic_ai_model_qwen,
        "tongyi" to R.drawable.ic_ai_model_qwen,
        "kimi" to R.drawable.ic_ai_model_kimi,
        "moonshot" to R.drawable.ic_ai_model_kimi,
        "minimax" to R.drawable.ic_ai_model_minimax,
        "abab" to R.drawable.ic_ai_model_minimax,
        "doubao" to R.drawable.ic_ai_model_doubao,
        "volc" to R.drawable.ic_ai_model_doubao,
        "claude" to R.drawable.ic_ai_model_claude,
        "anthropic" to R.drawable.ic_ai_model_claude,
        "chatgpt" to R.drawable.ic_ai_model_chatgpt,
        "openai" to R.drawable.ic_ai_model_chatgpt,
        "gpt" to R.drawable.ic_ai_model_chatgpt,
        "dashscope" to R.drawable.ic_ai_model_qwen,
        "aliyun" to R.drawable.ic_ai_model_qwen
    )

    /** 无匹配时的通用模型图标 */
    val FALLBACK = R.drawable.ic_switch_model

    /**
     * 按 provider_id 与模型名匹配图标。
     *
     * @param providerId 供应商 id（如 bigmodel / moonshot），可为 null
     * @param modelName 模型名或模型 id（如 GLM-5 / glm-5）
     * @return drawable 资源 id，无匹配时返回 [FALLBACK]
     */
    fun of(providerId: String?, modelName: String?): Int =
        match(modelName) ?: match(providerId) ?: FALLBACK

    private fun match(text: String?): Int? {
        val lower = text?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return RULES.firstOrNull { lower.contains(it.first) }?.second
    }
}

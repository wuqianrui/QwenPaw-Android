package com.qwenpaw.chat.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qwenpaw.chat.R
import com.qwenpaw.chat.model.ModelInfo
import com.qwenpaw.chat.model.SwitchModelRequest
import com.qwenpaw.chat.network.ModelService
import com.qwenpaw.chat.util.ModelIcons
import com.qwenpaw.chat.util.TokenManager
import kotlinx.coroutines.launch

/**
 * 切换模型弹窗(作用域为当前 agent)。
 *
 * 仅列出各供应商的 extra_models(已激活模型),每项显示厂商标识 + 模型名,
 * 供应商以小字灰色显示在模型名下方;选中后调用 PUT /api/models/active
 * (scope=agent,参考 docs/assets/api/model.md)。
 */
object ModelSwitchDialog {

    /** 一个可选项:已激活模型及其所属供应商 */
    private data class Candidate(
        val providerId: String,
        val providerName: String,
        val model: ModelInfo
    ) {
        /** 弹窗主标题:模型名缺失时退回模型 id */
        val label: String get() = model.name?.takeIf { it.isNotBlank() } ?: model.id.orEmpty()
    }

    /**
     * 拉取模型列表并弹出切换弹窗。
     *
     * @param agentId 切换作用域所属的 agent
     * @param onSwitched 切换成功后的回调(主线程),参数为切换到的供应商 id 与模型(含 supports_image)
     */
    fun show(
        activity: AppCompatActivity,
        agentId: String,
        onSwitched: ((providerId: String, model: ModelInfo) -> Unit)? = null
    ) {
        val tokenManager = TokenManager.getInstance(activity)
        val baseUrl = tokenManager.getBaseUrl()
        if (baseUrl.isNullOrEmpty()) {
            Toast.makeText(activity, "当前账号缺少服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "正在加载模型列表...", Toast.LENGTH_SHORT).show()

        val service = ModelService(baseUrl = baseUrl, authToken = tokenManager.getToken())
        activity.lifecycleScope.launch {
            service.getModels()
                .onSuccess { providers ->
                    // 仅取 extra_models(已激活模型)
                    val candidates = mutableListOf<Candidate>()
                    providers.forEach { provider ->
                        val providerId = provider.id?.takeIf { it.isNotBlank() } ?: return@forEach
                        val providerName = provider.name?.takeIf { it.isNotBlank() } ?: providerId
                        provider.extra_models.orEmpty().forEach { model ->
                            if (!model.id.isNullOrEmpty()) {
                                candidates.add(Candidate(providerId, providerName, model))
                            }
                        }
                    }
                    if (candidates.isEmpty()) {
                        Toast.makeText(activity, "没有可用的已激活模型", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("切换模型")
                        .setAdapter(CandidateAdapter(activity, candidates)) { _, which ->
                            switch(activity, service, agentId, candidates[which], onSwitched)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                .onFailure { e ->
                    Toast.makeText(activity, "加载模型列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** PUT /api/models/active:按 agent 作用域切换激活模型 */
    private fun switch(
        activity: AppCompatActivity,
        service: ModelService,
        agentId: String,
        candidate: Candidate,
        onSwitched: ((providerId: String, model: ModelInfo) -> Unit)?
    ) {
        val request = SwitchModelRequest(
            provider_id = candidate.providerId,
            model = candidate.model.id.orEmpty(),
            scope = "agent",
            agent_id = agentId
        )
        activity.lifecycleScope.launch {
            service.switchActiveModel(request)
                .onSuccess {
                    Toast.makeText(activity, "已切换到 ${candidate.label}", Toast.LENGTH_SHORT).show()
                    onSwitched?.invoke(candidate.providerId, candidate.model)
                }
                .onFailure { e ->
                    Toast.makeText(activity, "切换模型失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 选项渲染:左侧按模型名匹配的厂商标识,右侧模型名 + 小字灰色供应商 */
    private class CandidateAdapter(
        private val context: Context,
        private val items: List<Candidate>
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_model_option, parent, false)
            val candidate = items[position]
            view.findViewById<ImageView>(R.id.imageViewModelIcon).setImageResource(
                ModelIcons.of(candidate.providerId, candidate.label)
            )
            // 支持图片输入(supports_image)的模型显示眼睛角标
            view.findViewById<ImageView>(R.id.imageViewModelEye).visibility =
                if (candidate.model.supports_image == true) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.textViewModelName).text = candidate.label
            view.findViewById<TextView>(R.id.textViewProviderName).text = candidate.providerName
            return view
        }
    }
}

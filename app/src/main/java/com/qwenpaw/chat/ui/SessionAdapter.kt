package com.qwenpaw.chat.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.qwenpaw.chat.R
import com.qwenpaw.chat.model.ChatSession
import kotlin.math.abs

/**
 * 会话列表适配器
 *
 * 会话条目展示:头像块(会话名首字) + 会话名 + 最后更新时间 + 置顶标签;
 * 条目可左滑露出右侧操作菜单(目前仅「删除」),同一时刻只允许一个条目展开。
 */
class SessionAdapter(
    private val onSessionClick: (ChatSession) -> Unit,
    private val onSessionDelete: (ChatSession) -> Unit
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    private val sessions = mutableListOf<ChatSession>()

    // 当前左滑展开的条目,用于在滑动/点击其它条目或列表刷新时收起
    private var openHolder: SessionViewHolder? = null

    fun submitList(list: List<ChatSession>) {
        openHolder = null
        sessions.clear()
        sessions.addAll(list)
        notifyDataSetChanged()
    }

    /** 收起已展开的左滑菜单(列表滚动等场景调用) */
    fun closeOpenMenu() {
        openHolder?.closeMenu()
        openHolder = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(
            itemView = view,
            onSessionClick = onSessionClick,
            onSessionDelete = onSessionDelete,
            onMenuExpand = { holder ->
                val previous = openHolder
                if (previous != null && previous !== holder) previous.closeMenu()
                openHolder = holder
            }
        )
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(sessions[position])
    }

    override fun onViewRecycled(holder: SessionViewHolder) {
        if (openHolder === holder) openHolder = null
    }

    override fun getItemCount(): Int = sessions.size

    class SessionViewHolder(
        itemView: View,
        private val onSessionClick: (ChatSession) -> Unit,
        private val onSessionDelete: (ChatSession) -> Unit,
        private val onMenuExpand: (SessionViewHolder) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val content: View = itemView.findViewById(R.id.layoutSessionContent)
        private val menu: View = itemView.findViewById(R.id.layoutSessionDelete)
        private val textViewAvatar: TextView = itemView.findViewById(R.id.textViewSessionAvatar)
        private val textViewName: TextView = itemView.findViewById(R.id.textViewSessionName)
        private val textViewTime: TextView = itemView.findViewById(R.id.textViewSessionTime)
        private val textViewPinned: TextView = itemView.findViewById(R.id.textViewSessionPinned)

        /** 菜单宽度(布局中为固定 dp),内容层最多左移这么多 */
        private val menuWidth: Float = menu.layoutParams.width.toFloat()
        private val touchSlop: Int = ViewConfiguration.get(itemView.context).scaledTouchSlop

        // 内容层背景:静止态(四角圆角) / 滑动与展开态(右侧直角,避免接缝处出现弧形缺口)
        private val contentBgIdle = ContextCompat.getDrawable(itemView.context, R.drawable.bg_account_item)
        private val contentBgSwiped = ContextCompat.getDrawable(itemView.context, R.drawable.bg_account_item_swiped)

        private var session: ChatSession? = null

        // 本次手势的起点与位移基准
        private var downRawX = 0f
        private var downRawY = 0f
        private var downTranslation = 0f

        // 是否已判定为横向滑动 / 是否已放弃本次手势(判定为纵向滚动)
        private var dragging = false
        private var abandoned = false

        init {
            itemView.setOnClickListener { session?.let(onSessionClick) }
            menu.setOnClickListener { session?.let(onSessionDelete) }
            content.setOnTouchListener { _, event -> handleTouch(event) }
        }

        fun bind(session: ChatSession) {
            this.session = session

            // 复用视图时复位左滑状态
            content.animate().cancel()
            content.translationX = 0f
            content.isPressed = false
            content.background = contentBgIdle
            // 菜单静止时不可见:内容层的圆角边缘与菜单边缘抗锯齿叠加会透出红色轮廓
            menu.visibility = View.INVISIBLE
            dragging = false
            abandoned = false

            val name = session.name?.takeIf { it.isNotBlank() } ?: "未命名对话"
            textViewAvatar.text = name.take(1)
            textViewName.text = name
            textViewTime.text = formatTime(session.updated_at ?: session.created_at)
            textViewPinned.visibility = if (session.pinned == true) View.VISIBLE else View.GONE
        }

        /** 收起左滑菜单 */
        fun closeMenu() {
            animateTo(0f)
        }

        /**
         * 内容层触摸:横向拖动时把内容层左移露出菜单,纵向手势交还给 RecyclerView 滚动。
         *
         * 返回 true 表示自行消费事件(避免事件被 ViewGroup 中断导致丢失后续 MOVE)。
         */
        private fun handleTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downTranslation = content.translationX
                    dragging = false
                    abandoned = false
                    content.isPressed = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abandoned) return false

                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging) {
                        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            dragging = true
                            content.isPressed = false
                            // 滑动期间:内容层右边缘改直角,菜单左边缘本就是直角,两者拼接无缺口
                            content.background = contentBgSwiped
                            menu.visibility = View.VISIBLE
                            // 通知适配器收起其它条目,并阻止 RecyclerView/抽屉把本次手势当成滚动或侧滑
                            onMenuExpand(this)
                            itemView.parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (abs(dy) > touchSlop) {
                            // 纵向手势:放弃滑动,交给 RecyclerView
                            abandoned = true
                            content.isPressed = false
                            return false
                        }
                    }

                    if (dragging) {
                        content.translationX = (downTranslation + dx).coerceIn(-menuWidth, 0f)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    content.isPressed = false
                    when {
                        dragging -> settle()
                        abandoned -> Unit
                        // 菜单已展开时点内容层先收起菜单,否则视为点击进入会话
                        content.translationX != 0f -> closeMenu()
                        else -> itemView.performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    content.isPressed = false
                    if (dragging) settle()
                    return true
                }
            }
            return false
        }

        /** 抬手后按滑动距离决定展开还是收起 */
        private fun settle() {
            if (content.translationX < -menuWidth / 2f) animateTo(-menuWidth) else animateTo(0f)
        }

        private fun animateTo(target: Float) {
            content.animate().cancel()
            if (target != 0f) menu.visibility = View.VISIBLE
            content.animate()
                .translationX(target)
                .setDuration(180)
                .withEndAction {
                    // 收起完成后恢复静止样式,让内容层完整盖住菜单
                    if (target == 0f) {
                        menu.visibility = View.INVISIBLE
                        content.background = contentBgIdle
                    }
                }
                .start()
        }

        /** ISO 时间(2026-06-27T13:01:59.593984Z) → "2026-06-27 13:01" */
        private fun formatTime(iso: String?): String {
            if (iso.isNullOrBlank()) return ""
            return iso.take(16).replace("T", " ")
        }
    }
}

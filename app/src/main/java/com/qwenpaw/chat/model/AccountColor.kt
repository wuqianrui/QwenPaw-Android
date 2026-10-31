package com.qwenpaw.chat.model

import androidx.annotation.ColorRes
import com.qwenpaw.chat.R

/**
 * 账号环境标识色
 *
 * 每个账号在首次登录成功后会被分配一个标识色,用于在切换账号时提供视觉上的区分,
 * 与别名、用户名一起构成账号(环境)的识别要素。
 *
 * 色值统一维护在 `res/values/colors.xml` 的 system_* 色板中,此处只持有资源引用。
 *
 * @property colorRes 颜色资源 id
 * @property displayName 语义色名称(与系统色板命名一致)
 * @property description 语义说明
 */
enum class AccountColor(
    @ColorRes val colorRes: Int,
    val displayName: String,
    val description: String
) {
    RED(R.color.system_red, "systemRed", "高警示、强识别"),
    ORANGE(R.color.system_orange, "systemOrange", "温暖、活跃"),
    YELLOW(R.color.system_yellow, "systemYellow", "明亮、提醒"),
    GREEN(R.color.system_green, "systemGreen", "自然、通过"),
    TEAL(R.color.system_teal, "systemTeal", "清爽、科技感"),
    BLUE(R.color.system_blue, "systemBlue", "默认、信任"),
    PURPLE(R.color.system_purple, "systemPurple", "创意、个性"),
    PINK(R.color.system_pink, "systemPink", "柔和、点缀");

    companion object {
        /** 未分配颜色的下标 */
        const val NO_COLOR_INDEX = -1

        /**
         * 根据下标取颜色,越界或未分配时返回 null。
         */
        fun fromIndex(index: Int): AccountColor? =
            values().getOrNull(index)

        /**
         * 根据名称取颜色(用于持久化数据反序列化),忽略大小写。
         */
        fun fromName(name: String?): AccountColor? {
            if (name.isNullOrBlank()) return null
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

        /**
         * 取某个下标的下一个颜色下标(循环递增),用于分配时避免与上一个账号撞色。
         *
         * @param lastIndex 上次分配的下标,首次分配传 [NO_COLOR_INDEX]
         */
        fun nextIndex(lastIndex: Int): Int {
            val size = values().size
            return (lastIndex + 1).mod(size)
        }
    }
}

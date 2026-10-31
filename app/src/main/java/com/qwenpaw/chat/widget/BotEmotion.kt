package com.qwenpaw.chat.widget

/**
 * Bot 情绪状态名（24 个，对应 replica/src/tables.js 的 EYE_PLAYLIST 键）。
 *
 * 存在的意义：外部调用 [GrokBotView.setBotState] / [AIFaceView.setState] 时
 * 用 `BotEmotion.HAPPY` 代替手写 "happy"。手写字符串拼错不会报错 ——
 * 无效状态名是被静默忽略的，界面表现只是"切了没反应"，很难排查。
 *
 * [Tables] 里所有情绪键都取自这里，所以这个对象是状态名的唯一定义处：
 * 增删情绪只改这一处。
 */
object BotEmotion {
    const val SLEEPING = "sleeping" // 睡眠
    const val WAKING = "waking" // 唤醒
    const val IDLE = "idle" // 空闲
    const val LISTENING = "listening" // 监听
    const val THINKING = "thinking" // 思考
    const val SEARCHING = "searching" // 搜索
    const val WORKING = "working" // 工作
    const val EXCITED = "excited" // 兴趣
    const val SURPRISED = "surprised" // 惊喜
    const val SUSPICIOUS = "suspicious" // 怀疑
    const val ANGRY = "angry" // 愤怒
    const val DROWSY = "drowsy" // 疲睡
    const val HAPPY = "happy" // 快乐
    const val CURIOUS = "curious" // 好奇
    const val CONFUSED = "confused" // 混淆
    const val BORED = "bored" // 无聊
    const val PROUD = "proud" // 傲慢
    const val SHY = "shy" // 害羞
    const val SAD = "sad" // 悲伤
    const val LAUGHING = "laughing" // 喜笑
    const val SCARED = "scared" // 恐怕
    const val PLAYFUL = "playful" // 播放
    const val CELEBRATE = "celebrate" // 庆祝
    const val DRAGGING = "dragging" // 拖动
}

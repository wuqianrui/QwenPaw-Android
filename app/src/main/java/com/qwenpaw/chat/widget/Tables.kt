package com.qwenpaw.chat.widget

/**
 * 常量表（对应 replica/src/tables.js）。
 * 三张核心表（EYE_PLAYLIST / EYE_HOLD_MS / BLINK_MS）24 个状态全覆盖，
 * 缺一个键 getValue() 就会在运行期抛异常 —— 已逐键核对。
 *
 * 情绪键统一取自 [BotEmotion]（各状态含义见那边的注释），改名字只动常量。
 */
internal object Tables {

    /** [freq, damp] —— 每条通道的脾气。damp < 1 会回弹。 */
    val SPRINGS: Map<String, FloatArray> = mapOf(
        "spin" to floatArrayOf(5f, 0.9f),
        "x" to floatArrayOf(3.5f, 1f),
        "y" to floatArrayOf(4f, 1f),
        "squash" to floatArrayOf(10f, 0.8f),
        "blink" to floatArrayOf(26f, 1f),
        "eyeScale" to floatArrayOf(9f, 0.85f),
        "gazeX" to floatArrayOf(13f, 1f),
        "gazeY" to floatArrayOf(13f, 1f),
        "overlay" to floatArrayOf(14f, 1f),
        "overlayMix" to floatArrayOf(11f, 1f),
        "shape" to floatArrayOf(10f, 1f),
        "spinTurn" to floatArrayOf(6.2f, 1f)
    )

    /** 每个状态用哪些眼睛。25 个形状的语义分组就是情绪本身。 */
    val EYE_PLAYLIST: Map<String, IntArray> = mapOf(
        BotEmotion.SLEEPING to intArrayOf(13, 22, 4),
        BotEmotion.WAKING to intArrayOf(13),
        BotEmotion.IDLE to intArrayOf(0, 8),
        BotEmotion.LISTENING to intArrayOf(10, 1, 19),
        BotEmotion.THINKING to intArrayOf(8, 16, 14, 17, 5),
        BotEmotion.SEARCHING to intArrayOf(15, 9, 3, 20, 12, 18),
        BotEmotion.WORKING to intArrayOf(7, 16, 11, 10),
        BotEmotion.EXCITED to intArrayOf(2, 17, 21, 3, 11),
        BotEmotion.SURPRISED to intArrayOf(3, 21),
        BotEmotion.SUSPICIOUS to intArrayOf(14, 5, 23),
        BotEmotion.ANGRY to intArrayOf(7, 16),
        BotEmotion.DROWSY to intArrayOf(4, 22, 13),
        BotEmotion.HAPPY to intArrayOf(2, 11, 17, 19),
        BotEmotion.CURIOUS to intArrayOf(3, 21, 0, 15),
        BotEmotion.CONFUSED to intArrayOf(14, 5, 8),
        BotEmotion.BORED to intArrayOf(4, 22, 0),
        BotEmotion.PROUD to intArrayOf(15, 8, 2),
        BotEmotion.SHY to intArrayOf(0, 24, 13),
        BotEmotion.SAD to intArrayOf(4, 13, 22),
        BotEmotion.LAUGHING to intArrayOf(2, 11, 17),
        BotEmotion.SCARED to intArrayOf(3, 21),
        BotEmotion.PLAYFUL to intArrayOf(2, 17, 11, 8),
        BotEmotion.CELEBRATE to intArrayOf(2, 8, 17),
        BotEmotion.DRAGGING to intArrayOf(3, 15, 0)
    )

    /** 每个状态的换眼间隔 [min, max] ms。间隔越短 = 越不安。 */
    val EYE_HOLD_MS: Map<String, FloatArray> = mapOf(
        BotEmotion.SLEEPING to floatArrayOf(6000f, 10000f),
        BotEmotion.WAKING to floatArrayOf(800f, 800f),
        BotEmotion.IDLE to floatArrayOf(9000f, 16000f),
        BotEmotion.LISTENING to floatArrayOf(2800f, 5000f),
        BotEmotion.THINKING to floatArrayOf(2000f, 3600f),
        BotEmotion.SEARCHING to floatArrayOf(1000f, 1800f),
        BotEmotion.WORKING to floatArrayOf(1800f, 3200f),
        BotEmotion.EXCITED to floatArrayOf(1100f, 2000f),
        BotEmotion.SURPRISED to floatArrayOf(2500f, 4000f),
        BotEmotion.SUSPICIOUS to floatArrayOf(2600f, 4500f),
        BotEmotion.ANGRY to floatArrayOf(2200f, 3800f),
        BotEmotion.DROWSY to floatArrayOf(4000f, 8000f),
        BotEmotion.HAPPY to floatArrayOf(2500f, 4500f),
        BotEmotion.CURIOUS to floatArrayOf(1800f, 3200f),
        BotEmotion.CONFUSED to floatArrayOf(2200f, 3800f),
        BotEmotion.BORED to floatArrayOf(3500f, 6000f),
        BotEmotion.PROUD to floatArrayOf(3500f, 6000f),
        BotEmotion.SHY to floatArrayOf(3000f, 5500f),
        BotEmotion.SAD to floatArrayOf(4000f, 7000f),
        BotEmotion.LAUGHING to floatArrayOf(1200f, 2400f),
        BotEmotion.SCARED to floatArrayOf(900f, 1800f),
        BotEmotion.PLAYFUL to floatArrayOf(1500f, 3000f),
        BotEmotion.CELEBRATE to floatArrayOf(1400f, 2600f),
        BotEmotion.DRAGGING to floatArrayOf(1600f, 3000f)
    )

    /** 眨眼节奏 [min, max] ms。null = 这个状态不眨眼（睡觉、困倦）。 */
    val BLINK_MS: Map<String, FloatArray?> = mapOf(
        BotEmotion.SLEEPING to null,
        BotEmotion.WAKING to null,
        BotEmotion.IDLE to floatArrayOf(6000f, 14000f),
        BotEmotion.LISTENING to floatArrayOf(3000f, 7000f),
        BotEmotion.THINKING to floatArrayOf(3500f, 7000f),
        BotEmotion.SEARCHING to floatArrayOf(1600f, 4000f),
        BotEmotion.WORKING to floatArrayOf(2800f, 5500f),
        BotEmotion.EXCITED to floatArrayOf(2000f, 4000f),
        BotEmotion.SURPRISED to floatArrayOf(1800f, 3500f),
        BotEmotion.SUSPICIOUS to floatArrayOf(4500f, 8000f),
        BotEmotion.ANGRY to floatArrayOf(3500f, 7000f),
        BotEmotion.DROWSY to null,
        BotEmotion.HAPPY to floatArrayOf(2500f, 5000f),
        BotEmotion.CURIOUS to floatArrayOf(2500f, 5500f),
        BotEmotion.CONFUSED to floatArrayOf(2800f, 5500f),
        BotEmotion.BORED to floatArrayOf(4000f, 8000f),
        BotEmotion.PROUD to floatArrayOf(3500f, 7000f),
        BotEmotion.SHY to floatArrayOf(3000f, 6000f),
        BotEmotion.SAD to floatArrayOf(4000f, 8000f),
        BotEmotion.LAUGHING to floatArrayOf(2500f, 5000f),
        BotEmotion.SCARED to floatArrayOf(1200f, 3000f),
        BotEmotion.PLAYFUL to floatArrayOf(2000f, 4500f),
        BotEmotion.CELEBRATE to floatArrayOf(2200f, 4500f),
        BotEmotion.DRAGGING to floatArrayOf(2200f, 4500f)
    )

    val ONBOARDING = arrayOf(
        BotEmotion.CURIOUS,
        BotEmotion.HAPPY,
        BotEmotion.PLAYFUL,
        BotEmotion.EXCITED,
        BotEmotion.LISTENING,
        BotEmotion.PROUD,
        BotEmotion.LAUGHING,
        BotEmotion.SHY
    )
    const val ONBOARDING_MS = 1200f

    /** 是否为可用的情绪状态名（24 个，键集即 [EYE_PLAYLIST]）。 */
    fun isState(name: String): Boolean = EYE_PLAYLIST.containsKey(name)

    fun onboardMood(n: Int): String =
        if (n % 2 == 0) BotEmotion.IDLE else ONBOARDING[((n - 1) / 2) % ONBOARDING.size]

    /** 登录页默认姿态：本体拧过 17°，眼睛按 home(33,-19,38) 做相对旋转。 */
    val POSE = floatArrayOf(17f, -14f, 29f)
    val POSE_HOME = floatArrayOf(33f, -19f, 38f)

    /** 会主动转圈的情绪与只会蹦的情绪。 */
    val SPINNY = setOf(BotEmotion.HAPPY, BotEmotion.EXCITED, BotEmotion.PROUD)
    val BOUNCY = setOf(BotEmotion.PLAYFUL)
    val WINK_STATES = setOf(
        BotEmotion.IDLE,
        BotEmotion.HAPPY,
        BotEmotion.EXCITED,
        BotEmotion.CURIOUS,
        BotEmotion.PLAYFUL
    )

    /** FACE_TUNE（tables.js）：注意 gap(1.18) 是水平间距乘数，不是眼睛宽度。 */
    const val FACE_SIZE = 0.86f
    const val FACE_GAP = 1.18f
    const val FACE_HEIGHT = 1f
    const val FACE_EYE_W = 0.96f
    const val FACE_EYE_H = 0.92f
}

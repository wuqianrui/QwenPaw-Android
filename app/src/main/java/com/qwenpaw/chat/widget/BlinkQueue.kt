package com.qwenpaw.chat.widget

/**
 * 眨眼队列（对应 eyes.js queueBlink）。
 *
 * 一次眨眼是四个时间戳关键值（不是一条曲线）：
 * 0ms→0.05（闭上）70ms→0.05（保持）150ms→1.08（过冲睁开）300ms→1（回落）。
 * 14% 概率追加一次双眨。
 */
internal class BlinkQueue {

    private class Key(val at: Float, val v: Float)
    private val q = ArrayDeque<Key>()

    fun queue(now: Float) {
        q.addLast(Key(now, 0.05f))
        q.addLast(Key(now + 70f, 0.05f))
        q.addLast(Key(now + 150f, 1.08f))
        q.addLast(Key(now + 300f, 1f))
        if (Math.random() < 0.14) {
            q.addLast(Key(now + 370f, 0.05f))
            q.addLast(Key(now + 480f, 1f))
        }
    }

    /** 只取最新一个到期值，中间帧直接丢弃 —— 不追补，避免卡顿后连续闪。 */
    fun consume(now: Float): Float? {
        var k: Float? = null
        while (q.isNotEmpty() && now >= q.first().at) k = q.removeFirst().v
        return k
    }

    fun isEmpty() = q.isEmpty()
}

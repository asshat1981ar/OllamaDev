package com.example.data

/** Gates DB writes for an in-progress streamed step so a fast token stream doesn't turn into
 *  a write per NDJSON line; only emits once the text has grown enough or enough time passed. */
class StreamThrottle(private val minCharDelta: Int = 20, private val minMillis: Long = 150) {
    private var lastLength = 0
    private var lastEmitTime = 0L

    fun shouldEmit(current: String): Boolean {
        val now = System.currentTimeMillis()
        if (current.length - lastLength >= minCharDelta || now - lastEmitTime >= minMillis) {
            lastLength = current.length
            lastEmitTime = now
            return true
        }
        return false
    }
}

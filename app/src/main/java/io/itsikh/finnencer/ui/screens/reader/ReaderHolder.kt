package io.itsikh.finnencer.ui.screens.reader

/**
 * In-process singleton that hands a long-form summary payload from any
 * caller (Tasks card, Article Detail card, …) into the dedicated Reader
 * screen. We can't pass the body through nav arguments because summaries
 * can run to thousands of characters and Android caps route URIs at a few
 * KB; pushing the blob through here keeps the nav signature trivial.
 *
 * Consume-once — [take] clears the slot. Thread-safe via `@Volatile`.
 */
object ReaderHolder {

    data class Payload(
        val title: String,
        val body: String,
        val attribution: String?,
    )

    @Volatile private var pending: Payload? = null

    fun store(payload: Payload) { pending = payload }
    fun take(): Payload? {
        val p = pending
        pending = null
        return p
    }
}

package cz.starenka.jiffy

object ServerState {
    @Volatile var boundHost: String? = null
    val running get() = boundHost != null
    @Volatile var error: String? = null
    @Volatile var fingerprint: String? = null
    @Volatile var listener: (() -> Unit)? = null
    fun changed() { listener?.invoke() }
}

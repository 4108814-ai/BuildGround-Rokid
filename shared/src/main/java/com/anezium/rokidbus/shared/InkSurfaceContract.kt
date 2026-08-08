package com.anezium.rokidbus.shared

object InkSurfaceContract {
    const val KIND = "ink"

    const val EVENT_READY = "ready"
    const val EVENT_ACTION = "action"
    const val EVENT_CLOSED = "closed"
    const val EVENT_RESYNC = "resync"
    const val EVENT_ERROR = "error"

    const val CLOSE_USER = "user"
    const val CLOSE_PLUGIN = "plugin"
    const val CLOSE_REPLACED = "replaced"
    const val CLOSE_LINK_LOST = "link_lost"
    const val CLOSE_RENDERER_ERROR = "renderer_error"

    private val closeReasons = setOf(
        CLOSE_USER,
        CLOSE_PLUGIN,
        CLOSE_REPLACED,
        CLOSE_LINK_LOST,
        CLOSE_RENDERER_ERROR,
    )

    fun isCloseReason(value: String): Boolean = value in closeReasons
}

/** One foreground slot, regardless of whether the caller speaks card or Ink paths. */
object ForegroundSurfacePathPolicy {
    fun isShowOrUpdate(path: String): Boolean = path == BusPaths.SURFACE_SHOW ||
        path == BusPaths.SURFACE_UPDATE ||
        path == BusPaths.INK_SHOW ||
        path == BusPaths.INK_UPDATE

    fun isShow(path: String): Boolean = path == BusPaths.SURFACE_SHOW || path == BusPaths.INK_SHOW
}

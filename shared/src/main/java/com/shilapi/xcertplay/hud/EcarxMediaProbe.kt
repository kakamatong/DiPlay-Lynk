package com.shilapi.xcertplay.hud

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Diagnostic probe for the opaque ECARX media contract.
 *
 * The public ECARX adaptapi declares `updatePlaybackInfo(IPlaybackInfo)` and lyric getters, but the
 * Lynk 05 system image is older than that source. This probe dumps what the car actually exposes,
 * selects the online source, registers the media callback and pushes repeated playback frames with
 * a lyric line, so the driver can see whether the cluster's own music card reads the lyric field.
 *
 * Nothing here is used by the live output; a failure only affects the diagnostic result.
 */
internal object EcarxMediaProbe {
    private const val PROBE_TITLE = "探针歌曲"
    private const val PROBE_ARTIST = "DiPlay"
    private const val PROBE_ALBUM = "ECARX Probe"
    private const val PROBE_LYRIC = "歌词字段测试"
    private const val PROBE_UUID = "diplay-ecarx-media-probe"
    private const val SOURCE_ONLINE = 6
    private const val FRAMES = 6

    private val events = mutableListOf<String>()
    private var activeMedia: Any? = null
    private var activeCallback: Any? = null
    private var activeCallbackType: Class<*>? = null

    fun run(context: Context): String {
        events.clear()
        val dim = EcarxDimApi.createDimInteraction(context)
            ?: return "DimInteraction.create() returned null"
        val media = EcarxDimApi.mediaInteraction(dim)
            ?: return "getMediaInteraction() returned null"
        val playbackClass = runCatching { Class.forName(EcarxCapabilities.MEDIA_PLAYBACK_INFO) }.getOrNull()
            ?: return "IPlaybackInfo class not found: " + EcarxCapabilities.MEDIA_PLAYBACK_INFO
        val mediaClass = runCatching { Class.forName(EcarxCapabilities.MEDIA_INFO) }.getOrNull()
            ?: return "IMedia class not found: " + EcarxCapabilities.MEDIA_INFO
        val callbackClass = runCatching { Class.forName(EcarxCapabilities.MEDIA_CALLBACK) }.getOrNull()
            ?: return "IMediaInteractionCallback class not found: " + EcarxCapabilities.MEDIA_CALLBACK

        val report = StringBuilder()
        report.appendLine("media: " + media.javaClass.name)
        report.appendLine("IMediaInteraction methods:")
        report.appendLine(methodList(media.javaClass))
        report.appendLine("IPlaybackInfo methods:")
        report.appendLine(methodList(playbackClass))
        report.appendLine("IMedia methods:")
        report.appendLine(methodList(mediaClass))
        report.appendLine()

        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader, arrayOf(callbackClass), CallbackHandler(events),
        )
        activeMedia = media
        activeCallback = callback
        activeCallbackType = callbackClass

        val setCallback = EcarxDimApi.setMediaInteractionCallback(media, callback, callbackClass)
        val sourceList = EcarxDimApi.updateMediaSourceTypeList(
            media, intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        )
        val currentSource = EcarxDimApi.updateCurrentSourceType(media, SOURCE_ONLINE)
        val item = newMedia(mediaClass, PROBE_TITLE, PROBE_ARTIST, PROBE_ALBUM, PROBE_LYRIC)
        val playlist = EcarxDimApi.updatePlaylist(media, SOURCE_ONLINE, listOf(item))

        report.appendLine("setMediaInteractionCallback: " + result(setCallback))
        report.appendLine("updateMediaSourceTypeList(0..11): " + result(sourceList))
        report.appendLine("updateCurrentSourceType(6): " + result(currentSource))
        report.appendLine("updatePlaylist(6,1): " + result(playlist))
        report.appendLine()

        for (index in 0 until FRAMES) {
            val lyric = PROBE_LYRIC + " " + (index + 1)
            val info = newInfo(playbackClass, PROBE_TITLE, PROBE_ARTIST, PROBE_ALBUM, lyric)
            val push = EcarxDimApi.updatePlaybackInfo(media, info, playbackClass)
            val progress = EcarxDimApi.updateCurrentProgress(media, 30_000L + index * 1_000L)
            report.appendLine("frame $index: push=" + result(push) + " progress=" + result(progress))
            if (index < FRAMES - 1) runCatching { Thread.sleep(1_000L) }
        }
        report.appendLine()

        val seen = synchronized(events) { events.toList() }
        report.appendLine("media callbacks: " + if (seen.isEmpty()) "none" else seen.size)
        seen.forEach { report.appendLine("  " + it) }
        report.appendLine()
        report.appendLine("仪表上应出现：")
        report.appendLine("歌名：" + PROBE_TITLE)
        report.appendLine("歌手：" + PROBE_ARTIST)
        report.appendLine("歌词字段：" + PROBE_LYRIC + " 1.." + FRAMES)
        report.appendLine()
        report.appendLine("若只显示《" + PROBE_TITLE + "》而没有《" + PROBE_LYRIC + "》，说明原生卡片不读歌词字段；")
        report.appendLine("若显示《" + PROBE_LYRIC + "》，说明歌词字段可用。")
        return report.toString()
    }

    /** Pushes an empty frame and releases the probe callback; the next real update replaces it. */
    fun clear(context: Context): String {
        val media = activeMedia
        val callback = activeCallback
        val callbackType = activeCallbackType
        activeMedia = null
        activeCallback = null
        activeCallbackType = null
        val report = StringBuilder()
        if (media != null && callback != null && callbackType != null) {
            report.appendLine("unsetMediaInteractionCallback: " +
                result(EcarxDimApi.unsetMediaInteractionCallback(media, callback, callbackType)))
        }
        val dim = EcarxDimApi.createDimInteraction(context)
        val current = dim?.let(EcarxDimApi::mediaInteraction)
        val playbackClass = runCatching { Class.forName(EcarxCapabilities.MEDIA_PLAYBACK_INFO) }.getOrNull()
        if (current != null && playbackClass != null) {
            val info = newInfo(playbackClass, "", "", "", "")
            report.appendLine("clear updatePlaybackInfo: " +
                result(EcarxDimApi.updatePlaybackInfo(current, info, playbackClass)))
        }
        return report.toString().ifEmpty { "probe was not active" }
    }

    private fun result(value: EcarxDimApi.EcarxResult): String =
        (if (value.ok) "ok" else "failed") + " (" + value.reason + ")"

    private fun newInfo(
        playbackClass: Class<*>,
        title: String,
        artist: String,
        album: String,
        lyric: String,
    ): Any = Proxy.newProxyInstance(
        playbackClass.classLoader,
        arrayOf(playbackClass),
        PlaybackInfoHandler(title, artist, album, lyric),
    )

    private fun newMedia(
        mediaClass: Class<*>,
        title: String,
        artist: String,
        album: String,
        lyric: String,
    ): Any = Proxy.newProxyInstance(
        mediaClass.classLoader,
        arrayOf(mediaClass),
        MediaHandler(title, artist, album, lyric),
    )

    private class PlaybackInfoHandler(
        private val title: String,
        private val artist: String,
        private val album: String,
        private val lyric: String,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getTitle" -> title
            "getArtist" -> artist
            "getAlbum" -> album
            "getDuration" -> 180_000L
            "getPlaybackStatus" -> 1
            "getCurrentLyricSentence" -> lyric
            "getLyricContent" -> lyric
            "getLyric" -> null
            "getArtwork" -> null
            "getNextArtwork" -> null
            "getPreviousArtwork" -> null
            "getMediaPath" -> null
            "getUUID" -> PROBE_UUID
            "getSourceType" -> SOURCE_ONLINE
            "getFavoriteState" -> 0
            "getLoopMode" -> 0
            "getPlayingItemPositionInQueue" -> 0
            "getRadioFrequency" -> ""
            "getRadioStationName" -> ""
            "getRadioMode" -> 0
            "toString" -> "EcarxProbePlaybackInfo(title=" + title + ", lyric=" + lyric + ")"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
        }
    }

    private class MediaHandler(
        private val title: String,
        private val artist: String,
        private val album: String,
        private val lyric: String,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getTitle" -> title
            "getArtist" -> artist
            "getAlbum" -> album
            "getDuration" -> 180_000L
            "getCurrentLyricSentence" -> lyric
            "getLyricContent" -> lyric
            "getLyric" -> null
            "getArtwork" -> null
            "getMediaPath" -> null
            "getUUID" -> PROBE_UUID
            "getSourceType" -> SOURCE_ONLINE
            "getFavoriteState" -> 0
            "getPlayingItemPositionInQueue" -> 0
            "getRadioFrequency" -> ""
            "getRadioStationName" -> ""
            "toString" -> "EcarxProbeMedia(title=" + title + ")"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
        }
    }

    private class CallbackHandler(private val events: MutableList<String>) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "onUpdateMediaStatusRequest",
                "onSourceSelected",
                "onMediaSelected",
                "onMediaHighlighted" -> {
                    val line = method.name + "(" + (args?.joinToString(",") { it?.toString() ?: "null" } ?: "") + ")"
                    synchronized(events) { events.add(line) }
                }
                "toString" -> return "EcarxProbeMediaCallback"
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.firstOrNull()
            }
            return defaultValue(method.returnType)
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun methodList(type: Class<*>): String = runCatching {
        type.methods.sortedBy { it.name }.joinToString("\n") { method ->
            "  " + method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } +
                "): " + method.returnType.simpleName
        }
    }.getOrDefault("unavailable")
}

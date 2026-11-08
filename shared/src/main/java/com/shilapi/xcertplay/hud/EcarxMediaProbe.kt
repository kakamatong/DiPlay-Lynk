package com.shilapi.xcertplay.hud

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Diagnostic probe for the opaque ECARX media contract.
 *
 * The public ECARX adaptapi declares `updatePlaybackInfo(IPlaybackInfo)` and lyric getters, but the
 * Lynk 05 system image is older than that source. This probe dumps what the car actually exposes
 * and pushes one fake media frame with a lyric line and a separate song title, so the driver can
 * see whether the cluster's own music card reads the lyric field or only the title.
 *
 * Nothing here is used by the live output; a failure only affects the diagnostic result.
 */
internal object EcarxMediaProbe {
    private const val PROBE_TITLE = "探针歌曲"
    private const val PROBE_ARTIST = "DiPlay"
    private const val PROBE_ALBUM = "ECARX Probe"
    private const val PROBE_LYRIC = "歌词字段测试"
    private const val PROBE_UUID = "diplay-ecarx-media-probe"

    fun run(context: Context): String {
        val dim = EcarxDimApi.createDimInteraction(context)
            ?: return "DimInteraction.create() returned null"
        val media = EcarxDimApi.mediaInteraction(dim)
            ?: return "getMediaInteraction() returned null"
        val playbackClass = runCatching { Class.forName(EcarxCapabilities.MEDIA_PLAYBACK_INFO) }.getOrNull()
            ?: return "IPlaybackInfo class not found: " + EcarxCapabilities.MEDIA_PLAYBACK_INFO

        val report = StringBuilder()
        report.appendLine("media: " + media.javaClass.name)
        report.appendLine("IMediaInteraction methods:")
        report.appendLine(methodList(media.javaClass))
        report.appendLine("IPlaybackInfo methods:")
        report.appendLine(methodList(playbackClass))
        report.appendLine()

        val info = newInfo(playbackClass, PROBE_TITLE, PROBE_ARTIST, PROBE_ALBUM, PROBE_LYRIC)
        val source = EcarxDimApi.updateMediaSourceTypeList(media, intArrayOf(SOURCE_ONLINE))
        val push = EcarxDimApi.updatePlaybackInfo(media, info, playbackClass)
        val progress = EcarxDimApi.updateCurrentProgress(media, 30_000L)
        val mute = EcarxDimApi.updateMediaMuteState(media, 0)
        report.appendLine("updateMediaSourceTypeList(6): " + result(source))
        report.appendLine("updatePlaybackInfo: " + result(push))
        report.appendLine("updateCurrentProgress(30000): " + result(progress))
        report.appendLine("updateMediaMuteState(0): " + result(mute))
        report.appendLine()
        report.appendLine("仪表上应出现：")
        report.appendLine("歌名：" + PROBE_TITLE)
        report.appendLine("歌手：" + PROBE_ARTIST)
        report.appendLine("歌词字段：" + PROBE_LYRIC)
        report.appendLine()
        report.appendLine("若仪表只显示《" + PROBE_TITLE + "》而没有《" + PROBE_LYRIC + "》，说明原生卡片不读歌词字段；")
        report.appendLine("若显示《" + PROBE_LYRIC + "》，说明歌词字段可用。")
        return report.toString()
    }

    /** Pushes an empty frame; the next real media update replaces it anyway. */
    fun clear(context: Context): String {
        val dim = EcarxDimApi.createDimInteraction(context)
            ?: return "DimInteraction.create() returned null"
        val media = EcarxDimApi.mediaInteraction(dim)
            ?: return "getMediaInteraction() returned null"
        val playbackClass = runCatching { Class.forName(EcarxCapabilities.MEDIA_PLAYBACK_INFO) }.getOrNull()
            ?: return "IPlaybackInfo class not found"
        val info = newInfo(playbackClass, "", "", "", "")
        val push = EcarxDimApi.updatePlaybackInfo(media, info, playbackClass)
        return "clear updatePlaybackInfo: " + result(push)
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
    }

    private fun methodList(type: Class<*>): String = runCatching {
        type.methods.sortedBy { it.name }.joinToString("\n") { method ->
            "  " + method.name + "(" + method.parameterTypes.joinToString(",") { it.simpleName } +
                "): " + method.returnType.simpleName
        }
    }.getOrDefault("unavailable")

    private const val SOURCE_ONLINE = 6
}

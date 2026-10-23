package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.PackageManager
import java.util.Locale

/**
 * Lists installed packages and services that could be the car's stock navigation/lane source.
 *
 * The ECARX cluster and HUD already receive lane arrows even when DiPlay sends no lane data. On the
 * Geely/ECARX image that source is likely the stock navigation service (`ecarx.naviservice`) or an
 * AMap/高德 package that keeps a location/lane service running in the background. This scanner only
 * reads PackageManager; the Settings dialog opens the system app page for a candidate so the driver
 * can disable or force-stop it. It never changes package state itself.
 */
object EcarxNavPackageProbe {
    /** The ECARX navigation service package seen on this image. */
    const val ECARX_NAVI_SERVICE = "ecarx.naviservice"

    data class NavService(val name: String, val enabled: Boolean, val exported: Boolean)

    data class NavPackage(
        val packageName: String,
        val label: String,
        val enabled: Boolean,
        val services: List<NavService>,
    )

    private val packageKeywords = listOf(
        "autonavi", "amap", "gaode", "高德", "navi", "navigation", "map", "lane",
        "ecarx", "geely", "dcy", "tmap", "tencent", "baidu", "导航", "地图",
    )
    private val serviceKeywords = listOf(
        "lane", "navi", "navigation", "map", "guidance", "route", "location",
        "高德", "导航", "车道",
    )

    fun scan(context: Context): List<NavPackage> {
        val pm = context.packageManager
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_DISABLED_COMPONENTS
        val installed = runCatching { pm.getInstalledPackages(flags) }.getOrElse { return emptyList() }
        return installed.mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val packageName = info.packageName
            val label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(packageName)
            val services = (info.services ?: emptyArray()).map { service ->
                NavService(service.name, service.enabled, service.exported)
            }
            val packageHit = packageKeywords.any {
                packageName.contains(it, ignoreCase = true) || label.contains(it, ignoreCase = true)
            }
            val serviceHit = services.any { service ->
                serviceKeywords.any { service.name.contains(it, ignoreCase = true) }
            }
            if (!packageHit && !serviceHit) return@mapNotNull null
            NavPackage(packageName, label, app.enabled, services)
        }.sortedWith(
            compareBy<NavPackage> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName },
        )
    }

    fun report(packages: List<NavPackage>): String {
        if (packages.isEmpty()) return "no matching packages"
        return buildString {
            append("candidates=").append(packages.size).appendLine()
            appendLine("adb: pm disable-user --user 0 <pkg>   (service: <pkg>/<service>)")
            packages.forEach { pkg ->
                append(pkg.label).append(" (").append(pkg.packageName).append(") enabled=")
                    .append(pkg.enabled).appendLine()
                pkg.services.take(40).forEach { service ->
                    append("  service ").append(service.name)
                        .append(" enabled=").append(service.enabled)
                        .append(" exported=").append(service.exported).appendLine()
                }
                if (pkg.services.size > 40) append("  ... ").append(pkg.services.size - 40).appendLine()
            }
        }.trimEnd()
    }
}

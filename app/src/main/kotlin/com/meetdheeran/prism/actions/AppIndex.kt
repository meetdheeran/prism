package com.meetdheeran.prism.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock

/**
 * Launchable apps by name, with forgiving matching ("insta" → Instagram, "yt music" →
 * YouTube Music). Requires QUERY_ALL_PACKAGES (declared; this is a sideloaded assistant).
 */
class AppIndex(context: Context) {
    private val ctx = context.applicationContext

    data class AppEntry(val label: String, val packageName: String, val component: ComponentName) {
        val key: String = normalize(label)
    }

    @Volatile private var cache: List<AppEntry> = emptyList()
    @Volatile private var cachedAt = 0L

    fun all(force: Boolean = false): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        if (!force && cache.isNotEmpty() && now - cachedAt < 10 * 60_000) return cache
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                AppEntry(ri.loadLabel(pm).toString(), ai.packageName, ComponentName(ai.packageName, ai.name))
            }
            .distinctBy { it.packageName }
            .sortedBy { it.key }
        cache = list
        cachedAt = now
        return list
    }

    /** Best matches first. Empty when nothing is plausibly the app the user meant. */
    fun find(query: String, limit: Int = 5): List<AppEntry> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qTokens = q.split(' ').filter { it.isNotEmpty() }
        return all()
            .map { it to score(it, q, qTokens) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun launch(entry: AppEntry): String? {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(entry.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(entry.component)
        return ctx.launch(intent)
    }

    private fun score(e: AppEntry, q: String, qTokens: List<String>): Int {
        val k = e.key
        val pkg = e.packageName.lowercase()
        return when {
            k == q -> 100
            k.replace(" ", "") == q.replace(" ", "") -> 95
            k.startsWith(q) -> 85
            k.split(' ').any { it.startsWith(q) } -> 75
            k.contains(q) -> 65
            qTokens.isNotEmpty() && qTokens.all { t -> k.split(' ').any { it.startsWith(t) } } -> 60
            pkg.contains(q.replace(" ", "")) -> 45
            initials(k) == q.replace(" ", "") -> 40
            else -> 0
        }
    }

    private fun initials(k: String) = k.split(' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }

    companion object {
        fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}

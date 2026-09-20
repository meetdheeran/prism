package com.meetdheeran.prism.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.meetdheeran.prism.ai.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * Shared plumbing for every phone-action handler.
 *
 * Models are sloppy with argument types (numbers as strings, "yes" for true, a comma list
 * instead of an array), so the readers below coerce instead of failing. Results always carry
 * an "ok" flag and, on failure, a "needs" hint ("permission", "shizuku", "notification_access",
 * "policy_access", "write_settings") so the engine/UI can offer the matching fix instead of the
 * model guessing.
 */

/** Reads a non-blank string argument; accepts numbers/booleans and stringifies them. */
internal fun JsonObject.argStr(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** Reads an integer argument, tolerating "7", 7.0 and 7. */
internal fun JsonObject.argInt(name: String): Int? {
    val p = this[name] as? JsonPrimitive ?: return null
    p.intOrNull?.let { return it }
    p.doubleOrNull?.let { return it.toInt() }
    return p.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
}

/** Reads a boolean argument, tolerating "true"/"yes"/"on"/"1" and their opposites. */
internal fun JsonObject.argBool(name: String): Boolean? {
    val p = this[name] as? JsonPrimitive ?: return null
    p.booleanOrNull?.let { return it }
    return when (p.contentOrNull?.trim()?.lowercase()) {
        "true", "yes", "on", "1", "enable", "enabled" -> true
        "false", "no", "off", "0", "disable", "disabled" -> false
        else -> null
    }
}

/** Reads a string array argument; a single comma-separated string is accepted too. */
internal fun JsonObject.argList(name: String): List<String> {
    (this[name] as? JsonArray)?.let { arr ->
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
    }
    return argStr(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

/** Success with extra structured fields for the model. */
internal fun okResult(message: String, userVisible: String? = message, block: JsonObjectBuilder.() -> Unit = {}): ToolResult =
    ToolResult(
        buildJsonObject {
            put("ok", true)
            put("message", message)
            block()
        },
        userVisible,
    )

/**
 * Failure with a machine-readable "needs" hint. The engine surfaces [message] to the user and
 * the model reads "needs"/"fix" to suggest the right next step (e.g. call open_settings).
 */
internal fun failResult(
    message: String,
    needs: String? = null,
    fix: String? = null,
    userVisible: String? = message,
    block: JsonObjectBuilder.() -> Unit = {},
): ToolResult =
    ToolResult(
        buildJsonObject {
            put("ok", false)
            put("error", message)
            if (needs != null) put("needs", needs)
            if (fix != null) put("fix", fix)
            block()
        },
        userVisible,
    )

/** Failure because a runtime permission is missing; the Permissions screen can request it. */
internal fun needsPermission(permission: String, why: String): ToolResult =
    failResult(
        message = "$why needs the ${permission.substringAfterLast('.').replace('_', ' ').lowercase()} permission, which Prism does not have yet. Ask the user to grant it in Prism > Permissions.",
        needs = "permission",
        fix = "Grant ${permission.substringAfterLast('.')} in Prism > Permissions",
        userVisible = "Needs ${permission.substringAfterLast('.').replace('_', ' ').lowercase()} permission",
    ) { put("permission", permission) }

/**
 * Starts an activity from a non-Activity context. Returns null on success or a short reason
 * on failure. FLAG_ACTIVITY_NEW_TASK is mandatory outside an Activity; we never rely on
 * resolveActivity() because package-visibility filtering makes it lie on Android 11+.
 */
internal fun Context.launch(intent: Intent): String? {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "No installed app can handle ${intent.action ?: "this request"}"
    } catch (e: SecurityException) {
        "Android refused to open it: ${e.message ?: "security exception"}"
    } catch (e: Exception) {
        "Could not open it: ${e.message ?: e.javaClass.simpleName}"
    }
}

internal fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

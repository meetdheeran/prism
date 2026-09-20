package com.meetdheeran.prism.shizuku

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Prism's Shizuku UserService. The Shizuku server spawns this class by reflection inside a
 * fresh `<applicationId>:shell` process that runs as uid 2000 (adb shell), so every command
 * here has exactly the rights of `adb shell` — no more, no less. Nothing in this process can
 * reach app singletons (the Application subclass is not created there); keep it self-contained.
 */
class ShellService : IShellService.Stub {

    /** Required: the server tries the (Context) constructor first, then this one. */
    constructor() : super()

    /** Shizuku API 13 hands over a plain android.app.Application; we do not need it. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() = destroy()

    override fun exec(command: String, timeoutMs: Int): Bundle {
        val out = StringBuilder()
        val err = StringBuilder()
        val process = try {
            ProcessBuilder("sh", "-c", command).start()
        } catch (e: IOException) {
            return result(126, "", e.message ?: "could not start sh")
        }
        val tOut = thread(name = "shell-out") { process.inputStream.copyCapped(out) }
        val tErr = thread(name = "shell-err") { process.errorStream.copyCapped(err) }
        val code = if (process.waitFor(timeoutMs.toLong().coerceAtLeast(500), TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            124
        }
        tOut.join(1000)
        tErr.join(1000)
        return result(code, out.toString(), err.toString())
    }

    override fun execToFd(command: String, timeoutMs: Int, stdout: ParcelFileDescriptor): Bundle {
        val err = StringBuilder()
        val sink = ParcelFileDescriptor.AutoCloseOutputStream(stdout)
        val process = try {
            ProcessBuilder("sh", "-c", command).start()
        } catch (e: IOException) {
            sink.close()
            return result(126, "", e.message ?: "could not start sh")
        }
        val tOut = thread(name = "shell-fd") {
            try {
                process.inputStream.use { it.copyTo(sink, 64 * 1024) }
            } catch (_: IOException) {
                // Reader went away; nothing to do.
            } finally {
                runCatching { sink.close() }
            }
        }
        val tErr = thread(name = "shell-err") { process.errorStream.copyCapped(err) }
        val code = if (process.waitFor(timeoutMs.toLong().coerceAtLeast(500), TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            124
        }
        tOut.join(2000)
        tErr.join(1000)
        runCatching { sink.close() }
        return result(code, "", err.toString())
    }

    private fun InputStream.copyCapped(into: StringBuilder) {
        try {
            bufferedReader().use { r ->
                val buf = CharArray(8192)
                var n: Int
                while (r.read(buf).also { n = it } >= 0) {
                    if (into.length < MAX_STDOUT_BYTES) into.append(buf, 0, n.coerceAtMost(MAX_STDOUT_BYTES - into.length))
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun result(code: Int, out: String, err: String) = Bundle().apply {
        putInt("code", code)
        putString("out", out)
        putString("err", err)
    }

    companion object {
        const val MAX_STDOUT_BYTES = 512 * 1024
    }
}

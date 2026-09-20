package com.meetdheeran.prism.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.meetdheeran.prism.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/**
 * The one door to Shizuku. Tracks whether Shizuku is installed / running / allowed, and runs
 * shell commands through [ShellService] (a UserService in a uid-2000 process). Non-root Shizuku
 * must be restarted after every reboot on Android 12; [state] tells the UI when that happened.
 */
object ShizukuBridge {
    private const val PKG = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 0x5A1

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state

    private var app: Context? = null
    @Volatile private var service: IShellService? = null
    private var pending: CompletableDeferred<IShellService>? = null
    private val mutex = Mutex()

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app!!.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .tag("prism-shell")
            .version(2)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val s = IShellService.Stub.asInterface(binder)
                service = s
                pending?.complete(s)
            } else {
                pending?.completeExceptionally(IllegalStateException("Shell service binder is dead"))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == REQUEST_CODE) refresh()
    }

    /** Idempotent. Call from Application.onCreate. */
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun isInstalled(): Boolean {
        val a = app ?: return false
        return runCatching { a.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)
    }

    fun refresh(): ShizukuState {
        val next = when {
            !isInstalled() -> ShizukuState.NOT_INSTALLED
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) -> ShizukuState.READY
            else -> ShizukuState.NO_PERMISSION
        }
        _state.value = next
        return next
    }

    fun isReady(): Boolean = refresh() == ShizukuState.READY

    /** Asks Shizuku to allow Prism; if the user chose "don't ask again", opens the Shizuku app instead. */
    fun requestPermission() {
        val a = app ?: return
        if (!Shizuku.pingBinder()) { openShizukuApp(a); return }
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) openShizukuApp(a) else Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure { openShizukuApp(a) }
    }

    fun openShizukuApp(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    private suspend fun service(): IShellService = mutex.withLock {
        service?.let { if (it.asBinder().pingBinder()) return@withLock it }
        val d = CompletableDeferred<IShellService>()
        pending = d
        withContext(Dispatchers.Main) {
            Shizuku.bindUserService(args, connection)
        }
        withTimeout(12_000) { d.await() }
    }

    /** Runs `sh -c cmd` as uid shell. Text output only (capped); use [screenshotPng] for binary. */
    suspend fun exec(cmd: String, timeoutMs: Long = 8_000): Result<ShellResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isReady()) throw IllegalStateException(state.value.label)
            val b = service().exec(cmd, timeoutMs.toInt())
            ShellResult(b.getInt("code", -1), b.getString("out") ?: "", b.getString("err") ?: "")
        }
    }

    /** Full-screen PNG via `screencap -p`, streamed through a pipe (binder can't carry 2 MB). */
    suspend fun screenshotPng(): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isReady()) throw IllegalStateException(state.value.label)
            val svc = service()
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            coroutineScope {
                val reader = async(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { it.readBytes() }
                }
                val b = try {
                    svc.execToFd("screencap -p", 6_000, writeEnd)
                } finally {
                    runCatching { writeEnd.close() }
                }
                val bytes = reader.await()
                if (b.getInt("code", -1) != 0 || bytes.size < 100) {
                    throw IllegalStateException("screencap failed: " + (b.getString("err")?.take(120) ?: "no output"))
                }
                bytes
            }
        }
    }

    fun disconnect() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }
}

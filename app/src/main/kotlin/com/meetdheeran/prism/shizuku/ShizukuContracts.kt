package com.meetdheeran.prism.shizuku

/** Where Shizuku stands right now. Drives every "needs Shizuku" badge in the UI. */
enum class ShizukuState(val label: String) {
    NOT_INSTALLED("Shizuku is not installed"),
    NOT_RUNNING("Shizuku is installed but not running (start it from the Shizuku app or tools/shizuku-start.ps1)"),
    NO_PERMISSION("Prism has not been allowed in Shizuku yet"),
    READY("Shizuku ready"),
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

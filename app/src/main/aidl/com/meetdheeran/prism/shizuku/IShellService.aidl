// Contract between the app process and Prism's Shizuku UserService (ShellService), which the
// Shizuku server spawns as uid 2000 (adb shell) in the "<applicationId>:shell" process.
//
// Transaction ids are explicit because Shizuku reserves one of them: the server stops a
// UserService by sending transaction 16777115 == FIRST_CALL_TRANSACTION(1) + 16777114, so
// `destroy` MUST carry id 16777114. When one method has an explicit id, every method needs one.
package com.meetdheeran.prism.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IShellService {
    /** Reserved by the Shizuku server. The implementation must terminate the process. */
    void destroy() = 16777114;

    /** Same as destroy(), callable by the app. */
    void exit() = 1;

    /**
     * Runs `sh -c command` and waits at most timeoutMs. Returns a Bundle with
     * "code" (int exit code; 124 = timed out, 126 = could not start sh),
     * "out" (String, UTF-8 stdout capped at ShellService.MAX_STDOUT_BYTES) and
     * "err" (String, capped stderr).
     * Text only: Binder transactions are limited to ~1 MB, so large or binary output must go
     * through execToFd instead.
     */
    Bundle exec(String command, int timeoutMs) = 2;

    /**
     * Runs `sh -c command`, streaming raw stdout bytes into the write end of a pipe supplied by
     * the caller (closed by the service when the command ends). Returns a Bundle with "code"
     * and "err" as in exec. Used for `screencap`, whose output is far larger than one Binder
     * transaction allows.
     */
    Bundle execToFd(String command, int timeoutMs, in ParcelFileDescriptor stdout) = 3;
}

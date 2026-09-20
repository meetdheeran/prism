package com.meetdheeran.prism.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thrown for any non-2xx answer so providers can map the HTTP code to a user-facing state
 * (invalid key, rate limit, model missing). [body] is the raw error JSON; it never contains
 * the API key because keys travel in headers, not in bodies or URLs.
 */
class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")

/**
 * The one OkHttp client shared by every provider. A single client means one connection pool
 * and one thread pool, which matters on a phone. No logging interceptor is installed on
 * purpose: request bodies carry user text, screenshots and (in headers) API keys.
 *
 * Read timeout is long because thinking models may pause for tens of seconds before the
 * first streamed token; connect timeout is short so a dead network fails fast.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Streaming requests are not idempotent from the user's point of view (a retry
            // would re-run a tool-calling turn), so let the provider decide what to retry.
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Same client, callable as a function for call sites that prefer `Http.client()`. */
    fun client(): OkHttpClient = client

    /**
     * Executes [request] and returns the body text, throwing [HttpException] for non-2xx.
     * Runs on the IO dispatcher; the caller's coroutine is not blocked.
     */
    suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HttpException(response.code, text)
                text
            }
        } finally {
            handle.dispose()
        }
    }

    /**
     * Server-sent events as a cold flow of `data:` payloads (prefix stripped, multi-line data
     * joined with '\n' per the SSE spec). Comment lines (`:`), `event:` and `id:` lines and
     * blank keep-alives are skipped. The flow completes when the server closes the stream and
     * throws [HttpException] (an [IOException]) for a non-2xx status before any event.
     *
     * Cancelling the collector cancels the underlying call, so a user tapping "stop" frees
     * the socket immediately instead of waiting for the read timeout.
     */
    fun sse(request: Request): Flow<String> = flow {
        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpException(response.code, response.body?.string().orEmpty())
                }
                val source = response.body?.source() ?: return@use
                val data = StringBuilder()
                var hasData = false
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            if (hasData) {
                                emit(data.toString())
                                data.setLength(0)
                                hasData = false
                            }
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("data:") -> {
                            var payload = line.substring(5)
                            if (payload.startsWith(" ")) payload = payload.substring(1)
                            if (hasData) data.append('\n')
                            data.append(payload)
                            hasData = true
                        }
                        else -> Unit // event:, id:, retry: — not needed by any provider we call
                    }
                }
                if (hasData) emit(data.toString())
            }
        } finally {
            handle.dispose()
        }
    }.flowOn(Dispatchers.IO)
}

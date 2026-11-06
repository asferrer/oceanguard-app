package com.oceanguard.ai.inference

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Why a model download failed, in terms the user can act on. The UI shows a plain sentence per
 * kind and never the raw exception message.
 */
enum class DownloadFailureKind {
    /** No network, a dropped connection or a timeout: try again with a connection. */
    NO_CONNECTION,

    /** The phone ran out of storage while writing the file. */
    NO_SPACE,

    /** The server no longer has the file: retrying will not help. */
    NOT_FOUND,

    /** The server failed or is overloaded: retrying later usually works. */
    SERVER,

    /** The file arrived truncated or was not a model. */
    INCOMPLETE,

    UNKNOWN,
}

/** Pure mapping from what the download saw to a [DownloadFailureKind]. */
object DownloadFailure {

    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_GONE = 410
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR = 500

    fun fromHttpStatus(code: Int): DownloadFailureKind = when {
        code == HTTP_FORBIDDEN || code == HTTP_NOT_FOUND || code == HTTP_GONE ->
            DownloadFailureKind.NOT_FOUND
        code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR -> DownloadFailureKind.SERVER
        else -> DownloadFailureKind.UNKNOWN
    }

    fun fromIo(error: Throwable): DownloadFailureKind = when {
        isOutOfSpace(error) -> DownloadFailureKind.NO_SPACE
        error is UnknownHostException || error is SocketTimeoutException ||
            error is ConnectException || error is SocketException || error is SSLException ->
            DownloadFailureKind.NO_CONNECTION
        error is IOException -> DownloadFailureKind.NO_CONNECTION
        else -> DownloadFailureKind.UNKNOWN
    }

    private fun isOutOfSpace(error: Throwable): Boolean {
        val text = error.message.orEmpty()
        return text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true)
    }
}

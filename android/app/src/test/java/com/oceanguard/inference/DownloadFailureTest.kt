package com.oceanguard.ai.inference

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFailureTest {

    @Test fun aMissingFileIsNotWorthRetrying() {
        assertEquals(DownloadFailureKind.NOT_FOUND, DownloadFailure.fromHttpStatus(404))
        assertEquals(DownloadFailureKind.NOT_FOUND, DownloadFailure.fromHttpStatus(403))
        assertEquals(DownloadFailureKind.NOT_FOUND, DownloadFailure.fromHttpStatus(410))
    }

    @Test fun serverTroubleIsWorthRetryingLater() {
        assertEquals(DownloadFailureKind.SERVER, DownloadFailure.fromHttpStatus(500))
        assertEquals(DownloadFailureKind.SERVER, DownloadFailure.fromHttpStatus(503))
        assertEquals(DownloadFailureKind.SERVER, DownloadFailure.fromHttpStatus(429))
        assertEquals(DownloadFailureKind.UNKNOWN, DownloadFailure.fromHttpStatus(302))
    }

    @Test fun networkErrorsAreAConnectionProblem() {
        listOf(
            UnknownHostException("huggingface.co"),
            SocketTimeoutException("timeout"),
            ConnectException("refused"),
            SSLHandshakeException("handshake"),
            IOException("unexpected end of stream"),
        ).forEach { assertEquals(it.toString(), DownloadFailureKind.NO_CONNECTION, DownloadFailure.fromIo(it)) }
    }

    @Test fun aFullDiskIsToldApartFromTheNetwork() {
        assertEquals(
            DownloadFailureKind.NO_SPACE,
            DownloadFailure.fromIo(IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(
            DownloadFailureKind.NO_SPACE,
            DownloadFailure.fromIo(IOException("No space left on device")),
        )
    }

    @Test fun otherExceptionsAreUnknown() {
        assertEquals(DownloadFailureKind.UNKNOWN, DownloadFailure.fromIo(IllegalStateException("x")))
    }

    @Test fun theExceptionCarriesItsKindAndErrorStateKeepsIt() {
        val failure = VlmDownloadException("boom", kind = DownloadFailureKind.NO_SPACE)
        assertEquals(DownloadFailureKind.NO_SPACE, failure.kind)
        assertEquals(DownloadFailureKind.NO_SPACE, VlmDownloadState.Error("boom", failure.kind).kind)
        assertEquals(DownloadFailureKind.UNKNOWN, VlmDownloadState.Error("boom").kind)
    }
}

package com.oceanguard.ai.inference

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.ToolSet
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * ToolAgentLoop against a scripted [Conversation]: the model answers each turn from the script.
 * Reproduces the S22 sequence: eight tool calls in one turn, then prose in PHASE 1.
 */
/** Mockito matcher usable for non-null Kotlin parameters. */
@Suppress("UNCHECKED_CAST")
private fun <T> anyK(): T {
    ArgumentMatchers.any<Any>()
    return null as T
}

class ToolAgentLoopTest {

    /** SDK form of the names, as seen in the device log. */
    private val sdkNames = listOf(
        "per-session-details", "debris-summary", "material-breakdown", "type-breakdown",
        "ecological-impacts", "risk-assessment", "survey-statistics", "temporal-trend",
    )
    private val required = setOf(
        "get_debris_summary", "get_material_breakdown", "get_type_breakdown", "get_risk_assessment",
        "get_ecological_impacts", "get_survey_statistics", "get_per_session_details", "get_temporal_trend",
    )

    private sealed class Turn {
        data class Calls(val names: List<String>) : Turn()
        data class Prose(val text: String) : Turn()
    }

    private class Harness(turns: List<Turn>) {
        val conversation: Conversation = mock(Conversation::class.java)
        val sentInputs = mutableListOf<Any>()
        private var cancelled = false

        init {
            val toolManager = mock(ToolManager::class.java)
            `when`(toolManager.execute(anyK(), anyK())).thenReturn(JsonObject())
            `when`(conversation.toolManager).thenReturn(toolManager)
            val queue = ArrayDeque(turns)
            val answer = { cb: MessageCallback, input: Any ->
                sentInputs.add(input)
                if (cancelled) {
                    // After our own cancelProcess() the native side fails the next turn.
                    cb.onError(java.util.concurrent.CancellationException("Task cancelled"))
                } else {
                    when (val t = queue.removeFirst()) {
                        is Turn.Calls -> {
                            cb.onMessage(Message.model(Contents.of(""), t.names.map { ToolCall(it, emptyMap()) }))
                            cb.onDone()
                        }
                        is Turn.Prose -> {
                            cb.onMessage(Message.model(t.text))
                            cb.onDone()
                        }
                    }
                }
            }
            doAnswer { answer(it.getArgument(1), it.getArgument<Any>(0)); null }
                .`when`(conversation).sendMessageAsync(
                    anyK<String>(), anyK<MessageCallback>(),
                    anyK(), anyK(), anyK(), anyK(), anyK(), anyK(), anyK(),
                )
            doAnswer { answer(it.getArgument(1), it.getArgument<Any>(0)); null }
                .`when`(conversation).sendMessageAsync(
                    anyK<Contents>(), anyK<MessageCallback>(),
                    anyK(), anyK(), anyK(), anyK(), anyK(), anyK(), anyK(),
                )
            doAnswer { cancelled = true; null }.`when`(conversation).cancelProcess()
        }
    }

    private fun run(h: Harness, bundle: String?): String = runBlocking {
        ToolAgentLoop(h.conversation, mock(ToolSet::class.java)).run(
            prompt = "go", maxToolRounds = 8, requiredToolNames = required, dataBundle = bundle,
            onPartialResult = {}, onToolCallStarted = {},
        )
    }

    private val prose = "Here is the report of the survey, which starts with a long sentence ......"

    @Test fun eightToolsThenPhase1ProseHandsOffWithoutRedirect() {
        val h = Harness(listOf(Turn.Calls(sdkNames), Turn.Prose(prose)))
        assertEquals("", run(h, bundle = "BUNDLE"))
        assertEquals("one tool turn and one prose turn, no redirect", 2, h.sentInputs.size)
        assertTrue(h.sentInputs.none { it is String && it.startsWith("STOP") })
    }

    @Test fun phase1ProseWithToolsStillMissingDoesNotRedirectOnACancelledConversation() {
        val h = Harness(listOf(Turn.Calls(sdkNames.take(3)), Turn.Prose(prose)))
        assertEquals("", run(h, bundle = "BUNDLE"))
        verify(h.conversation, times(1)).cancelProcess()
        assertEquals(2, h.sentInputs.size)
    }

    @Test fun finishingWithAllToolsInSdkFormNeedsNoRedirectInAgenticMode() {
        val h = Harness(listOf(Turn.Calls(sdkNames), Turn.Prose("done")))
        assertEquals("done", run(h, bundle = null))
        verify(h.conversation, never()).cancelProcess()
        assertEquals(2, h.sentInputs.size)
    }

    @Test fun agenticModeStillRedirectsWhenToolsAreReallyMissing() {
        val h = Harness(
            listOf(
                Turn.Calls(sdkNames.take(7)), Turn.Prose("early"),
                Turn.Calls(listOf("temporal-trend")), Turn.Prose("final"),
            ),
        )
        assertEquals("final", run(h, bundle = null))
        val redirect = h.sentInputs.filterIsInstance<String>().first { it.startsWith("STOP") }
        assertTrue(redirect.contains("temporal-trend"))
        assertFalse(redirect.contains("get_"))
    }
}

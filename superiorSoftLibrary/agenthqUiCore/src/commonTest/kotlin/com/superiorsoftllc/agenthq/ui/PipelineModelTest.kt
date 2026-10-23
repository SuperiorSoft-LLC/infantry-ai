package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.PipelineSectionView
import com.superiorsoftllc.agenthq.ui.model.pipelineSectionView
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.WireRequests
import com.superiorsoftllc.agenthq.ui.protocol.parseStageNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PipelineModelTest {
    private val configured = """{"projectId":"P","configured":true,"config":{"stages":["merged","testing","passed"],"exclusiveCheck":false},
        "commits":[{"commit":"abcdef1234567890","stage":"passed","movedBy":"qa (agent-1)","movedAt":5,"evidence":"suite ok"}],
        "history":[],"checks":[{"scope":"w","commit":"abcdef1234567890","what":"gradle test","startedAt":1,"lastOutputAt":9}]}"""

    @Test
    fun noAnswerYetIsLoadingAndAnUnconfiguredProjectIsTheDefaultEmptyState() {
        assertEquals(PipelineSectionView.Loading, pipelineSectionView(null))
        val s = reduce(AppState(), Envelope(Envelope.PIPELINE_STATE, """{"projectId":"P","configured":false}"""), 0)
        assertEquals(PipelineSectionView.NotConfigured, pipelineSectionView(s.pipelines["P"]))
    }

    @Test
    fun aPushedStateFillsOnlyItsProjectsSlot() {
        var s = reduce(AppState(), Envelope(Envelope.PIPELINE_STATE, """{"projectId":"Other","configured":false}"""), 0)
        s = reduce(s, Envelope(Envelope.PIPELINE_STATE, configured), 0)
        val view = assertIs<PipelineSectionView.Configured>(pipelineSectionView(s.pipelines["P"]))
        assertEquals(listOf("merged", "testing", "passed"), view.stages)
        assertEquals("passed", view.commits.single().stage)
        assertEquals("gradle test", view.checks.single().what)
        assertEquals(PipelineSectionView.NotConfigured, pipelineSectionView(s.pipelines["Other"]))
    }

    @Test
    fun aGarbledPushLeavesTheStateAsItWas() {
        val s = AppState()
        assertTrue(reduce(s, Envelope(Envelope.PIPELINE_STATE, "not json"), 0).pipelines.isEmpty())
    }

    @Test
    fun configureRequestsMatchTheDaemonsFrames() {
        val set = WireRequests.pipelineConfigure("P", listOf("a", "b"))
        assertEquals(Envelope.PIPELINE_CONFIG, set.type)
        assertTrue("\"stages\":[\"a\",\"b\"]" in set.data && "\"clear\":false" in set.data, set.data)
        assertTrue("\"clear\":true" in WireRequests.pipelineConfigure("P", null).data)
        assertEquals(Envelope.PIPELINE_SHOW, WireRequests.pipelineShow("P").type)
    }

    @Test
    fun stageNamesAreTrimmedAndDeduplicatedInOrder() {
        assertEquals(listOf("merged", "testing", "passed"), parseStageNames(" merged, testing ,,passed\nmerged"))
        assertTrue(parseStageNames(" , ").isEmpty())
    }
}

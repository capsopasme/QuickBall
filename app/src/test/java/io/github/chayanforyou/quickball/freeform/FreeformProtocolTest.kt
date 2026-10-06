package io.github.chayanforyou.quickball.freeform

import io.github.chayanforyou.quickball.freeform.FreeformProtocol.Event
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.Ready
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.Reply
import io.github.chayanforyou.quickball.freeform.FreeformProtocol.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformProtocolTest {

    @Test
    fun boxRoundTrips() {
        val box = Box(10, -20, 300, 640)
        assertEquals(box, Box.decode(box.encode()))
        assertNull(Box.decode("1,2,3"))
        assertNull(Box.decode("a,b,c,d"))
        assertNull(Box.decode(null))
    }

    @Test
    fun requestIsParsedByTheDaemon() {
        val line = FreeformProtocol.request(7, FreeformProtocol.LAUNCH, "com.example", Box(1, 2, 3, 4).encode(), 320)
        val request = FreeformProtocol.parseRequest(line)!!
        assertEquals(7, request.id)
        assertEquals(FreeformProtocol.LAUNCH, request.command)
        assertEquals(listOf("com.example", "1,2,3,4", "320"), request.args)
        assertNull(FreeformProtocol.parseRequest("garbage"))
        assertNull(FreeformProtocol.parseRequest("x PING"))
    }

    @Test
    fun repliesAndEventsAreParsedByTheApp() {
        val ok = FreeformProtocol.parseFromDaemon(FreeformProtocol.ok(3, "TASK", 42)) as Reply
        assertTrue(ok.ok)
        assertEquals(3, ok.id)
        assertEquals(listOf("TASK", "42"), ok.args)

        val err = FreeformProtocol.parseFromDaemon(
            FreeformProtocol.error(4, FreeformProtocol.ERR_FAILED, "Security exception\nline two")
        ) as Reply
        assertFalse(err.ok)
        assertEquals(FreeformProtocol.ERR_FAILED, err.code)
        assertEquals(1, err.args.size)

        val event = FreeformProtocol.parseFromDaemon(FreeformProtocol.event(FreeformProtocol.EVT_REMOVED, 9)) as Event
        assertEquals(FreeformProtocol.EVT_REMOVED, event.type)
        assertEquals("9", event.arg(0))

        assertEquals(Ready(FreeformProtocol.VERSION), FreeformProtocol.parseFromDaemon(FreeformProtocol.ready()))
        // su noise and the daemon's own failure line are not protocol lines.
        assertNull(FreeformProtocol.parseFromDaemon("Permission denied"))
        assertNull(FreeformProtocol.parseFromDaemon("INIT_FAILED NoSuchMethodException"))
        assertNull(FreeformProtocol.parseFromDaemon(""))
    }

    @Test
    fun taskStateRoundTrips() {
        val state = TaskState(12, FreeformProtocol.WINDOWING_MODE_FREEFORM, visible = true, focused = false,
            bounds = Box(5, 6, 700, 1300))
        val line = FreeformProtocol.event(FreeformProtocol.EVT_STATE, state.encode())
        val event = FreeformProtocol.parseFromDaemon(line) as Event
        assertEquals(state, TaskState.decode(event.args))
        assertNull(TaskState.decode(listOf("1", "5")))
    }

    @Test
    fun idListsRoundTrip() {
        assertEquals("-", FreeformProtocol.encodeIds(emptyList()))
        assertEquals(emptySet<Int>(), FreeformProtocol.decodeIds("-"))
        assertEquals(setOf(3, 8), FreeformProtocol.decodeIds(FreeformProtocol.encodeIds(listOf(3, 8))))
        assertEquals(setOf(4), FreeformProtocol.decodeIds("4,x"))
    }
}

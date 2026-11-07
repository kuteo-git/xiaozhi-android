package info.dourok.voicebot.domain.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fixtures are copied verbatim from the R1 (`ps` and `dumpsys media.audio_flinger`, grep'd). */
class SinkSessionTest {

    private val ps = "bluetooth 799   210   606152 36580 ffffffff 00000000 S com.android.bluetooth\n"

    private val flinger = """
Output thread 0xb4a49008:
       1    yes    799    3 00000001 00000003      54   5644 A 3 44100     0     0  019BA84C 0xb766b7e0 0x0 0x000       942 
       yes   9544   1 00000010      34 6 00963000   4096 16000
       yes    799   1 0000000C      53 6 019BB280   1360 44100
""".trimStart('\n')

    @Test
    fun `picks the playback session, not the Bluetooth AudioRecord session`() {
        assertEquals(54, sinkPlaybackSession(ps + flinger + "__END__\n"))
    }

    @Test
    fun `reads output with CRLF line ends`() {
        assertEquals(54, sinkPlaybackSession((ps + flinger).replace("\n", "\r\n")))
    }

    @Test
    fun `null when the Bluetooth process has no output track`() {
        val others = """
Output thread 0xb4a49008:
       0     no   9544    3 00000001 00000003      31  12288 A 1 48000     0     0  00000000 0xb768f3e8 0x0 0x008     51200 
       yes    799   1 0000000C      53 6 019BB280   1360 44100
"""
        assertNull(sinkPlaybackSession(ps + others))
    }

    @Test
    fun `null when ps has no bluetooth line`() {
        assertNull(sinkPlaybackSession(flinger))
    }

    @Test
    fun `ignores the rows of other pids`() {
        val ps855 = "bluetooth 855   210   606152 36580 ffffffff 00000000 S com.android.bluetooth\n"
        assertNull(sinkPlaybackSession(ps855 + flinger))
    }

    @Test
    fun `prefers an active track over an idle one`() {
        val both = """
Output thread 0xb4a49008:
       0     no    799    3 00000001 00000003      31  12288 A 1 48000     0     0  00000000 0xb768f3e8 0x0 0x008     51200 
       1    yes    799    3 00000001 00000003      54   5644 A 3 44100     0     0  019BA84C 0xb766b7e0 0x0 0x000       942 
"""
        assertEquals(54, sinkPlaybackSession(ps + both))
    }

    @Test
    fun `an idle track is still the session when nothing is active`() {
        val idle = """
Output thread 0xb4a49008:
       0     no    799    3 00000001 00000003      31  12288 A 1 48000     0     0  00000000 0xb768f3e8 0x0 0x008     51200 
"""
        assertEquals(31, sinkPlaybackSession(ps + idle))
    }

    @Test
    fun `the echoed command is not mistaken for the ps line`() {
        val echo = "ps | grep com.android.bluetooth; dumpsys media.audio_flinger | grep -e 'Output thread'; echo __EN''D__\n"
        assertNull(sinkPlaybackSession(echo + flinger))
    }

    @Test
    fun `empty output`() {
        assertNull(sinkPlaybackSession(""))
    }
}

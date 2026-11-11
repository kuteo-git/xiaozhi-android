package info.dourok.voicebot.domain.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeGateTest {
    @Test fun detectionWakesWhenEnabled() = assertTrue(WakeGate.accepts(detected = true, wakeEnabled = true))
    @Test fun detectionIgnoredWhenDisabled() = assertFalse(WakeGate.accepts(detected = true, wakeEnabled = false))
    @Test fun noDetectionNeverWakes() = assertFalse(WakeGate.accepts(detected = false, wakeEnabled = true))
}

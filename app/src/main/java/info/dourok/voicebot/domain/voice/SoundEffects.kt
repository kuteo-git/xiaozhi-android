package info.dourok.voicebot.domain.voice

/** Plays short UI feedback sounds (wake / stop chimes). */
interface SoundEffects {
    /** Chime played when the wake word is detected ("listening" cue). */
    fun playWake()

    /** Chime played when a session is stopped. */
    fun playStop()
}

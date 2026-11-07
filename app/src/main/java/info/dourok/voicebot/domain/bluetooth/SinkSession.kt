package info.dourok.voicebot.domain.bluetooth

private val BLUETOOTH_PS_LINE = Regex("""^\S+\s+(\d+)\s.*\scom\.android\.bluetooth$""")

// Name Active Client Type Fmt ChnMask Session ... Input-thread rows have no leading Name number,
// which is what keeps the Bluetooth process's AudioRecord session out of this.
private val OUTPUT_TRACK_ROW = Regex("""^(\d+)\s+(yes|no)\s+(\d+)\s+\d+\s+\S+\s+\S+\s+(\d+)\s""")

/**
 * The audio session the ROM's Bluetooth process plays a phone's music on, read from the combined
 * output of `ps` and `dumpsys media.audio_flinger` (see R1Shell and SinkEffects), or null when it
 * cannot be told. There is no public API for another process's session on API 22, so this is read
 * from the mixer's own listing: the pid comes from the `ps` line, then the track whose Client is
 * that pid. An active track wins over an idle one.
 */
fun sinkPlaybackSession(shellOutput: String): Int? {
    val lines = shellOutput.lineSequence().map { it.trim() }.toList()
    val pid = lines.firstNotNullOfOrNull { BLUETOOTH_PS_LINE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: return null
    val tracks = lines.mapNotNull { OUTPUT_TRACK_ROW.find(it)?.groupValues }
        .filter { it[3].toIntOrNull() == pid }
    val chosen = tracks.firstOrNull { it[2] == "yes" } ?: tracks.firstOrNull()
    return chosen?.get(4)?.toIntOrNull()
}

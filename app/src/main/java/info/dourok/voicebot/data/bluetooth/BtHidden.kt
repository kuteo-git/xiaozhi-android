package info.dourok.voicebot.data.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.media.AudioManager
import android.util.Log
import info.dourok.voicebot.domain.bluetooth.BtResult

/**
 * The Bluetooth operations this app needs that the public SDK does not expose. Verified
 * against android.jar (compileSdk 35): `BluetoothDevice.createBond()` and `getBondState()` are
 * public, `removeBond()` is not, and neither is `BluetoothA2dp.connect()/disconnect()`.
 *
 * Kept in one file, each call naming the method it reaches for, because a reflection miss is
 * invisible at build time — R8 does not rename framework classes, so no keep rule is needed, and
 * nothing anywhere fails until somebody presses the button. The reason therefore travels back to
 * the panel as text instead of being logged where nobody is looking.
 */
object BtHidden {

    /** "Forget this device" — the whole reason reflection is in this feature at all. */
    fun removeBond(device: BluetoothDevice): BtResult =
        invokeBool(device, "removeBond", "BluetoothDevice.removeBond()")

    fun connect(proxy: BluetoothA2dp, device: BluetoothDevice): BtResult =
        invokeWithDevice(proxy, device, "connect", "BluetoothA2dp.connect(BluetoothDevice)")

    fun disconnect(proxy: BluetoothA2dp, device: BluetoothDevice): BtResult =
        invokeWithDevice(proxy, device, "disconnect", "BluetoothA2dp.disconnect(BluetoothDevice)")

    /**
     * `BluetoothAdapter.setScanMode(int mode, int durationSec)`. The public API has none: the
     * platform's "make discoverable" is an Activity with a dialog, and this box has no screen.
     * [durationSec] only matters for DISCOVERABLE; the stack falls back to CONNECTABLE afterwards.
     */
    fun setScanMode(adapter: BluetoothAdapter, mode: Int, durationSec: Int): BtResult = try {
        val m = adapter.javaClass.getMethod(
            "setScanMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        val ok = m.invoke(adapter, mode, durationSec) as? Boolean ?: false
        if (ok) BtResult.OK else BtResult.fail("BluetoothAdapter.setScanMode(int,int) trả về false")
    } catch (e: NoSuchMethodException) {
        Log.e(TAG, "BluetoothAdapter.setScanMode(int,int) missing on this ROM", e)
        BtResult.fail("ROM này không có BluetoothAdapter.setScanMode(int,int)")
    } catch (e: Exception) {
        Log.e(TAG, "BluetoothAdapter.setScanMode(int,int) failed", e)
        BtResult.fail("BluetoothAdapter.setScanMode(int,int) lỗi: ${e.message}")
    }

    /**
     * `BluetoothProfile.A2DP_SINK`, a hidden constant read from the class rather than copied: its
     * value differs between Android versions, and a hardcoded number would bind some other profile
     * without any error. Null when this ROM does not have it.
     */
    fun a2dpSinkProfileId(): Int? = try {
        BluetoothProfile::class.java.getField("A2DP_SINK").getInt(null)
    } catch (e: Exception) {
        Log.e(TAG, "BluetoothProfile.A2DP_SINK missing on this ROM: ${e.message}")
        null
    }

    /**
     * Force the volume index Android applies to the A2DP output.
     *
     * Measured on the R1 with a speaker connected: `dumpsys audio` holds two indices for
     * STREAM_MUSIC -- `(default): 6` and `(spdif): 15` -- and no entry for A2DP at all, so the
     * Bluetooth output falls back to 6 of 15. The panel's slider cannot reach it, because
     * AudioService stores whatever it sets against the one device it considers current, and on this
     * box that reduction picks SPDIF (0x80000, numerically above A2DP's 0x80) out of the mask the
     * primary output declares. So the public setStreamVolume writes an index the speaker never
     * reads, which is why turning it up did nothing.
     *
     * AudioSystem.setStreamVolumeIndex(stream, index, device) is the per-device setter and is
     * hidden. All three A2DP device constants are written: which one a given speaker presents
     * depends on its class, and writing the two it is not costs nothing.
     */
    fun setA2dpMusicVolumeIndex(index: Int): BtResult = try {
        val m = Class.forName("android.media.AudioSystem").getMethod(
            "setStreamVolumeIndex",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        var wrote = 0
        for (device in A2DP_DEVICES) {
            val rc = m.invoke(null, AudioManager.STREAM_MUSIC, index, device) as? Int ?: -1
            if (rc == 0) wrote++
        }
        if (wrote > 0) BtResult.OK
        else BtResult.fail("AudioSystem.setStreamVolumeIndex() từ chối cả 3 device A2DP")
    } catch (e: NoSuchMethodException) {
        Log.e(TAG, "AudioSystem.setStreamVolumeIndex missing on this ROM", e)
        BtResult.fail("ROM này không có AudioSystem.setStreamVolumeIndex()")
    } catch (e: Exception) {
        Log.e(TAG, "setStreamVolumeIndex failed", e)
        BtResult.fail("AudioSystem.setStreamVolumeIndex() lỗi: ${e.message}")
    }

    /** The index Android currently applies to the A2DP output, or -1 when it cannot be read. */
    fun a2dpMusicVolumeIndex(): Int = try {
        val m = Class.forName("android.media.AudioSystem").getMethod(
            "getStreamVolumeIndex",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        A2DP_DEVICES.map { m.invoke(null, AudioManager.STREAM_MUSIC, it) as? Int ?: -1 }.maxOrNull() ?: -1
    } catch (e: Exception) {
        Log.e(TAG, "getStreamVolumeIndex: ${e.message}")
        -1
    }

    private fun invokeBool(target: Any, method: String, signature: String): BtResult = try {
        val ok = target.javaClass.getMethod(method).invoke(target) as? Boolean ?: false
        if (ok) BtResult.OK else BtResult.fail("$signature trả về false")
    } catch (e: NoSuchMethodException) {
        Log.e(TAG, "$signature missing on this ROM", e)
        BtResult.fail("ROM này không có $signature")
    } catch (e: Exception) {
        Log.e(TAG, "$signature failed", e)
        BtResult.fail("$signature lỗi: ${e.message}")
    }

    private fun invokeWithDevice(
        target: Any,
        device: BluetoothDevice,
        method: String,
        signature: String,
    ): BtResult = try {
        val m = target.javaClass.getMethod(method, BluetoothDevice::class.java)
        val ok = m.invoke(target, device) as? Boolean ?: false
        if (ok) BtResult.OK else BtResult.fail("$signature trả về false")
    } catch (e: NoSuchMethodException) {
        Log.e(TAG, "$signature missing on this ROM", e)
        BtResult.fail("ROM này không có $signature")
    } catch (e: Exception) {
        Log.e(TAG, "$signature failed", e)
        BtResult.fail("$signature lỗi: ${e.message}")
    }

    private const val TAG = "BtHidden"

    /** From system/audio.h: BLUETOOTH_A2DP, _HEADPHONES, _SPEAKER. */
    private val A2DP_DEVICES = intArrayOf(0x80, 0x100, 0x200)
}

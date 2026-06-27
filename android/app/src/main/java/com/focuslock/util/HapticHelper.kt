package com.focuslock.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Haptic feedback helper.
 *
 * Layered fallback strategy (best to worst):
 *   1. Composition primitives (API 31+, Pixel/supported OEM only) — gated by arePrimitivesSupported()
 *   2. Predefined effects EFFECT_* (API 29+) — well-supported on OnePlus/OEM
 *   3. Waveform with amplitude control (API 26+)
 *   4. Legacy vibrate()
 *
 * VibrationAttributes (API 33+) replaces AudioAttributes for vibration so that
 * Do-Not-Disturb cannot suppress block/alert feedback.
 */
object HapticHelper {

    private const val TAG = "HapticHelper"

    // ── vibrator acquisition ──────────────────────────────────────────────────

    private fun getVibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to obtain vibrator", e)
        null
    }

    /** Returns true only if every requested composition primitive is available on this device. */
    private fun primitivesSupported(vibrator: Vibrator, vararg ids: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return vibrator.arePrimitivesSupported(*ids).all { it }
    }

    // ── central dispatch (handles attribute API level) ────────────────────────

    private fun vibrate(vibrator: Vibrator, effect: VibrationEffect, isAlert: Boolean = false) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(
                        if (isAlert) VibrationAttributes.USAGE_ALARM
                        else VibrationAttributes.USAGE_TOUCH
                    )
                    .build()
                vibrator.vibrate(effect, attrs)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
                val attrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
                vibrator.vibrate(effect, attrs)
            }
            else -> vibrator.vibrate(effect)
        }
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Triple strong pulse played when the lock overlay appears.
     * Uses USAGE_ALARM so it cuts through Do-Not-Disturb.
     */
    fun playLockAlert(context: Context) {
        val v = getVibrator(context) ?: return

        val effect: VibrationEffect = when {
            // Composition path — only when THUD is actually supported (Pixel, some OEM)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_THUD) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 130)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 200)
                    .compose()

            // OnePlus 13r path — CLICK composition (very reliable on OxygenOS)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 130)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 200)
                    .compose()

            // Amplitude waveform (API 26+, most Android devices)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createWaveform(
                    longArrayOf(0, 90, 80, 90, 80, 140),
                    intArrayOf(0, 255, 0, 255, 0, 255),
                    -1
                )

            // No-amplitude waveform
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120, 80, 150), -1)

            // Legacy
            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 120, 80, 120, 80, 150), -1)
                return
            }
        }
        vibrate(v, effect, isAlert = true)
    }

    /**
     * Double-click success feel — math answer correct, step completed.
     */
    fun playSuccess(context: Context) {
        val v = getVibrator(context) ?: return

        val effect: VibrationEffect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 80)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 70), intArrayOf(0, 170, 0, 230), -1)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 70), -1)

            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 40, 50, 70), -1)
                return
            }
        }
        vibrate(v, effect)
    }

    /**
     * Heavy error buzz — wrong answer, validation failure.
     */
    fun playFailure(context: Context) {
        val v = getVibrator(context) ?: return

        val effect: VibrationEffect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_THUD) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.9f, 110)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createOneShot(320, 230)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)

            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(350)
                return
            }
        }
        vibrate(v, effect)
    }

    /**
     * Minimal single click — shake meter increments, UI taps.
     */
    fun playTick(context: Context) {
        val v = getVibrator(context) ?: return

        val effect: VibrationEffect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_TICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createOneShot(18, 110)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)

            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(18)
                return
            }
        }
        vibrate(v, effect)
    }

    /**
     * Gradually intensifying tick for each inhale second (seconds 1-4 of a 4-4-4 breath).
     */
    fun playBreathingInhaleTick(context: Context, secondOfFour: Int) {
        val v = getVibrator(context) ?: return
        val intensity = (0.20f + 0.15f * secondOfFour).coerceIn(0.1f, 1.0f)

        val effect: VibrationEffect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_LOW_TICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, intensity)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_TICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, intensity)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() -> {
                val amp = (intensity * 140).toInt().coerceIn(40, 200)
                VibrationEffect.createOneShot(15, amp)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)

            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(15)
                return
            }
        }
        vibrate(v, effect)
    }

    /**
     * Very soft, nearly imperceptible tick for the hold phase.
     */
    fun playBreathingHoldTick(context: Context) {
        val v = getVibrator(context) ?: return

        val effect: VibrationEffect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_TICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.18f)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createOneShot(12, 55)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)

            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(12)
                return
            }
        }
        vibrate(v, effect)
    }

    /**
     * Gradually softening click for each exhale second (seconds 1-4).
     */
    fun playBreathingExhaleTick(context: Context, secondOfFour: Int) {
        val v = getVibrator(context) ?: return
        val intensity = (0.65f - 0.12f * secondOfFour).coerceIn(0.1f, 1.0f)

        val effect: VibrationEffect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() -> {
                val amp = (intensity * 150).toInt().coerceIn(40, 200)
                VibrationEffect.createOneShot(18, amp)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)

            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(18)
                return
            }
        }
        vibrate(v, effect)
    }

    /**
     * Rise-then-click transition played when moving between friction steps.
     */
    fun playStepTransition(context: Context) {
        val v = getVibrator(context) ?: return

        val effect: VibrationEffect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(
                        v,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                        VibrationEffect.Composition.PRIMITIVE_CLICK
                    ) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.7f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, 110)
                    .compose()

            // OnePlus: QUICK_RISE unsupported but CLICK usually is
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 100)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createWaveform(longArrayOf(0, 50, 70, 80), intArrayOf(0, 140, 0, 210), -1)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createWaveform(longArrayOf(0, 50, 70, 80), -1)

            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 50, 70, 80), -1)
                return
            }
        }
        vibrate(v, effect)
    }

    /**
     * Crisp single click for intention buttons and digit input.
     */
    fun playUiUpdate(context: Context) {
        val v = getVibrator(context) ?: return

        val effect: VibrationEffect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    primitivesSupported(v, VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f)
                    .compose()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createOneShot(20, 100)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)

            else -> {
                @Suppress("DEPRECATION")
                v.vibrate(20)
                return
            }
        }
        vibrate(v, effect)
    }

    fun cancel(context: Context) {
        getVibrator(context)?.cancel()
    }
}

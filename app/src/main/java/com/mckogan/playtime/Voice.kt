package com.mckogan.playtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * Spoken "N more minutes" reminders for the kids, from short recordings built into the app
 * (Hebrew or English, by the app's language). The game's sound dips while the voice speaks.
 */
object Voice {

    private val CLIPS = mapOf(
        "he" to mapOf(10 to R.raw.voice_he_10, 5 to R.raw.voice_he_5, 1 to R.raw.voice_he_1),
        "en" to mapOf(10 to R.raw.voice_en_10, 5 to R.raw.voice_en_5, 1 to R.raw.voice_en_1),
    )

    private var player: MediaPlayer? = null

    /** Plays the reminder for [minutes] left (10, 5 or 1). Quietly does nothing if it can't. */
    fun play(context: Context, minutes: Int) {
        val app = context.applicationContext
        playClip(app, CLIPS[lang(app)]?.get(minutes) ?: return)
    }

    /** "Time's up! Do you want three more minutes?" */
    fun playExtraOffer(context: Context) {
        val app = context.applicationContext
        playClip(app, if (lang(app) == "he") R.raw.voice_he_extra else R.raw.voice_en_extra)
    }

    private fun lang(app: Context) =
        if (app.resources.configuration.locales[0].language in setOf("iw", "he")) "he" else "en"

    private fun playClip(app: Context, clip: Int) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        runCatching {
            stop()
            val mp = MediaPlayer.create(app, clip, attributes, audio.generateAudioSessionId()) ?: return
            player = mp
            audio.requestAudioFocus(focus)
            mp.setOnCompletionListener {
                audio.abandonAudioFocusRequest(focus)
                if (player === it) player = null
                it.release()
            }
            mp.start()
        }
    }

    private fun stop() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}

package dev.russell.yagi.core.pref

import android.content.Context
import android.content.SharedPreferences
import dev.russell.yagi.core.pref.PrefsStore.getString
import dev.russell.yagi.core.pref.PrefsStore.prefs

/**
 * Owns the global ad auto-skip settings: the default on/off state (a feed can override it, see
 * `AdSkipUtils`), how sure the detector has to be before a segment is skipped (the "sensitivity"
 * list preference and the confidence threshold derived from it), whether a finished download queues an analysis run, and whether the player
 * announces a skip with a snackbar. It reads and writes through [PrefsStore]; [Prefs] exposes
 * every member of it unchanged.
 *
 * The feature is opt-in ([isAdSkipEnabled] defaults to false) because detection is heuristic.
 * The threshold only ever gates [dev.russell.yagi.model.feed.AdSegment.Source.DETECTED]
 * segments: chapter-derived and hand-marked ones are exact and always skip.
 *
 * The user's Anthropic API key (for transcript analysis by Claude) is kept in its own
 * [SharedPreferences] file, [CLAUDE_KEY_FILE], so that the settings backup and Android Auto
 * Backup can leave exactly that file out.
 */
internal object AdSkipPrefs {

    /** Name of the SharedPreferences file that holds only the Anthropic API key. */
    const val CLAUDE_KEY_FILE = "anthropic"

    private const val CLAUDE_KEY = "api_key"

    /** Require a very sure detection: only segments at 0.75 confidence or above are skipped. */
    const val SENSITIVITY_LOW = "low"

    /** The default: skip from 0.60 confidence upwards. */
    const val SENSITIVITY_MEDIUM = "medium"

    /** Skip eagerly, from 0.45 confidence upwards, accepting more false positives. */
    const val SENSITIVITY_HIGH = "high"

    private const val MIN_CONFIDENCE_LOW = 0.75f
    private const val MIN_CONFIDENCE_MEDIUM = 0.60f
    private const val MIN_CONFIDENCE_HIGH = 0.45f

    var isAdSkipEnabled: Boolean
        get() = prefs.getBoolean(Prefs.PREF_AD_SKIP_ENABLED, false)
        set(enabled) {
            prefs.edit().putBoolean(Prefs.PREF_AD_SKIP_ENABLED, enabled).apply()
        }

    /** One of [SENSITIVITY_LOW], [SENSITIVITY_MEDIUM], [SENSITIVITY_HIGH]. */
    var adSkipSensitivity: String
        get() = getString(Prefs.PREF_AD_SKIP_SENSITIVITY, SENSITIVITY_MEDIUM)
        set(sensitivity) {
            prefs.edit().putString(Prefs.PREF_AD_SKIP_SENSITIVITY, sensitivity).apply()
        }

    /**
     * The lowest confidence a detected segment may have and still be skipped. An unknown stored
     * value reads as the medium threshold rather than throwing, so a hand-edited or migrated
     * preference cannot break playback.
     */
    val adSkipMinConfidence: Float
        get() = when (adSkipSensitivity) {
            SENSITIVITY_LOW -> MIN_CONFIDENCE_LOW
            SENSITIVITY_HIGH -> MIN_CONFIDENCE_HIGH
            else -> MIN_CONFIDENCE_MEDIUM
        }

    var isAdSkipAnalyzeOnDownload: Boolean
        get() = prefs.getBoolean(Prefs.PREF_AD_SKIP_ANALYZE_ON_DOWNLOAD, true)
        set(analyze) {
            prefs.edit().putBoolean(Prefs.PREF_AD_SKIP_ANALYZE_ON_DOWNLOAD, analyze).apply()
        }

    var isAdSkipShowSnackbar: Boolean
        get() = prefs.getBoolean(Prefs.PREF_AD_SKIP_SHOW_SNACKBAR, true)
        set(show) {
            prefs.edit().putBoolean(Prefs.PREF_AD_SKIP_SHOW_SNACKBAR, show).apply()
        }

    /**
     * Whether the audio analysis has completed for a media id. The result of an analysis is a
     * set of segments, and "none found" is indistinguishable from "never run" in that table, so
     * completion is remembered here. It only gates the analysis that playback of a downloaded
     * episode triggers; a fresh download and the "analyse this episode" action always run.
     */
    fun isAdAnalyzed(mediaId: Long): Boolean =
        prefs.getStringSet(Prefs.PREF_AD_SKIP_ANALYZED_MEDIA, emptySet())
            ?.contains(mediaId.toString()) == true

    fun markAdAnalyzed(mediaId: Long) {
        val current = prefs.getStringSet(Prefs.PREF_AD_SKIP_ANALYZED_MEDIA, emptySet())
            ?: emptySet()
        if (mediaId.toString() in current) {
            return
        }
        // getStringSet's result must never be mutated; write a fresh copy.
        val updated = HashSet(current)
        updated.add(mediaId.toString())
        prefs.edit().putStringSet(Prefs.PREF_AD_SKIP_ANALYZED_MEDIA, updated).apply()
    }

    /** Whether transcripts (when a podcast publishes one) are searched for sponsor reads. */
    var isAdSkipUseTranscripts: Boolean
        get() = prefs.getBoolean(Prefs.PREF_AD_SKIP_USE_TRANSCRIPTS, true)
        set(use) {
            prefs.edit().putBoolean(Prefs.PREF_AD_SKIP_USE_TRANSCRIPTS, use).apply()
        }

    /**
     * Whether the show's promotions of itself (memberships, merchandise, newsletters) are
     * skipped too. Off by default: unlike third-party ads, many listeners want to hear them.
     */
    var isAdSkipSelfPromo: Boolean
        get() = prefs.getBoolean(Prefs.PREF_AD_SKIP_SELF_PROMO, false)
        set(skip) {
            prefs.edit().putBoolean(Prefs.PREF_AD_SKIP_SELF_PROMO, skip).apply()
        }

    private val claudeKeyPrefs: SharedPreferences
        get() = PrefsStore.context.getSharedPreferences(CLAUDE_KEY_FILE, Context.MODE_PRIVATE)

    /** The stored Anthropic API key, trimmed, or null when none (or only blanks) is stored. */
    fun getClaudeApiKey(): String? =
        claudeKeyPrefs.getString(CLAUDE_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Stores the Anthropic API key (trimmed); null or blank removes it. */
    fun setClaudeApiKey(key: String?) {
        val trimmed = key?.trim()
        val editor = claudeKeyPrefs.edit()
        if (trimmed.isNullOrEmpty()) {
            editor.remove(CLAUDE_KEY)
        } else {
            editor.putString(CLAUDE_KEY, trimmed)
        }
        editor.apply()
    }

    fun hasClaudeApiKey(): Boolean = getClaudeApiKey() != null

    /**
     * How the transcript of a media id was last analysed for ads (the method string is the
     * caller's, e.g. which detector ran), or null when it never was. Entries are stored as
     * `"<mediaId>:<method>"` in one string set.
     */
    fun getTranscriptAdMethod(mediaId: Long): String? {
        val prefix = "$mediaId:"
        return prefs.getStringSet(Prefs.PREF_AD_SKIP_TRANSCRIPT_ANALYZED, emptySet())
            ?.firstOrNull { it.startsWith(prefix) }
            ?.substring(prefix.length)
    }

    /** Records how the transcript of a media id was analysed, replacing any earlier entry. */
    fun setTranscriptAdMethod(mediaId: Long, method: String) {
        val prefix = "$mediaId:"
        val entry = prefix + method
        val current = prefs.getStringSet(Prefs.PREF_AD_SKIP_TRANSCRIPT_ANALYZED, emptySet())
            ?: emptySet()
        if (entry in current && current.count { it.startsWith(prefix) } == 1) {
            return
        }
        // getStringSet's result must never be mutated; write a fresh copy.
        val updated = HashSet<String>(current.size + 1)
        current.filterTo(updated) { !it.startsWith(prefix) }
        updated.add(entry)
        prefs.edit().putStringSet(Prefs.PREF_AD_SKIP_TRANSCRIPT_ANALYZED, updated).apply()
    }
}

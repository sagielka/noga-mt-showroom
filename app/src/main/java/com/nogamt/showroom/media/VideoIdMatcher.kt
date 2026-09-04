package com.nogamt.showroom.media

/**
 * Turns a media file name into the video id the Lovable playlist uses.
 *
 * Rules, highest priority first:
 *  1. YouTube id in square brackets anywhere in the name -> "... [N-mTagRbXuM].mp4"
 *  2. YouTube id at the very end of the name            -> "...-N-mTagRbXuM.mp4"
 *  3. The whole base name is itself a valid id          -> "N-mTagRbXuM.mp4"
 *
 * Anything else is reported as UNMATCHED but still indexed under its file name so
 * exact-file-name lookups from the web app keep working.
 */
object VideoIdMatcher {

    private val BRACKET_ID = Regex("\\[([A-Za-z0-9_-]{11})]")
    private val TRAILING_ID = Regex("[-_. ]([A-Za-z0-9_-]{11})$")
    private val PLAIN_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Ids/keys accepted from JavaScript. Deliberately narrow - no path characters. */
    private val SAFE_REQUEST_KEY = Regex("^[A-Za-z0-9 ._\\-]{1,160}$")

    data class Result(val id: String, val type: MatchType)

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun baseNameOf(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).trim()

    fun match(fileName: String): Result? {
        val base = baseNameOf(fileName)
        BRACKET_ID.find(base)?.let { return Result(it.groupValues[1], MatchType.BRACKET_ID) }
        TRAILING_ID.find(base)?.let { return Result(it.groupValues[1], MatchType.SUFFIX_ID) }
        if (PLAIN_ID.matches(base)) return Result(base, MatchType.FILENAME_ID)
        return null
    }

    /** Secondary lookup key so "exact file name" requests resolve too. */
    fun nameKey(fileName: String): String = baseNameOf(fileName).lowercase()

    fun isSafeRequestKey(key: String?): Boolean =
        !key.isNullOrBlank() && SAFE_REQUEST_KEY.matches(key)
}

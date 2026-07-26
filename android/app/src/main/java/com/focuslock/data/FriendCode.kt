package com.focuslock.data

import java.security.MessageDigest

/**
 * Pure logic for human-shareable friend codes. No Android dependencies —
 * derives a code from a Firebase uid via a one-way hash rather than
 * generating and storing a random code.
 *
 * Tradeoff (deterministic-from-uid vs. random-and-stored):
 *  - Deterministic: the code is always recomputable from the uid with no
 *    extra write, and two devices signed into the same account never
 *    disagree on "my code". Downside: it cannot be rotated if leaked (a
 *    leaked code stays valid until the uid itself is invalidated) and, in
 *    the birthday-paradox sense, two different uids could theoretically hash
 *    to the same code. At personal-app scale (a handful of friends, not a
 *    public directory) a 40-bit code space makes that collision practically
 *    irrelevant, and simplicity wins over rotatability here.
 *  - The code still has to be looked up by a Firestore query (uid -> code is
 *    one-way), so [FocusPolicyClient] stores it on the profile doc at
 *    creation time and queries `where friendCode == X` rather than trying to
 *    reverse the hash.
 *
 * Alphabet is Crockford's Base32 (excludes I, L, O, U to avoid visual
 * ambiguity with 1, 1, 0, and profanity respectively) — the same spirit as
 * [FocusPolicyClient.sanitizeSyncId]'s normalization, just for a fixed-width
 * code instead of a free-typed slug.
 */
object FriendCode {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 8

    /** Derives an 8-character friend code from [uid]. Deterministic — same uid always yields the same code. */
    fun generate(uid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        // 40 bits = 8 Base32 symbols at 5 bits each.
        var bits = 0L
        for (i in 0 until 5) {
            bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
        }
        val sb = StringBuilder(CODE_LENGTH)
        for (i in 0 until CODE_LENGTH) {
            val shift = 5 * (CODE_LENGTH - 1 - i)
            val index = ((bits shr shift) and 0x1F).toInt()
            sb.append(ALPHABET[index])
        }
        return sb.toString()
    }

    /**
     * Normalizes user-typed input the way Crockford Base32 decoding does:
     * uppercase, strip anything that isn't alphanumeric, then fold the
     * visually-ambiguous letters back onto their canonical digit (O -> 0,
     * I/L -> 1). The result may still contain characters outside
     * [ALPHABET] (e.g. a stray "U") — [isValid] is what rejects those.
     */
    fun normalize(raw: String): String {
        var clean = raw.trim().uppercase()
        clean = clean.replace(Regex("[^A-Z0-9]"), "")
        clean = clean.replace('O', '0').replace('I', '1').replace('L', '1')
        return clean
    }

    /** True if [raw], once normalized, is a well-formed friend code (right length, every char in the alphabet). */
    fun isValid(raw: String): Boolean {
        val normalized = normalize(raw)
        if (normalized.length != CODE_LENGTH) return false
        return normalized.all { it in ALPHABET }
    }
}

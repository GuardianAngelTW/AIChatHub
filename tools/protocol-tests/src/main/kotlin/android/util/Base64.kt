package android.util
// JVM stand-in for android.util.Base64 (test only)
object Base64 {
    const val DEFAULT = 0; const val NO_PADDING = 1; const val NO_WRAP = 2; const val URL_SAFE = 8
    @JvmStatic fun encodeToString(b: ByteArray, flags: Int): String {
        var e = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) e = e.withoutPadding()
        return e.encodeToString(b)
    }
    @JvmStatic fun decode(s: String, flags: Int): ByteArray =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder().decode(s) else java.util.Base64.getDecoder().decode(s)
}

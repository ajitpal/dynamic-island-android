package com.ajit.dynamicisland.core.inbox

/** Finds one-time passwords in notification text. Pure Kotlin (unit-tested). */
object Otp {
    // "OTP is 123456", "Your code: 4821", "Verification code 829-114", "123456 is your OTP"
    private val keyThenCode = Regex(
        "(?i)\\b(otp|one[- ]time password|passcode|verification code|security code|login code|code|pin)\\b[^0-9]{0,30}(\\d{3}[- ]?\\d{1,5})"
    )
    private val codeThenKey = Regex(
        "(?i)\\b(\\d{3}[- ]?\\d{1,5})\\b[^0-9]{0,25}\\b(is your|is the|as your|as)\\b[^0-9]{0,20}\\b(otp|code|one[- ]time password|verification)"
    )

    fun extract(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val s = text.toString()
        val m = keyThenCode.find(s)?.groupValues?.get(2) ?: codeThenKey.find(s)?.groupValues?.get(1) ?: return null
        val digits = m.filter { it.isDigit() }
        return digits.takeIf { it.length in 4..8 }
    }

    fun looksLikeOtp(text: CharSequence?) = extract(text) != null
}

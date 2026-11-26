// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.cjk.plugin.japanese

/**
 * High-performance Romaji-to-Kana state machine supporting Hepburn,
 * Kunrei-shiki, sokuon (っ), hatsuan (ん), small kana, and common punctuation.
 */
object RomajiConverter {

    private val ROMAJI_TABLE: Map<String, String> = mapOf(
        // Vowels
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",

        // K
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",

        // S / Sh
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",

        // T / Ch / Ts
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ", "te" to "て", "to" to "と",

        // N
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",

        // H / F
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",

        // M
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",

        // Y
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",

        // R
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",

        // W
        "wa" to "わ", "wo" to "を",

        // G (voiced K)
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",

        // Z / J (voiced S)
        "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",

        // D (voiced T)
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "де", "de" to "で", "do" to "ど",

        // B (voiced H)
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",

        // P (semi-voiced H)
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",

        // Yōon (combining kana)
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ", "she" to "しぇ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ", "che" to "ちぇ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ", "je" to "じぇ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",

        // Foreign sounds
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "va" to "ヴぁ", "vi" to "ヴぃ", "vu" to "ヴ", "ve" to "ヴぇ", "vo" to "ヴぉ",

        // Small kana
        "xtsu" to "っ", "ltsu" to "っ",
        "xa" to "ぁ", "la" to "ぁ", "xi" to "ぃ", "li" to "ぃ",
        "xu" to "ぅ", "lu" to "ぅ", "xe" to "ぇ", "le" to "ぇ",
        "xo" to "ぉ", "lo" to "ぉ",
        "xya" to "ゃ", "lya" to "ゃ", "xyu" to "ゅ", "lyu" to "ゅ",
        "xyo" to "ょ", "lyo" to "ょ",
        "xwa" to "ゎ", "lwa" to "ゎ",

        // Punctuation
        "-" to "ー",
        "," to "、",
        "." to "。",
        "?" to "？",
        "!" to "！",
        "~" to "〜"
    )

    data class ConversionResult(
        val kana: String,
        val pending: String
    ) {
        val fullDisplay: String get() = kana + pending
    }

    /** Hiragana and Japanese punctuation, as sent by a kana layout. */
    fun isDirectInput(c: Char): Boolean = c in 'ぁ'..'ゖ' || c in "ー〜、。？！…「」・"

    /**
     * Converts a stream of romaji keystrokes into Hiragana and any trailing pending consonant.
     * @param romaji Raw Latin romaji characters.
     * @param finalize If true, forces trailing 'n' to 'ん'.
     */
    fun convert(romaji: String, finalize: Boolean = false): ConversionResult {
        if (romaji.isEmpty()) return ConversionResult("", "")

        val lower = romaji.lowercase()
        val kanaBuilder = StringBuilder()
        var buf = ""

        var i = 0
        while (i < lower.length) {
            val c = lower[i]
            // kana typed on a kana layout is already converted: the romaji rules must not see it
            // (the double consonant rule would turn はは into っは)
            if (isDirectInput(c)) {
                kanaBuilder.append(if (buf == "n") "ん" else buf).append(c)
                buf = ""
                i++
                continue
            }
            buf += c

            // 1. Direct match in dictionary table
            val matched = ROMAJI_TABLE[buf]
            if (matched != null) {
                kanaBuilder.append(matched)
                buf = ""
            }
            // 2. Double 'n' (e.g. "konnichiwa" -> first 'n' is "ん", second 'n' carries over)
            else if (buf.length >= 2 && buf[0] == 'n' && buf[1] == 'n') {
                kanaBuilder.append("ん")
                buf = "n"
            }
            // 3. Double consonant (Sokuon 'っ')
            else if (buf.length >= 2 && buf[0] == buf[1] && buf[0] !in "aeiou") {
                kanaBuilder.append("っ")
                buf = buf.substring(1)
            }
            // 4. 'n' followed by non-vowel consonant other than 'y' or apostrophe -> convert 'n' to 'ん'
            else if (buf.length >= 2 && buf[0] == 'n' && buf[1] !in "aeiouy'" && buf != "ny") {
                kanaBuilder.append("ん")
                buf = buf.substring(1)
                val nextMatch = ROMAJI_TABLE[buf]
                if (nextMatch != null) {
                    kanaBuilder.append(nextMatch)
                    buf = ""
                }
            }
            // 5. Explicit "n'" delimiter
            else if (buf == "n'") {
                kanaBuilder.append("ん")
                buf = ""
            }

            i++
        }

        var pending = buf
        if (finalize && pending == "n") {
            kanaBuilder.append("ん")
            pending = ""
        }

        return ConversionResult(kanaBuilder.toString(), pending)
    }
}

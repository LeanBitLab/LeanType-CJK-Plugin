// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.cjk.plugin.japanese

import helium314.keyboard.latin.cjk.CjkCandidate
import helium314.keyboard.latin.cjk.CjkContextData

/**
 * Japanese composition engine managing session pre-edit state,
 * dictionary lookups, phonetic candidate generation, and commits.
 */
class JapaneseEngine(private val dictionary: JapaneseDictionary) {

    private val rawBuffer = StringBuilder()
    private var rawCursor = 0
    private var pendingCommit: String? = null
    private var cachedContext: CjkContextData? = null
    private var currentReading: String = ""

    fun isComposing(): Boolean = rawBuffer.isNotEmpty()

    fun processKey(keysym: Int, mask: Int): Boolean {
        when (keysym) {
            XK_LEFT -> {
                if (rawBuffer.isNotEmpty()) {
                    if (rawCursor > 0) {
                        rawCursor--
                        updateContext()
                    }
                    return true
                }
                return false
            }

            XK_RIGHT -> {
                if (rawBuffer.isNotEmpty()) {
                    if (rawCursor < rawBuffer.length) {
                        rawCursor++
                        updateContext()
                    }
                    return true
                }
                return false
            }

            XK_BACKSPACE -> {
                if (rawBuffer.isNotEmpty()) {
                    if (rawCursor > 0) {
                        rawBuffer.deleteCharAt(rawCursor - 1)
                        rawCursor--
                        if (rawBuffer.isEmpty()) {
                            clearComposition()
                        } else {
                            updateContext()
                        }
                        return true
                    }
                    return false
                }
                return false
            }

            XK_DELETE -> {
                if (rawBuffer.isNotEmpty()) {
                    if (rawCursor < rawBuffer.length) {
                        rawBuffer.deleteCharAt(rawCursor)
                        if (rawBuffer.isEmpty()) {
                            clearComposition()
                        } else {
                            updateContext()
                        }
                        return true
                    }
                    return false
                }
                return false
            }

            XK_RETURN -> {
                if (rawBuffer.isNotEmpty()) {
                    // Enter commits raw Hiragana preedit directly without Kanji conversion
                    val conv = RomajiConverter.convert(rawBuffer.toString(), finalize = true)
                    val commitText = conv.kana.ifEmpty { rawBuffer.toString() }
                    pendingCommit = commitText
                    clearComposition()
                    return true
                }
                return false
            }

            XK_SPACE -> {
                if (rawBuffer.isNotEmpty()) {
                    // Space selects top candidate
                    return selectCandidate(0)
                }
                return false
            }

            // Quick selection with numeric keys 1..9
            in '1'.code..'9'.code -> {
                if (rawBuffer.isNotEmpty()) {
                    val index = keysym - '1'.code
                    return selectCandidate(index)
                }
                return false
            }

            // Letters, hyphens (prolonged sound mark), punctuation
            in 'a'.code..'z'.code, in 'A'.code..'Z'.code,
            '-'.code, ','.code, '.'.code, '?'.code, '!'.code, '\''.code, '~'.code -> {
                val ch = if (keysym in 'A'.code..'Z'.code) (keysym + 32).toByte() else keysym.toByte()
                rawBuffer.insert(rawCursor, ch.toInt().toChar())
                rawCursor++
                updateContext()
                return true
            }
        }

        // kana and Japanese punctuation from a kana layout
        if (keysym in 0..0xFFFF && RomajiConverter.isDirectInput(keysym.toChar())) {
            rawBuffer.insert(rawCursor, keysym.toChar())
            rawCursor++
            updateContext()
            return true
        }

        return false
    }

    fun getContext(): CjkContextData? = cachedContext

    fun getCommit(): String? {
        val commit = pendingCommit
        pendingCommit = null
        return commit
    }

    fun selectCandidate(index: Int): Boolean {
        val context = cachedContext ?: return false
        val candidates = context.candidates
        if (index !in candidates.indices) return false

        val chosenText = candidates[index].text
        if (currentReading.isNotEmpty()) {
            dictionary.recordWord(currentReading, chosenText)
        }

        pendingCommit = chosenText
        clearComposition()
        return true
    }

    fun clearComposition() {
        rawBuffer.clear()
        rawCursor = 0
        cachedContext = null
        currentReading = ""
    }

    private fun updateContext() {
        if (rawBuffer.isEmpty()) {
            cachedContext = null
            currentReading = ""
            return
        }

        val conv = RomajiConverter.convert(rawBuffer.toString(), finalize = false)
        val baseDisplay = if (conv.pending == "n") conv.kana + "ん" else conv.fullDisplay
        val preeditDisplay = if (rawCursor in 0 until rawBuffer.length) {
            val cursorKanaPos = minOf(rawCursor, baseDisplay.length)
            StringBuilder(baseDisplay).insert(cursorKanaPos, '|').toString()
        } else {
            baseDisplay
        }

        // Reading used for dictionary lookups (trailing single 'n' treated as 'ん')
        val queryReading = if (conv.pending == "n") conv.kana + "ん" else conv.kana + conv.pending
        currentReading = queryReading

        val candidateList = ArrayList<CjkCandidate>()
        var candIndex = 0

        // 1. Dictionary Kanji conversions
        if (queryReading.isNotEmpty()) {
            val dictCands = dictionary.lookup(queryReading, MAX_DICT_CANDIDATES)
            for (c in dictCands) {
                if (candidateList.none { it.text == c }) {
                    candidateList.add(CjkCandidate(candIndex++, c, null))
                }
            }
        }

        // 2. Hiragana representation
        val hiraganaText = conv.kana + if (conv.pending == "n") "ん" else ""
        if (hiraganaText.isNotEmpty() && candidateList.none { it.text == hiraganaText }) {
            candidateList.add(CjkCandidate(candIndex++, hiraganaText, null))
        }

        // 3. Full-width Katakana
        if (hiraganaText.isNotEmpty()) {
            val katakanaText = KanaUtils.hiraganaToKatakana(hiraganaText)
            if (katakanaText.isNotEmpty() && candidateList.none { it.text == katakanaText }) {
                candidateList.add(CjkCandidate(candIndex++, katakanaText, null))
            }

            // 4. Half-width Katakana
            val halfKatakana = KanaUtils.katakanaToHalfWidth(katakanaText)
            if (halfKatakana.isNotEmpty() && candidateList.none { it.text == halfKatakana }) {
                candidateList.add(CjkCandidate(candIndex++, halfKatakana, null))
            }
        }

        // 5. Raw Romaji
        val rawRomaji = rawBuffer.toString()
        if (candidateList.none { it.text == rawRomaji }) {
            candidateList.add(CjkCandidate(candIndex++, rawRomaji, null))
        }

        // 6. Uppercase Romaji
        val upperRomaji = rawRomaji.uppercase()
        if (upperRomaji != rawRomaji && candidateList.none { it.text == upperRomaji }) {
            candidateList.add(CjkCandidate(candIndex++, upperRomaji, null))
        }

        // 7. Predictive word completions
        if (queryReading.length >= 2 && candidateList.size < MAX_TOTAL_CANDIDATES) {
            val predictions = dictionary.predict(queryReading, MAX_PREDICTIONS)
            for (pred in predictions) {
                val word = pred.second
                if (word.isNotEmpty() && candidateList.none { it.text == word }) {
                    candidateList.add(CjkCandidate(candIndex++, word, null))
                    if (candidateList.size >= MAX_TOTAL_CANDIDATES) break
                }
            }
        }

        cachedContext = CjkContextData(
            preedit = preeditDisplay,
            candidates = candidateList,
            selectedIndex = 0,
            hasPrevPage = false,
            hasNextPage = false
        )
    }

    companion object {
        private const val MAX_DICT_CANDIDATES = 20
        private const val MAX_PREDICTIONS = 8
        private const val MAX_TOTAL_CANDIDATES = 30

        private const val XK_BACKSPACE = 0xff08
        private const val XK_RETURN = 0xff0d
        private const val XK_SPACE = 0x0020
        private const val XK_DELETE = 0xffff
        private const val XK_LEFT = 0xff51
        private const val XK_RIGHT = 0xff53
    }
}

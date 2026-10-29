// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.cjk.plugin

import android.content.Context
import android.util.Log
import com.leanbitlab.leantype.cjk.plugin.japanese.JapaneseDictionary
import com.leanbitlab.leantype.cjk.plugin.japanese.JapaneseEngine
import helium314.keyboard.latin.cjk.CjkCandidate
import helium314.keyboard.latin.cjk.CjkContextData
import helium314.keyboard.latin.cjk.ICjkEngine
import java.io.File
import java.io.FileOutputStream

class CjkEngineImpl : ICjkEngine {

    private var activeLanguage = "zh"
    private var isPinyinReady = false
    private var isJapaneseReady = false

    // Chinese Pinyin Engine state
    private val pinyinBuffer = StringBuilder()
    private var pinyinCursor = 0
    private var lastPinyinCandidateCount = 0
    private var pendingPinyinCommit: String? = null
    private var cachedPinyinContext: CjkContextData? = null

    // Japanese Engine
    private var japaneseDictionary: JapaneseDictionary? = null
    private var japaneseEngine: JapaneseEngine? = null

    override fun getInterfaceVersion(): Int = 2

    override fun setLocale(locale: String): Boolean {
        val lang = locale.lowercase().split("_", "-")[0]
        if (lang == "ja" || lang == "zh") {
            if (activeLanguage != lang) {
                clearComposition()
                activeLanguage = lang
                Log.d(TAG, "Switched CJK engine active language to $activeLanguage")
            }
            return true
        }
        return false
    }

    override fun supportsLanguage(language: String): Boolean {
        val lang = language.lowercase().split("_", "-")[0]
        return lang == "zh" || lang == "ja"
    }

    override fun init(context: Context, sharedDir: String, userDir: String): Boolean {
        try {
            val sharedFolder = File(sharedDir).apply { mkdirs() }
            val userFolder = File(userDir).apply { mkdirs() }

            // 1. Initialize Chinese Pinyin Engine
            if (PinyinDecoderJni.isNativeLoaded()) {
                val pinyinDictFile = File(sharedFolder, DICT_PINYIN_FILENAME)
                if (extractAsset(context, DICT_PINYIN_FILENAME, pinyinDictFile)) {
                    val userDictFile = File(userFolder, USER_PINYIN_FILENAME)
                    val success = PinyinDecoderJni.nativeOpenDecoder(
                        pinyinDictFile.absolutePath,
                        userDictFile.absolutePath
                    )
                    if (success) {
                        isPinyinReady = true
                        Log.i(TAG, "Pinyin decoder engine initialized successfully")
                    } else {
                        Log.e(TAG, "Failed to open Pinyin decoder: ${pinyinDictFile.absolutePath}")
                    }
                }
            } else {
                Log.w(TAG, "Native library jni_pinyin is not loaded")
            }

            // 2. Initialize Japanese Engine
            val jaDictFile = File(sharedFolder, DICT_JAPANESE_FILENAME)
            if (extractAsset(context, DICT_JAPANESE_FILENAME, jaDictFile)) {
                val jaUserDictFile = File(userFolder, USER_JAPANESE_FILENAME)
                val jaDict = JapaneseDictionary()
                if (jaDict.init(jaDictFile, jaUserDictFile)) {
                    japaneseDictionary = jaDict
                    japaneseEngine = JapaneseEngine(jaDict)
                    isJapaneseReady = true
                    Log.i(TAG, "Japanese engine initialized successfully")
                } else {
                    Log.e(TAG, "Failed to initialize Japanese dictionary: ${jaDictFile.absolutePath}")
                }
            }

            return isPinyinReady || isJapaneseReady
        } catch (e: Exception) {
            Log.e(TAG, "Exception during CJK engine initialization", e)
            return false
        }
    }

    override fun isAvailable(): Boolean {
        return if (activeLanguage == "ja") isJapaneseReady else isPinyinReady
    }

    override fun processKey(keysym: Int, mask: Int): Boolean {
        if (!isAvailable()) return false

        if (activeLanguage == "ja") {
            return japaneseEngine?.processKey(keysym, mask) ?: false
        }

        // Chinese Pinyin processing
        when (keysym) {
            XK_LEFT -> {
                if (pinyinBuffer.isNotEmpty()) {
                    if (pinyinCursor > 0) {
                        pinyinCursor--
                        updatePinyinContext(lastPinyinCandidateCount)
                    }
                    return true
                }
                return false
            }

            XK_RIGHT -> {
                if (pinyinBuffer.isNotEmpty()) {
                    if (pinyinCursor < pinyinBuffer.length) {
                        pinyinCursor++
                        updatePinyinContext(lastPinyinCandidateCount)
                    }
                    return true
                }
                return false
            }

            XK_BACKSPACE -> {
                if (pinyinBuffer.isNotEmpty()) {
                    if (pinyinCursor > 0) {
                        pinyinBuffer.deleteCharAt(pinyinCursor - 1)
                        pinyinCursor--
                        if (pinyinBuffer.isEmpty()) {
                            clearComposition()
                        } else {
                            val count = searchCurrentPinyin()
                            updatePinyinContext(count)
                        }
                        return true
                    }
                    return false
                }
                return false
            }

            XK_DELETE -> {
                if (pinyinBuffer.isNotEmpty()) {
                    if (pinyinCursor < pinyinBuffer.length) {
                        pinyinBuffer.deleteCharAt(pinyinCursor)
                        if (pinyinBuffer.isEmpty()) {
                            clearComposition()
                        } else {
                            val count = searchCurrentPinyin()
                            updatePinyinContext(count)
                        }
                        return true
                    }
                    return false
                }
                return false
            }

            XK_RETURN -> {
                if (pinyinBuffer.isNotEmpty()) {
                    pendingPinyinCommit = pinyinBuffer.toString()
                    clearComposition()
                    return true
                }
                return false
            }

            XK_SPACE -> {
                if (pinyinBuffer.isNotEmpty()) {
                    return selectCandidate(0)
                }
                return false
            }

            // Quick selection with numeric keys 1..9
            in '1'.code..'9'.code -> {
                if (pinyinBuffer.isNotEmpty()) {
                    val index = keysym - '1'.code
                    return selectCandidate(index)
                }
                return false
            }

            // Letters 'a'..'z', 'A'..'Z', or single quote '\'' delimiter
            in 'a'.code..'z'.code, in 'A'.code..'Z'.code, '\''.code -> {
                val ch = if (keysym in 'A'.code..'Z'.code) (keysym + 32).toByte() else keysym.toByte()
                pinyinBuffer.insert(pinyinCursor, ch.toInt().toChar())
                pinyinCursor++
                val count = searchCurrentPinyin()
                updatePinyinContext(count)
                return true
            }

            // Punctuation handling during active composition: commit candidate 0 + Chinese punctuation
            ','.code, '.'.code, '!'.code, '?'.code, ':'.code, ';'.code,
            0xff0c /* ， */, 0x3002 /* 。 */, 0xff01 /* ！ */, 0xff1f /* ？ */,
            0xff1a /* ： */, 0xff1b /* ； */, 0x3001 /* 、 */ -> {
                if (pinyinBuffer.isNotEmpty()) {
                    val topCandidate = cachedPinyinContext?.candidates?.firstOrNull()?.text ?: pinyinBuffer.toString()
                    val punctChar = when (keysym) {
                        ','.code, 0xff0c -> "，"
                        '.'.code, 0x3002 -> "。"
                        '!'.code, 0xff01 -> "！"
                        '?'.code, 0xff1f -> "？"
                        ':'.code, 0xff1a -> "："
                        ';'.code, 0xff1b -> "；"
                        0x3001 -> "、"
                        else -> keysym.toChar().toString()
                    }
                    pendingPinyinCommit = topCandidate + punctChar
                    clearComposition()
                    return true
                }
                return false
            }
        }

        return false
    }

    override fun getContext(): CjkContextData? {
        return if (activeLanguage == "ja") {
            japaneseEngine?.getContext()
        } else {
            cachedPinyinContext
        }
    }

    override fun getCommit(): String? {
        return if (activeLanguage == "ja") {
            japaneseEngine?.getCommit()
        } else {
            val commit = pendingPinyinCommit
            pendingPinyinCommit = null
            commit
        }
    }

    override fun selectCandidate(index: Int): Boolean {
        if (!isAvailable()) return false

        if (activeLanguage == "ja") {
            return japaneseEngine?.selectCandidate(index) ?: false
        }

        // Chinese Pinyin candidate selection
        if (pinyinBuffer.isEmpty()) return false
        val candidates = cachedPinyinContext?.candidates ?: return false
        if (index !in candidates.indices) return false

        val chosenText = candidates[index].text
        val newCandCount = PinyinDecoderJni.nativeChoose(index)
        val fixedLen = PinyinDecoderJni.nativeGetFixedLen()
        val pyStr = PinyinDecoderJni.nativeGetPyStr(false)

        if (newCandCount <= 1 || fixedLen >= pyStr.length || newCandCount == 0) {
            val fullText = if (newCandCount == 1) {
                PinyinDecoderJni.nativeGetCandidate(0) ?: chosenText
            } else {
                chosenText
            }
            pendingPinyinCommit = fullText
            clearComposition()
            return true
        }

        updatePinyinContext(newCandCount)
        return true
    }

    override fun clearComposition() {
        if (activeLanguage == "ja") {
            japaneseEngine?.clearComposition()
        } else {
            pinyinBuffer.clear()
            pinyinCursor = 0
            lastPinyinCandidateCount = 0
            cachedPinyinContext = null
            if (isPinyinReady) {
                PinyinDecoderJni.nativeResetSearch()
            }
        }
    }

    override fun destroySession() {
        clearComposition()
        if (isPinyinReady) {
            PinyinDecoderJni.nativeFlushCache()
        }
        japaneseDictionary?.flush()
    }

    override fun cleanup() {
        clearComposition()
        if (isPinyinReady) {
            PinyinDecoderJni.nativeCloseDecoder()
            isPinyinReady = false
        }
        japaneseDictionary?.close()
        japaneseDictionary = null
        japaneseEngine = null
        isJapaneseReady = false
    }

    private fun extractAsset(context: Context, filename: String, destinationFile: File): Boolean {
        if (destinationFile.exists() && destinationFile.length() > 0L) {
            return true
        }

        Log.i(TAG, "Extracting $filename to ${destinationFile.absolutePath}")
        val inputStream = javaClass.classLoader?.getResourceAsStream("assets/$filename")
            ?: try {
                context.createPackageContext("com.leanbitlab.leantype.cjk.plugin", 0).assets.open(filename)
            } catch (_: Exception) {
                null
            }
            ?: try {
                context.assets.open(filename)
            } catch (_: Exception) {
                null
            }

        if (inputStream != null) {
            inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            return true
        } else {
            Log.e(TAG, "Could not locate $filename in plugin bundle")
            return false
        }
    }

    private fun searchCurrentPinyin(): Int {
        if (pinyinBuffer.isEmpty()) {
            lastPinyinCandidateCount = 0
            return 0
        }
        val query = normalizePinyin(pinyinBuffer.toString())
        val count = PinyinDecoderJni.nativeSearch(query)
        lastPinyinCandidateCount = count
        return count
    }

    private fun normalizePinyin(raw: String): String {
        if (raw.isEmpty()) return raw
        // 1. Map [ln]ve -> [ln]ue (e.g. lve -> lue, nve -> nue, lvexian -> luexian)
        var s = REGEX_L_N_VE.replace(raw, "$1ue")
        // 2. Map [jqxy]v -> [jqxy]u (e.g. jv -> ju, qv -> qu, xv -> xu, yv -> yu, xve -> xue)
        s = REGEX_J_Q_X_Y_V.replace(s, "$1u")
        // 3. Map isolated v at start of word or after syllable delimiter ' (e.g. v -> yu, ve -> yue, van -> yuan, vn -> yun)
        s = REGEX_ISOLATED_V.replace(s, "$1yu")
        return s
    }

    private fun updatePinyinContext(candidateCount: Int) {
        if (pinyinBuffer.isEmpty()) {
            cachedPinyinContext = null
            return
        }

        val pyStr = PinyinDecoderJni.nativeGetPyStr(false)
        val base = if (pyStr.isNotEmpty() && pyStr.length == pinyinBuffer.length) pyStr else pinyinBuffer.toString()
        val preeditDisplay = if (pinyinCursor in 0 until base.length) {
            StringBuilder(base).insert(pinyinCursor, '|').toString()
        } else {
            base
        }

        val maxFetch = if (candidateCount > 0) minOf(candidateCount, MAX_CANDIDATES_PER_PAGE) else 0
        val candidateList = ArrayList<CjkCandidate>(maxFetch)

        for (i in 0 until maxFetch) {
            val candText = PinyinDecoderJni.nativeGetCandidate(i)
            if (!candText.isNullOrEmpty()) {
                candidateList.add(CjkCandidate(index = i, text = candText, comment = null))
            }
        }

        cachedPinyinContext = CjkContextData(
            preedit = preeditDisplay,
            candidates = candidateList,
            selectedIndex = 0,
            hasPrevPage = false,
            hasNextPage = candidateCount > MAX_CANDIDATES_PER_PAGE
        )
    }

    companion object {
        private const val TAG = "CjkEngineImpl"
        private const val DICT_PINYIN_FILENAME = "dict_pinyin.dat"
        private const val USER_PINYIN_FILENAME = "usr_pinyin.dat"
        private const val DICT_JAPANESE_FILENAME = "dict_japanese.dat"
        private const val USER_JAPANESE_FILENAME = "usr_japanese.dat"
        private const val MAX_CANDIDATES_PER_PAGE = 30

        private const val XK_BACKSPACE = 0xff08
        private const val XK_RETURN = 0xff0d
        private const val XK_SPACE = 0x0020
        private const val XK_DELETE = 0xffff
        private const val XK_LEFT = 0xff51
        private const val XK_RIGHT = 0xff53

        private val REGEX_L_N_VE = Regex("([lnLN])ve")
        private val REGEX_J_Q_X_Y_V = Regex("([jqxyJQXY])v")
        private val REGEX_ISOLATED_V = Regex("(^|')v")
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.cjk.plugin.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JapaneseEngineTest {

    @Test
    fun testRomajiConverter() {
        assertEquals("にほん", RomajiConverter.convert("nihon", finalize = true).kana)
        assertEquals("とうきょう", RomajiConverter.convert("toukyou", finalize = true).kana)
        assertEquals("かんじ", RomajiConverter.convert("kanji", finalize = true).kana)
        assertEquals("がっこう", RomajiConverter.convert("gakkou", finalize = true).kana)
        assertEquals("まって", RomajiConverter.convert("matte", finalize = true).kana)
        assertEquals("たんい", RomajiConverter.convert("tan'i", finalize = true).kana)
        assertEquals("たに", RomajiConverter.convert("tani", finalize = true).kana)
        assertEquals("しんかんせん", RomajiConverter.convert("shinkansen", finalize = true).kana)
        assertEquals("きゃりー", RomajiConverter.convert("kyari-", finalize = true).kana)
        assertEquals("こんにちは", RomajiConverter.convert("konnichiha", finalize = true).kana)
        assertEquals("こんにちわ", RomajiConverter.convert("konnichiwa", finalize = true).kana)
        assertEquals("じゃぱん", RomajiConverter.convert("japan", finalize = true).kana)
        assertEquals("ちゃいろ", RomajiConverter.convert("chairo", finalize = true).kana)
        assertEquals("しゃしん", RomajiConverter.convert("syasin", finalize = true).kana)
        assertEquals("、。", RomajiConverter.convert(",.", finalize = true).kana)
    }

    @Test
    fun testKanaInputSkipsRomajiRules() {
        // doubled kana must not become a small tsu, as doubled consonants do in romaji
        assertEquals("はは", RomajiConverter.convert("はは", finalize = true).kana)
        assertEquals("ああ", RomajiConverter.convert("ああ", finalize = false).kana)
        assertEquals("がっこう、。", RomajiConverter.convert("がっこう、。", finalize = true).kana)
        assertEquals("", RomajiConverter.convert("らー", finalize = false).pending)

        val dictFile = if (File("src/main/assets/dict_japanese.dat").exists()) {
            File("src/main/assets/dict_japanese.dat")
        } else {
            File("app/src/main/assets/dict_japanese.dat")
        }
        val dict = JapaneseDictionary()
        assertTrue(dict.init(dictFile, File.createTempFile("test_usr_ja_kana", ".dat").apply { deleteOnExit() }))
        val engine = JapaneseEngine(dict)

        for (c in "にほんご") assertTrue(engine.processKey(c.code, 0))
        assertTrue(engine.processKey(0xff08, 0)) // backspace removes one whole kana
        val ctx = engine.getContext()!!
        assertEquals("にほん", ctx.preedit)
        assertTrue(ctx.candidates.map { it.text }.containsAll(listOf("日本", "にほん", "ニホン")))
        assertTrue(engine.processKey(0xff0d, 0)) // return commits the kana as typed
        assertEquals("にほん", engine.getCommit())

        // other characters are still left to the keyboard
        assertFalse(engine.processKey('漢'.code, 0))
        assertFalse(engine.isComposing())
        dict.close()
    }

    @Test
    fun testKanaUtils() {
        assertEquals("ニホン", KanaUtils.hiraganaToKatakana("にほん"))
        assertEquals("トウキョウ", KanaUtils.hiraganaToKatakana("とうきょう"))
        assertEquals("ﾆﾎﾝ", KanaUtils.katakanaToHalfWidth("ニホン"))
        assertEquals("ｶﾞｯｺｳ", KanaUtils.katakanaToHalfWidth("ガッコウ"))
    }

    @Test
    fun testJapaneseDictionaryLookups() {
        val dictFile = if (File("src/main/assets/dict_japanese.dat").exists()) {
            File("src/main/assets/dict_japanese.dat")
        } else {
            File("app/src/main/assets/dict_japanese.dat")
        }
        assertTrue("dict_japanese.dat must exist", dictFile.exists())

        val tempUserFile = File.createTempFile("test_usr_ja", ".dat").apply { deleteOnExit() }
        val dict = JapaneseDictionary()
        assertTrue(dict.init(dictFile, tempUserFile))

        // Check common vocabulary and Kanji
        val nihonCands = dict.lookup("にほん")
        assertTrue(nihonCands.contains("日本"))

        val tokyoCands = dict.lookup("とうきょう")
        assertTrue(tokyoCands.contains("東京"))

        val kanjiCands = dict.lookup("かんじ")
        assertTrue(kanjiCands.contains("漢字"))

        val taberuCands = dict.lookup("たべる")
        assertTrue(taberuCands.contains("食べる"))

        val nomuCands = dict.lookup("のむ")
        assertTrue(nomuCands.contains("飲む"))

        val ikuCands = dict.lookup("いく")
        assertTrue(ikuCands.contains("行く"))

        val kuruCands = dict.lookup("くる")
        assertTrue(kuruCands.contains("来る"))

        val miruCands = dict.lookup("みる")
        assertTrue(miruCands.contains("見る"))

        val kikuCands = dict.lookup("きく")
        assertTrue(kikuCands.contains("聞く"))

        val hanasuCands = dict.lookup("はなす")
        assertTrue(hanasuCands.contains("話す"))

        val arigatouCands = dict.lookup("ありがとう")
        assertTrue(arigatouCands.contains("有難う"))

        val konnichiwaCands = dict.lookup("こんにちわ")
        assertTrue(konnichiwaCands.contains("こんにちは") || konnichiwaCands.contains("今日は"))

        // Check prefix prediction
        val predictions = dict.predict("とうきょう")
        assertTrue(predictions.isNotEmpty())

        // Check user dictionary adaptation
        dict.recordWord("にほん", "二本")
        val updatedNihon = dict.lookup("にほん")
        assertEquals("二本", updatedNihon.first())

        dict.close()
    }

    @Test
    fun testJapaneseEngineFlow() {
        val dictFile = if (File("src/main/assets/dict_japanese.dat").exists()) {
            File("src/main/assets/dict_japanese.dat")
        } else {
            File("app/src/main/assets/dict_japanese.dat")
        }
        assertTrue("dict_japanese.dat must exist", dictFile.exists())

        val tempUserFile = File.createTempFile("test_usr_ja_engine", ".dat").apply { deleteOnExit() }
        val dict = JapaneseDictionary()
        assertTrue(dict.init(dictFile, tempUserFile))

        val engine = JapaneseEngine(dict)
        assertFalse(engine.isComposing())

        // Type "nihon"
        for (c in "nihon") {
            assertTrue(engine.processKey(c.code, 0))
        }
        assertTrue(engine.isComposing())

        val ctx = engine.getContext()
        assertNotNull(ctx)
        assertEquals("にほん", ctx!!.preedit)
        val candTexts = ctx.candidates.map { it.text }
        assertTrue(candTexts.contains("日本"))
        assertTrue(candTexts.contains("にほん"))
        assertTrue(candTexts.contains("ニホン"))
        assertTrue(candTexts.contains("ﾆﾎﾝ"))
        assertTrue(candTexts.contains("nihon"))
        assertTrue(candTexts.contains("NIHON"))

        // Select candidate 0
        val topCand = ctx.candidates[0].text
        assertTrue(engine.selectCandidate(0))
        assertEquals(topCand, engine.getCommit())
        assertFalse(engine.isComposing())

        // Type "kanji" and commit via Space
        for (c in "kanji") {
            engine.processKey(c.code, 0)
        }
        val kanjiCtx = engine.getContext()
        assertNotNull(kanjiCtx)
        val expectedKanji = kanjiCtx!!.candidates[0].text
        assertTrue(engine.processKey(0x0020, 0)) // XK_SPACE
        assertEquals(expectedKanji, engine.getCommit())
        assertFalse(engine.isComposing())

        // Type "arigatou" and commit via Return (raw Hiragana)
        for (c in "arigatou") {
            engine.processKey(c.code, 0)
        }
        assertTrue(engine.processKey(0xff0d, 0)) // XK_RETURN
        assertEquals("ありがとう", engine.getCommit())
        assertFalse(engine.isComposing())

        dict.close()
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Boris's lines: each matches its plain twin's placeholders, none touches protected text, and the lookup's rules. */
@RunWith(RobolectricTestRunner::class)
class VoiceTest {

    private val res = ApplicationProvider.getApplicationContext<Application>().resources

    /** Every string and plural item in the default resources, by name; a plural's items are "name/quantity". */
    private val texts: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        val files = File("src/main/res/values").listFiles { f -> f.extension == "xml" }.orEmpty()
        assertTrue("no resource files found from ${File(".").absolutePath}", files.isNotEmpty())
        for (f in files) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
            val strings = root.getElementsByTagName("string")
            for (i in 0 until strings.length) (strings.item(i) as Element).let { out[it.getAttribute("name")] = it.textContent }
            val plurals = root.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val p = plurals.item(i) as Element
                val items = p.getElementsByTagName("item")
                for (j in 0 until items.length) (items.item(j) as Element).let { out["${p.getAttribute("name")}/${it.getAttribute("quantity")}"] = it.textContent }
            }
        }
        out
    }

    private val borisKeys get() = texts.keys.filter { it.substringBefore('/').endsWith(SUFFIX) }

    /** "x_boris" to "x", and "x_boris/one" to "x/one". */
    private fun plainOf(key: String): String {
        val name = key.substringBefore('/')
        return name.removeSuffix(SUFFIX) + key.substring(name.length)
    }

    @Test
    fun thereAreBorisLines() = assertTrue(borisKeys.size >= 10)

    /** The placeholders of every Boris line match its plain twin's in count, order and conversion. */
    @Test
    fun everyBorisLineHasThePlainTwinsPlaceholders() {
        for (key in borisKeys) {
            val plain = texts[plainOf(key)]
            assertTrue("$key has no plain twin ${plainOf(key)}", plain != null)
            assertEquals("placeholders of $key", placeholders(plain!!), placeholders(texts.getValue(key)))
        }
    }

    @Test
    fun placeholdersAreComparedByCountOrderAndType() {
        assertEquals(listOf("%1\$s", "%2\$d"), placeholders("%1\$s needs %2\$d ml, 100%%"))
        assertNotEquals(placeholders("%1\$s and %2\$s"), placeholders("%2\$s and %1\$s"))
        assertNotEquals(placeholders("about %1\$d ml"), placeholders("about %1\$s ml"))
        assertNotEquals(placeholders("%1\$s"), placeholders("%1\$s %1\$s"))
    }

    /** Rule 7: no Boris for toxicity, safety, permissions, data loss, privacy, import and export or legal text. */
    @Test
    fun noProtectedStringHasABorisTwin() {
        val offending = borisKeys.map { plainOf(it).substringBefore('/') }.filter(::isProtected)
        assertTrue("protected strings with a Boris twin: $offending", offending.isEmpty())
    }

    @Test
    fun theProtectedSetCoversTheKindsOfTextItNames() {
        val protectedKeys = listOf(
            "toxic_to_cats", "safety_note", "notifications_reason", "wizard_location_denied", "import_failed", "export_done",
            "delete_failed", "photo_add_private", "about_privacy", "location_privacy", "wizard_save_failed", "plant_entry_failed",
        )
        for (key in protectedKeys) assertTrue(key, isProtected(key))
        assertTrue(!isProtected("forecast_failed") && !isProtected("today_all_fine"))
    }

    /** Every _boris resource is reachable: listed in [Voice]'s twins under its plain key. */
    @Test
    fun everyBorisResourceIsInTheLookup() {
        val names = borisKeys.map { it.substringBefore('/') }.distinct()
        for (name in names) {
            val plural = texts.keys.any { it.startsWith("$name/") }
            val type = if (plural) R.plurals::class.java else R.string::class.java
            val id = type.getField(name).getInt(null)
            val plainId = type.getField(name.removeSuffix(SUFFIX)).getInt(null)
            assertEquals(name, id, (if (plural) Voice.plurals else Voice.strings)[plainId])
        }
        assertEquals(names.size, Voice.strings.size + Voice.plurals.size)
    }

    @Test
    fun borisOnAndAvailableGivesTheTwin() {
        assertEquals(res.getString(R.string.action_water_now_boris, 700), Voice.string(res, true, R.string.action_water_now, 700))
        assertEquals(res.getQuantityString(R.plurals.forecast_stale_boris, 4, 4), Voice.plural(res, true, R.plurals.forecast_stale, 4, 4))
        assertNotEquals(res.getString(R.string.action_water_now, 700), Voice.string(res, true, R.string.action_water_now, 700))
    }

    @Test
    fun plainVoiceGivesThePlainString() {
        assertEquals(res.getString(R.string.action_water_now, 700), Voice.string(res, false, R.string.action_water_now, 700))
        assertEquals(res.getQuantityString(R.plurals.forecast_stale, 4, 4), Voice.plural(res, false, R.plurals.forecast_stale, 4, 4))
    }

    /** A language whose values-xx/ sets the flag false: plain, whatever the switch says. */
    @Test
    fun aLanguageWithoutBorisGivesThePlainStringWithTheSwitchOn() {
        assertTrue(res.getBoolean(R.bool.boris_voice_available))
        val noBoris = object : Resources(res.assets, res.displayMetrics, res.configuration) {
            override fun getBoolean(id: Int) = if (id == R.bool.boris_voice_available) false else super.getBoolean(id)
        }
        assertEquals(res.getString(R.string.action_water_now, 700), Voice.string(noBoris, true, R.string.action_water_now, 700))
        assertEquals(res.getQuantityString(R.plurals.forecast_stale, 4, 4), Voice.plural(noBoris, true, R.plurals.forecast_stale, 4, 4))
    }

    @Test
    fun aStringWithoutATwinGivesThePlainOneNeverEmpty() {
        assertEquals(res.getString(R.string.today_title), Voice.string(res, true, R.string.today_title))
        assertEquals(res.getString(R.string.import_failed, "x"), Voice.string(res, true, R.string.import_failed, "x"))
    }

    private companion object {
        const val SUFFIX = "_boris"

        // Android format placeholders. A doubled percent sign is a literal and is dropped.
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(<]*\d*(\.\d+)?[a-zA-Z%]""")

        fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.filter { it != "%%" }.toList()

        // Text that must stay plain (voice rule 7): by key prefix, by word anywhere in the key, and by name.
        val PROTECTED_PREFIXES = listOf("toxic", "safety", "notifications_", "permission", "import", "export", "delete", "photo_", "about_", "location_", "wizard_location_")
        val PROTECTED_WORDS = listOf("toxic", "safety", "permission", "privacy", "private", "legal", "licence")
        val PROTECTED_KEYS = setOf("deleted", "wizard_save_failed", "plant_entry_failed", "plant_delete_question", "place_in_use", "undo")

        fun isProtected(key: String) =
            key in PROTECTED_KEYS || PROTECTED_PREFIXES.any { key.startsWith(it) } || PROTECTED_WORDS.any { key.contains(it) }
    }
}

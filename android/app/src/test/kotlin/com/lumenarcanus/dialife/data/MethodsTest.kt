package com.lumenarcanus.dialife.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ⓘ texts the server serves (internal/web/static/methods.json) read as the app expects them. */
class MethodsTest {
    private val methods: Methods = DiaLifeApi.json.decodeFromString(serverFile().readText())

    @Test
    fun everySectionHasThreeLanguages() {
        assertTrue(methods.sections.isNotEmpty())
        methods.sections.forEach { (id, langs) ->
            listOf("en", "uk", "es").forEach { lang ->
                val s = langs[lang]
                assertNotNull("$id $lang", s)
                assertTrue("$id $lang", s!!.title.isNotBlank() && s.short.isNotBlank())
            }
        }
    }

    @Test
    fun sectionFallsBackToEnglishAndLinksToTheDoc() {
        assertEquals("Statistics", methods.section("stats", "de")?.title)
        assertEquals("Статистика", methods.section("stats", "uk")?.title)
        assertNull(methods.section("nope", "en"))
        assertTrue(methods.link("sensor-delay").endsWith("docs/METHODS.md#sensor-delay"))
    }

    @Test
    fun everyInfoMarkInTheAppHasItsSection() {
        val ids = File("src/main/kotlin").walk().filter { it.extension == "kt" }.flatMap { f ->
            val text = f.readText()
            Regex("""InfoButton\(""").findAll(text).flatMap { m ->
                // the id, or the ids an if or a when picks from, before the call's end; not what a condition compares with
                val call = text.substring(m.range.last + 1).substringBefore(")\n")
                Regex("""(?:^|-> |\) |else )"([a-z0-9-]+)"""").findAll(call).map { it.groupValues[1] }
            }
        }.toSet()
        assertTrue(ids.size >= 15)
        ids.forEach { assertNotNull(it, methods.sections[it]) }
    }

    companion object {
        /** From the app module's directory, where Gradle runs the tests. */
        fun serverFile(): File = File("../../internal/web/static/methods.json")
    }
}

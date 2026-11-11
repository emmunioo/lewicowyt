package pl.lewicowyt.notifier.community.push

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Strażnik: wydania z GitHuba (arm64/armv7/x64/universal) i z Google Play (`play`) MUSZĄ mieć ten sam system
 * powiadomień. Wariant `play` może różnić się WYŁĄCZNIE brakiem samoaktualizacji. Ten test nie przepuści zmiany,
 * która by to złamała, np.:
 *  - kod lub zasoby w `src/play` albo w katalogu innego wariantu (mogłyby podmienić klasy push),
 *  - nakładka manifestu `play` ruszająca cokolwiek poza trzema elementami aktualizatora,
 *  - wariant `play` z dodatkowymi ustawieniami (np. innym BuildConfig dla push),
 *  - kod powiadomień zależny od wariantu (BuildConfig.FLAVOR, SELF_UPDATE, UPDATE_REPOSITORY).
 */
class PushParityTest {
    // testy jednostkowe Androida startują w katalogu modułu (app/); na wszelki wypadek obsługujemy też katalog główny
    private val module: File = listOf(File("."), File("app"))
        .map { it.absoluteFile }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("nie znaleziono modułu app (katalog roboczy: ${File(".").absolutePath})")

    @Test
    fun `only shared, build-type and play source sets exist`() {
        val allowed = setOf("main", "debug", "release", "play", "test", "androidTest")
        val present = File(module, "src").listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
        assertTrue(
            "Nowy katalog źródeł wariantu ${present - allowed} — kod/zasoby per wariant mogą rozjechać powiadomienia " +
                "GitHub i Google Play. Jeśli to celowe i NIE dotyczy powiadomień, dopisz go tu świadomie.",
            (present - allowed).isEmpty(),
        )
    }

    @Test
    fun `play source set contains only the manifest overlay`() {
        val files = File(module, "src/play").walkTopDown().filter { it.isFile }
            .map { it.relativeTo(File(module, "src/play")).invariantSeparatorsPath }.toList()
        assertEquals(listOf("AndroidManifest.xml"), files)
    }

    @Test
    fun `play manifest overlay only removes the self-updater`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(module, "src/play/AndroidManifest.xml"))
        val touched = ArrayList<String>()
        fun walk(e: Element) {
            val children = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()
            for (c in children) {
                if (c.tagName == "application") {
                    assertTrue("<application> w nakładce play nie może mieć atrybutów", c.attributes.length == 0)
                    walk(c)
                    continue
                }
                val node = c.getAttributeNS(TOOLS, "node")
                if (node != "remove") fail("Nakładka play może tylko USUWAĆ elementy aktualizatora, a zawiera <${c.tagName}> (tools:node=\"$node\")")
                touched += "${c.tagName}:" + (c.getAttributeNS(ANDROID, "name") + " " + c.getAttributeNS(ANDROID, "authorities")).trim()
            }
        }
        walk(doc.documentElement)
        assertEquals(
            setOf(
                "uses-permission:android.permission.REQUEST_INSTALL_PACKAGES",
                "activity:.updates.UpdateInstallActivity",
                "provider:androidx.core.content.FileProvider \${applicationId}.update-files",
            ),
            touched.toSet(),
        )
    }

    @Test
    fun `play flavor differs only by self-update settings`() {
        val gradle = File(module, "build.gradle.kts").readText()
        val block = Regex("""create\("play"\)\s*\{(.*?)\n\s{8}\}""", RegexOption.DOT_MATCHES_ALL).find(gradle)
            ?.groupValues?.get(1) ?: error("brak wariantu play w build.gradle.kts")
        val statements = block.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals(
            listOf(
                "dimension = \"abi\"",
                "ndk { abiFilters += listOf(\"arm64-v8a\", \"armeabi-v7a\", \"x86_64\") }",
                "buildConfigField(\"boolean\", \"SELF_UPDATE\", \"false\")",
                "buildConfigField(\"String\", \"UPDATE_REPOSITORY\", \"\\\"\\\"\")",
            ),
            statements,
        )
    }

    @Test
    fun `notification code does not depend on the distribution variant`() {
        val roots = listOf(
            "community/push", "notifications", "messaging", "community/CommunityDefaults.kt",
        ).map { File(module, "src/main/kotlin/pl/lewicowyt/notifier/$it") }
        val offenders = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { f -> FLAVOR_MARKERS.any { it in f.readText() } }
            .map { it.name }
        assertTrue("Kod powiadomień zależy od wariantu dystrybucji: $offenders", offenders.isEmpty())
        assertTrue("nie znaleziono kodu powiadomień", roots.all { it.exists() })
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val TOOLS = "http://schemas.android.com/tools"
        val FLAVOR_MARKERS = listOf("BuildConfig.FLAVOR", "SELF_UPDATE", "UPDATE_REPOSITORY")
    }
}

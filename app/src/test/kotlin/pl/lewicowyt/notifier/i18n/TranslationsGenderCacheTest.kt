package pl.lewicowyt.notifier.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationsGenderCacheTest {
    @After
    fun reset() {
        Translations.pronoun = Pronoun.ON
    }

    @Test
    fun `T-10 remembered gender forms follow the current pronoun`() {
        val source = "Wyraził{g:eś|aś|oś} zgodę"
        Translations.pronoun = Pronoun.ON
        assertEquals("Wyraziłeś zgodę", Translations.tr(source))
        assertEquals("Wyraziłeś zgodę", Translations.tr(source)) // z pamięci
        Translations.pronoun = Pronoun.ONA
        assertEquals("Wyraziłaś zgodę", Translations.tr(source))
        Translations.pronoun = Pronoun.ONO
        assertEquals("Wyraziłoś zgodę", Translations.tr(source))
        Translations.pronoun = Pronoun.ON
        assertEquals("Wyraziłeś zgodę", Translations.tr(source))
    }

    @Test
    fun `T-10 text without gender token is returned unchanged`() {
        assertEquals("Ustawienia", Translations.tr("Ustawienia"))
    }
}

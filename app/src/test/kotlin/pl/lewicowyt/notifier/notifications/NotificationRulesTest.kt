package pl.lewicowyt.notifier.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    @Test
    fun atMostThreeItemsUseDirectNotifications() {
        assertFalse(usesSummaryNotification(1))
        assertFalse(usesSummaryNotification(3))
    }

    @Test
    fun moreThanThreeItemsUseInboxSummary() {
        assertTrue(usesSummaryNotification(4))
    }

    @Test
    fun summaryUsesPolishNumberAgreement() {
        assertEquals("Nie pojawiły się nowe materiały.", polishNewMaterialsSentence(0))
        assertEquals("Pojawił się 1 nowy materiał.", polishNewMaterialsSentence(1))
        assertEquals("Pojawiły się 2 nowe materiały.", polishNewMaterialsSentence(2))
        assertEquals("Pojawiły się 4 nowe materiały.", polishNewMaterialsSentence(4))
        assertEquals("Pojawiło się 5 nowych materiałów.", polishNewMaterialsSentence(5))
        assertEquals("Pojawiło się 12 nowych materiałów.", polishNewMaterialsSentence(12))
        assertEquals("Pojawiły się 22 nowe materiały.", polishNewMaterialsSentence(22))
        assertEquals("Pojawiły się 24 nowe materiały.", polishNewMaterialsSentence(24))
        assertEquals("Pojawiło się 25 nowych materiałów.", polishNewMaterialsSentence(25))
    }
}

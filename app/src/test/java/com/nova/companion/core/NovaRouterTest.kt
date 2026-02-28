package com.nova.companion.core

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for NovaRouter singleton.
 * Tests routing logic, intent classification, and data extraction methods.
 */
class NovaRouterTest {

    // ==================== AUTOMATION INTENT DETECTION ====================

    @Test
    fun testAutomationIntent_openSpotify() {
        val result = NovaRouter.classifyIntent("open spotify")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_launchWhatsapp() {
        val result = NovaRouter.classifyIntent("launch whatsapp")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_sendMessage() {
        val result = NovaRouter.classifyIntent("send message to john")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_setAlarm() {
        val result = NovaRouter.classifyIntent("set alarm for 7am")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_reminder() {
        val result = NovaRouter.classifyIntent("remind me to take creatine")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_turnOnWifi() {
        val result = NovaRouter.classifyIntent("turn on wifi")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_navigate() {
        val result = NovaRouter.classifyIntent("navigate to starbucks")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_playMusic() {
        val result = NovaRouter.classifyIntent("play music")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_search() {
        val result = NovaRouter.classifyIntent("search for best protein powder")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    // ==================== CASUAL MESSAGES → TEXT_LOCAL ====================

    @Test
    fun testCasualMessage_yoBro() {
        val result = NovaRouter.classifyIntent("yo bro whats good")
        assertEquals(NovaMode.TEXT_LOCAL, result)
    }

    @Test
    fun testCasualMessage_laughing() {
        val result = NovaRouter.classifyIntent("lol thats funny")
        assertEquals(NovaMode.TEXT_LOCAL, result)
    }

    @Test
    fun testCasualMessage_negative() {
        val result = NovaRouter.classifyIntent("nah im good")
        assertEquals(NovaMode.TEXT_LOCAL, result)
    }

    @Test
    fun testCasualMessage_greeting() {
        val result = NovaRouter.classifyIntent("hey nova how are you")
        assertEquals(NovaMode.TEXT_LOCAL, result)
    }

    // ==================== EXTRACT APP NAME ====================

    @Test
    fun testExtractAppName_openSpotify() {
        val result = NovaRouter.extractAppName("open spotify")
        assertEquals("spotify", result)
    }

    @Test
    fun testExtractAppName_launchChrome() {
        val result = NovaRouter.extractAppName("launch chrome")
        assertEquals("chrome", result)
    }

    @Test
    fun testExtractAppName_openYoutube() {
        val result = NovaRouter.extractAppName("open youtube")
        assertEquals("youtube", result)
    }

    @Test
    fun testExtractAppName_noAppFound() {
        val result = NovaRouter.extractAppName("just a casual message")
        assertNull(result)
    }

    @Test
    fun testExtractAppName_caseSensitivity() {
        val result = NovaRouter.extractAppName("open Spotify")
        assertNotNull(result)
    }

    // ==================== EXTRACT CONTACT NAME ====================

    @Test
    fun testExtractContactName_sendMessageToJohn() {
        val result = NovaRouter.extractContactName("send message to john")
        assertEquals("john", result)
    }

    @Test
    fun testExtractContactName_textSarah() {
        val result = NovaRouter.extractContactName("text sarah")
        assertEquals("sarah", result)
    }

    @Test
    fun testExtractContactName_callMom() {
        val result = NovaRouter.extractContactName("call mom")
        assertEquals("mom", result)
    }

    @Test
    fun testExtractContactName_noContact() {
        val result = NovaRouter.extractContactName("play some music")
        assertNull(result)
    }

    @Test
    fun testExtractContactName_multipleWords() {
        val result = NovaRouter.extractContactName("send message to john smith")
        assertNotNull(result)
    }

    // ==================== EXTRACT LOCATION ====================

    @Test
    fun testExtractLocation_navigateToStarbucks() {
        val result = NovaRouter.extractLocation("navigate to starbucks")
        assertEquals("starbucks", result)
    }

    @Test
    fun testExtractLocation_goToGym() {
        val result = NovaRouter.extractLocation("go to the gym")
        assertNotNull(result)
    }

    @Test
    fun testExtractLocation_directions() {
        val result = NovaRouter.extractLocation("directions to central park")
        assertNotNull(result)
    }

    @Test
    fun testExtractLocation_noLocation() {
        val result = NovaRouter.extractLocation("play a song")
        assertNull(result)
    }

    @Test
    fun testExtractLocation_withArticles() {
        val result = NovaRouter.extractLocation("navigate to the airport")
        assertNotNull(result)
    }

    // ==================== EXTRACT SEARCH QUERY ====================

    @Test
    fun testExtractSearchQuery_searchForCats() {
        val result = NovaRouter.extractSearchQuery("search for cats")
        assertEquals("cats", result)
    }

    @Test
    fun testExtractSearchQuery_searchProteinPowder() {
        val result = NovaRouter.extractSearchQuery("search for best protein powder")
        assertEquals("best protein powder", result)
    }

    @Test
    fun testExtractSearchQuery_googleIt() {
        val result = NovaRouter.extractSearchQuery("google best restaurants near me")
        assertNotNull(result)
    }

    @Test
    fun testExtractSearchQuery_noSearch() {
        val result = NovaRouter.extractSearchQuery("open spotify")
        assertNull(result)
    }

    @Test
    fun testExtractSearchQuery_lookupTerm() {
        val result = NovaRouter.extractSearchQuery("look up the weather")
        assertNotNull(result)
    }

    // ==================== CLASSIFY INTENT EDGE CASES ====================

    @Test
    fun testClassifyIntent_emptyString() {
        val result = NovaRouter.classifyIntent("")
        assertNotNull(result)
    }

    @Test
    fun testClassifyIntent_whitespaceOnly() {
        val result = NovaRouter.classifyIntent("   ")
        assertNotNull(result)
    }

    @Test
    fun testClassifyIntent_longMessage() {
        val result = NovaRouter.classifyIntent("hey nova can you open spotify and play some relaxing music for me please")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testClassifyIntent_mixedCase() {
        val result = NovaRouter.classifyIntent("OPEN SPOTIFY")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testClassifyIntent_specialCharacters() {
        val result = NovaRouter.classifyIntent("open spotify!!!!")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    // ==================== EXTRACT HELPERS EDGE CASES ====================

    @Test
    fun testExtractAppName_multipleApps() {
        val result = NovaRouter.extractAppName("open spotify and youtube")
        assertNotNull(result)
    }

    @Test
    fun testExtractAppName_emptyString() {
        val result = NovaRouter.extractAppName("")
        assertNull(result)
    }

    @Test
    fun testExtractContactName_emptyString() {
        val result = NovaRouter.extractContactName("")
        assertNull(result)
    }

    @Test
    fun testExtractLocation_emptyString() {
        val result = NovaRouter.extractLocation("")
        assertNull(result)
    }

    @Test
    fun testExtractSearchQuery_emptyString() {
        val result = NovaRouter.extractSearchQuery("")
        assertNull(result)
    }

    // ==================== ADDITIONAL AUTOMATION SCENARIOS ====================

    @Test
    fun testAutomationIntent_openApp() {
        val result = NovaRouter.classifyIntent("open gmail")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_controlVolume() {
        val result = NovaRouter.classifyIntent("turn up volume")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_disableBluetooth() {
        val result = NovaRouter.classifyIntent("turn off bluetooth")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_setReminder() {
        val result = NovaRouter.classifyIntent("remind me in 5 minutes")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    @Test
    fun testAutomationIntent_sendEmail() {
        val result = NovaRouter.classifyIntent("send email to boss")
        assertEquals(NovaMode.AUTOMATION, result)
    }

    // ==================== TEXT_LOCAL EDGE CASES ====================

    @Test
    fun testTextLocal_questions() {
        val result = NovaRouter.classifyIntent("what time is it")
        assertEquals(NovaMode.TEXT_LOCAL, result)
    }

    @Test
    fun testTextLocal_statements() {
        val result = NovaRouter.classifyIntent("thats pretty cool")
        assertEquals(NovaMode.TEXT_LOCAL, result)
    }

    @Test
    fun testTextLocal_abbreviations() {
        val result = NovaRouter.classifyIntent("lmao thats hilarious")
        assertEquals(NovaMode.TEXT_LOCAL, result)
    }

    // ==================== ENUM VALIDATION ====================

    @Test
    fun testNovaModeEnumValues() {
        val modes = setOf(
            NovaMode.TEXT_LOCAL,
            NovaMode.TEXT_CLOUD,
            NovaMode.VOICE_LOCAL,
            NovaMode.VOICE_ELEVEN,
            NovaMode.AUTOMATION
        )
        assertFalse(modes.isEmpty())
    }

    @Test
    fun testClassifyIntentReturnsValidMode() {
        val result = NovaRouter.classifyIntent("open spotify")
        assertTrue(result in NovaMode.values())
    }
}

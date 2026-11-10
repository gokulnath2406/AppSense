package com.appsense.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCheckPolicyTest {

    @Test
    fun onlyAllSixRequiredConditionsReturnTrue() {

        for (mask in 0 until 64) {

            val enabled = mask and 1 != 0
            val newSessionStarted = mask and 2 != 0
            val promptAlreadyShown = mask and 4 != 0
            val interventionActive = mask and 8 != 0
            val interventionsAvailable = mask and 16 != 0
            val overlayAllowed = mask and 32 != 0

            val result = OpenCheckPolicy.shouldPrompt(
                enabled = enabled,
                newSessionStarted = newSessionStarted,
                promptAlreadyShownThisSession = promptAlreadyShown,
                interventionActive = interventionActive,
                interventionsAvailable = interventionsAvailable,
                overlayAllowed = overlayAllowed
            )

            val expected =
                enabled &&
                        newSessionStarted &&
                        !promptAlreadyShown &&
                        !interventionActive &&
                        interventionsAvailable &&
                        overlayAllowed

            assertEquals(
                "Unexpected result for combination: $mask",
                expected,
                result
            )
        }
    }

    @Test
    fun allRequiredConditionsReturnTrue() {

        val result = OpenCheckPolicy.shouldPrompt(
            enabled = true,
            newSessionStarted = true,
            promptAlreadyShownThisSession = false,
            interventionActive = false,
            interventionsAvailable = true,
            overlayAllowed = true
        )

        assertEquals(true, result)
    }

    @Test
    fun disabledFeatureDoesNotPrompt() {

        val result = OpenCheckPolicy.shouldPrompt(
            enabled = false,
            newSessionStarted = true,
            promptAlreadyShownThisSession = false,
            interventionActive = false,
            interventionsAvailable = true,
            overlayAllowed = true
        )

        assertEquals(false, result)
    }

    @Test
    fun alreadyShownPromptDoesNotRepeat() {

        val result = OpenCheckPolicy.shouldPrompt(
            enabled = true,
            newSessionStarted = true,
            promptAlreadyShownThisSession = true,
            interventionActive = false,
            interventionsAvailable = true,
            overlayAllowed = true
        )

        assertEquals(false, result)
    }
}
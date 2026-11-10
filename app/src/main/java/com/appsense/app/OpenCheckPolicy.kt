package com.appsense.app

object OpenCheckPolicy {

    fun shouldPrompt(
        enabled: Boolean,
        newSessionStarted: Boolean,
        promptAlreadyShownThisSession: Boolean,
        interventionActive: Boolean,
        interventionsAvailable: Boolean,
        overlayAllowed: Boolean
    ): Boolean {

        return enabled &&
                newSessionStarted &&
                !promptAlreadyShownThisSession &&
                !interventionActive &&
                interventionsAvailable &&
                overlayAllowed
    }
}
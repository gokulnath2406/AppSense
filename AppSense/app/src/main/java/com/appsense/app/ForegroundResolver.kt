package com.appsense.app

data class ForegroundEvent(
    val packageName: String?,
    val className: String?,
    val type: Type,
    val timestamp: Long
) {
    enum class Type {
        FOREGROUND,
        PAUSED,
        BACKGROUND,
        SYSTEM_STOP,
        OTHER
    }
}

data class ForegroundState(
    val packageName: String?,
    val className: String?
)

object ForegroundResolver {

    fun resolve(
        events: List<ForegroundEvent>,
        previous: ForegroundState
    ): ForegroundState {

        var latestPackage = previous.packageName
        var latestClass = previous.className
        var latestTime = 0L

        for (event in events) {

            when (event.type) {

                ForegroundEvent.Type.SYSTEM_STOP -> {

                    if (event.timestamp >= latestTime) {

                        latestTime = event.timestamp

                        latestPackage = null
                        latestClass = null
                    }
                }

                ForegroundEvent.Type.FOREGROUND -> {

                    if (event.timestamp >= latestTime) {

                        latestTime = event.timestamp

                        latestPackage = event.packageName
                        latestClass = event.className
                    }
                }

                ForegroundEvent.Type.PAUSED -> {

                    if (
                        event.timestamp >= latestTime &&
                        latestPackage == event.packageName &&
                        latestClass == event.className
                    ) {

                        latestTime = event.timestamp

                        latestPackage = null
                        latestClass = null
                    }
                }

                ForegroundEvent.Type.BACKGROUND -> {

                    if (
                        event.timestamp >= latestTime &&
                        latestPackage == event.packageName &&
                        latestClass == event.className
                    ) {

                        latestTime = event.timestamp

                        latestPackage = null
                        latestClass = null
                    }
                }

                ForegroundEvent.Type.OTHER -> {
                    // No state change.
                }
            }
        }

        return ForegroundState(
            packageName = latestPackage,
            className = latestClass
        )
    }
}
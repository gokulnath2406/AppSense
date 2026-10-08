package com.appsense.app

import android.service.notification.NotificationListenerService

/**
 * Notification Access service for AppSense.
 *
 * Notification processing is intentionally not implemented yet.
 * This service only provides the system-level Notification Access
 * entry so the permission can be enabled during AppSense setup.
 */
class AppSenseNotificationListenerService :
    NotificationListenerService()

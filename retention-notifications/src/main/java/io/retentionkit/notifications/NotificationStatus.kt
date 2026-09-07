package io.retentionkit.notifications

/** Current-process diagnostic snapshot. Reading this does not post, reconcile or request permission. */
data class NotificationStatus(
    val installed: Boolean,
    val revision: Long,
    val foreground: Boolean,
    val campaigns: List<NotificationCampaignStatus>,
)

data class NotificationCampaignStatus(
    val campaign: NotificationCampaign,
    val enabled: Boolean,
    val pendingForeground: Boolean,
    /** Last actual attempt in this process; null means no attempt, not eligibility or success. */
    val lastOutcome: NotificationOutcome?,
    /** Requested inexact alarm/checkpoint time, not a guarantee of execution or display. */
    val nextAlarmAtMillis: Long?,
)

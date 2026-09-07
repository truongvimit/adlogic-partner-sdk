package io.retentionkit.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import io.retentionkit.core.RetentionFeature

/** Local content only. Destinations must be present in the core feature catalogue. */
data class NotificationAction @JvmOverloads constructor(val id: String, val label: String, val destination: String, val iconRes: Int = 0)
data class NotificationContent @JvmOverloads constructor(
    val id: String, val title: String, val body: String, val destination: String,
    val actions: List<NotificationAction> = emptyList(), val imageRes: Int? = null,
    /** Optional expanded headline; collapsed title remains independently configurable. */
    val expandedTitle: String = title,
)
fun interface NotificationContentProvider {
    fun content(context: Context, campaign: NotificationCampaign, features: List<RetentionFeature>): List<NotificationContent>
}

data class PreparedNotificationAction(val id: String, val label: String, val iconRes: Int, val pendingIntent: PendingIntent)
data class NotificationRenderRequest(
    val context: Context, val campaign: NotificationCampaign, val content: NotificationContent,
    val contentIntent: PendingIntent, val dismissIntent: PendingIntent, val actions: List<PreparedNotificationAction>,
)
/** Synchronous, bounded, local-only decoration. Use request PendingIntents in custom RemoteViews. */
fun interface NotificationRenderer {
    fun decorate(request: NotificationRenderRequest, builder: Notification.Builder)
}

data class RetentionNotificationOptions @JvmOverloads constructor(
    val smallIconRes: Int = R.drawable.rk_ic_notification,
    /** Override with old IDs to preserve a user's existing channel choices during migration. */
    val channelIds: Map<NotificationCampaign, String> = emptyMap(),
    val contentProvider: NotificationContentProvider = StandardNotificationContent,
    val renderer: NotificationRenderer = StandardNotificationRenderer,
    val preset: NotificationPreset = NotificationPreset.COMMON_PLAN,
)

object StandardNotificationContent : NotificationContentProvider {
    override fun content(context: Context, campaign: NotificationCampaign, features: List<RetentionFeature>): List<NotificationContent> {
        val body = when (campaign) {
            NotificationCampaign.DAILY -> R.string.rk_n_daily_body
            NotificationCampaign.WINBACK -> R.string.rk_n_winback_body
            NotificationCampaign.ONBOARDING -> R.string.rk_n_onboarding_body
            NotificationCampaign.AD_RETURN, NotificationCampaign.APP_EXIT -> R.string.rk_n_return_body
            NotificationCampaign.REMINDER -> R.string.rk_n_reminder_body
            NotificationCampaign.PINNED -> R.string.rk_n_pinned_body
            NotificationCampaign.LOCKSCREEN -> R.string.rk_n_lockscreen_body
        }
        val heading = when (campaign) {
            NotificationCampaign.DAILY -> R.string.rk_n_daily_title
            NotificationCampaign.WINBACK -> R.string.rk_n_winback_title
            NotificationCampaign.ONBOARDING -> R.string.rk_n_onboarding_title
            NotificationCampaign.AD_RETURN, NotificationCampaign.APP_EXIT -> R.string.rk_n_before_you_go
            NotificationCampaign.REMINDER -> R.string.rk_n_reminder_title
            NotificationCampaign.PINNED -> R.string.rk_n_pinned_title
            NotificationCampaign.LOCKSCREEN -> R.string.rk_n_lockscreen_title
        }
        return features.map { feature ->
            val title = context.getString(heading, feature.label).take(200)
            val detail = listOf(context.getString(body), feature.description).filter { it.isNotBlank() }.joinToString(" ")
            NotificationContent(feature.id, title, detail.take(1000), feature.id,
                (if (campaign == NotificationCampaign.PINNED) features.take(4) else listOf(feature)).map {
                    NotificationAction(it.id, if (campaign == NotificationCampaign.PINNED) it.label else context.getString(R.string.rk_n_action_now), it.id, it.iconRes)
                }, feature.imageRes ?: if (campaign.updates || campaign == NotificationCampaign.LOCKSCREEN) R.drawable.rk_notification_feature else null,
                if (campaign == NotificationCampaign.AD_RETURN || campaign == NotificationCampaign.APP_EXIT)
                    context.getString(R.string.rk_n_return_expanded, feature.label).take(200) else title)
        }
    }
}

/** Accessible Android system templates with bundled feature tiles/lockscreen layouts. */
object StandardNotificationRenderer : NotificationRenderer {
    override fun decorate(request: NotificationRenderRequest, builder: Notification.Builder) {
        val (context, campaign, content) = request
        builder.setStyle(Notification.BigTextStyle().setBigContentTitle(content.expandedTitle).bigText(content.body))
        if (campaign == NotificationCampaign.PINNED) {
            fun tiles(compact: Boolean): RemoteViews {
                val views = RemoteViews(context.packageName, if (compact) R.layout.rk_notification_tiles_compact else R.layout.rk_notification_tiles)
                if (!compact) views.setTextViewText(R.id.rk_notification_title, content.title)
                val roots = intArrayOf(R.id.rk_tile_1, R.id.rk_tile_2, R.id.rk_tile_3, R.id.rk_tile_4)
                val icons = intArrayOf(R.id.rk_icon_1, R.id.rk_icon_2, R.id.rk_icon_3, R.id.rk_icon_4)
                val labels = intArrayOf(R.id.rk_label_1, R.id.rk_label_2, R.id.rk_label_3, R.id.rk_label_4)
                roots.indices.forEach { i ->
                    val action = request.actions.getOrNull(i)
                    views.setViewVisibility(roots[i], if (action == null) View.GONE else View.VISIBLE)
                    if (action != null) {
                        views.setTextViewText(labels[i], action.label)
                        views.setImageViewResource(icons[i], action.iconRes.takeIf { it != 0 } ?: R.drawable.rk_ic_notification)
                        views.setContentDescription(roots[i], action.label)
                        views.setOnClickPendingIntent(roots[i], action.pendingIntent)
                    }
                }
                return views
            }
            builder.setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(tiles(true)).setCustomBigContentView(tiles(false))
        } else if (campaign == NotificationCampaign.LOCKSCREEN) {
            fun card(compact: Boolean): RemoteViews {
                val views = RemoteViews(context.packageName, if (compact) R.layout.rk_notification_lockscreen_compact else R.layout.rk_notification_lockscreen)
                views.setTextViewText(R.id.rk_notification_title, content.title)
                views.setTextViewText(R.id.rk_notification_body, content.body)
                val openLabel = request.actions.firstOrNull()?.label ?: context.getString(R.string.rk_n_action_now)
                views.setTextViewText(R.id.rk_notification_open, openLabel)
                views.setContentDescription(R.id.rk_notification_open, openLabel)
                // Set runtime-localized text explicitly: SystemUI can inflate resources in another locale.
                views.setTextViewText(R.id.rk_notification_close, context.getString(if (compact) R.string.rk_n_close_symbol else R.string.rk_n_dismiss))
                views.setContentDescription(R.id.rk_notification_close, context.getString(R.string.rk_n_dismiss))
                views.setOnClickPendingIntent(R.id.rk_notification_open, request.actions.firstOrNull()?.pendingIntent ?: request.contentIntent)
                views.setOnClickPendingIntent(R.id.rk_notification_close, request.dismissIntent)
                if (!compact && content.imageRes != null) {
                    views.setImageViewResource(R.id.rk_notification_image, content.imageRes)
                    views.setViewVisibility(R.id.rk_notification_image, View.VISIBLE)
                }
                return views
            }
            builder.setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(card(true))
                .setCustomHeadsUpContentView(card(true)).setCustomBigContentView(card(false))
        } else if (campaign.updates && content.imageRes != null) {
            // Bundled image only; bound decode dimensions and avoid allocating a full-size photograph.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, content.imageRes, bounds)
            val bitmap = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val decode = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 768 || bounds.outHeight / inSampleSize > 768) inSampleSize *= 2
                }
                BitmapFactory.decodeResource(context.resources, content.imageRes, decode)
            } else context.getDrawable(content.imageRes)?.let { drawable ->
                Bitmap.createBitmap(768, 384, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, it.width, it.height)
                    drawable.draw(Canvas(it))
                }
            }
            bitmap?.let { builder.setStyle(Notification.BigPictureStyle().bigPicture(it).setBigContentTitle(content.expandedTitle).setSummaryText(content.body)) }
        }
        // No network image loading, Activity references, custom click trampoline, or async renderer.
    }
}

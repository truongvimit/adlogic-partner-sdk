package io.retentionkit.notifications

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** Inflate actual RemoteViews and send their real PendingIntents; no copied callback logic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
class NotificationCompactRendererTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val english = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) })

    private fun route(id: String): PendingIntent = PendingIntent.getActivity(app, 0,
        Intent().setComponent(ComponentName(app.packageName, "HostSplashActivity"))
            .setData(Uri.parse("retention-test://feature/$id")).putExtra("destination", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun render(campaign: NotificationCampaign): Notification {
        val features = listOf("notes", "saved_items", "text_tools", "guide")
        val actions = features.map { PreparedNotificationAction(it, it, R.drawable.rk_ic_notification, route(it)) }
        val dismiss = PendingIntent.getBroadcast(app, 0, Intent("test.dismiss").setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
        val content = NotificationContent("notes", "Try Notes", "A useful tool, ready when you need it.", "notes", imageRes = R.drawable.rk_notification_feature)
        val request = NotificationRenderRequest(english, campaign, content, route("notes"), dismiss, actions)
        return Notification.Builder(english, "test").setSmallIcon(R.drawable.rk_ic_notification).let {
            StandardNotificationRenderer.decorate(request, it); it.build()
        }
    }

    private fun assertCompact(view: View) {
        val density = app.resources.displayMetrics.density
        // Measure through a parent so the XML root's48dp LayoutParams actually constrain it.
        val parent = FrameLayout(app).apply { addView(view) }
        parent.measure(View.MeasureSpec.makeMeasureSpec((328 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        assertTrue("Collapsed custom content must fit the48dp budget", view.measuredHeight <= (48 * density).toInt())
    }

    @Test fun lockscreenCollapsedAndHeadsUpExposeLocalisedCloseAndActualFeatureAction() {
        val notification = render(NotificationCampaign.LOCKSCREEN)
        assertNotNull("Collapsed card cannot require the user to expand to find X/CTA", notification.contentView)
        assertNotNull(notification.headsUpContentView)
        for (remote in listOf(notification.contentView, notification.headsUpContentView, notification.bigContentView)) {
            val view = remote.apply(app, FrameLayout(app))
            if (remote !== notification.bigContentView) assertCompact(view)
            assertEquals("Try Notes", view.findViewById<TextView>(R.id.rk_notification_title).text.toString())
            assertTrue(view.findViewById<TextView>(R.id.rk_notification_body).text.isNotBlank())
            val close = view.findViewById<TextView>(R.id.rk_notification_close)
            assertEquals("Dismiss", close.contentDescription.toString())
            assertTrue(close.performClick())
            assertEquals("test.dismiss", shadowOf(app).broadcastIntents.last().action)
            assertTrue(view.findViewById<View>(R.id.rk_notification_open).performClick())
            assertEquals("notes", shadowOf(app).nextStartedActivity.getStringExtra("destination"))
        }
    }

    @Test fun allFourPinnedTilesAreVisibleAndRouteFromTheCollapsedCard() {
        val notification = render(NotificationCampaign.PINNED)
        assertNotNull("Pinned tools must be usable without expansion", notification.contentView)
        val view = notification.contentView.apply(app, FrameLayout(app))
        assertCompact(view)
        val roots = listOf(R.id.rk_tile_1, R.id.rk_tile_2, R.id.rk_tile_3, R.id.rk_tile_4)
        roots.zip(listOf("notes", "saved_items", "text_tools", "guide")).forEach { (id, target) ->
            val tile = view.findViewById<View>(id)
            assertEquals(View.VISIBLE, tile.visibility)
            assertTrue(tile.performClick())
            assertEquals(target, shadowOf(app).nextStartedActivity.getStringExtra("destination"))
        }
    }
}

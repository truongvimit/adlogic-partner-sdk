package io.onboardkit.ui.widget

import android.app.Application
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.onboardkit.R
import io.onboardkit.config.GoalsScreenConfig
import io.onboardkit.config.GoalOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoalOptionAdapterTest {
    @Test
    fun `recycled holder replaces Edit PDF with Compress and current selection`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val adapter = GoalOptionAdapter(
            listOf(GoalOption("edit", title = "Edit PDF"), GoalOption("compress", title = "Compress")),
            GoalsScreenConfig().optionLayoutRes,
        ) { _, _ -> }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("Edit PDF", holder.itemView.findViewById<TextView>(R.id.ob_goal_option_title).text.toString())
        adapter.selectedIds = setOf("compress")
        adapter.onBindViewHolder(holder, 1)
        assertEquals("Compress", holder.itemView.findViewById<TextView>(R.id.ob_goal_option_title).text.toString())
        assertTrue(holder.itemView.isSelected)
    }
}

package com.ads.module.helper

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EntitlementTest {

    @After
    fun clearSource() {
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = false
        })
    }

    @Test
    fun `external billing grant reaches an existing observer`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var premium = false
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            assertEquals(listOf(false), observed)

            premium = true
            Entitlement.notifyChanged()
            yield()

            assertEquals(listOf(false, true), observed)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `collection seeds the current premium answer using the application context`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var premium = false
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context): Boolean {
                assertSame(app, context)
                return premium
            }
        })
        val flow = Entitlement.observe(ContextWrapper(app))
        premium = true

        assertEquals(true, flow.first())
    }

    @Test
    fun `source installed after observation replaces the seeded answer`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        clearSource()
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            Entitlement.install(object : EntitlementSource {
                override fun isPremium(context: Context) = true
            })
            yield()

            assertEquals(listOf(false, true), observed)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `repeated notifications emit only grants and revocations`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var premium = true
        Entitlement.install(object : EntitlementSource {
            override fun isPremium(context: Context) = premium
        })
        val observed = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            Entitlement.observe(app).collect { observed.add(it) }
        }

        try {
            repeat(2) {
                Entitlement.notifyChanged()
                yield()
            }
            premium = false
            Entitlement.notifyChanged()
            yield()
            Entitlement.notifyChanged()
            yield()

            assertEquals(listOf(true, false), observed)
        } finally {
            collector.cancelAndJoin()
        }
    }
}

package io.retentionkit.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionConcurrencyTest {
    @Before fun before() { RetentionRuntime.uninstallForTests() }
    @After fun after() { RetentionRuntime.uninstallForTests() }

    @Test fun moduleMayEmitWhileHoldingOwnLockWithoutInvertingCoreDispatchLock() {
        val moduleLock = ReentrantLock()
        val lockHeld = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val emitFromWorker = CountDownLatch(1)
        val module = object : RetentionModule {
            override val id = "notifications"
            override fun attach(runtime: RetentionRuntime) {}
            override fun onSignal(signal: RetentionSignal) {
                if (signal is RetentionSignal.AdClicked) {
                    callbackEntered.countDown()
                    check(moduleLock.tryLock(2, TimeUnit.SECONDS)) { "Module/core lock inversion" }
                    moduleLock.unlock()
                }
            }
        }
        val runtime = installed(RetentionOptions(store = testStore(), modules = listOf(module)))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val worker = pool.submit<Boolean> {
                moduleLock.withLock {
                    lockHeld.countDown()
                    check(emitFromWorker.await(3, TimeUnit.SECONDS))
                    runtime.signal(RetentionSignal.BusinessSuccess("notes"))
                }
            }
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS))
            val dispatch = pool.submit<Boolean> { runtime.signal(RetentionSignal.AdClicked()) }
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            emitFromWorker.countDown()
            assertTrue(worker.get(1, TimeUnit.SECONDS))
            assertTrue(dispatch.get(2, TimeUnit.SECONDS))
            assertTrue(runtime.diagnostics.snapshot().none { it.component == "notifications.signal" })
        } finally { emitFromWorker.countDown(); pool.shutdownNow() }
    }

    @Test fun validationDoesNotHoldStateLockAgainstConcurrentEntitlementSignal() {
        val validating = CountDownLatch(1)
        val releaseValidation = CountDownLatch(1)
        val module = object : RetentionModule {
            override val id = "review"
            override fun attach(runtime: RetentionRuntime) {}
            override fun validateConfig(config: RetentionConfigSnapshot): List<String> {
                if (config.revision > 0) {
                    validating.countDown()
                    check(releaseValidation.await(3, TimeUnit.SECONDS))
                }
                return emptyList()
            }
        }
        val runtime = installed(RetentionOptions(store = testStore(), modules = listOf(module)))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val config = pool.submit<RetentionConfigResult> { runtime.updateConfig(mapOf("review.enabled" to "true")) }
            assertTrue(validating.await(2, TimeUnit.SECONDS))
            val state = pool.submit<Boolean> { runtime.signal(RetentionSignal.EntitlementChanged(RetentionEntitlement.SUBSCRIBER)) }
            assertTrue(state.get(1, TimeUnit.SECONDS))
            assertEquals(RetentionEntitlement.SUBSCRIBER, runtime.userState.entitlement)
            releaseValidation.countDown()
            assertTrue(config.get(2, TimeUnit.SECONDS) is RetentionConfigResult.Applied)
        } finally { releaseValidation.countDown(); pool.shutdownNow() }
    }

    @Test fun concurrentConfigPatchesRetainBothUpdatesAndPublishWholeRevision() {
        val runtime = installed(RetentionOptions(store = testStore()))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val one = pool.submit<RetentionConfigResult> { runtime.updateConfig(mapOf("notifications.enabled" to "false")) }
            val two = pool.submit<RetentionConfigResult> { runtime.updateConfig(mapOf("review.enabled" to "true")) }
            assertTrue(one.get(3, TimeUnit.SECONDS) is RetentionConfigResult.Applied)
            assertTrue(two.get(3, TimeUnit.SECONDS) is RetentionConfigResult.Applied)
            assertEquals(2, runtime.config.revision)
            assertFalse(runtime.config.boolean("notifications.enabled", true))
            assertTrue(runtime.config.boolean("review.enabled", false))
            assertEquals(2, runtime.store.snapshot("core.config").long("__revision"))
        } finally { pool.shutdownNow() }
    }

    @Test fun coldAttachSeesCurrentInstallEntitlementBeforeReconciliation() {
        val store = testStore()
        installed(RetentionOptions(store = store, initialUserState = RetentionUserState(setupCompleted = true, entitlement = RetentionEntitlement.NON_SUBSCRIBER)))
        RetentionRuntime.uninstallForTests()
        for (current in listOf(RetentionEntitlement.UNKNOWN, RetentionEntitlement.SUBSCRIBER)) {
            val observed = mutableListOf<RetentionEntitlement>()
            val module = object : RetentionModule {
                override val id = "notifications"
                private lateinit var runtime: RetentionRuntime
                override fun attach(runtime: RetentionRuntime) { this.runtime = runtime; observed.add(runtime.userState.entitlement) }
                override fun reconcile(reason: String) { observed.add(runtime.userState.entitlement) }
            }
            val runtime = installed(RetentionOptions(store = store, modules = listOf(module), initialUserState = RetentionUserState(entitlement = current)))
            assertTrue(runtime.userState.setupCompleted)
            assertEquals(listOf(current, current), observed)
            assertEquals(current.name, store.snapshot("core.user").string("entitlement"))
            RetentionRuntime.uninstallForTests()
        }
    }
}

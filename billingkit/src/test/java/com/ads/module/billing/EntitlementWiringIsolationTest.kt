package com.ads.module.billing

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Loads the real bridge against the optional dependency shapes that a billing-only host can ship. */
class EntitlementWiringIsolationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `billing bridge remains usable without the ads module`() {
        val loader = bridgeLoader(emptyMap())

        invokeBridge(loader, "installIntoAdsIfPresent")
        invokeBridge(loader, "notifyAdsIfPresent")
    }

    @Test
    fun `older ads installs its synchronous source without requiring the new notification method`() {
        val directory = temporaryFolder.newFolder()
        val source = File(directory, "EntitlementSource.java").apply {
            writeText("package com.ads.module.helper; public interface EntitlementSource {}")
        }
        val entitlement = File(directory, "Entitlement.java").apply {
            writeText(
                """
                package com.ads.module.helper;
                public final class Entitlement {
                    public static int installs;
                    public static void install(EntitlementSource source) { installs++; }
                }
                """.trimIndent(),
            )
        }
        // Android's compile stubs omit javax.tools; the unit-test JVM still runs on a full JDK.
        val compiler = checkNotNull(
            Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null),
        ) { "Tests require a JDK" }
        val compile = Class.forName("javax.tools.Tool").getMethod(
            "run",
            InputStream::class.java,
            OutputStream::class.java,
            OutputStream::class.java,
            Array<String>::class.java,
        )
        assertEquals(
            0,
            compile.invoke(compiler, null, null, null, arrayOf("-d", directory.path, source.path, entitlement.path)),
        )
        val adsClasses = listOf("Entitlement", "EntitlementSource").associate { name ->
            "com.ads.module.helper.$name" to
                File(directory, "com/ads/module/helper/$name.class").readBytes()
        }
        val loader = bridgeLoader(adsClasses)

        invokeBridge(loader, "installIntoAdsIfPresent")
        invokeBridge(loader, "notifyAdsIfPresent")

        assertEquals(1, loader.loadClass("com.ads.module.helper.Entitlement").getField("installs").get(null))
    }

    private fun invokeBridge(loader: ClassLoader, method: String) {
        loader.loadClass("com.ads.module.billing.EntitlementWiring").getMethod(method).invoke(null)
    }

    private fun bridgeLoader(adsClasses: Map<String, ByteArray>): ClassLoader =
        object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                val isBridge = name.startsWith("com.ads.module.billing.EntitlementWiring") ||
                    name.startsWith("com.ads.module.billing.AdsEntitlementHook")
                val isOptionalAds = name.startsWith("com.ads.module.helper.Entitlement")
                if (!isBridge && !isOptionalAds) return super.loadClass(name, resolve)

                synchronized(this) {
                    findLoadedClass(name)?.let { return it }
                    val bytes = if (isOptionalAds) {
                        adsClasses[name] ?: throw ClassNotFoundException(name)
                    } else {
                        val resource = name.replace('.', '/') + ".class"
                        parent.getResourceAsStream(resource)?.use { it.readBytes() }
                            ?: throw ClassNotFoundException(name)
                    }
                    return defineClass(name, bytes, 0, bytes.size).also {
                        if (resolve) resolveClass(it)
                    }
                }
            }
        }
}

package io.onboardkit.ui.pool

import io.onboardkit.ui.onboarding.ObOnboardingHostActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The pooled pager reuses the classic step fragments through a resolver instead of changing the
 * classic host. This pins the host's callable surface to what it was at SDK 5.6.3 (3fe392a), so a
 * pooled-flow change that leaks into it fails here.
 */
class LegacyHostApiGuardTest {

    @Test fun `the classic pager host keeps its 5_6_3 public and protected methods`() {
        val actual = ObOnboardingHostActivity::class.java.declaredMethods
            .filter { (Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers)) && !it.isSynthetic && !it.isBridge }
            .map(::signature)
            .sorted()
        assertEquals(BASE.sorted().joinToString("\n"), actual.joinToString("\n"))
    }

    /** Internal members carry the module and variant in their JVM name; the guard ignores the suffix. */
    private fun signature(method: Method): String {
        val visibility = if (Modifier.isPublic(method.modifiers)) "public" else "protected"
        val name = method.name.substringBefore('$')
        val params = method.parameterTypes.joinToString(",") { it.simpleName }
        return "$visibility $name($params):${method.returnType.simpleName}"
    }

    private companion object {
        /** Compiled from ObOnboardingHostActivity.kt at 3fe392a; StepId is a value class, hence the name hashes. */
        val BASE = listOf(
            "protected getExcludeFromAppResume():boolean",
            "protected getScreenName():String",
            "protected handleBack():void",
            "protected onCreateSafe(Bundle):void",
            "public back():boolean",
            "public completeAdStep-TEuHuxk(String,String):void",
            "public dispatchTouchEvent(MotionEvent):boolean",
            "public finishFlow(FinishReason):void",
            "public getCurrentIndex():StateFlow",
            "public getResumeBlockedByScreen():boolean",
            "public getTotalSteps():StateFlow",
            "public next():void",
            "public next(String):void",
            "public rebuildPendingPages():void",
            "public setAdStepSwipeEnabled-TEuHuxk(String,boolean):void",
            "public skipAdStep-HUea1JY(String):void",
            "public stepDefinition-HUea1JY(String):StepDefinition",
            "public stepIdAt-JbFxCyk(int):String",
        )
    }
}

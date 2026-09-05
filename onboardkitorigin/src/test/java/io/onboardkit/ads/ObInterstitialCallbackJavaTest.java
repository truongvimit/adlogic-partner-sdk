package io.onboardkit.ads;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A pre-existing concrete Java consumer only implements the original callback methods. */
public class ObInterstitialCallbackJavaTest {
    @Test
    public void reportedTerminalDelegatesToTheOriginalJavaCallback() {
        LegacyConsumer consumer = new LegacyConsumer();
        ObInterstitialCallback callback = consumer;

        callback.onNextAction();
        callback.onPresented(); // The additive default does not require an old consumer override.
        callback.onAdSkipped(AdSkipReason.HOST_NOT_RESUMED, true);

        assertEquals(1, consumer.nextCalls);
        assertEquals(1, consumer.skippedCalls);
        assertEquals(AdSkipReason.HOST_NOT_RESUMED, consumer.lastReason);
    }

    private static final class LegacyConsumer extends ObInterstitialCallback {
        private int nextCalls;
        private int skippedCalls;
        private AdSkipReason lastReason;

        @Override
        public void onNextAction() {
            nextCalls++;
        }

        @Override
        public void onAdSkipped(AdSkipReason reason) {
            skippedCalls++;
            lastReason = reason;
        }
    }
}

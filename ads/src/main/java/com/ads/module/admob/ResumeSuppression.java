package com.ads.module.admob;

/**
 * Ownership of one bounded resume-ad suppression. Closing is idempotent and cancels only this
 * handle, including its captured return; it never changes the configured OPEN/WELCOME mode.
 * No Activity or other UI owner is retained. Close from the UI's terminal/lifecycle callback.
 */
public interface ResumeSuppression extends AutoCloseable {
    @Override void close();
}

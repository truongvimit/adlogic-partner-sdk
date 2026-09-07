package com.ads.module.event;

import android.util.Log;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One registration per owner; callbacks run synchronously outside registry locks and never replay. */
final class AdClickObservers {
    private static final Map<String, Registration> observers = new ConcurrentHashMap<>();

    static AutoCloseable observe(String owner, AdClickObserver observer) {
        if (owner == null || owner.trim().isEmpty() || owner.length() > 128 || observer == null) {
            throw new IllegalArgumentException("Ad click observer needs a bounded owner and callback");
        }
        Registration registration = new Registration(owner, observer);
        Registration previous = observers.put(owner, registration);
        if (previous != null) previous.closed.set(true);
        return registration;
    }

    static void dispatch(String unitId) {
        String clickId = UUID.randomUUID().toString();
        for (Registration registration : new ArrayList<>(observers.values())) {
            if (registration.closed.get()) continue;
            try {
                registration.observer.onAdClick(clickId, unitId);
            } catch (RuntimeException error) {
                Log.w("ERainLogEventManager", "Ad click observer failed: " + registration.owner, error);
            }
        }
    }

    private static final class Registration implements AutoCloseable {
        final String owner;
        final AdClickObserver observer;
        final AtomicBoolean closed = new AtomicBoolean();
        Registration(String owner, AdClickObserver observer) { this.owner = owner; this.observer = observer; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) observers.remove(owner, this);
        }
    }
}

package org.example.sync;

/** Stops a sync apply from being recorded again as a new local change. */
public final class SyncGuard {

    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();

    private SyncGuard() {
    }

    public static boolean isApplying() {
        return Boolean.TRUE.equals(APPLYING.get());
    }

    public static void markApplying() {
        APPLYING.set(Boolean.TRUE);
    }

    public static void clear() {
        APPLYING.remove();
    }
}

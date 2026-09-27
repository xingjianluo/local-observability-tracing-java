package com.github.xingjianluo.localobservabilitytracingjava.trace;

import java.util.UUID;

/**
 * Holds the correlation (trace) id for the current thread. The id is
 * created lazily and reused on re-entry, so repeated execution within the
 * same logical request never spawns a new id.
 */
public final class TraceContext {

    public static final String HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /** Returns the current trace id, or null if none is bound. */
    public static String get() {
        return CURRENT.get();
    }

    /** Returns the current trace id, creating and binding one if absent. */
    public static String getOrCreate() {
        String id = CURRENT.get();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            CURRENT.set(id);
        }
        return id;
    }

    /** Binds the given trace id to the current thread. */
    public static void set(String traceId) {
        CURRENT.set(traceId);
    }

    /** Clears the binding of the current thread. */
    public static void clear() {
        CURRENT.remove();
    }
}

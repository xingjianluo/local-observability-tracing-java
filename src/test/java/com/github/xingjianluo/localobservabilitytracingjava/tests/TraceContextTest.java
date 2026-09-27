package com.github.xingjianluo.localobservabilitytracingjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TraceContextTest {

    private static final Logger log = LoggerFactory.getLogger(TraceContextTest.class);

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void getOrCreateIsIdempotentWithinSameThread() {
        String first = TraceContext.getOrCreate();
        String second = TraceContext.getOrCreate();
        log.info("input: re-enter getOrCreate twice | first={} second={} | expect equal", first, second);
        assertNotNull(first);
        assertEquals(first, second, "re-entry must not create a new trace id");
    }

    @Test
    void setThenGetReturnsSameIdAndClearRemovesIt() {
        TraceContext.set("upstream-id-123");
        String bound = TraceContext.get();
        log.info("input: set('upstream-id-123') | get={} | expect same", bound);
        assertEquals("upstream-id-123", bound);
        TraceContext.clear();
        assertNull(TraceContext.get(), "clear must remove the binding");
    }
}

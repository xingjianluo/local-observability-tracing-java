package com.github.xingjianluo.localobservabilitytracingjava.trace;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local, in-memory recorder of trace events. Bounded to avoid unbounded
 * memory growth; any write failure is logged and swallowed so it can never
 * break or slow down the request path.
 */
@Component
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);
    private static final int MAX_EVENTS = 10_000;

    private final ConcurrentLinkedDeque<TraceEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong dropped = new AtomicLong();

    public void record(TraceEvent event) {
        try {
            if (event == null || event.traceId() == null) {
                return;
            }
            events.addLast(event);
            while (events.size() > MAX_EVENTS) {
                if (events.pollFirst() != null) {
                    dropped.incrementAndGet();
                }
            }
        } catch (RuntimeException e) {
            // Observability must never break the business path.
            log.warn("failed to record trace event: {}", e.toString());
        }
    }

    public void record(String stage, String outcome, String detail) {
        record(new TraceEvent(TraceContext.get(), stage, System.currentTimeMillis(),
                null, outcome, detail));
    }

    public List<TraceEvent> findByTraceId(String traceId) {
        return events.stream().filter(e -> traceId.equals(e.traceId())).toList();
    }

    public List<TraceEvent> findAll() {
        return List.copyOf(events);
    }

    public long droppedCount() {
        return dropped.get();
    }

    public void clear() {
        events.clear();
        dropped.set(0);
    }
}

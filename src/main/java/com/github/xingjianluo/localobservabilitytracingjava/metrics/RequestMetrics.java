package com.github.xingjianluo.localobservabilitytracingjava.metrics;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Thread-safe, in-memory request metrics: per-route call counts, latency
 * distribution (bucketed histogram) and failure breakdown by category.
 * Uses LongAdder so concurrent updates are never lost or double counted.
 */
@Component
public class RequestMetrics {

    /** Latency bucket upper bounds in milliseconds (last bucket = +Inf). */
    private static final long[] BUCKETS = {10, 50, 100, 500, 1000, 3000};

    private static final class RouteStats {
        final LongAdder total = new LongAdder();
        final LongAdder success = new LongAdder();
        final LongAdder failure = new LongAdder();
        final LongAdder latencySumMillis = new LongAdder();
        final LongAdder[] histogram;
        final EnumMap<FailureCategory, LongAdder> failures = new EnumMap<>(FailureCategory.class);

        RouteStats() {
            histogram = new LongAdder[BUCKETS.length + 1];
            for (int i = 0; i < histogram.length; i++) {
                histogram[i] = new LongAdder();
            }
            for (FailureCategory c : FailureCategory.values()) {
                failures.put(c, new LongAdder());
            }
        }
    }

    private final ConcurrentHashMap<String, RouteStats> routes = new ConcurrentHashMap<>();

    public void recordSuccess(String route, long durationMillis) {
        RouteStats stats = routes.computeIfAbsent(route, r -> new RouteStats());
        stats.total.increment();
        stats.success.increment();
        stats.latencySumMillis.add(durationMillis);
        bucket(stats, durationMillis).increment();
    }

    public void recordFailure(String route, long durationMillis, FailureCategory category) {
        RouteStats stats = routes.computeIfAbsent(route, r -> new RouteStats());
        stats.total.increment();
        stats.failure.increment();
        stats.latencySumMillis.add(durationMillis);
        bucket(stats, durationMillis).increment();
        stats.failures.get(category).increment();
    }

    private static LongAdder bucket(RouteStats stats, long durationMillis) {
        for (int i = 0; i < BUCKETS.length; i++) {
            if (durationMillis <= BUCKETS[i]) {
                return stats.histogram[i];
            }
        }
        return stats.histogram[BUCKETS.length];
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, RouteStats> entry : routes.entrySet()) {
            RouteStats s = entry.getValue();
            Map<String, Object> routeView = new LinkedHashMap<>();
            long total = s.total.sum();
            routeView.put("total", total);
            routeView.put("success", s.success.sum());
            routeView.put("failure", s.failure.sum());
            routeView.put("avgLatencyMillis", total == 0 ? 0 : s.latencySumMillis.sum() / total);

            Map<String, Object> histogram = new LinkedHashMap<>();
            for (int i = 0; i < BUCKETS.length; i++) {
                histogram.put("<=" + BUCKETS[i] + "ms", s.histogram[i].sum());
            }
            histogram.put(">" + BUCKETS[BUCKETS.length - 1] + "ms", s.histogram[BUCKETS.length].sum());
            routeView.put("latencyHistogram", histogram);

            Map<String, Object> failures = new LinkedHashMap<>();
            for (Map.Entry<FailureCategory, LongAdder> f : s.failures.entrySet()) {
                failures.put(f.getKey().name(), f.getValue().sum());
            }
            routeView.put("failuresByCategory", failures);

            result.put(entry.getKey(), routeView);
        }
        return result;
    }

    public void reset() {
        routes.clear();
    }
}

package com.github.xingjianluo.localobservabilitytracingjava.web;

import com.github.xingjianluo.localobservabilitytracingjava.metrics.RequestMetrics;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceEvent;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceRecorder;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local endpoints to inspect metrics and trace events, and to export all
 * recorded events for offline analysis.
 */
@RestController
@RequestMapping("/observe")
public class ObserveController {

    private final RequestMetrics metrics;
    private final TraceRecorder recorder;

    public ObserveController(RequestMetrics metrics, TraceRecorder recorder) {
        this.metrics = metrics;
        this.recorder = recorder;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    @GetMapping("/traces/{traceId}")
    public List<TraceEvent> trace(@PathVariable String traceId) {
        return recorder.findByTraceId(traceId);
    }

    @GetMapping("/traces")
    public List<TraceEvent> export() {
        return recorder.findAll();
    }
}

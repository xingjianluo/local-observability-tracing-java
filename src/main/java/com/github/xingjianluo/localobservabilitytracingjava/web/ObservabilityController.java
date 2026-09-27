package com.github.xingjianluo.localobservabilitytracingjava.web;

import com.github.xingjianluo.localobservabilitytracingjava.observability.InMemoryRecordStore;
import com.github.xingjianluo.localobservabilitytracingjava.observability.JsonFileExporter;
import com.github.xingjianluo.localobservabilitytracingjava.observability.MetricsRegistry;
import com.github.xingjianluo.localobservabilitytracingjava.observability.MetricsSnapshot;
import com.github.xingjianluo.localobservabilitytracingjava.observability.RequestRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地可观测数据查看/导出端点，不依赖任何外部系统。
 */
@RestController
@RequestMapping("/observability")
public class ObservabilityController {

    private final MetricsRegistry metricsRegistry;
    private final InMemoryRecordStore store;
    private final JsonFileExporter exporter;

    public ObservabilityController(MetricsRegistry metricsRegistry,
                                   InMemoryRecordStore store,
                                   JsonFileExporter exporter) {
        this.metricsRegistry = metricsRegistry;
        this.store = store;
        this.exporter = exporter;
    }

    @GetMapping("/metrics")
    public MetricsSnapshot metrics() {
        return metricsRegistry.snapshot();
    }

    @GetMapping("/traces")
    public List<RequestRecord> traces(@RequestParam(defaultValue = "20") int limit) {
        return store.latest(limit);
    }

    @GetMapping("/traces/failures")
    public List<RequestRecord> failures(@RequestParam(defaultValue = "20") int limit) {
        return store.failures(limit);
    }

    @GetMapping("/traces/{traceId}")
    public List<RequestRecord> trace(@PathVariable String traceId) {
        List<RequestRecord> records = store.findByTraceId(traceId);
        if (records.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trace not found: " + traceId);
        }
        return records;
    }

    @PostMapping("/traces/{traceId}/export")
    public Map<String, Object> exportTrace(@PathVariable String traceId) {
        Path path = exporter.exportTrace(traceId);
        return result(path, traceId);
    }

    @PostMapping("/export")
    public Map<String, Object> exportAll() {
        Path path = exporter.exportAll();
        return result(path, null);
    }

    private Map<String, Object> result(Path path, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exported", path != null);
        if (traceId != null) {
            body.put("traceId", traceId);
        }
        if (path != null) {
            body.put("path", path.toString());
        } else {
            body.put("reason", traceId == null
                    ? "export failed, see server logs"
                    : "trace not found or export failed, see server logs");
        }
        return body;
    }
}

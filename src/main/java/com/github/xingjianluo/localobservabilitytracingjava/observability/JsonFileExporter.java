package com.github.xingjianluo.localobservabilitytracingjava.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.xingjianluo.localobservabilitytracingjava.config.ObservabilityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Jackson 的本地 JSON 文件导出口。
 *
 * <p>写入目录来自配置（默认 ./observability-data）。任何 IO 失败都只记录告警并返回 null，
 * 绝不抛出异常中断触发导出的请求。
 */
@Component
public class JsonFileExporter implements LocalExporter {

    private static final Logger log = LoggerFactory.getLogger(JsonFileExporter.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final ObservabilityProperties properties;
    private final InMemoryRecordStore store;
    private final ObjectMapper mapper;

    public JsonFileExporter(ObservabilityProperties properties, InMemoryRecordStore store) {
        this.properties = properties;
        this.store = store;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public Path exportTrace(String traceId) {
        List<RequestRecord> records = store.findByTraceId(traceId);
        if (records.isEmpty()) {
            log.warn("export skipped, trace not found: traceId={}", traceId);
            return null;
        }
        return exportRecords(records);
    }

    @Override
    public Path exportAll() {
        return exportRecords(store.latest(properties.getMaxRecords()));
    }

    @Override
    public Path exportRecords(List<RequestRecord> records) {
        Path dir = Path.of(properties.getExportDir());
        Path target = dir.resolve("traces-" + LocalDateTime.now().format(TS) + ".json");
        try {
            Files.createDirectories(dir);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("exportedAt", java.time.Instant.now().toString());
            payload.put("count", records.size());
            payload.put("records", records);
            // 先写临时文件再原子移动，避免导出到一半产生残缺文件。
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(payload),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("traces exported to {}", target.toAbsolutePath());
            return target.toAbsolutePath();
        } catch (IOException | RuntimeException e) {
            log.warn("export traces failed, ignored: target={}", target, e);
            return null;
        }
    }
}

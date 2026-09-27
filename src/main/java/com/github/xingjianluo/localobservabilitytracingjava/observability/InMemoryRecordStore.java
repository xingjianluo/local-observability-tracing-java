package com.github.xingjianluo.localobservabilitytracingjava.observability;

import com.github.xingjianluo.localobservabilitytracingjava.config.ObservabilityProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求观测记录的本地内存存储。
 *
 * <p>一个 traceId 下允许存在多条入口记录（例如 HTTP 请求与其触发的异步任务共用同一标识），
 * 因此按 traceId 分组存放；组内、组间均保持插入顺序，超过容量上限时整组淘汰最旧数据。
 * 所有读写在同一把锁上串行化，保证并发下查询口径稳定。
 */
@Component
public class InMemoryRecordStore {

    private final int capacity;
    private final Map<String, List<RequestRecord>> groups = new LinkedHashMap<>();
    private int totalRecords = 0;

    public InMemoryRecordStore(ObservabilityProperties properties) {
        this.capacity = Math.max(100, properties.getMaxRecords());
    }

    public synchronized void save(RequestRecord record) {
        if (record == null) {
            return;
        }
        groups.computeIfAbsent(record.traceId(), k -> new ArrayList<>()).add(record);
        totalRecords++;
        while (totalRecords > capacity && !groups.isEmpty()) {
            String oldestKey = groups.keySet().iterator().next();
            List<RequestRecord> removed = groups.remove(oldestKey);
            totalRecords -= removed.size();
        }
    }

    /** 返回同一 traceId 下的全部入口记录（HTTP/异步/调度），按结束顺序排列。 */
    public synchronized List<RequestRecord> findByTraceId(String traceId) {
        List<RequestRecord> list = groups.get(traceId);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    /** 最近 limit 条（按结束顺序倒序，跨 traceId）。 */
    public synchronized List<RequestRecord> latest(int limit) {
        int n = Math.max(1, limit);
        List<RequestRecord> flat = flatten();
        List<RequestRecord> result = new ArrayList<>(Math.min(n, flat.size()));
        for (int i = flat.size() - 1; i >= 0 && result.size() < n; i--) {
            result.add(flat.get(i));
        }
        return result;
    }

    /** 最近的失败请求（按结束顺序倒序）。 */
    public synchronized List<RequestRecord> failures(int limit) {
        int n = Math.max(1, limit);
        List<RequestRecord> flat = flatten();
        List<RequestRecord> result = new ArrayList<>();
        for (int i = flat.size() - 1; i >= 0 && result.size() < n; i--) {
            RequestRecord record = flat.get(i);
            if (!record.success()) {
                result.add(record);
            }
        }
        return result;
    }

    public synchronized int size() {
        return totalRecords;
    }

    public synchronized void clear() {
        groups.clear();
        totalRecords = 0;
    }

    private List<RequestRecord> flatten() {
        List<RequestRecord> all = new ArrayList<>(totalRecords);
        for (List<RequestRecord> list : groups.values()) {
            all.addAll(list);
        }
        return all;
    }
}

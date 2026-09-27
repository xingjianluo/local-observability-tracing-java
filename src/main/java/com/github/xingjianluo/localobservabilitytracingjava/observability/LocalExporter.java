package com.github.xingjianluo.localobservabilitytracingjava.observability;

import java.nio.file.Path;
import java.util.List;

/**
 * 把本地观测记录/指标导出为 JSON 文件；写入失败只告警、不影响请求。
 */
public interface LocalExporter {

    Path exportTrace(String traceId);

    Path exportAll();

    Path exportRecords(List<RequestRecord> records);
}

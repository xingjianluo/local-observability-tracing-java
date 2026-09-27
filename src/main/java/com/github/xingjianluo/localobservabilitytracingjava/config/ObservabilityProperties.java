package com.github.xingjianluo.localobservabilitytracingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地可观测能力的可调参数（超时、慢请求阈值、落盘路径等）。
 */
@ConfigurationProperties(prefix = "observability")
public final class ObservabilityProperties {

    private long timeoutMillis = 1000;
    private long slowRequestMillis = 500;
    private String exportDir = "./observability-data";
    private boolean schedulingEnabled = false;
    private int maxRecords = 10_000;

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public long getSlowRequestMillis() {
        return slowRequestMillis;
    }

    public void setSlowRequestMillis(long slowRequestMillis) {
        this.slowRequestMillis = slowRequestMillis;
    }

    public String getExportDir() {
        return exportDir;
    }

    public void setExportDir(String exportDir) {
        this.exportDir = exportDir;
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int maxRecords) {
        this.maxRecords = maxRecords;
    }
}

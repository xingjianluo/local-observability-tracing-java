package com.github.xingjianluo.localobservabilitytracingjava.downstream;

/**
 * 模拟一个可能慢、可能失败的下游依赖，用于演示依赖失败/超时分类。
 */
public interface DownstreamClient {

    String call(boolean fail, long sleepMillis);
}

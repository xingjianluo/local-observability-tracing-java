package com.github.xingjianluo.localobservabilitytracingjava.downstream;

import com.github.xingjianluo.localobservabilitytracingjava.exception.DownstreamException;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link DownstreamClient} 的本地模拟实现：睡眠指定毫秒后按入参抛错，零外部依赖。
 * 日志带上 traceId，可用于验证上下文在线程内可用。
 */
@Component
public class SimulatedDownstreamClient implements DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDownstreamClient.class);

    @Override
    public String call(boolean fail, long sleepMillis) {
        String traceId = TraceContextHolder.currentTraceId();
        log.debug("downstream call begin traceId={} fail={} sleepMs={}", traceId, fail, sleepMillis);
        try {
            if (sleepMillis > 0) {
                Thread.sleep(sleepMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException("downstream call interrupted", e);
        }
        if (fail) {
            throw new DownstreamException("simulated downstream failure");
        }
        return "downstream-ok";
    }
}

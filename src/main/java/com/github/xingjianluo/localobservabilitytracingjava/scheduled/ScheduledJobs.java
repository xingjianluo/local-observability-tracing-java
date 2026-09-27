package com.github.xingjianluo.localobservabilitytracingjava.scheduled;

import com.github.xingjianluo.localobservabilitytracingjava.observability.TraceRecorder;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 后台调度入口。
 *
 * <p>每次触发由 {@link #traceId()} 沿用已有标识或生成新标识（调度线程通常无上游标识，
 * 因此每次调度一个新 traceId；同一次调度内的子环节、重试都沿用它）。
 * 默认关闭，{@code observability.scheduling-enabled=true} 时启用。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(value = "observability.scheduling-enabled", havingValue = "true")
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final TraceRecorder recorder;

    public ScheduledJobs(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Scheduled(fixedDelayString = "${observability.schedule.fixed-delay:60000}")
    public void heartbeat() {
        TraceContext context = TraceContextHolder.getOrCreate(null);
        recorder.start("scheduled:heartbeat");
        try {
            recorder.recordStage("heartbeat-work", () ->
                    log.info("scheduled heartbeat traceId={} thread={}",
                            context.traceId(), Thread.currentThread().getName()));
            recorder.end(null);
        } catch (Exception e) {
            recorder.end(e);
        } finally {
            // 调度线程是长生命周期复用线程，必须清掉本线程本周期的标识。
            TraceContextHolder.clear();
        }
    }
}

package com.github.xingjianluo.localobservabilitytracingjava.config;

import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async and scheduling infrastructure. The executor carries the trace id
 * from the submitting thread to the worker thread so async tasks and
 * retries keep the same correlation id, and clears it afterwards so
 * pooled threads never leak ids across requests.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean
    public TaskDecorator traceContextTaskDecorator() {
        return runnable -> {
            String traceId = TraceContext.get();
            return () -> {
                String previous = TraceContext.get();
                try {
                    if (traceId != null) {
                        TraceContext.set(traceId);
                    }
                    runnable.run();
                } finally {
                    if (previous != null) {
                        TraceContext.set(previous);
                    } else {
                        TraceContext.clear();
                    }
                }
            };
        };
    }

    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    public Executor taskExecutor(TaskDecorator traceContextTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(traceContextTaskDecorator);
        executor.initialize();
        return executor;
    }
}

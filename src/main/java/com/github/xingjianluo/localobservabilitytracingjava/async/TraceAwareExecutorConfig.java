package com.github.xingjianluo.localobservabilitytracingjava.async;

import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * @Async 使用的线程池配置。
 *
 * <p>{@code setTaskDecorator} 在提交线程捕获 traceId，在工作线程恢复；
 * 工作线程执行完会清理 ThreadLocal/MDC，线程池复用时不会把上一个任务的标识带给下一个。
 */
@Configuration
@EnableAsync
public class TraceAwareExecutorConfig implements AsyncConfigurer {

    @Bean(name = "traceAwareExecutor")
    public ThreadPoolTaskExecutor traceAwareExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(TraceContextHolder::wrap);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return traceAwareExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                org.slf4j.LoggerFactory.getLogger(TraceAwareExecutorConfig.class)
                        .error("uncaught exception in async method {} params={} traceId={}",
                                method.getName(), Arrays.toString(params),
                                MDC.get("traceId"), ex);
    }
}

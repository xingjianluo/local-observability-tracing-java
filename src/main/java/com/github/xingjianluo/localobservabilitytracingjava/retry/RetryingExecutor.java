package com.github.xingjianluo.localobservabilitytracingjava.retry;

import com.github.xingjianluo.localobservabilitytracingjava.observability.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * 本地重试执行器。
 *
 * <p>语义：
 * <ul>
 *   <li>重试发生在调用方线程与同一个 traceId 之下，每次尝试记为独立环节
 *       {@code retry-attempt-1..n}，不生成新标识；</li>
 *   <li>仅对异常重试；最终成功则对外正常返回，最终失败则抛出最后一次异常
 *       （由入口统一归为一次失败，不会因为重试而重复计数）；</li>
 *   <li>退避通过 Thread.sleep 在本地完成，不依赖外部组件。</li>
 * </ul>
 */
@Component
public class RetryingExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryingExecutor.class);

    private final TraceRecorder recorder;

    public RetryingExecutor(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    public <T> T execute(Callable<T> action, int maxAttempts, long backoffMillis) throws Exception {
        int attempts = Math.max(1, maxAttempts);
        Exception lastError = null;
        for (int i = 1; i <= attempts; i++) {
            String stageName = "retry-attempt-" + i;
            try {
                T result = recorder.recordStage(stageName, action);
                if (i > 1) {
                    log.info("retry succeeded on attempt {}", i);
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                log.warn("retry attempt {} failed: {}", i, e.getMessage());
                if (i < attempts && backoffMillis > 0) {
                    Thread.sleep(backoffMillis);
                }
            }
        }
        throw lastError;
    }
}

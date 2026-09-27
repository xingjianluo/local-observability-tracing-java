package com.github.xingjianluo.localobservabilitytracingjava.observability;

import com.github.xingjianluo.localobservabilitytracingjava.exception.BusinessRejectionException;
import com.github.xingjianluo.localobservabilitytracingjava.exception.DownstreamException;
import com.github.xingjianluo.localobservabilitytracingjava.exception.RequestTimeoutException;
import org.springframework.stereotype.Component;

/**
 * 把结束时的异常归并为唯一的 {@link FailureType}。
 *
 * <p>沿异常因果链查找，优先级固定（超时 &gt; 下游 &gt; 业务拒绝 &gt; 系统异常），
 * 一次请求只会命中一个类型，避免同一次故障被重复或含糊归类。
 */
@Component
public class FailureClassifier {

    public FailureType classify(Throwable error) {
        if (error == null) {
            return FailureType.NONE;
        }
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof RequestTimeoutException) {
                return FailureType.TIMEOUT;
            }
            current = current.getCause();
        }
        current = error;
        depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof DownstreamException) {
                return FailureType.DOWNSTREAM_FAILURE;
            }
            current = current.getCause();
        }
        current = error;
        depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof BusinessRejectionException) {
                return FailureType.BUSINESS_REJECTION;
            }
            current = current.getCause();
        }
        return FailureType.SYSTEM_ERROR;
    }
}

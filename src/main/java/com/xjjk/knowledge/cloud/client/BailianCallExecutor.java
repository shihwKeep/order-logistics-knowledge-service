package com.xjjk.knowledge.cloud.client;

import com.xjjk.knowledge.cloud.budget.BudgetReservation;
import com.xjjk.knowledge.cloud.budget.CloudModelBudgetService;
import com.xjjk.knowledge.cloud.budget.CloudModelCallType;
import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

@Component
public class BailianCallExecutor {
    private final CloudModelBudgetService budget;
    private final BailianModelProperties properties;
    private final Sleeper sleeper;

    public BailianCallExecutor(CloudModelBudgetService budget, BailianModelProperties properties) {
        this(budget, properties, duration -> Thread.sleep(duration.toMillis()));
    }

    BailianCallExecutor(CloudModelBudgetService budget, BailianModelProperties properties,
                        Sleeper sleeper) {
        this.budget = Objects.requireNonNull(budget);
        this.properties = Objects.requireNonNull(properties);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public <T> T execute(String logicalRequestId, CloudModelCallType callType,
                         String model, long maximumChargeMicros,
                         Attempt<T> attemptOperation) {
        for (int attemptNo = 1; attemptNo <= properties.getMaxAttempts(); attemptNo++) {
            BudgetReservation reservation = budget.reserve(logicalRequestId, callType,
                    attemptNo, model, maximumChargeMicros);
            try {
                BailianCallResult<T> result = attemptOperation.call();
                budget.settle(reservation, result.totalTokens(), result.providerRequestId());
                return result.value();
            } catch (BailianProviderException exception) {
                if (exception.provablyUnbilled()) {
                    budget.release(reservation, exception.errorCode());
                } else {
                    budget.markUnknown(reservation, exception.errorCode());
                }
                if (!exception.retryable() || attemptNo == properties.getMaxAttempts()) throw exception;
            } catch (IOException exception) {
                budget.markUnknown(reservation, exception.getClass().getSimpleName());
                if (attemptNo == properties.getMaxAttempts()) {
                    throw new BailianProviderException(exception.getClass().getSimpleName(), exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                budget.markUnknown(reservation, "InterruptedException");
                throw new BailianProviderException("INTERRUPTED", exception);
            } catch (RuntimeException exception) {
                budget.markUnknown(reservation, exception.getClass().getSimpleName());
                throw exception;
            } catch (Exception exception) {
                budget.markUnknown(reservation, exception.getClass().getSimpleName());
                throw new BailianProviderException(exception.getClass().getSimpleName(), exception);
            }
            backoff(attemptNo);
        }
        throw new IllegalStateException("百炼重试状态异常");
    }

    private void backoff(int completedAttempt) {
        Duration delay = properties.getInitialBackoff().multipliedBy(1L << (completedAttempt - 1));
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BailianProviderException("RETRY_INTERRUPTED", exception);
        }
    }

    @FunctionalInterface
    public interface Attempt<T> {
        BailianCallResult<T> call() throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}

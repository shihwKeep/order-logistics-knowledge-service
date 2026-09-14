package com.xjjk.knowledge.cloud.budget;

import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

@Service
public class CloudModelBudgetService {
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final CloudModelBudgetMapper mapper;
    private final BailianModelProperties properties;
    private final CloudModelCostEstimator estimator;
    private final Clock clock;

    @Autowired
    public CloudModelBudgetService(CloudModelBudgetMapper mapper,
                                   BailianModelProperties properties,
                                   CloudModelCostEstimator estimator) {
        this(mapper, properties, estimator, Clock.systemUTC());
    }

    CloudModelBudgetService(CloudModelBudgetMapper mapper,
                            BailianModelProperties properties,
                            CloudModelCostEstimator estimator,
                            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.properties = Objects.requireNonNull(properties);
        this.estimator = Objects.requireNonNull(estimator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public BudgetReservation reserve(String logicalRequestId, CloudModelCallType callType,
                                     int attemptNo, String model, long maximumChargeMicros) {
        if (logicalRequestId == null || logicalRequestId.isBlank() || callType == null
                || attemptNo <= 0 || model == null || model.isBlank() || maximumChargeMicros <= 0) {
            throw new IllegalArgumentException("云模型预算预留参数不合法");
        }
        LocalDateTime now = now();
        String month = YearMonth.from(now).format(MONTH_FORMAT);
        mapper.insertMonthIfAbsent(month, properties.getHardLimitMicros(), now);
        if (mapper.reserve(month, maximumChargeMicros, now) != 1) {
            throw new CloudModelBudgetExceededException();
        }
        String callId = UUID.randomUUID().toString();
        if (mapper.insertCall(callId, month, logicalRequestId, attemptNo, callType.name(),
                model, maximumChargeMicros, now) != 1) {
            throw new IllegalStateException("云模型调用账本写入失败");
        }
        return new BudgetReservation(callId, month, maximumChargeMicros, callType, attemptNo);
    }

    @Transactional
    public void settle(BudgetReservation reservation, long totalTokens, String providerRequestId) {
        Objects.requireNonNull(reservation, "reservation");
        long actual = estimator.actualCharge(totalTokens, price(reservation.callType()));
        if (actual > reservation.reservedMicros()) {
            throw new IllegalStateException("实际费用超过保守预留，拒绝错误结算");
        }
        LocalDateTime now = now();
        if (mapper.settleAccount(reservation.billingMonth(), reservation.reservedMicros(), actual, now) != 1
                || mapper.markSettled(reservation.callId(), actual, totalTokens,
                providerRequestId, now) != 1) {
            throw new IllegalStateException("云模型费用结算失败");
        }
    }

    @Transactional
    public void release(BudgetReservation reservation, String errorCode) {
        Objects.requireNonNull(reservation, "reservation");
        LocalDateTime now = now();
        if (mapper.releaseAccount(reservation.billingMonth(), reservation.reservedMicros(), now) != 1
                || mapper.markReleased(reservation.callId(), safeCode(errorCode), now) != 1) {
            throw new IllegalStateException("云模型费用释放失败");
        }
    }

    @Transactional
    public void markUnknown(BudgetReservation reservation, String errorCode) {
        Objects.requireNonNull(reservation, "reservation");
        if (mapper.markUnknown(reservation.callId(), safeCode(errorCode), now()) != 1) {
            throw new IllegalStateException("云模型未知费用状态写入失败");
        }
    }

    LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), BILLING_ZONE);
    }

    private long price(CloudModelCallType type) {
        return type == CloudModelCallType.EMBEDDING
                ? properties.getEmbeddingPriceMicrosPerMillionTokens()
                : properties.getRerankerPriceMicrosPerMillionTokens();
    }

    private String safeCode(String errorCode) {
        return errorCode == null || errorCode.isBlank() ? "UNKNOWN" : errorCode.substring(0, Math.min(128, errorCode.length()));
    }
}

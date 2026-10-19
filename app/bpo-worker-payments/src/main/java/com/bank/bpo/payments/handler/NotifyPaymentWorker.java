package com.bank.bpo.payments.handler;

import com.bank.bpo.payments.port.NotificationPort;
import com.bank.bpo.workerkit.IdempotencyKeys;
import com.bank.bpo.workerkit.Ids;
import com.bank.bpo.workerkit.JobErrors;
import com.bank.bpo.workerkit.OutputVariables;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** ntf.notify-payment: a transactional notification about the payment. One key per payment and purpose. */
@Component
public class NotifyPaymentWorker {

    private static final Set<String> OUTPUTS = Set.of("notificationRef");
    private static final Set<String> PURPOSES = Set.of("PAYMENT_SETTLED", "PAYMENT_REJECTED");
    private final NotificationPort notifications;

    public NotifyPaymentWorker(NotificationPort notifications) {
        this.notifications = notifications;
    }

    @JobWorker(type = "ntf.notify-payment", fetchVariables = {"paymentId", "purpose"})
    public Map<String, Object> handle(@Variable String paymentId, @Variable String purpose) {
        return JobErrors.guard(paymentId, () -> {
            String step = "notify-" + Ids.oneOf("purpose", purpose, PURPOSES).toLowerCase(Locale.ROOT).replace('_', '-');
            return OutputVariables.of(OUTPUTS,
                Map.of("notificationRef", notifications.notify(paymentId, purpose, IdempotencyKeys.of(paymentId, step))));
        });
    }
}

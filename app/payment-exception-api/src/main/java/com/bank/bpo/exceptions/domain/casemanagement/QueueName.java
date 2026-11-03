package com.bank.bpo.exceptions.domain.casemanagement;

import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A work queue: the candidate group the routing decision (pay-exception-routing) assigns a review task to, and the
 * Keycloak group a reviewer belongs to, such as {@code pay-tr-fast-ops}.
 */
public record QueueName(String value) {

    private static final Pattern GROUP = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");

    public QueueName {
        if (value == null || !GROUP.matcher(value).matches()) {
            throw new DomainException(ErrorCode.INVALID_QUEUE, "a queue is 2 to 63 of a-z, 0-9 and -, starting with a letter or digit");
        }
    }

    public static QueueName of(String value) {
        return new QueueName(value);
    }

    /**
     * A group as Keycloak puts it in the token: {@code /pay-tr-fast-ops}, or a path whose last segment is the
     * group. A value that is no queue name is not a queue, not an error: a token may carry other groups.
     */
    public static Optional<QueueName> parse(String group) {
        if (group == null) {
            return Optional.empty();
        }
        String name = group.substring(group.lastIndexOf('/') + 1);
        return GROUP.matcher(name).matches() ? Optional.of(new QueueName(name)) : Optional.empty();
    }

    @Override
    public String toString() {
        return value;
    }
}

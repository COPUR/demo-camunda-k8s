package com.bank.bpo.exceptions.application.service;

import com.bank.bpo.exceptions.application.port.in.CasePage;
import com.bank.bpo.exceptions.application.port.in.CaseQuery;
import com.bank.bpo.exceptions.application.port.out.CaseWorkflow;
import com.bank.bpo.exceptions.application.port.out.DomainEventPublisher;
import com.bank.bpo.exceptions.application.port.out.ExceptionCaseRepository;
import com.bank.bpo.exceptions.domain.casemanagement.CaseState;
import com.bank.bpo.exceptions.domain.casemanagement.DomainEvent;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase;
import com.bank.bpo.exceptions.domain.casemanagement.ExceptionDecisionSubmitted;
import com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** An in-memory workflow, repository and event log: the ports as the application sees them, without an engine. */
public final class FakeCases implements ExceptionCaseRepository, CaseWorkflow, DomainEventPublisher {

    public final Map<PaymentId, ExceptionCase> cases = new ConcurrentHashMap<>();
    public final List<NewExceptionCase> opened = new CopyOnWriteArrayList<>();
    public final List<ExceptionDecisionSubmitted> submitted = new CopyOnWriteArrayList<>();
    public final List<DomainEvent> published = new CopyOnWriteArrayList<>();
    public final List<CaseQuery> queries = new ArrayList<>();
    private final AtomicLong keys = new AtomicLong(2251799813685000L);
    public RuntimeException failOpen;
    public RuntimeException failSubmit;

    @Override
    public Optional<ExceptionCase> findByPaymentId(PaymentId paymentId) {
        return Optional.ofNullable(cases.get(paymentId));
    }

    @Override
    public CasePage findInReview(CaseQuery query) {
        queries.add(query);
        List<ExceptionCase> items = cases.values().stream().filter(c -> c.state() == CaseState.IN_REVIEW).toList();
        return new CasePage(items, Optional.empty());
    }

    @Override
    public long open(NewExceptionCase request) {
        if (failOpen != null) {
            throw failOpen;
        }
        opened.add(request);
        long key = keys.incrementAndGet();
        cases.put(request.paymentId(), new ExceptionCase(request.paymentId(), request.jurisdiction(), request.rail(),
            CaseState.AWAITING_SCHEME_STATUS, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            false, Instant.EPOCH, key));
        return key;
    }

    @Override
    public void submit(ExceptionDecisionSubmitted decision) {
        if (failSubmit != null) {
            throw failSubmit;
        }
        submitted.add(decision);
    }

    @Override
    public void publish(DomainEvent event) {
        published.add(event);
    }
}

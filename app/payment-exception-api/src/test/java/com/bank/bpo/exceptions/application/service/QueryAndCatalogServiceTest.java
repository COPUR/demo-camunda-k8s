package com.bank.bpo.exceptions.application.service;

import static com.bank.bpo.exceptions.application.service.RaiseExceptionCaseServiceTest.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.application.port.in.CaseQuery;
import com.bank.bpo.exceptions.application.port.in.IdempotencyKey;
import com.bank.bpo.exceptions.application.port.out.CatalogSource;
import com.bank.bpo.exceptions.domain.casemanagement.QueueName;
import com.bank.bpo.exceptions.domain.casemanagement.ReviewLevel;
import com.bank.bpo.exceptions.domain.catalog.ErrorDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCaseDefinition;
import com.bank.bpo.exceptions.domain.catalog.ExceptionCatalog;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCategory;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QueryAndCatalogServiceTest {

    @Test
    void aCaseIsReadByItsPaymentId() {
        FakeCases fake = new FakeCases();
        fake.open(new com.bank.bpo.exceptions.domain.casemanagement.NewExceptionCase(PaymentId.of("demo-tr-unres-001"),
            com.bank.bpo.exceptions.domain.shared.Jurisdiction.of("TR"), com.bank.bpo.exceptions.domain.shared.Rail.FAST));
        ViewExceptionCasesService service = new ViewExceptionCasesService(fake);

        assertThat(service.get(PaymentId.of("demo-tr-unres-001")).paymentId()).isEqualTo(PaymentId.of("demo-tr-unres-001"));
        assertRefused(() -> service.get(PaymentId.of("demo-tr-none")), ErrorCode.EXCEPTION_CASE_NOT_FOUND);
    }

    @Test
    void theReviewListingPassesTheQueryThrough() {
        FakeCases fake = new FakeCases();
        ViewExceptionCasesService service = new ViewExceptionCasesService(fake);
        CaseQuery query = new CaseQuery(Optional.of(QueueName.of("pay-tr-fast-ops")), Optional.of(ReviewLevel.OPERATIONS), 20, Optional.empty());

        assertThat(service.inReview(query).items()).isEmpty();
        assertThat(fake.queries).containsExactly(query);
    }

    @Test
    void aQueryAsksForOneToOneHundredCasesWithABoundedToken() {
        assertThat(CaseQuery.firstPage(Optional.empty(), Optional.empty(), null).pageSize()).isEqualTo(CaseQuery.DEFAULT_PAGE_SIZE);
        assertRefused(() -> CaseQuery.firstPage(Optional.empty(), Optional.empty(), 0), ErrorCode.INVALID_PAGE_REQUEST);
        assertRefused(() -> CaseQuery.firstPage(Optional.empty(), Optional.empty(), 101), ErrorCode.INVALID_PAGE_REQUEST);
        assertRefused(() -> new CaseQuery(Optional.empty(), Optional.empty(), 10, Optional.of("x".repeat(1025))), ErrorCode.INVALID_PAGE_REQUEST);
        assertRefused(() -> new CaseQuery(Optional.empty(), Optional.empty(), 10, Optional.of("not base64url!")), ErrorCode.INVALID_PAGE_REQUEST);
        assertThat(new CaseQuery(Optional.empty(), Optional.empty(), 10, Optional.of("eyJhIjoxfQ")).pageToken()).contains("eyJhIjoxfQ");
    }

    @Test
    void anIdempotencyKeyIsEightToSixtyFourSafeCharacters() {
        assertThat(IdempotencyKey.of("6f1c2a9e-8d1b-4c7e-9f00-1a2b3c4d5e6f").value()).hasSize(36);
        for (String raw : new String[] {null, "short", "x".repeat(65), "key with spaces", "key/../../etc"}) {
            assertRefused(() -> IdempotencyKey.of(raw), ErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }

    @Test
    void theCatalogIsLoadedOnceAndAnsweredByCode() {
        AtomicInteger loads = new AtomicInteger();
        ExceptionCatalog catalog = new ExceptionCatalog("1.0.0",
            List.of(new ExceptionCaseDefinition("EXC-001", "SCHEME_STATUS_TIMEOUT", "t", "scheme-status", "trigger", List.of(), "OPERATIONS",
                List.of(), "sla", "owner", List.of(), "runbook")),
            List.of(new ErrorDefinition("PEX-3001", "EXCEPTION_CASE_NOT_FOUND", "t", ErrorCategory.NOT_FOUND, 404, false, "s", "d", "r")));
        CatalogSource source = () -> {
            loads.incrementAndGet();
            return catalog;
        };
        ExceptionCatalogService service = new ExceptionCatalogService(source);

        assertThat(service.catalog()).isSameAs(catalog);
        assertThat(service.catalog()).isSameAs(catalog);
        assertThat(loads).hasValue(1);
        assertThat(service.error("PEX-3001").name()).isEqualTo("EXCEPTION_CASE_NOT_FOUND");
        assertThat(service.exceptionCase("EXC-001").name()).isEqualTo("SCHEME_STATUS_TIMEOUT");
        assertRefused(() -> service.error("PEX-0001"), ErrorCode.CATALOG_ENTRY_NOT_FOUND);
        assertRefused(() -> service.exceptionCase("EXC-999"), ErrorCode.CATALOG_ENTRY_NOT_FOUND);
    }

    @Test
    void aFingerprintIsAStableDigestOfTheRequest() {
        assertThat(Fingerprint.of("raise", "demo-tr-1", "TR")).isEqualTo(Fingerprint.of("raise", "demo-tr-1", "TR")).hasSize(64);
        assertThat(Fingerprint.of("raise", "demo-tr-1", "TR")).isNotEqualTo(Fingerprint.of("raise", "demo-tr-1TR"));
        assertThatThrownBy(() -> Fingerprint.of((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void paymentLocksSerialiseOnePaymentNotAll() {
        PaymentLocks locks = new PaymentLocks(16);
        assertThat(locks.lockFor(PaymentId.of("demo-tr-a"))).isSameAs(locks.lockFor(PaymentId.of("demo-tr-a")));
        assertThatThrownBy(() -> new PaymentLocks(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(locks.call(PaymentId.of("demo-tr-a"), () -> "done")).isEqualTo("done");
        assertThatThrownBy(() -> locks.call(PaymentId.of("demo-tr-a"), () -> {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "x");
        })).isInstanceOf(DomainException.class);
        assertThat(locks.lockFor(PaymentId.of("demo-tr-a")).isLocked()).isFalse();
    }
}

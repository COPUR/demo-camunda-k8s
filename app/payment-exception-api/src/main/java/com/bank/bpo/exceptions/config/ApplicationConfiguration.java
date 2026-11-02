package com.bank.bpo.exceptions.config;

import com.bank.bpo.exceptions.adapter.out.catalog.YamlCatalogSource;
import com.bank.bpo.exceptions.adapter.out.idempotency.InMemoryIdempotencyStore;
import com.bank.bpo.exceptions.application.port.in.BrowseExceptionCatalog;
import com.bank.bpo.exceptions.application.port.in.DecideExceptionCase;
import com.bank.bpo.exceptions.application.port.in.RaiseExceptionCase;
import com.bank.bpo.exceptions.application.port.in.ViewExceptionCases;
import com.bank.bpo.exceptions.application.port.out.CaseWorkflow;
import com.bank.bpo.exceptions.application.port.out.CatalogSource;
import com.bank.bpo.exceptions.application.port.out.DomainEventPublisher;
import com.bank.bpo.exceptions.application.port.out.ExceptionCaseRepository;
import com.bank.bpo.exceptions.application.port.out.IdempotencyStore;
import com.bank.bpo.exceptions.application.service.DecideExceptionCaseService;
import com.bank.bpo.exceptions.application.service.ExceptionCatalogService;
import com.bank.bpo.exceptions.application.service.RaiseExceptionCaseService;
import com.bank.bpo.exceptions.application.service.ViewExceptionCasesService;
import com.bank.bpo.exceptions.domain.casemanagement.JurisdictionPolicy;
import com.bank.bpo.exceptions.domain.shared.Jurisdiction;
import java.time.Clock;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The composition root of the application ring: use cases wired to whichever adapters the other configurations give. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExceptionApiProperties.class)
public class ApplicationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JurisdictionPolicy jurisdictionPolicy(ExceptionApiProperties properties) {
        return new JurisdictionPolicy(properties.servedJurisdictions().stream().map(Jurisdiction::of).collect(Collectors.toSet()));
    }

    @Bean
    IdempotencyStore idempotencyStore(ExceptionApiProperties properties, Clock clock) {
        ExceptionApiProperties.Idempotency idempotency = properties.idempotency();
        return new InMemoryIdempotencyStore(idempotency.ttl(), idempotency.maxEntries(), clock);
    }

    @Bean
    CatalogSource catalogSource() {
        return new YamlCatalogSource(YamlCatalogSource.DEFAULT_RESOURCE);
    }

    @Bean
    RaiseExceptionCase raiseExceptionCase(JurisdictionPolicy jurisdictions, ExceptionCaseRepository cases, CaseWorkflow workflow,
                                          DomainEventPublisher events, IdempotencyStore idempotency, Clock clock) {
        return new RaiseExceptionCaseService(jurisdictions, cases, workflow, events, idempotency, clock);
    }

    @Bean
    DecideExceptionCase decideExceptionCase(ExceptionCaseRepository cases, CaseWorkflow workflow, DomainEventPublisher events,
                                            IdempotencyStore idempotency, Clock clock) {
        return new DecideExceptionCaseService(cases, workflow, events, idempotency, clock);
    }

    @Bean
    ViewExceptionCases viewExceptionCases(ExceptionCaseRepository cases) {
        return new ViewExceptionCasesService(cases);
    }

    @Bean
    BrowseExceptionCatalog browseExceptionCatalog(CatalogSource source) {
        return new ExceptionCatalogService(source);
    }
}

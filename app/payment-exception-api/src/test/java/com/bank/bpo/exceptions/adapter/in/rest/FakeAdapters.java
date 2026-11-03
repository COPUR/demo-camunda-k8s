package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.application.service.FakeCases;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The engine and the event log replaced by one in-memory fake that the tests seed and inspect. */
@TestConfiguration(proxyBeanMethods = false)
class FakeAdapters {

    @Bean
    @Primary
    FakeCases fakeCases() {
        return new FakeCases();
    }
}

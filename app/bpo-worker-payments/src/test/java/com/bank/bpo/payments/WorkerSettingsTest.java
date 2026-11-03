package com.bank.bpo.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.spring.properties.CamundaClientProperties;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The job settings that keep one stalled call from stopping every job type (T1), bound from application.yml
 * the way the Camunda starter binds them, so a misspelt key fails here instead of silently falling back to the
 * client's defaults (one thread, 32 jobs held, PT5M).
 */
class WorkerSettingsTest {

    private static CamundaClientProperties bound() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("camunda.client", CamundaClientProperties.class).get();
    }

    @Test
    void eightThreadsRunTheJobsAndNoMoreJobsAreHeldThanThreads() throws IOException {
        CamundaClientProperties client = bound();
        assertThat(client.getExecutionThreads()).isEqualTo(8);
        assertThat(client.getWorker().getDefaults().getMaxJobsActive()).isEqualTo(8);
    }

    @Test
    void aJobOfAWorkerThatIsGoneReturnsToTheEngineAfterAMinuteUnderALease() throws IOException {
        CamundaClientProperties client = bound();
        assertThat(client.getWorker().getDefaults().getTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(client.getWorker().getDefaults().getWithLease()).isTrue();
    }
}

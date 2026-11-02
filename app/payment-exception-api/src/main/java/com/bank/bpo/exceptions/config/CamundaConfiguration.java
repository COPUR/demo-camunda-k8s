package com.bank.bpo.exceptions.config;

import com.bank.bpo.exceptions.adapter.out.camunda.CamundaExceptionCases;
import io.camunda.client.CamundaClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The engine adapter, on the client the Camunda starter builds from CAMUNDA_CLIENT_*. One bean serves both ports
 * (the repository reads cases, the workflow changes them), because both are the same process instances.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "camunda.client.enabled", havingValue = "true", matchIfMissing = true)
public class CamundaConfiguration {

    @Bean
    CamundaExceptionCases camundaExceptionCases(CamundaClient client, ExceptionApiProperties properties, Clock clock) {
        ExceptionApiProperties.Workflow workflow = properties.workflow();
        return new CamundaExceptionCases(client, workflow.processId(), workflow.requestTimeout(), workflow.readYourWritesWindow(), clock);
    }
}

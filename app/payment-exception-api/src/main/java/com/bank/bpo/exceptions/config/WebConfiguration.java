package com.bank.bpo.exceptions.config;

import com.bank.bpo.exceptions.adapter.in.rest.filter.InteractionIdFilter;
import com.bank.bpo.exceptions.adapter.in.rest.filter.RequestSizeLimitFilter;
import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import com.google.protobuf.util.JsonFormat;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.converter.protobuf.ProtobufJsonFormatHttpMessageConverter;

/**
 * The HTTP edge: protobuf bodies in binary ({@code application/x-protobuf}) and in the proto3 JSON mapping
 * ({@code application/json}), and the two filters that run before authentication (interaction id, body limit).
 */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration {

    @Bean
    ProblemWriter problemWriter() {
        return new ProblemWriter();
    }

    /**
     * Strict in, complete out: the parser refuses fields the contract does not define (a typo is an error, not a
     * silently dropped value), and the printer writes every field, so clients see zero values instead of guessing.
     */
    @Bean
    ServerHttpMessageConvertersCustomizer protobufConverter() {
        ProtobufJsonFormatHttpMessageConverter converter = new ProtobufJsonFormatHttpMessageConverter(JsonFormat.parser(),
            JsonFormat.printer().alwaysPrintFieldsWithNoPresence().omittingInsignificantWhitespace());
        return builder -> builder.addCustomConverter(converter);
    }

    @Bean
    FilterRegistrationBean<InteractionIdFilter> interactionIdFilter() {
        FilterRegistrationBean<InteractionIdFilter> registration = new FilterRegistrationBean<>(new InteractionIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(ExceptionApiProperties properties, ProblemWriter problems) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
            new FilterRegistrationBean<>(new RequestSizeLimitFilter(properties.http().maxRequestBytes(), problems));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}

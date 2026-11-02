package com.bank.bpo.exceptions.adapter.in.rest.filter;

import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import com.bank.bpo.exceptions.domain.shared.InteractionId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an interaction id: the caller's {@code x-fapi-interaction-id} when it is a UUID, a new one
 * otherwise. It is echoed in the response, put in the log context and carried into commands and events.
 */
public final class InteractionIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "x-fapi-interaction-id";
    public static final String ATTRIBUTE = ProblemWriter.INTERACTION_ID_ATTRIBUTE;
    static final String MDC_KEY = "interactionId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        InteractionId id = InteractionId.parse(request.getHeader(HEADER)).orElseGet(InteractionId::random);
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id.value());
        MDC.put(MDC_KEY, id.value());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The id the filter gave the request; a fresh one when the filter did not run (a unit test calling a controller). */
    public static InteractionId of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof InteractionId id ? id : InteractionId.random();
    }
}

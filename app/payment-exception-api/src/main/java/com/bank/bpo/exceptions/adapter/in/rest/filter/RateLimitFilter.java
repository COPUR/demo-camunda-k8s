package com.bank.bpo.exceptions.adapter.in.rest.filter;

import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Load shedding per caller: one token bucket per authenticated principal, answered with 429 and Retry-After. It
 * runs after authentication, so it limits identities, not addresses; address-based limits belong in front of the
 * service (a WAF rate-based rule on the load balancer). The bucket map is bounded so many identities cannot grow it.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private final int capacity;
    private final double refillPerSecond;
    private final int maxCallers;
    private final LongSupplier nanoTime;
    private final ProblemWriter problems;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int capacity, double refillPerSecond, int maxCallers, LongSupplier nanoTime, ProblemWriter problems) {
        if (capacity < 1 || refillPerSecond <= 0 || maxCallers < 1) {
            throw new IllegalArgumentException("a rate limit allows at least one request, refills and tracks at least one caller");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxCallers = maxCallers;
        this.nanoTime = nanoTime;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        if (buckets.size() >= maxCallers && !buckets.containsKey(authentication.getName())) {
            // Coarse but bounded: forget every bucket rather than grow without limit.
            buckets.clear();
        }
        long waitSeconds = buckets.computeIfAbsent(authentication.getName(), k -> new TokenBucket(capacity, refillPerSecond, nanoTime)).tryAcquire();
        if (waitSeconds > 0) {
            response.setHeader("Retry-After", Long.toString(waitSeconds));
            problems.write(request, response, ErrorCode.RATE_LIMITED, "slow down: the caller's request budget is spent");
            return;
        }
        chain.doFilter(request, response);
    }
}

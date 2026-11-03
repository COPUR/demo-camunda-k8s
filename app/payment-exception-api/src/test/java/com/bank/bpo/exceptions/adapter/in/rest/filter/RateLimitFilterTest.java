package com.bank.bpo.exceptions.adapter.in.rest.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    final RateLimitFilter filter = new RateLimitFilter(2, 1.0, 2, nanos::get, new ProblemWriter());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    int call(String principal) throws Exception {
        if (principal == null) {
            SecurityContextHolder.clearContext();
        } else {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken(principal, "n/a", List.of());
            authentication.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/exception-catalog"), response, new MockFilterChain());
        if (response.getStatus() == 429) {
            assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            assertThat(response.getContentAsString()).contains("PEX-5004");
        }
        return response.getStatus();
    }

    @Test
    void aCallerGetsABurstThenTheRefillRate() throws Exception {
        assertThat(call("ops1")).isEqualTo(200);
        assertThat(call("ops1")).isEqualTo(200);
        assertThat(call("ops1")).isEqualTo(429);
        nanos.addAndGet(1_000_000_000L);
        assertThat(call("ops1")).isEqualTo(200);
    }

    @Test
    void callersHaveTheirOwnBudgetAndTheBucketMapIsBounded() throws Exception {
        assertThat(call("ops1")).isEqualTo(200);
        assertThat(call("ops1")).isEqualTo(200);
        assertThat(call("ops2")).isEqualTo(200);
        // A third caller exceeds the map's bound: every bucket is forgotten, so ops1 starts afresh.
        assertThat(call("ops3")).isEqualTo(200);
        assertThat(call("ops1")).isEqualTo(200);
    }

    @Test
    void anUnauthenticatedRequestIsLeftToTheEntryPoint() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(call(null)).isEqualTo(200);
        }
        TestingAuthenticationToken notYet = new TestingAuthenticationToken("ops1", "n/a");
        notYet.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(notYet);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void refusesALimitThatAllowsNothing() {
        assertThatThrownBy(() -> new RateLimitFilter(0, 1, 1, System::nanoTime, new ProblemWriter())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitFilter(1, 0, 1, System::nanoTime, new ProblemWriter())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitFilter(1, 1, 0, System::nanoTime, new ProblemWriter())).isInstanceOf(IllegalArgumentException.class);
    }
}

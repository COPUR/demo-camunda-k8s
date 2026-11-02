package com.bank.bpo.exceptions.adapter.in.rest.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** A body without a declared length (chunked) is counted while it is read. */
class RequestSizeLimitFilterTest {

    final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(8, new ProblemWriter());

    static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payment-exceptions") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }

    ServletInputStream passOn(MockHttpServletRequest request) throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));
        ServletInputStream in = seen.get().getInputStream();
        assertThat(seen.get().getInputStream()).isSameAs(in);
        return in;
    }

    @Test
    void aBodyWithinTheLimitReadsThrough() throws Exception {
        ServletInputStream in = passOn(chunked("12345678".getBytes()));
        assertThat(in.read()).isEqualTo('1');
        assertThat(in.read(new byte[16], 0, 16)).isEqualTo(7);
        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.isFinished()).isTrue();
        assertThat(in.isReady()).isTrue();
    }

    @Test
    void aLargerBodyFailsWhileItIsReadByteByByteOrInBlocks() throws Exception {
        ServletInputStream blocks = passOn(chunked("123456789".getBytes()));
        assertThatThrownBy(() -> blocks.read(new byte[16], 0, 16)).isInstanceOf(RequestSizeLimitFilter.PayloadTooLargeException.class);

        ServletInputStream bytes = passOn(chunked("123456789".getBytes()));
        assertThatThrownBy(() -> {
            for (int i = 0; i < 9; i++) {
                bytes.read();
            }
        }).isInstanceOf(IOException.class).hasMessageContaining("exceeds 8 bytes");
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedUpFront() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payment-exceptions");
        request.setContent("123456789".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("the chain must not run");
        });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PEX-5001");
    }

    @Test
    void theLimitIsPositive() {
        assertThatThrownBy(() -> new RequestSizeLimitFilter(0, new ProblemWriter())).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.bank.bpo.exceptions.adapter.in.rest.filter;

import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses bodies larger than the API ever needs, before anything parses them: a declared Content-Length is checked
 * up front, a chunked body is counted while it is read.
 */
public final class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Thrown while reading a body that grew past the limit; the exception handler answers it with PEX-5001. */
    public static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(long limit) {
            super("request body exceeds " + limit + " bytes");
        }
    }

    private final long maxBytes;
    private final ProblemWriter problems;

    public RequestSizeLimitFilter(long maxBytes, ProblemWriter problems) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("the limit is positive");
        }
        this.maxBytes = maxBytes;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            problems.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE, "the request body is limited to " + maxBytes + " bytes");
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream limited;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return limited;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long maxBytes;
        private long read;

        LimitedInputStream(ServletInputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws PayloadTooLargeException {
            read += n;
            if (read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}

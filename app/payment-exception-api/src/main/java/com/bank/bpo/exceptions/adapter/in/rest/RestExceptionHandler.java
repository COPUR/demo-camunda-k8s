package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.adapter.in.rest.filter.RequestSizeLimitFilter;
import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCategory;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into a catalogued problem. A client error is the caller's to fix and is logged quietly; a
 * dependency or internal failure is logged with its cause, and the response names only the code and the interaction
 * id, never the cause (no stack traces, hostnames or engine messages reach the caller).
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    private final ProblemWriter problems;

    public RestExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @ExceptionHandler(DomainException.class)
    void domain(DomainException e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        ErrorCategory category = e.code().category();
        if (category == ErrorCategory.DEPENDENCY || category == ErrorCategory.INTERNAL) {
            log.warn("{} {} failed: {}", request.getMethod(), request.getRequestURI(), e.code().code(), e);
        } else {
            log.debug("{} {} refused: {} {}", request.getMethod(), request.getRequestURI(), e.code().code(), e.getMessage());
        }
        problems.write(request, response, e.code(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    void unreadable(HttpMessageNotReadableException e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (causedBy(e, RequestSizeLimitFilter.PayloadTooLargeException.class)) {
            problems.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE, "the request body is larger than this API accepts");
            return;
        }
        problems.write(request, response, ErrorCode.MALFORMED_REQUEST, "the body is not a valid message of this API version");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    void unsupportedMediaType(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(request, response, ErrorCode.UNSUPPORTED_MEDIA_TYPE, "send application/json or application/x-protobuf");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    void notAcceptable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(request, response, ErrorCode.NOT_ACCEPTABLE, "accept application/json or application/x-protobuf");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    void methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (e.getSupportedHttpMethods() != null) {
            response.setHeader("Allow", e.getSupportedHttpMethods().stream().map(HttpMethod::name).sorted().collect(Collectors.joining(", ")));
        }
        problems.write(request, response, ErrorCode.METHOD_NOT_ALLOWED, "the resource does not support " + request.getMethod());
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(request, response, ErrorCode.RESOURCE_NOT_FOUND, "no such resource");
    }

    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    void badRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(request, response, ErrorCode.MALFORMED_REQUEST, "the request's parameters or headers are not valid");
    }

    @ExceptionHandler(Exception.class)
    void unexpected(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.error("{} {} failed unexpectedly", request.getMethod(), request.getRequestURI(), e);
        problems.write(request, response, ErrorCode.INTERNAL_ERROR, "an unexpected error occurred; quote the interaction id when reporting it");
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}

package com.bank.bpo.exceptions.adapter.in.rest.problem;

import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an RFC 9457 problem with a catalogued code. Used by the controllers' exception handler, the security entry
 * points and the filters alike, so every error looks the same. It writes straight to the response: an error must
 * not depend on the content negotiation that may itself have failed.
 */
public final class ProblemWriter {

    public static final String PROBLEM_JSON = "application/problem+json";
    public static final String TYPE_PREFIX = "/api/v1/exception-catalog/errors/";
    /** The request attribute that holds the request's interaction id (set by the interaction id filter). */
    public static final String INTERACTION_ID_ATTRIBUTE = "com.bank.bpo.exceptions.interactionId";

    private final JsonMapper json = JsonMapper.builder().build();

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        int status = HttpStatusMapping.status(code);
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(PROBLEM_JSON);
        response.setHeader("Cache-Control", "no-store");
        if (code.retryable() && response.getHeader("Retry-After") == null) {
            response.setHeader("Retry-After", Integer.toString(HttpStatusMapping.retryAfterSeconds(code)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_PREFIX + code.code());
        body.put("title", code.title());
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code.code());
        body.put("error", code.name());
        body.put("retryable", code.retryable());
        Object interactionId = request.getAttribute(INTERACTION_ID_ATTRIBUTE);
        if (interactionId != null) {
            body.put("interactionId", interactionId.toString());
        }
        json.writeValue(response.getOutputStream(), body);
    }
}

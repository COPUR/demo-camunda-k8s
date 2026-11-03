package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The container's error page, for the few failures that happen outside the controllers (a request the servlet
 * container itself refuses). It answers with the same problem format as everything else instead of Boot's default.
 */
@RestController
public class ProblemErrorController implements ErrorController {

    private final ProblemWriter problems;

    public ProblemErrorController(ProblemWriter problems) {
        this.problems = problems;
    }

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        ErrorCode code = switch (status instanceof Integer value ? value : 500) {
            case 400 -> ErrorCode.MALFORMED_REQUEST;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
        problems.write(request, response, code, code.title());
    }
}

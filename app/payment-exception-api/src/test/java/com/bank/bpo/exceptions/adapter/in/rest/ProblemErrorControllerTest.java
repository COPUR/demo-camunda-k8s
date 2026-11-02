package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.adapter.in.rest.problem.ProblemWriter;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The container's own errors come out in the API's problem format. */
class ProblemErrorControllerTest {

    @ParameterizedTest
    @CsvSource({"400,PEX-1008", "401,PEX-2001", "403,PEX-2002", "404,PEX-5006", "405,PEX-5005", "413,PEX-5001", "415,PEX-5002",
        "500,PEX-9999", "503,PEX-9999"})
    void mapsTheContainersStatusToACataloguedProblem(int status, String code) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemErrorController(new ProblemWriter()).error(request, response);

        assertThat(response.getContentType()).isEqualTo(ProblemWriter.PROBLEM_JSON);
        assertThat(response.getContentAsString()).contains("\"code\":\"" + code + "\"");
    }

    @ParameterizedTest
    @CsvSource({"x"})
    void withoutAStatusItIsAnInternalError(String ignored) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ProblemErrorController(new ProblemWriter()).error(new MockHttpServletRequest("GET", "/error"), response);
        assertThat(response.getStatus()).isEqualTo(500);
    }
}

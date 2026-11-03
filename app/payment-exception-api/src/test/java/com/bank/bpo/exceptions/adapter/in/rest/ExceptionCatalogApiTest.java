package com.bank.bpo.exceptions.adapter.in.rest;

import static com.bank.bpo.exceptions.adapter.in.rest.PaymentExceptionApiTest.PROTOBUF;
import static com.bank.bpo.exceptions.adapter.in.rest.PaymentExceptionApiTest.problem;
import static com.bank.bpo.exceptions.adapter.in.rest.Tokens.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.bpo.exceptions.contract.v1.ErrorCategory;
import com.bank.bpo.exceptions.contract.v1.ExceptionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@ApiTest
class ExceptionCatalogApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void servesTheWholeCatalogAsACacheableVersionedDocument() throws Exception {
        mvc.perform(get("/api/v1/exception-catalog").with(reader()))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"catalog-1.0.0\""))
            .andExpect(header().string("Cache-Control", "max-age=300, private"))
            .andExpect(jsonPath("$.version").value("1.0.0"))
            .andExpect(jsonPath("$.exceptionCases", hasSize(10)))
            .andExpect(jsonPath("$.errors", hasSize(32)))
            .andExpect(jsonPath("$.exceptionCases[0].code").value("EXC-001"))
            .andExpect(jsonPath("$.errors[0].code").value("PEX-1001"));

        mvc.perform(get("/api/v1/exception-catalog").with(reader()).header("If-None-Match", "\"catalog-1.0.0\""))
            .andExpect(status().isNotModified());
    }

    @Test
    void servesTheCatalogInBinaryProtobuf() throws Exception {
        byte[] body = mvc.perform(get("/api/v1/exception-catalog").with(reader()).accept(PROTOBUF))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        ExceptionCatalog catalog = ExceptionCatalog.parseFrom(body);
        assertThat(catalog.getErrorsList()).extracting(e -> e.getCategory()).doesNotContain(ErrorCategory.ERROR_CATEGORY_UNSPECIFIED)
            .containsAll(java.util.EnumSet.complementOf(java.util.EnumSet.of(ErrorCategory.ERROR_CATEGORY_UNSPECIFIED, ErrorCategory.UNRECOGNIZED)));
    }

    @Test
    void servesOneExceptionCaseAndOneErrorByCode() throws Exception {
        mvc.perform(get("/api/v1/exception-catalog/cases/EXC-003").with(reader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("OPERATIONS_ESCALATION"))
            .andExpect(jsonPath("$.routeTo").value("SUPERVISOR"));
        mvc.perform(get("/api/v1/exception-catalog/errors/PEX-3004").with(reader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.httpStatus").value(412))
            .andExpect(jsonPath("$.category").value("ERROR_CATEGORY_PRECONDITION"));
    }

    @Test
    void anUnknownCodeIsNotFound() throws Exception {
        problem(mvc.perform(get("/api/v1/exception-catalog/errors/PEX-0000").with(reader())).andReturn(), 404, "PEX-3009");
        problem(mvc.perform(get("/api/v1/exception-catalog/cases/EXC-999").with(reader())).andReturn(), 404, "PEX-3009");
    }

    @Test
    void theCatalogIsForTokenHoldersOnly() throws Exception {
        problem(mvc.perform(get("/api/v1/exception-catalog")).andReturn(), 401, "PEX-2001");
    }
}

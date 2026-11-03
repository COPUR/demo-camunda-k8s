package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.application.port.in.BrowseExceptionCatalog;
import com.bank.bpo.exceptions.contract.v1.ErrorDefinition;
import com.bank.bpo.exceptions.contract.v1.ExceptionCaseDefinition;
import com.bank.bpo.exceptions.contract.v1.ExceptionCatalog;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exception case catalog and the error code repository. Every problem's {@code type} points here, so a client
 * can look up what an error means and what to do about it. The catalog changes only with a release: its version is
 * the entity tag, and a private cache may keep it for five minutes.
 */
@RestController
@RequestMapping(path = "/api/v1/exception-catalog", produces = {MediaType.APPLICATION_JSON_VALUE, ProtobufMedia.PROTOBUF_VALUE})
public class ExceptionCatalogController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

    private final BrowseExceptionCatalog catalog;

    public ExceptionCatalogController(BrowseExceptionCatalog catalog) {
        this.catalog = catalog;
    }

    /** Spring answers a matching If-None-Match with 304 from the entity tag set here. */
    @GetMapping
    public ResponseEntity<ExceptionCatalog> catalog() {
        return ResponseEntity.ok().eTag(etag()).cacheControl(CACHE).body(ProtoMapper.toProto(catalog.catalog()));
    }

    @GetMapping("/cases/{code}")
    public ResponseEntity<ExceptionCaseDefinition> exceptionCase(@PathVariable String code) {
        return ResponseEntity.ok().eTag(etag()).cacheControl(CACHE).body(ProtoMapper.toProto(catalog.exceptionCase(code)));
    }

    @GetMapping("/errors/{code}")
    public ResponseEntity<ErrorDefinition> error(@PathVariable String code) {
        return ResponseEntity.ok().eTag(etag()).cacheControl(CACHE).body(ProtoMapper.toProto(catalog.error(code)));
    }

    private String etag() {
        return "\"catalog-" + catalog.catalog().version() + "\"";
    }
}

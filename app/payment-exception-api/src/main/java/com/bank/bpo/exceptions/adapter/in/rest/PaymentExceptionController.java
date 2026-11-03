package com.bank.bpo.exceptions.adapter.in.rest;

import com.bank.bpo.exceptions.adapter.in.rest.filter.InteractionIdFilter;
import com.bank.bpo.exceptions.adapter.in.rest.security.JwtPrincipals;
import com.bank.bpo.exceptions.application.port.in.DecideCommand;
import com.bank.bpo.exceptions.application.port.in.DecideExceptionCase;
import com.bank.bpo.exceptions.application.port.in.DecisionResult;
import com.bank.bpo.exceptions.application.port.in.RaiseCommand;
import com.bank.bpo.exceptions.application.port.in.RaiseExceptionCase;
import com.bank.bpo.exceptions.application.port.in.RaiseResult;
import com.bank.bpo.exceptions.application.port.in.ViewExceptionCases;
import com.bank.bpo.exceptions.contract.v1.DecisionReceipt;
import com.bank.bpo.exceptions.contract.v1.ExceptionCase;
import com.bank.bpo.exceptions.contract.v1.ListExceptionCasesResponse;
import com.bank.bpo.exceptions.contract.v1.RaiseExceptionCaseRequest;
import com.bank.bpo.exceptions.contract.v1.SubmitDecisionRequest;
import com.bank.bpo.exceptions.domain.shared.PaymentId;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exception cases as resources. A case is named by its payment ({@code /payment-exceptions/{paymentId}}); its
 * entity tag is the open review task, so a decision names the view it was taken on (If-Match). Commands carry an
 * Idempotency-Key and say whether the answer is a replay. Nothing here is cached: cases change while people work.
 */
@RestController
@RequestMapping(path = "/api/v1/payment-exceptions", produces = {MediaType.APPLICATION_JSON_VALUE, ProtobufMedia.PROTOBUF_VALUE})
public class PaymentExceptionController {

    static final String BASE = "/api/v1/payment-exceptions/";

    private final RaiseExceptionCase raise;
    private final DecideExceptionCase decide;
    private final ViewExceptionCases view;
    private final JwtPrincipals principals;

    public PaymentExceptionController(RaiseExceptionCase raise, DecideExceptionCase decide, ViewExceptionCases view, JwtPrincipals principals) {
        this.raise = raise;
        this.decide = decide;
        this.view = view;
        this.principals = principals;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, ProtobufMedia.PROTOBUF_VALUE})
    public ResponseEntity<ExceptionCase> raise(@RequestBody RaiseExceptionCaseRequest body,
                                               @RequestHeader(name = Requests.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                               @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        RaiseResult result = raise.raise(new RaiseCommand(ProtoMapper.toDomain(Requests.knownFieldsOnly(body)), principals.actor(jwt),
            InteractionIdFilter.of(request), Requests.idempotencyKey(idempotencyKey)));
        com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase opened = result.exceptionCase();
        return ResponseEntity.created(URI.create(BASE + opened.paymentId()))
            .eTag(ETags.of(opened))
            .header(Requests.IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
            .cacheControl(CacheControl.noStore())
            .body(ProtoMapper.toProto(opened));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<ExceptionCase> get(@PathVariable String paymentId) {
        com.bank.bpo.exceptions.domain.casemanagement.ExceptionCase found = view.get(PaymentId.of(paymentId));
        return ResponseEntity.ok()
            .eTag(ETags.of(found))
            .cacheControl(CacheControl.noStore())
            .body(ProtoMapper.toProto(found));
    }

    /** The work queue: cases waiting for a person, oldest first, a page at a time. */
    @GetMapping
    public ResponseEntity<ListExceptionCasesResponse> list(@RequestParam(required = false) String queue,
                                                           @RequestParam(required = false) String reviewLevel,
                                                           @RequestParam(required = false) String pageSize,
                                                           @RequestParam(required = false) String pageToken) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ProtoMapper.toProto(view.inReview(Requests.caseQuery(queue, reviewLevel, pageSize, pageToken))));
    }

    /**
     * A reviewer's decision on the open review task. Accepted, not done: the process resolves the payment after it,
     * and the case resource shows the result.
     */
    @PostMapping(path = "/{paymentId}/decisions", consumes = {MediaType.APPLICATION_JSON_VALUE, ProtobufMedia.PROTOBUF_VALUE})
    public ResponseEntity<DecisionReceipt> decide(@PathVariable String paymentId, @RequestBody SubmitDecisionRequest body,
                                                  @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                  @RequestHeader(name = Requests.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                                  @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        PaymentId payment = PaymentId.of(paymentId);
        DecisionResult result = decide.decide(new DecideCommand(payment, ProtoMapper.toDomain(Requests.knownFieldsOnly(body).getDecision()),
            principals.reviewer(jwt), ETags.token(ifMatch), InteractionIdFilter.of(request), Requests.idempotencyKey(idempotencyKey)));
        return ResponseEntity.accepted()
            .location(URI.create(BASE + payment))
            .header(Requests.IDEMPOTENT_REPLAYED, Boolean.toString(result.replayed()))
            .cacheControl(CacheControl.noStore())
            .body(ProtoMapper.toProto(result.receipt()));
    }
}

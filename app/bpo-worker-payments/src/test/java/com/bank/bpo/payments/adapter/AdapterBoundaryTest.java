package com.bank.bpo.payments.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.bank.bpo.simulator.DomainSimulator;
import com.bank.bpo.workerkit.ProviderException;
import com.bank.bpo.workerkit.ProviderHttp;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The onboarding boundary rules (DevSecOps B-2, B-3, S-8) for the payments adapters: no crafted id reaches a
 * provider, no answer without its required field or with an unknown enum becomes an output, and each request
 * carries the fields its expectation names.
 */
class AdapterBoundaryTest {

    private static final DomainSimulator SIM = DomainSimulator.start();
    private static final ProviderHttp HTTP = ProviderHttp.loopback(SIM.baseUrl(), () -> "t", Duration.ofSeconds(3));
    private static final List<String> CRAFTED = List.of("..", "x/../y", "%2e%2e", "a.b", "a b", "");

    private final PsgTrSchemeStatusAdapter psg = new PsgTrSchemeStatusAdapter(HTTP);
    private final AccHoldAdapter acc = new AccHoldAdapter(HTTP);
    private final PayExceptionAdapter pay = new PayExceptionAdapter(HTTP);
    private final NtfNotificationAdapter ntf = new NtfNotificationAdapter(HTTP);

    @AfterAll
    static void stop() {
        SIM.close();
    }

    @BeforeEach
    void reset() {
        SIM.reset();
    }

    private static void contractFault(Runnable call) {
        ProviderException e = catchThrowableOfType(ProviderException.class, call::run);
        assertThat(e).isNotNull();
        assertThat(e.kind()).isEqualTo(ProviderException.Kind.CONTRACT);
    }

    @Test
    void aCraftedIdNeverReachesAProvider() {
        List<Consumer<String>> calls = List.of(
            (id) -> psg.statusOf("FAST", id),
            (id) -> acc.capture(id, "k"),
            (id) -> acc.release(id, "k"),
            (id) -> pay.record(id, "SETTLED", "k"),
            (id) -> pay.markSettled(id, "jr-1", "k"),
            (id) -> pay.markSettled("pay-1", id, "k"),
            (id) -> pay.markRejected(id, "k"),
            (id) -> ntf.notify(id, "PAYMENT_SETTLED", "k"));
        for (Consumer<String> call : calls) {
            for (String id : CRAFTED) {
                contractFault(() -> call.accept(id));
            }
            contractFault(() -> call.accept(null));
        }
        assertThat(SIM.calls()).isEmpty();
    }

    @Test
    void aCaptureAnswerWithoutAJournalRefIsAContractFault() {
        SIM.answerRaw("capture-hold", 200, "{}", 1);
        contractFault(() -> acc.capture("pay-1", "pay-1:capture"));
        SIM.answerRaw("capture-hold", 201, "{\"journalRef\":null}", 1);
        contractFault(() -> acc.capture("pay-1", "pay-1:capture"));
        SIM.answerRaw("capture-hold", 200, "{\"journalRef\":\"../x\"}", 1);
        contractFault(() -> acc.capture("pay-1", "pay-1:capture"));
    }

    @Test
    void aStatusOrOutcomeOutsideItsCodesIsAContractFault() {
        SIM.answerRaw("scheme-status", 200, "{}", 1);
        contractFault(() -> psg.statusOf("FAST", "pay-1"));
        SIM.answerRaw("scheme-status", 200, "{\"schemeStatus\":\"ACCP\"}", 1);
        contractFault(() -> psg.statusOf("FAST", "pay-1"));
        SIM.answerRaw("record-exception-decision", 200, "{\"outcome\":\"MAYBE\"}", 1);
        contractFault(() -> pay.record("pay-1", "SETTLED", "k"));
        SIM.answerRaw("notify", 201, "{}", 1);
        contractFault(() -> ntf.notify("pay-1", "PAYMENT_SETTLED", "k"));
    }

    @Test
    void enumInputsOutsideTheirCodesAreRefusedBeforeTheCall() {
        contractFault(() -> psg.statusOf("AANI", "pay-1"));
        contractFault(() -> psg.statusOf(null, "pay-1"));
        contractFault(() -> pay.record("pay-1", "ESCALATE", "k"));
        contractFault(() -> pay.record("pay-1", null, "k"));
        contractFault(() -> ntf.notify("pay-1", "ACCOUNT_OPENED", "k"));
        assertThat(SIM.calls()).isEmpty();
    }

    @Test
    void requestsCarryTheFieldsTheirExpectationNames() {
        pay.record("pay-1", "REJECTED", "pay-1:record-exception-decision");
        pay.markSettled("pay-2", "jr-7", "pay-2:mark-settled");
        ntf.notify("pay-3", "PAYMENT_REJECTED", "pay-3:notify-payment-rejected");

        assertThat(SIM.callsTo("/api/v1/payments/pay-1/exception/decision").get(0).body()).containsEntry("decision", "REJECTED");
        assertThat(SIM.callsTo("/api/v1/payments/pay-2/exception/settle").get(0).body()).containsEntry("journalRef", "jr-7");
        assertThat(SIM.callsTo("/api/v1/notifications").get(0).body()).containsEntry("subjectRef", "pay-3")
            .containsEntry("purpose", "PAYMENT_REJECTED").doesNotContainKey("paymentId");
    }
}

# bpo-worker-payments

Job workers for the **payments** family (package `com.bank.bpo.payments`, Keycloak client `svc-bpo-worker-payments`).

Live: P2 `pay-instant-exception` with `lib-await-external-status`, TR FAST profile (`docs/packages/tr-fast.md`).
Planned: P5 bulk file execution, P6 return and recall, P7 request to pay, P8 safe payment escrow (owners: Payment
Orchestration; Recurring and Bulk for P5/P7).

Same layout as `bpo-worker-onboarding`: one port per provider, one adapter per port over `ProviderHttp`, one handler
per job type, and a `JobTypeContractTest` that ties the served job types to `models/dictionary/job-types.yaml`.

| Job type | Provider | Idempotency key |
|---|---|---|
| `psg.status-inquiry` | psg-tr (rail FAST only; any other rail is an incident) | read only |
| `acc.capture-hold` (pivot) | acc | `<paymentId>:capture` |
| `acc.release-hold` | acc | `<paymentId>:release` |
| `pay.record-exception-decision` | pay | `<paymentId>:record-exception-decision` |
| `pay.mark-settled`, `pay.mark-rejected` | pay | `<paymentId>:mark-settled`, `<paymentId>:mark-rejected` |
| `ntf.notify-payment` | ntf | `<paymentId>:notify-payment-settled` or `-rejected` |

The deployment runs only in the jurisdiction cluster it serves; the TR deployment is the one configured with
`BPO_PROVIDER_PSG_TR_URL`.

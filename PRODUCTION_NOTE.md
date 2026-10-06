# Production design note

*10,000 requests/day containing personal information, against a slow and
partially documented approval system. 400 words.*

## Deployment

Two containers behind an internal load balancer on managed Kubernetes, in one
region, private subnets only. The Python service gets no ingress route — the
gateway reaches it through a cluster-internal service, as it does today. 10k/day
is roughly 0.1 rps average; the sizing problem is burst and tail latency, not
throughput, so two or three replicas each with a horizontal autoscaler on p95
latency is ample.

The one structural change: **batch intake becomes asynchronous.** `POST
/batches` persists the manifest, enqueues the items and returns `202` with a
polling URL. A synchronous batch of fifty documents against a slow approval
system will exceed any sane HTTP timeout, and today a single slow item blocks
the whole request.

## Security risks, in priority order

1. **Personal information in logs and errors.** Today's error messages already
   exclude document content, but documents pass through memory, multipart temp
   files and any queue. I would encrypt at rest, set short retention on
   uploads, and add a CI check that fails if a log statement interpolates
   document text.
2. **Tenant isolation under concurrency.** The filter is exact-match and
   correct, but it is enforced in application code with nothing behind it. I
   would add tenant scoping at the storage layer so a logic bug cannot leak
   across tenants, plus a contract test asserting no citation ever crosses
   tenant.
3. **Real authentication.** `X-Caller-Id` is a stand-in. It becomes a signed
   token with tenant and role as verified claims; the registry becomes an
   identity provider lookup.
4. **Prompt injection at larger corpus scale.** The two-barrier defence holds,
   but a corpus of thousands of passages needs the screen applied at ingestion,
   with quarantine and review, not only at query time.

## Operational risks

The slow, partially documented approval system is the main one. I would put a
circuit breaker and a bounded queue in front of it, keep the existing
zero-retry policy for non-idempotent calls, and treat its latency as a first
class SLO. Per-tenant rate limits stop one tenant starving another. Tracing
keyed by `batch_id` and `document_id` already exists in the logs and would move
to OpenTelemetry.

## What I would clarify before committing to a date

- **What the approval system actually guarantees** — idempotency, latency
  distribution, error semantics. "Partially documented" is the schedule risk.
- **Data residency and retention** for documents containing personal
  information, since that drives storage architecture.
- **Who resolves policy conflicts**, and whether a precedence rule is coming.
  Today we return `CONFLICT` by design; at volume someone must own that queue.
- **Expected batch size and peak shape**, which decides queue sizing.
- **Whether the answer must stay deterministic.** If a real model is introduced,
  the offline double, the test strategy and the injection defence all change.

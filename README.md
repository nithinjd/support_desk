# Employee policy and reimbursement triage

A Spring Boot public API backed by a private Python service. Spring Boot owns
caller identity, request validation and the public response; Python owns
document extraction, policy retrieval and answer generation.

Everything runs locally and offline. There is no model provider, no API key and
no network call outside `localhost`.

---

## Prerequisites

| Tool | Version used here | Notes |
|---|---|---|
| Java | 26 | Compiled at `--release 21` for Spring Boot 3.4 compatibility |
| Maven | 3.9.14 | A wrapper (`./mvnw`) is committed, so a local Maven is optional |
| Python | 3.13 | Needs `venv` |
| curl | any | For `run_demo.sh` |

---

## Start-up

### 1. Seed the data (once)

Writes the 12-passage policy corpus, the caller registry and the eight
synthetic request files.

```bash
python seed_data.py
```

Produces `data/policies.json`, `data/callers.json` and
`data/requests/request-01.txt` … `request-08.txt` (plus `request-02.pdf`).
It self-audits: `request-06.txt` must hash identically to `request-01.txt`,
`request-08.txt` must be exactly 0 bytes, and the PDF must carry a `%PDF-`
header.

`reportlab` is required for the PDF and is installed automatically if missing.

### 2. Python service — port 8000

```bash
cd python-service
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt     # Windows
# .venv/bin/python -m pip install -r requirements.txt       # Linux / macOS

.venv/Scripts/python -m uvicorn main:app --port 8000
```

Interactive API docs at <http://localhost:8000/docs>.

### 3. Spring Boot gateway — port 8080

```bash
cd spring-boot-service
./mvnw spring-boot:run
```

Or build and run the jar:

```bash
./mvnw clean package -DskipTests
java -jar target/gateway-1.0.0.jar
```

Port 8080 is set in `src/main/resources/application.yml`.

---

## Tests

```bash
# Python — 205 tests
cd python-service && .venv/Scripts/python -m pytest

# Java — 98 tests
cd spring-boot-service && ./mvnw test
```

The Java tests use WireMock as a controllable test double, so they need neither
a running Python service nor the seeded corpus. The Python tests do read
`data/policies.json`, and skip with a clear message if `seed_data.py` has not
been run.

---

## Demonstration

With both services running:

```bash
./run_demo.sh
```

Runs seven `/answer` questions, three rejection cases, the full eight-file
batch, and a rejected manifest. Saves every response under `demo_responses/`
and verifies thirteen business rules against the saved output.

Options: `--caller`, `--as-of`, `--out`, `--gateway`. Defaults are
`atlas-employee-01` and `as_of 2026-09-21`, as the assessment specifies.

---

## Public API

Both endpoints require an `X-Caller-Id` header and a valid `as_of` date.
Unknown or missing callers get **401**.

| Caller | Tenant | Role |
|---|---|---|
| `atlas-employee-01` | Atlas | employee |
| `atlas-contractor-01` | Atlas | contractor |
| `boreal-employee-01` | Boreal | employee |

### `POST /answer`

```bash
curl -X POST http://localhost:8080/answer \
  -H "X-Caller-Id: atlas-employee-01" \
  -H "Content-Type: application/json" \
  -d '{"question":"What is my annual certification reimbursement limit?",
       "as_of":"2026-09-21"}'
```

Returns `{status, answer, citations}` plus context fields.

| `status` | `answer` | `citations` |
|---|---|---|
| `ANSWERED` | supported answer string | supporting eligible quotations |
| `INSUFFICIENT_EVIDENCE` | `null` | `[]` |
| `CONFLICT` | `null` | quotations showing the disagreement |

Each citation carries `chunk_id` (the policy ID) and `quote` (verbatim source
text), plus `tenant`, `role`, `approval_state`, `effective_from`,
`effective_to`, `amount` and `currency` as reviewer context.

Every response also carries `review_required: true`, `decision: "NO_DECISION"`,
`payment_initiated: false` and an `issues` array.

### `POST /batches`

Synchronous `multipart/form-data`: one `metadata` JSON part and repeated
`files` parts.

```bash
curl -X POST http://localhost:8080/batches \
  -H "X-Caller-Id: atlas-employee-01" \
  -F 'metadata={"batch_id":"demo-01","as_of":"2026-09-21","documents":[
        {"document_id":"request-01","filename":"request-01.txt"}]}' \
  -F "files=@data/requests/request-01.txt"
```

Response envelope:

```json
{
  "batch_id": "demo-01",
  "caller_id": "atlas-employee-01",
  "tenant": "Atlas",
  "role": "employee",
  "as_of": "2026-09-21",
  "summary": { "total": 8, "completed": 7, "failed": 1 },
  "review_required": true,
  "decision": "NO_DECISION",
  "payment_initiated": false,
  "results": [ ... ]
}
```

#### Result shape (nested shapes documented as required)

| Field | Meaning |
|---|---|
| `document_id` | manifest ID |
| `processing_status` | `COMPLETED` or `FAILED` |
| `extracted` | `{benefit, amount, currency, reference}` — `null` for any value that is missing or unresolved |
| `field_evidence` | same four keys, each mapping to `{"quote": "..."}` for supported values and `null` otherwise |
| `policy` | the `/answer` response shape, or `null` when processing failed or the benefit could not be identified |
| `review_required` | always `true` |
| `issues` | array of `{code, category, message, detail}` |
| `duplicate_of` | earlier `document_id` with identical file bytes, else `null` |
| `error` | `{code, message}` on failure, `null` on completion |
| `sha256`, `size_bytes` | file identity, for audit |
| `injection_suspected` | whether the document tried to steer the system |
| `extraction_diagnostics` | everything else the extractor reported |

#### Issue categories

| Category | Means |
|---|---|
| `UNRESOLVED_INPUT` | the document is incomplete or self-contradictory — go back to the claimant |
| `POLICY_LIMITATION` | the corpus cannot settle the question, or answers a narrower one |
| `SECURITY` | the input or a retrieved passage tried to steer the system; recorded, never obeyed |

#### Error codes

| Code | When |
|---|---|
| `EMPTY_FILE` | the uploaded file is 0 bytes |
| `NO_EXTRACTABLE_TEXT` | bytes present but no readable text (scanned or corrupt) |
| `UPLOAD_UNREADABLE` | the bytes could not be read from the request |
| `EXTRACTION_FAILED` | extraction service timed out, refused, or returned malformed output |
| `POLICY_LOOKUP_FAILED` | policy service timed out, refused, or returned malformed output |
| `OUTPUT_WITHHELD` | the downstream response asserted a claim approval and was blocked |
| `MISSING_UPLOAD` | defensive; a missing part is normally a 400 for the whole batch |

Error messages never repeat document contents — a failed claim form may hold
personal information, and error strings end up in logs.

### Failure boundaries

| Condition | Result |
|---|---|
| Invalid metadata, duplicate manifest IDs, **missing or extra file parts** | **400**, whole batch, nothing processed |
| Empty or unreadable file, item-level dependency failure | that item `FAILED`, others continue, **200** |
| `/answer` dependency failure | **504** (timeout/unreachable) or **502** (unusable response) |
| Malformed downstream output | technical failure (502 / item `FAILED`), never `INSUFFICIENT_EVIDENCE` |

Timeouts are bounded at 5 seconds on connect and read, with **zero** retries —
verified by counting requests at a stub.

---

## Offline mode

There is no online mode to switch off. Extraction is pure regex plus `pypdf`;
retrieval is a filter over a JSON file. The same bytes always produce the same
answer, so the whole suite runs in an air-gapped CI job.

## Configuration

| Setting | Default | Where |
|---|---|---|
| Gateway port | 8080 | `application.yml` → `server.port` |
| Python service URL | `http://localhost:8000` | `application.yml` → `policy-service.base-url` |
| Downstream timeout | `5s` | `application.yml` → `policy-service.timeout` |
| Max upload size | 10 MB | `application.yml` → `spring.servlet.multipart` |
| Policy corpus path | `../data/policies.json` | `POLICIES_PATH` env var overrides |

---

## How retrieval works

A policy passage is quoted only if it survives six checks, applied in one loop
in `policy_engine.answer()`:

1. `approval_state == "Approved"` — Draft never counts
2. tenant matches exactly (case-sensitive)
3. role matches exactly
4. `effective_from <= as_of < effective_to` — **inclusive start, exclusive end**
5. the passage is about the requested benefit
6. the passage carries no prompt-injection markers

Rule 4 matters because adjacent policies share a boundary date. Inclusive on
both ends would make 2026-06-01 match both the INR 40000 and INR 25000
certification limits and report a false conflict; exclusive on both ends would
match neither. Half-open makes consecutive policies tile the calendar exactly.

## Project layout

```
data/                     seeded corpus and request files
python-service/           FastAPI service (port 8000)
  policy_engine.py          the six filter rules
  extractor.py              TXT/PDF extraction and field evidence
  benefits.py               benefit taxonomy and money parsing
  security.py               injection detection and output guards
  issues.py                 the review-reason catalogue
  tests/                    205 pytest tests
spring-boot-service/      Spring Boot gateway (port 8080)
  .../auth/                 caller registry and the X-Caller-Id filter
  .../service/              batch orchestration, hashing, output guard
  src/test/                 98 tests, WireMock doubles
seed_data.py              builds data/
run_demo.sh               end-to-end demonstration
DECISION_NOTE.md          design choice, rejected alternative, limitations
PRODUCTION_NOTE.md        scaling to 10k requests/day
REQUEST_LIFECYCLE.pdf     step-by-step trace of one request
```

"""
main.py - FastAPI surface for the Spring Boot orchestrator.

Internal endpoints (not meant for public exposure):

    POST /internal/answer           grounded policy answer
    POST /internal/extract          multipart file upload  -> extracted fields
    POST /internal/extract/base64   JSON base64 payload    -> extracted fields

Support endpoints:

    GET  /health                    liveness + corpus size
    GET  /internal/policies         the loaded corpus, for audit
    POST /internal/reload           re-read policies.json without a restart

Run:
    uvicorn main:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import base64
import binascii
import datetime as dt
from contextlib import asynccontextmanager
from typing import Any, Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

import policy_engine
from extractor import extract

MAX_UPLOAD_BYTES = 10 * 1024 * 1024  # 10 MB


# ---------------------------------------------------------------------------
# Schemas
# ---------------------------------------------------------------------------

class AnswerRequest(BaseModel):
    tenant: str = Field(..., examples=["Atlas"])
    role: str = Field(..., examples=["employee"])
    as_of_date: dt.date = Field(..., examples=["2026-09-01"])
    requested_benefit: Optional[str] = Field(
        None,
        description="Enum spelling (HOME_OFFICE), human spelling (home office), "
                    "or the raw request text - all are accepted.",
        examples=["CERTIFICATION"],
    )

    @field_validator("tenant", "role")
    @classmethod
    def _non_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("must not be blank")
        return value.strip()


class Citation(BaseModel):
    """chunk_id and quote are the contract; the rest is reviewer context."""

    chunk_id: str = Field(..., description="The policy ID")
    quote: str = Field(..., description="Verbatim source text of the passage")
    tenant: str
    role: str
    approval_state: str
    effective_from: Optional[str] = None
    effective_to: Optional[str] = None
    amount: Optional[float] = None
    currency: Optional[str] = None


class Issue(BaseModel):
    code: str
    category: str = Field(...,
        description="UNRESOLVED_INPUT | POLICY_LIMITATION | SECURITY")
    message: str
    detail: Optional[str] = None


class AnswerResponse(BaseModel):
    status: str = Field(..., description="ANSWERED | CONFLICT | INSUFFICIENT_EVIDENCE")
    tenant: str
    role: str
    as_of: str
    requested_benefit: Optional[str] = None
    requested_benefit_raw: Optional[str] = None
    answer: Optional[str] = None
    amount: Optional[float] = None
    currency: Optional[str] = None
    citations: list[Citation] = []
    reason: Optional[str] = None
    conflicting_amounts: Optional[list[float]] = None
    diagnostics: Optional[dict[str, Any]] = None

    # Invariants - never vary, on any path.
    issues: list[Issue] = []
    review_required: bool = Field(True,
        description="Always true. Every answer requires human review.")
    decision: str = Field("NO_DECISION",
        description="Always NO_DECISION. This service never approves or denies a claim.")
    payment_initiated: bool = Field(False,
        description="Always false. This service never initiates payment.")
    decision_note: Optional[str] = None


class ExtractBase64Request(BaseModel):
    filename: Optional[str] = None
    content_type: Optional[str] = None
    content_base64: str = Field(
        ...,
        description="Base64 of the raw file bytes. An empty string is valid and "
                    "models a 0-byte request file.",
    )


class ExtractResponse(BaseModel):
    benefit: Optional[str] = None
    amount: Optional[float] = None
    currency: Optional[str] = None
    reference: Optional[str] = None
    source_kind: Optional[str] = None
    filename: Optional[str] = None
    byte_count: int = 0
    char_count: int = 0
    text_extracted: bool = False
    amount_candidates: list[float] = []
    amount_conflict: bool = False
    manager_approval: str = Field("UNKNOWN",
        description="NOT_OBTAINED | CLAIMED | UNKNOWN. A claim is never treated as verified.")
    already_booked: bool = False
    injection_suspected: bool = False
    injection_signals: list[str] = []
    field_evidence: dict[str, Any] = Field(
        default_factory=dict,
        description="Each supported value mapped to the verbatim source text "
                    "proving it; null for values that could not be determined.",
    )
    issues: list[Issue] = []
    notes: list[str] = []


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Fail loudly at boot rather than on the first request if the corpus is
    # missing - a misconfigured POLICIES_PATH should not look like a 500 later.
    store = policy_engine.get_store()
    print(f"Loaded {len(store.policies)} policies from {store.path}")
    print(f"  tenants: {', '.join(store.tenants())}")
    print(f"  roles:   {', '.join(store.roles())}")
    yield


app = FastAPI(
    title="Marlabs Policy & Extraction Service",
    description="Deterministic, offline policy retrieval and document extraction.",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health")
def health() -> dict:
    store = policy_engine.get_store()
    return {
        "status": "UP",
        "policies_loaded": len(store.policies),
        "corpus_path": str(store.path),
        "tenants": store.tenants(),
        "roles": store.roles(),
    }


@app.post("/internal/answer", response_model=AnswerResponse)
def internal_answer(payload: AnswerRequest) -> AnswerResponse:
    """Answer a benefit question strictly from the approved, in-force corpus.

    ``tenant`` and ``role`` are the caller's *verified* identity, resolved by
    the Spring Boot layer from its caller registry. This service treats them as
    authoritative and ignores any identity claimed inside document text.
    """
    try:
        result = policy_engine.answer(
            tenant=payload.tenant,
            role=payload.role,
            as_of_date=payload.as_of_date,
            requested_benefit=payload.requested_benefit,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return AnswerResponse(**result)


@app.post("/internal/extract", response_model=ExtractResponse)
async def internal_extract(
    file: UploadFile = File(..., description="TXT or PDF bytes"),
    filename: Optional[str] = Form(None, description="Overrides the upload's name"),
) -> ExtractResponse:
    """Extract benefit, amount, currency and reference from an uploaded file.

    A 0-byte upload is valid input and returns null fields with a note, not an
    error.
    """
    data = await file.read()
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"file is {len(data)} bytes, limit is {MAX_UPLOAD_BYTES}",
        )
    try:
        result = extract(
            data,
            filename=filename or file.filename,
            content_type=file.content_type,
        )
    except Exception as exc:
        raise HTTPException(
            status_code=422,
            detail=f"could not parse {file.filename!r}: {type(exc).__name__}: {exc}",
        ) from exc
    return ExtractResponse(**result.to_dict())


@app.post("/internal/extract/base64", response_model=ExtractResponse)
def internal_extract_base64(payload: ExtractBase64Request) -> ExtractResponse:
    """Same extraction, for callers that would rather post JSON than multipart."""
    try:
        data = base64.b64decode(payload.content_base64 or "", validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status_code=422, detail=f"invalid base64: {exc}") from exc

    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"payload is {len(data)} bytes, limit is {MAX_UPLOAD_BYTES}",
        )
    try:
        result = extract(
            data, filename=payload.filename, content_type=payload.content_type
        )
    except Exception as exc:
        raise HTTPException(
            status_code=422,
            detail=f"could not parse payload: {type(exc).__name__}: {exc}",
        ) from exc
    return ExtractResponse(**result.to_dict())


@app.get("/internal/policies")
def internal_policies() -> dict:
    """The loaded corpus with derived tags, for audit and debugging."""
    store = policy_engine.get_store()
    return {
        "count": len(store.policies),
        "corpus_path": str(store.path),
        "interval_semantics": "[effective_from, effective_to) - inclusive start, exclusive end",
        "withheld_for_injection": [p.chunk_id for p in store.withheld_for_injection()],
        "policies": [
            {
                **p.to_citation(),
                "benefits": [b.value for b in p.benefits],
                "safe_to_cite": p.safe_to_cite,
                "injection_signals": list(p.injection_signals),
                "requires_manager_approval": p.requires_manager_approval,
            }
            for p in store.policies
        ],
    }


@app.post("/internal/reload")
def internal_reload() -> dict:
    """Re-read policies.json in place, e.g. after re-running seed_data.py."""
    try:
        store = policy_engine.reload_store()
    except (FileNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {"reloaded": True, "policies_loaded": len(store.policies)}


@app.exception_handler(ValueError)
def value_error_handler(request, exc: ValueError) -> JSONResponse:
    return JSONResponse(status_code=422, content={"detail": str(exc)})


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

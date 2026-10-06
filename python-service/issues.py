"""
issues.py - the catalogue of things a human reviewer needs told.

An issue is anything that stops this system's output from being a complete,
self-sufficient answer. Three categories, because they call for different
actions:

``UNRESOLVED_INPUT``
    The submitted document is incomplete or self-contradictory. Someone must go
    back to the claimant. Example: request-03 states INR 22000 on the invoice
    and INR 28000 on the form with neither corrected; request-07 has no invoice
    amount at all and says manager approval was never obtained.

``POLICY_LIMITATION``
    The corpus cannot settle the question, or can only answer a narrower
    question than the one asked. The headline case is that an annual limit is
    not a balance: knowing the cap is INR 25000 says nothing about how much of
    it this employee has already spent, nor whether this particular expense is
    an eligible one.

``SECURITY``
    The input or a retrieved passage tried to steer the system. Recorded so the
    attempt is visible in the audit trail, never acted upon.

Every issue carries a stable ``code`` for machines and a ``message`` for people.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
from typing import Optional


class IssueCategory(str, Enum):
    UNRESOLVED_INPUT = "UNRESOLVED_INPUT"
    POLICY_LIMITATION = "POLICY_LIMITATION"
    SECURITY = "SECURITY"


@dataclass(frozen=True)
class Issue:
    code: str
    category: str
    message: str
    detail: Optional[str] = None

    def to_dict(self) -> dict:
        return asdict(self)


def _issue(code: str, category: IssueCategory, message: str,
           detail: Optional[str] = None) -> Issue:
    return Issue(code=code, category=category.value, message=message, detail=detail)


# ---------------------------------------------------------------------------
# Unresolved input
# ---------------------------------------------------------------------------

def no_extractable_content(byte_count: int) -> Issue:
    return _issue(
        "NO_EXTRACTABLE_CONTENT",
        IssueCategory.UNRESOLVED_INPUT,
        "The submitted file contains no readable text, so no request details "
        "could be determined.",
        f"{byte_count} bytes received.",
    )


def missing_invoice_amount() -> Issue:
    return _issue(
        "MISSING_INVOICE_AMOUNT",
        IssueCategory.UNRESOLVED_INPUT,
        "No invoice or claim amount is stated in the request, so the amount "
        "being claimed is unknown.",
    )


def conflicting_invoice_amounts(candidates: list[float], currency: Optional[str]) -> Issue:
    rendered = ", ".join(f"{currency or ''} {c:g}".strip() for c in candidates)
    return _issue(
        "CONFLICTING_INVOICE_AMOUNTS",
        IssueCategory.UNRESOLVED_INPUT,
        "The request states more than one claim amount and none has been "
        "corrected, so the amount claimed cannot be determined.",
        f"Amounts found: {rendered}.",
    )


def missing_manager_approval() -> Issue:
    return _issue(
        "MISSING_MANAGER_APPROVAL",
        IssueCategory.UNRESOLVED_INPUT,
        "The request states that manager approval was not obtained, while the "
        "applicable policy requires it.",
    )


def manager_approval_not_evidenced() -> Issue:
    return _issue(
        "MANAGER_APPROVAL_NOT_EVIDENCED",
        IssueCategory.UNRESOLVED_INPUT,
        "The applicable policy requires manager approval, and the request "
        "provides no evidence that it was obtained.",
    )


def booked_before_approval() -> Issue:
    return _issue(
        "BOOKED_BEFORE_APPROVAL",
        IssueCategory.UNRESOLVED_INPUT,
        "The request indicates the booking was already made, so any approval "
        "required beforehand cannot now be obtained in the required order.",
    )


def missing_reference() -> Issue:
    return _issue(
        "MISSING_REFERENCE",
        IssueCategory.UNRESOLVED_INPUT,
        "No reference code was found in the request, so it cannot be matched "
        "to a case record.",
    )


def unidentified_benefit() -> Issue:
    return _issue(
        "UNIDENTIFIED_BENEFIT",
        IssueCategory.UNRESOLVED_INPUT,
        "The request does not identify which benefit it concerns, so no "
        "policy could be selected.",
    )


# ---------------------------------------------------------------------------
# Policy limitations
# ---------------------------------------------------------------------------

def limit_is_not_balance() -> Issue:
    return _issue(
        "LIMIT_NOT_BALANCE_OR_ELIGIBILITY",
        IssueCategory.POLICY_LIMITATION,
        "An annual policy limit does not establish remaining balance or "
        "expense eligibility.",
        "The figure quoted is the annual cap only. Year-to-date usage, the "
        "remaining balance, and whether this particular expense qualifies are "
        "all outside the policy corpus and must be confirmed separately.",
    )


def unresolved_policy_conflict(amounts: list[float], chunk_ids: list[str]) -> Issue:
    return _issue(
        "UNRESOLVED_POLICY_CONFLICT",
        IssueCategory.POLICY_LIMITATION,
        "Two or more policies in force for this caller state different "
        "amounts, so no single limit can be quoted.",
        "Conflicting amounts "
        + ", ".join(f"{a:g}" for a in amounts)
        + " from " + ", ".join(chunk_ids)
        + ". A human must decide which passage governs.",
    )


def no_applicable_policy(benefit: Optional[str], tenant: str, role: str, as_of: str) -> Issue:
    return _issue(
        "NO_APPLICABLE_POLICY",
        IssueCategory.POLICY_LIMITATION,
        "No approved policy covering this request is in force for the caller "
        "on the evaluation date.",
        f"benefit={benefit or 'unidentified'}, tenant={tenant}, role={role}, "
        f"as_of={as_of}.",
    )


def manager_approval_required() -> Issue:
    return _issue(
        "MANAGER_APPROVAL_REQUIRED",
        IssueCategory.POLICY_LIMITATION,
        "The applicable policy requires manager approval before the expense is "
        "committed; this service cannot verify whether that approval exists.",
    )


def qualitative_policy_only() -> Issue:
    return _issue(
        "QUALITATIVE_POLICY_ONLY",
        IssueCategory.POLICY_LIMITATION,
        "The applicable policy states a condition rather than a monetary "
        "limit, so no amount can be quoted for this request.",
    )


def eligibility_not_determined() -> Issue:
    return _issue(
        "EXPENSE_ELIGIBILITY_NOT_DETERMINED",
        IssueCategory.POLICY_LIMITATION,
        "Whether the specific expense claimed is eligible under the quoted "
        "policy has not been determined and requires human assessment.",
    )


# ---------------------------------------------------------------------------
# Security
# ---------------------------------------------------------------------------

def prompt_injection_in_request(signals: list[str]) -> Issue:
    return _issue(
        "PROMPT_INJECTION_IN_REQUEST_IGNORED",
        IssueCategory.SECURITY,
        "The submitted document contains instructions aimed at this system. "
        "They were recorded and ignored; caller identity remains as "
        "authenticated and no approval or payment was performed.",
        "Signals: " + ", ".join(signals) + ".",
    )


def prompt_injection_in_corpus(chunk_ids: list[str], signals: list[str]) -> Issue:
    return _issue(
        "PROMPT_INJECTION_IN_CORPUS_WITHHELD",
        IssueCategory.SECURITY,
        "A retrieved policy passage contains instructions aimed at this "
        "system. It was withheld from the citations and did not influence the "
        "answer.",
        "Withheld: " + ", ".join(chunk_ids) + ". Signals: " + ", ".join(signals) + ".",
    )


def tenant_claim_ignored(claimed: Optional[str], authenticated: str) -> Issue:
    return _issue(
        "TENANT_CLAIM_IGNORED",
        IssueCategory.SECURITY,
        "The document asserted a tenant or role for the caller. Identity is "
        "taken only from the authenticated caller id, so the assertion had no "
        "effect.",
        (f"Claimed: {claimed}. " if claimed else "")
        + f"Answered as: {authenticated}.",
    )


def dedupe(found: list[Issue]) -> list[Issue]:
    """Drop repeats by code, preserving first-seen order."""
    seen: set[str] = set()
    ordered: list[Issue] = []
    for issue in found:
        if issue.code not in seen:
            seen.add(issue.code)
            ordered.append(issue)
    return ordered

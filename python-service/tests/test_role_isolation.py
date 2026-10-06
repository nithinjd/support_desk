"""
Tenant and role isolation.

Both are exact-match filters with no wildcard and no fallback. The corpus is
built so that the same question has a different correct answer per principal -
certification is INR 25000 for an Atlas employee, INR 10000 for an Atlas
contractor, and INR 80000 for a Boreal employee - so a leak shows up as a
wrong number rather than merely an extra citation.
"""

from __future__ import annotations

import datetime as dt

import pytest

import policy_engine
from policy_engine import STATUS_ANSWERED, STATUS_CONFLICT, STATUS_INSUFFICIENT

pytestmark = pytest.mark.corpus

#: The three registered callers, as the gateway resolves them.
PRINCIPALS = [
    ("Atlas", "employee"),
    ("Atlas", "contractor"),
    ("Boreal", "employee"),
]


def _date(value: str) -> dt.date:
    return dt.datetime.strptime(value, "%Y-%m-%d").date()


# ---------------------------------------------------------------------------
# The same question, three different correct answers
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("tenant,role,expected_amount,expected_chunk", [
    ("Atlas", "employee", 25000.0, "atlas-cert-current"),
    ("Atlas", "contractor", 10000.0, "atlas-cert-contractor"),
    ("Boreal", "employee", 80000.0, "boreal-cert-current"),
])
def test_certification_limit_is_per_principal(store, as_of, tenant, role,
                                              expected_amount, expected_chunk):
    result = policy_engine.answer(tenant, role, as_of, "CERTIFICATION", store=store)

    assert result["status"] == STATUS_ANSWERED
    assert result["amount"] == expected_amount
    assert [c["chunk_id"] for c in result["citations"]] == [expected_chunk]


@pytest.mark.parametrize("tenant,role", PRINCIPALS)
def test_every_citation_belongs_to_the_asking_principal(store, as_of, tenant, role):
    """The invariant behind isolation: no citation may name another principal."""
    for benefit in ("CERTIFICATION", "HOME_OFFICE", "TRAVEL", "TRAINING", "WELLNESS"):
        result = policy_engine.answer(tenant, role, as_of, benefit, store=store)
        for citation in result["citations"]:
            assert citation["tenant"] == tenant, \
                f"{benefit}: leaked {citation['chunk_id']} from {citation['tenant']}"
            assert citation["role"] == role, \
                f"{benefit}: leaked {citation['chunk_id']} for role {citation['role']}"


# ---------------------------------------------------------------------------
# Role isolation within one tenant
# ---------------------------------------------------------------------------

def test_contractor_cannot_see_employee_certification_policy(store, as_of):
    result = policy_engine.answer("Atlas", "contractor", as_of, "CERTIFICATION",
                                  store=store)
    cited = [c["chunk_id"] for c in result["citations"]]
    assert "atlas-cert-current" not in cited
    assert result["amount"] != 25000.0


def test_employee_cannot_see_contractor_certification_policy(store, as_of):
    result = policy_engine.answer("Atlas", "employee", as_of, "CERTIFICATION", store=store)
    cited = [c["chunk_id"] for c in result["citations"]]
    assert "atlas-cert-contractor" not in cited
    assert result["amount"] != 10000.0


def test_contractor_has_no_home_office_policy_at_all(store, as_of):
    """Home-office exists only for Atlas employees, so a contractor gets nothing."""
    result = policy_engine.answer("Atlas", "contractor", as_of, "HOME_OFFICE", store=store)
    assert result["status"] == STATUS_INSUFFICIENT
    assert result["citations"] == []


def test_employee_home_office_conflict_does_not_reach_contractor(store, as_of):
    """The 12000/15000 contradiction is an employee problem only."""
    employee = policy_engine.answer("Atlas", "employee", as_of, "HOME_OFFICE", store=store)
    contractor = policy_engine.answer("Atlas", "contractor", as_of, "HOME_OFFICE",
                                      store=store)

    assert employee["status"] == STATUS_CONFLICT
    assert contractor["status"] == STATUS_INSUFFICIENT


# ---------------------------------------------------------------------------
# Tenant isolation
# ---------------------------------------------------------------------------

def test_atlas_never_sees_boreal_policies(store, as_of):
    for benefit in ("CERTIFICATION", "HOME_OFFICE"):
        result = policy_engine.answer("Atlas", "employee", as_of, benefit, store=store)
        cited = " ".join(c["chunk_id"] for c in result["citations"])
        assert "boreal" not in cited


def test_boreal_never_sees_atlas_policies(store, as_of):
    for benefit in ("CERTIFICATION", "HOME_OFFICE"):
        result = policy_engine.answer("Boreal", "employee", as_of, benefit, store=store)
        cited = " ".join(c["chunk_id"] for c in result["citations"])
        assert "atlas" not in cited


def test_boreal_home_office_is_unambiguous(store, as_of):
    """Boreal has one home-office policy, so it answers where Atlas conflicts."""
    result = policy_engine.answer("Boreal", "employee", as_of, "HOME_OFFICE", store=store)
    assert result["status"] == STATUS_ANSWERED
    assert result["amount"] == 30000.0


def test_boreal_employee_sees_only_its_two_benefits(store, as_of):
    """Atlas-only benefits are absent for Boreal, not silently substituted."""
    for benefit in ("TRAVEL", "TRAINING", "WELLNESS"):
        result = policy_engine.answer("Boreal", "employee", as_of, benefit, store=store)
        assert result["status"] == STATUS_INSUFFICIENT, benefit


# ---------------------------------------------------------------------------
# Unknown principals do not degrade into a broad search
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("tenant,role", [
    ("Cobalt", "employee"),      # unknown tenant
    ("Atlas", "admin"),          # unknown role
    ("atlas", "employee"),       # right name, wrong case
    ("ATLAS", "EMPLOYEE"),
    ("", "employee"),
    ("Atlas", ""),
])
def test_unknown_principal_yields_no_evidence(store, as_of, tenant, role):
    result = policy_engine.answer(tenant, role, as_of, "CERTIFICATION", store=store)
    assert result["status"] == STATUS_INSUFFICIENT
    assert result["citations"] == []
    assert result["amount"] is None


def test_tenant_match_is_case_sensitive(store, as_of):
    """'atlas' is not 'Atlas'. Loose matching here would be a tenant leak."""
    correct = policy_engine.answer("Atlas", "employee", as_of, "CERTIFICATION", store=store)
    lowered = policy_engine.answer("atlas", "employee", as_of, "CERTIFICATION", store=store)

    assert correct["status"] == STATUS_ANSWERED
    assert lowered["status"] == STATUS_INSUFFICIENT


def test_unknown_tenant_reason_names_the_tenant(store, as_of):
    result = policy_engine.answer("Cobalt", "employee", as_of, "CERTIFICATION", store=store)
    assert "Cobalt" in result["reason"]


# ---------------------------------------------------------------------------
# Isolation holds across the whole principal x benefit matrix
# ---------------------------------------------------------------------------

#: The complete expected visibility map on 2026-09-01.
EXPECTED_VISIBILITY = {
    ("Atlas", "employee"): {
        "atlas-cert-current",
        "atlas-home-office-a",
        "atlas-home-office-b",
        "atlas-travel-current",
        "atlas-training-current",
    },
    ("Atlas", "contractor"): {"atlas-cert-contractor"},
    ("Boreal", "employee"): {"boreal-cert-current", "boreal-home-office-current"},
}


@pytest.mark.parametrize("tenant,role", PRINCIPALS)
def test_visible_set_is_exactly_as_expected(store, as_of, tenant, role):
    """A full snapshot. Any widening or narrowing of visibility fails here.

    atlas-injection-example is absent by design: it is Approved, in-tenant,
    in-role and in force, and is excluded only by the injection screen.
    """
    visible = {
        p.chunk_id for p in store.policies
        if p.is_approved() and p.tenant == tenant and p.role == role
        and p.is_effective(as_of) and p.safe_to_cite
    }
    assert visible == EXPECTED_VISIBILITY[(tenant, role)]


def test_no_two_principals_share_a_visible_policy(store, as_of):
    """Disjointness: this corpus has no cross-principal passage."""
    sets = {}
    for tenant, role in PRINCIPALS:
        sets[(tenant, role)] = {
            p.chunk_id for p in store.policies
            if p.is_approved() and p.tenant == tenant and p.role == role
            and p.is_effective(as_of)
        }

    principals = list(sets)
    for i, first in enumerate(principals):
        for second in principals[i + 1:]:
            overlap = sets[first] & sets[second]
            assert overlap == set(), f"{first} and {second} share {overlap}"


def test_role_filter_is_exact_not_substring(make_store, as_of):
    """'employee' must not match a role of 'employee-contractor'."""
    store = make_store([
        {"chunk_id": "odd-role", "role": "employee"},
    ])
    assert policy_engine.answer("Atlas", "employee", as_of, "CERTIFICATION",
                                store=store)["status"] == STATUS_ANSWERED
    assert policy_engine.answer("Atlas", "employe", as_of, "CERTIFICATION",
                                store=store)["status"] == STATUS_INSUFFICIENT
    assert policy_engine.answer("Atlas", "employees", as_of, "CERTIFICATION",
                                store=store)["status"] == STATUS_INSUFFICIENT

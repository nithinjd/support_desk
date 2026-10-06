"""
Date-boundary interval math: ``[effective_from, effective_to)``.

The start is inclusive and the end is exclusive, so on the day one policy's
``effective_to`` equals another's ``effective_from`` exactly one of them is in
force. That single day is where an off-by-one would hide, and the corpus is
built so that 2026-06-01 is exactly such a day: the INR 40000 certification
limit ends on it and the INR 25000 limit starts on it.
"""

from __future__ import annotations

import datetime as dt

import pytest

import policy_engine
from policy_engine import STATUS_ANSWERED, STATUS_INSUFFICIENT, Policy


def _date(value: str) -> dt.date:
    return dt.datetime.strptime(value, "%Y-%m-%d").date()


pytestmark = pytest.mark.corpus


# ---------------------------------------------------------------------------
# The half-open rule in isolation
# ---------------------------------------------------------------------------

def _policy(effective_from: str | None, effective_to: str | None) -> Policy:
    return Policy.from_record({
        "chunk_id": "under-test",
        "tenant": "Atlas",
        "role": "employee",
        "approval_state": "Approved",
        "effective_from": effective_from,
        "effective_to": effective_to,
        "text": "Limit is INR 100.",
    })


def test_effective_from_is_inclusive():
    policy = _policy("2026-06-01", "2027-01-01")
    assert policy.is_effective(_date("2026-06-01")) is True


def test_day_before_effective_from_is_excluded():
    policy = _policy("2026-06-01", "2027-01-01")
    assert policy.is_effective(_date("2026-05-31")) is False


def test_effective_to_is_exclusive():
    """The end date itself is NOT in force - the defining asymmetry."""
    policy = _policy("2026-06-01", "2027-01-01")
    assert policy.is_effective(_date("2027-01-01")) is False


def test_day_before_effective_to_is_included():
    policy = _policy("2026-06-01", "2027-01-01")
    assert policy.is_effective(_date("2026-12-31")) is True


def test_single_day_interval_covers_exactly_one_day():
    policy = _policy("2026-06-01", "2026-06-02")
    assert policy.is_effective(_date("2026-05-31")) is False
    assert policy.is_effective(_date("2026-06-01")) is True
    assert policy.is_effective(_date("2026-06-02")) is False


@pytest.mark.parametrize("as_of", ["2020-01-01", "2026-06-01", "2099-12-31"])
def test_null_bounds_mean_unbounded(as_of):
    assert _policy(None, None).is_effective(_date(as_of)) is True


def test_null_effective_to_is_open_ended():
    policy = _policy("2026-06-01", None)
    assert policy.is_effective(_date("2026-05-31")) is False
    assert policy.is_effective(_date("2026-06-01")) is True
    assert policy.is_effective(_date("2099-01-01")) is True


def test_null_effective_from_has_no_lower_bound():
    policy = _policy(None, "2026-06-01")
    assert policy.is_effective(_date("1999-01-01")) is True
    assert policy.is_effective(_date("2026-05-31")) is True
    assert policy.is_effective(_date("2026-06-01")) is False


@pytest.mark.parametrize("lo,hi", [
    ("2026-06-01", "2026-06-01"),   # zero-width
    ("2027-01-01", "2026-01-01"),   # inverted
])
def test_empty_interval_is_rejected_at_load(lo, hi):
    """A zero-width or inverted interval can never match, so it is a data bug."""
    with pytest.raises(ValueError, match="empty interval"):
        _policy(lo, hi)


# ---------------------------------------------------------------------------
# Adjacent intervals tile the timeline without gap or overlap
# ---------------------------------------------------------------------------

#: (date, the single certification chunk expected in force on it)
CERTIFICATION_TIMELINE = [
    ("2025-12-31", None),                       # before the corpus begins
    ("2026-01-01", "atlas-cert-historical"),    # inclusive start
    ("2026-05-31", "atlas-cert-historical"),    # last day of 40000
    ("2026-06-01", "atlas-cert-current"),       # exclusive end flips to 25000
    ("2026-12-31", "atlas-cert-current"),       # last day of 25000
    ("2027-01-01", "atlas-cert-future"),        # flips to 35000
    ("2027-12-31", "atlas-cert-future"),        # last day of 35000
    ("2028-01-01", None),                       # after the corpus ends
]


@pytest.mark.parametrize("day,expected_chunk", CERTIFICATION_TIMELINE)
def test_certification_timeline_has_exactly_one_policy_per_day(store, day, expected_chunk):
    result = policy_engine.answer("Atlas", "employee", _date(day), "CERTIFICATION",
                                  store=store)
    cited = [c["chunk_id"] for c in result["citations"]]

    if expected_chunk is None:
        assert result["status"] == STATUS_INSUFFICIENT
        assert cited == []
    else:
        assert result["status"] == STATUS_ANSWERED
        assert cited == [expected_chunk]


@pytest.mark.parametrize("day,expected_amount", [
    ("2026-05-31", 40000.0),
    ("2026-06-01", 25000.0),
    ("2027-01-01", 35000.0),
])
def test_amount_changes_on_the_boundary_day(store, day, expected_amount):
    result = policy_engine.answer("Atlas", "employee", _date(day), "CERTIFICATION",
                                  store=store)
    assert result["amount"] == expected_amount


def test_adjacent_policies_never_both_apply(store):
    """No date in the whole span yields two certification amounts."""
    historical = next(p for p in store.policies if p.chunk_id == "atlas-cert-historical")
    current = next(p for p in store.policies if p.chunk_id == "atlas-cert-current")
    future = next(p for p in store.policies if p.chunk_id == "atlas-cert-future")

    day = _date("2025-12-01")
    end = _date("2028-06-01")
    while day < end:
        in_force = [p for p in (historical, current, future) if p.is_effective(day)]
        assert len(in_force) <= 1, f"{day} is covered by {[p.chunk_id for p in in_force]}"
        day += dt.timedelta(days=1)


def test_no_gap_between_adjacent_intervals(store):
    """Every day from 2026-01-01 to 2027-12-31 has a certification policy."""
    day = _date("2026-01-01")
    end = _date("2028-01-01")
    uncovered = []
    while day < end:
        result = policy_engine.answer("Atlas", "employee", day, "CERTIFICATION",
                                      store=store)
        if result["status"] != STATUS_ANSWERED:
            uncovered.append(day.isoformat())
        day += dt.timedelta(days=1)
    assert uncovered == []


# ---------------------------------------------------------------------------
# Approval state is independent of the interval
# ---------------------------------------------------------------------------

def test_draft_is_excluded_even_while_in_force(store, as_of):
    """atlas-cert-draft (INR 99000) shares the in-force window exactly."""
    draft = next(p for p in store.policies if p.chunk_id == "atlas-cert-draft")
    assert draft.is_effective(as_of) is True       # the interval does match
    assert draft.is_approved() is False            # but the state does not

    result = policy_engine.answer("Atlas", "employee", as_of, "CERTIFICATION", store=store)
    assert "atlas-cert-draft" not in [c["chunk_id"] for c in result["citations"]]
    assert result["amount"] != 99000.0


def test_approved_but_expired_is_excluded(make_store):
    store = make_store([
        {"chunk_id": "expired", "effective_from": "2025-01-01", "effective_to": "2026-01-01"},
    ])
    result = policy_engine.answer("Atlas", "employee", _date("2026-06-01"),
                                  "CERTIFICATION", store=store)
    assert result["status"] == STATUS_INSUFFICIENT


def test_approved_but_future_is_excluded(make_store):
    store = make_store([
        {"chunk_id": "future", "effective_from": "2030-01-01", "effective_to": None},
    ])
    result = policy_engine.answer("Atlas", "employee", _date("2026-06-01"),
                                  "CERTIFICATION", store=store)
    assert result["status"] == STATUS_INSUFFICIENT


# ---------------------------------------------------------------------------
# Date coercion
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("value,expected", [
    ("2026-06-01", _date("2026-06-01")),
    (_date("2026-06-01"), _date("2026-06-01")),
    (dt.datetime(2026, 6, 1, 23, 59), _date("2026-06-01")),
    (None, None),
    ("", None),
])
def test_parse_date_accepts_strings_dates_and_datetimes(value, expected):
    assert policy_engine.parse_date(value) == expected


def test_answer_requires_an_as_of_date(store):
    with pytest.raises(ValueError, match="as_of_date is required"):
        policy_engine.answer("Atlas", "employee", None, "CERTIFICATION", store=store)


def test_datetime_is_truncated_not_rounded(store):
    """23:59 on the last in-force day is still that day, not the next."""
    late = dt.datetime(2026, 5, 31, 23, 59, 59)
    result = policy_engine.answer("Atlas", "employee", late, "CERTIFICATION", store=store)
    assert result["amount"] == 40000.0

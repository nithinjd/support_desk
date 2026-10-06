"""
Shared fixtures.

The real seeded corpus is used wherever a test is about real behaviour, and
purpose-built corpora are written to ``tmp_path`` wherever a test needs an edge
case the real data does not contain (open-ended intervals, a single contested
benefit, and so on). Building those through :class:`PolicyStore` rather than by
constructing ``Policy`` objects directly means the loader and its validation
are exercised too.
"""

from __future__ import annotations

import datetime as dt
import json
from pathlib import Path

import pytest

from policy_engine import PolicyStore

SERVICE_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = SERVICE_DIR.parent


def _date(value: str) -> dt.date:
    return dt.datetime.strptime(value, "%Y-%m-%d").date()


# ---------------------------------------------------------------------------
# Paths to the seeded corpus
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def data_dir() -> Path:
    return REPO_ROOT / "data"


@pytest.fixture(scope="session")
def policies_path(data_dir: Path) -> Path:
    path = data_dir / "policies.json"
    if not path.exists():
        pytest.skip(f"{path} not found - run seed_data.py first")
    return path


@pytest.fixture(scope="session")
def requests_dir(data_dir: Path) -> Path:
    path = data_dir / "requests"
    if not path.exists():
        pytest.skip(f"{path} not found - run seed_data.py first")
    return path


@pytest.fixture(scope="session")
def store(policies_path: Path) -> PolicyStore:
    """The real 12-passage corpus."""
    return PolicyStore(policies_path).load()


# ---------------------------------------------------------------------------
# Synthetic corpora
# ---------------------------------------------------------------------------

@pytest.fixture
def make_store(tmp_path: Path):
    """Builds a PolicyStore from inline records, via a real JSON file.

    Records may omit any field except ``chunk_id``; the defaults produce an
    Approved Atlas/employee certification passage so a test only states the
    part it cares about.
    """
    counter = {"n": 0}

    def _make(records: list[dict]) -> PolicyStore:
        complete = []
        for record in records:
            merged = {
                "tenant": "Atlas",
                "role": "employee",
                "approval_state": "Approved",
                "effective_from": "2026-01-01",
                "effective_to": "2027-01-01",
                "text": "The annual certification reimbursement limit for "
                        "employees is INR 25000.",
                **record,
            }
            complete.append(merged)

        counter["n"] += 1
        path = tmp_path / f"policies-{counter['n']}.json"
        path.write_text(json.dumps({"policies": complete}), encoding="utf-8")
        return PolicyStore(path).load()

    return _make


@pytest.fixture
def as_of() -> dt.date:
    """A date on which the 'current' generation of policies is in force."""
    return _date("2026-09-01")


# ---------------------------------------------------------------------------
# Request file bytes
# ---------------------------------------------------------------------------

@pytest.fixture
def request_bytes(requests_dir: Path):
    def _read(name: str) -> bytes:
        return (requests_dir / name).read_bytes()
    return _read

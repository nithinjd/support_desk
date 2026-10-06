"""
Deterministic extraction from TXT and PDF bytes.

Three properties matter more than any single field:

* **Determinism.** The same bytes must always produce the same record - there
  is no model and no network, so this is testable as an equality.
* **Refusal to guess.** request-03 states two uncorrected amounts; the only
  correct ``amount`` is ``None``.
* **No crash on degenerate input.** request-08 is 0 bytes and must come back as
  a record with null fields, not an exception.
"""

from __future__ import annotations

import pytest

from benefits import find_money, single_amount
from extractor import (
    MANAGER_APPROVAL_CLAIMED,
    MANAGER_APPROVAL_NOT_OBTAINED,
    MANAGER_APPROVAL_UNKNOWN,
    classify_manager_approval,
    detect_kind,
    extract,
    extract_reference,
    looks_like_pdf,
    to_text,
)

pytestmark = pytest.mark.corpus


# ---------------------------------------------------------------------------
# Every seeded request file
# ---------------------------------------------------------------------------

#: name -> (kind, benefit, amount, currency, reference)
EXPECTED = {
    "request-01.txt": ("txt", "CERTIFICATION", 18000.0, "INR", "CERT-101"),
    "request-02.pdf": ("pdf", "HOME_OFFICE", 14000.0, "INR", "HOME-202"),
    "request-03.txt": ("txt", "CERTIFICATION", None, "INR", "CERT-303"),
    "request-04.txt": ("txt", "WELLNESS", 6000.0, "INR", "WELL-404"),
    "request-05.txt": ("txt", "CERTIFICATION", 70000.0, "INR", "CERT-505"),
    "request-06.txt": ("txt", "CERTIFICATION", 18000.0, "INR", "CERT-101"),
    "request-07.txt": ("txt", "TRAINING", None, None, "TRAIN-707"),
    "request-08.txt": ("txt", None, None, None, None),
}


@pytest.mark.parametrize("name", sorted(EXPECTED))
def test_extracted_fields(request_bytes, name):
    kind, benefit, amount, currency, reference = EXPECTED[name]
    result = extract(request_bytes(name), filename=name)

    assert result.source_kind == kind
    assert result.benefit == benefit
    assert result.amount == amount
    assert result.currency == currency
    assert result.reference == reference


@pytest.mark.parametrize("name", sorted(EXPECTED))
def test_extraction_is_deterministic(request_bytes, name):
    data = request_bytes(name)
    assert extract(data, filename=name).to_dict() == extract(data, filename=name).to_dict()


# ---------------------------------------------------------------------------
# Conflicting amounts (request-03)
# ---------------------------------------------------------------------------

def test_conflicting_amounts_yield_none(request_bytes):
    result = extract(request_bytes("request-03.txt"), filename="request-03.txt")

    assert result.amount is None
    assert result.amount_conflict is True
    assert result.amount_candidates == [22000.0, 28000.0]


def test_conflicting_amounts_raise_an_issue(request_bytes):
    result = extract(request_bytes("request-03.txt"), filename="request-03.txt")
    codes = {i["code"] for i in result.issues}
    assert "CONFLICTING_INVOICE_AMOUNTS" in codes


def test_repeated_identical_amount_is_not_a_conflict():
    """Two mentions of the same figure state one amount, not two."""
    amount, currency, candidates = single_amount(
        "The invoice is INR 5000. Please reimburse INR 5000."
    )
    assert amount == 5000.0
    assert candidates == [5000.0]


def test_three_way_conflict_lists_all_candidates():
    amount, _, candidates = single_amount("INR 100 then INR 200 then INR 300")
    assert amount is None
    assert candidates == [100.0, 200.0, 300.0]


# ---------------------------------------------------------------------------
# The 0-byte file (request-08)
# ---------------------------------------------------------------------------

def test_empty_file_returns_a_record_not_an_exception(request_bytes):
    result = extract(request_bytes("request-08.txt"), filename="request-08.txt")

    assert result.byte_count == 0
    assert result.text_extracted is False
    assert result.benefit is None
    assert result.amount is None
    assert result.currency is None
    assert result.reference is None


def test_empty_file_raises_no_extractable_content(request_bytes):
    result = extract(request_bytes("request-08.txt"), filename="request-08.txt")
    assert {i["code"] for i in result.issues} == {"NO_EXTRACTABLE_CONTENT"}


@pytest.mark.parametrize("data", [b"", b"   ", b"\n\n", b"\t \r\n"])
def test_whitespace_only_input_is_also_nothing(data):
    result = extract(data, filename="blank.txt")
    assert result.text_extracted is False
    assert result.benefit is None


def test_no_filename_and_no_content_type_does_not_crash():
    assert extract(b"Reference:\nCERT-1\nINR 500 certification").benefit == "CERTIFICATION"


# ---------------------------------------------------------------------------
# Byte-identical files extract identically (request-01 / request-06)
# ---------------------------------------------------------------------------

def test_duplicate_file_extracts_identically(request_bytes):
    one = request_bytes("request-01.txt")
    six = request_bytes("request-06.txt")
    assert one == six

    # Filenames differ, so compare everything the filename does not determine.
    first = extract(one, filename="request-01.txt").to_dict()
    second = extract(six, filename="request-06.txt").to_dict()
    for key in ("benefit", "amount", "currency", "reference", "issues"):
        assert first[key] == second[key]


# ---------------------------------------------------------------------------
# PDF handling
# ---------------------------------------------------------------------------

def test_pdf_is_detected_by_magic_bytes_not_extension(request_bytes):
    data = request_bytes("request-02.pdf")
    assert looks_like_pdf(data) is True
    # Misnamed on purpose: content sniffing must win.
    assert detect_kind(data, "mislabelled.txt", "text/plain") == "pdf"
    assert extract(data, filename="mislabelled.txt").benefit == "HOME_OFFICE"


def test_pdf_text_is_extractable(request_bytes):
    result = extract(request_bytes("request-02.pdf"), filename="request-02.pdf")
    assert result.text_extracted is True
    assert result.char_count > 0


def test_corrupt_pdf_header_falls_back_to_text_without_crashing():
    result = extract(b"%PDF-1.4\nnot actually a pdf", filename="broken.pdf")
    assert result.benefit is None
    assert result.issues  # reports that nothing was extractable


# ---------------------------------------------------------------------------
# Reference codes must not be read as money
# ---------------------------------------------------------------------------

def test_reference_digits_are_not_an_amount():
    """CERT-101 must not become INR 101 - money needs a currency token."""
    result = extract(b"Reference:\nCERT-101\nNo amount given.", filename="r.txt")
    assert result.reference == "CERT-101"
    assert result.amount is None
    assert result.amount_candidates == []


@pytest.mark.parametrize("text", [
    "The year 2026 was fine.",
    "Policy 11 applies.",
    "Ticket CERT-9999 refers.",
])
def test_bare_numbers_are_never_money(text):
    assert find_money(text) == []


@pytest.mark.parametrize("text,expected", [
    ("INR 5000", [(5000.0, "INR")]),
    ("Rs. 1,250", [(1250.0, "INR")]),
    ("Rs 1,250.50", [(1250.50, "INR")]),
    ("5000 INR", [(5000.0, "INR")]),
    ("$42", [(42.0, "USD")]),
])
def test_money_formats(text, expected):
    assert find_money(text) == expected


@pytest.mark.parametrize("text,expected", [
    ("Reference:\nCERT-101\nbody", "CERT-101"),
    ("Reference: HOME-202", "HOME-202"),
    ("reference:\ntrain-707", "TRAIN-707"),
    ("Mentions WELL-404 in passing", "WELL-404"),
    ("No code at all", None),
])
def test_reference_extraction(text, expected):
    assert extract_reference(text) == expected


# ---------------------------------------------------------------------------
# Manager approval
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("text,expected", [
    ("I have not obtained manager approval.", MANAGER_APPROVAL_NOT_OBTAINED),
    ("I haven't obtained manager approval yet.", MANAGER_APPROVAL_NOT_OBTAINED),
    ("Booked without manager approval.", MANAGER_APPROVAL_NOT_OBTAINED),
    ("There is no manager approval on file.", MANAGER_APPROVAL_NOT_OBTAINED),
    ("Manager approval was obtained on 1 May.", MANAGER_APPROVAL_CLAIMED),
    ("This was approved by my manager.", MANAGER_APPROVAL_CLAIMED),
    ("Please process my claim.", MANAGER_APPROVAL_UNKNOWN),
])
def test_manager_approval_classification(text, expected):
    assert classify_manager_approval(text) == expected


def test_negation_beats_assertion():
    """The conservative reading wins when a text says both."""
    text = "Manager approval was obtained for the first trip, but I have not "\
           "obtained manager approval for this one."
    assert classify_manager_approval(text) == MANAGER_APPROVAL_NOT_OBTAINED


def test_request_07_reports_missing_amount_and_approval(request_bytes):
    result = extract(request_bytes("request-07.txt"), filename="request-07.txt")
    codes = {i["code"] for i in result.issues}

    assert result.manager_approval == MANAGER_APPROVAL_NOT_OBTAINED
    assert result.already_booked is True
    assert "MISSING_INVOICE_AMOUNT" in codes
    assert "MISSING_MANAGER_APPROVAL" in codes
    assert "BOOKED_BEFORE_APPROVAL" in codes


# ---------------------------------------------------------------------------
# Injection markers are recorded, never obeyed
# ---------------------------------------------------------------------------

def test_request_05_is_flagged_but_still_extracted(request_bytes):
    result = extract(request_bytes("request-05.txt"), filename="request-05.txt")

    assert result.injection_suspected is True
    assert "tenant_self_claim" in result.injection_signals
    assert "self_approval" in result.injection_signals

    # Flagging must not suppress the genuine request content.
    assert result.benefit == "CERTIFICATION"
    assert result.amount == 70000.0
    assert result.reference == "CERT-505"


def test_extraction_record_never_carries_a_tenant(request_bytes):
    """Identity is the caller registry's job; the record has no such field."""
    result = extract(request_bytes("request-05.txt"), filename="request-05.txt").to_dict()
    assert "tenant" not in result
    assert "role" not in result
    assert "approved" not in result


def test_clean_request_is_not_flagged(request_bytes):
    result = extract(request_bytes("request-01.txt"), filename="request-01.txt")
    assert result.injection_suspected is False
    assert result.injection_signals == []


# ---------------------------------------------------------------------------
# field_evidence - source quotations for supported values
# ---------------------------------------------------------------------------

EVIDENCE_FIELDS = {"benefit", "amount", "currency", "reference"}


@pytest.mark.parametrize("name", sorted(EXPECTED))
def test_field_evidence_always_has_the_same_four_keys(request_bytes, name):
    """Predictable shape: a consumer never has to check whether a key exists."""
    result = extract(request_bytes(name), filename=name)
    assert set(result.field_evidence) == EVIDENCE_FIELDS


@pytest.mark.parametrize("name", sorted(EXPECTED))
def test_every_supported_value_has_a_quotation(request_bytes, name):
    """If a value was extracted, its evidence must be present - and vice versa."""
    result = extract(request_bytes(name), filename=name)
    record = result.to_dict()

    for key in EVIDENCE_FIELDS:
        value = record[key]
        evidence = result.field_evidence[key]
        if value is None:
            assert evidence is None, f"{name}: {key} is null but has evidence"
        else:
            assert evidence is not None, f"{name}: {key}={value} has no evidence"
            assert evidence["quote"].strip(), f"{name}: {key} evidence is blank"


@pytest.mark.parametrize("name", sorted(EXPECTED))
def test_quotations_are_verbatim_from_the_source(request_bytes, name):
    """A quotation must appear in the document, character for character.

    Compared against the extracted text rather than the raw bytes, because a
    PDF's bytes are binary - the text is what was actually read.
    """
    data = request_bytes(name)
    result = extract(data, filename=name)
    if not result.text_extracted:
        return

    source, _kind, _notes = to_text(data, name)
    for key, evidence in result.field_evidence.items():
        if evidence is not None:
            assert evidence["quote"] in source, f"{name}: {key} quote is not verbatim"


def test_evidence_quotes_the_whole_sentence_not_just_the_token(request_bytes):
    """A bare "INR 18000" proves nothing; the sentence around it does."""
    result = extract(request_bytes("request-01.txt"), filename="request-01.txt")
    quote = result.field_evidence["amount"]["quote"]

    assert "INR 18000" in quote
    assert "certification reimbursement" in quote


def test_reference_evidence_keeps_both_lines(request_bytes):
    """The label and the code sit on different lines; both are quoted."""
    result = extract(request_bytes("request-01.txt"), filename="request-01.txt")
    quote = result.field_evidence["reference"]["quote"]

    assert "Reference:" in quote
    assert "CERT-101" in quote


def test_unresolved_amount_has_no_evidence(request_bytes):
    """request-03 states two amounts, so there is no single value to justify.

    The currency is a different matter: the document says INR unambiguously
    even though it cannot settle the figure, so currency keeps both its value
    and its evidence. Amount and currency are resolved independently.
    """
    result = extract(request_bytes("request-03.txt"), filename="request-03.txt")

    assert result.amount is None
    assert result.field_evidence["amount"] is None

    assert result.currency == "INR"
    assert result.field_evidence["currency"] is not None

    # The benefit was still determined, so that one does have evidence.
    assert result.field_evidence["benefit"] is not None


def test_amount_and_currency_share_one_quotation(request_bytes):
    """Both are read off the same money mention, so the evidence matches."""
    result = extract(request_bytes("request-01.txt"), filename="request-01.txt")
    assert result.field_evidence["amount"] == result.field_evidence["currency"]


def test_empty_file_has_the_shape_with_all_nulls(request_bytes):
    result = extract(request_bytes("request-08.txt"), filename="request-08.txt")
    assert result.field_evidence == {
        "benefit": None, "amount": None, "currency": None, "reference": None,
    }

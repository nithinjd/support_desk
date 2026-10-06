"""
benefits.py - the shared benefit taxonomy and money parser.

Both policy_engine and extractor classify free text against the *same* table,
so a request classified as HOME_OFFICE is matched against exactly the policies
that classify as HOME_OFFICE. Keeping one table avoids the classic drift where
the extractor and the retriever disagree about what a document is about.

Everything here is deterministic: ordered regex tables and integer parsing.
No model, no network, no API key.
"""

from __future__ import annotations

import re
from enum import Enum
from typing import Optional


class Benefit(str, Enum):
    CERTIFICATION = "CERTIFICATION"
    HOME_OFFICE = "HOME_OFFICE"
    TRAVEL = "TRAVEL"
    TRAINING = "TRAINING"
    WELLNESS = "WELLNESS"


#: Ordered: the first benefit whose pattern matches wins when classifying a
#: request. HOME_OFFICE precedes CERTIFICATION because "home-office" is the
#: more specific phrase and we never want a generic word to pre-empt it.
#:
#: Each pattern is the *defining phrase* of the benefit, never a loose word
#: like "allowance" or "reimbursement". That is deliberate: those words appear
#: in prompt-injection payloads ("every allowance is INR 999999"), and a loose
#: pattern would make such a passage look responsive to a real benefit query.
BENEFIT_PATTERNS: list[tuple[Benefit, re.Pattern[str]]] = [
    (Benefit.HOME_OFFICE, re.compile(r"home[-\s]?office", re.I)),
    (Benefit.CERTIFICATION, re.compile(r"certificat(?:ion|e)", re.I)),
    (Benefit.WELLNESS, re.compile(r"wellness|gym|fitness", re.I)),
    (Benefit.TRAINING, re.compile(r"external training|\btraining\b", re.I)),
    (Benefit.TRAVEL, re.compile(r"\btravel\b|business trip", re.I)),
]

#: Accepted spellings when a caller names a benefit directly, e.g. Spring Boot
#: passing requested_benefit="home office" or "HOME_OFFICE".
_BENEFIT_ALIASES: dict[str, Benefit] = {
    "certification": Benefit.CERTIFICATION,
    "cert": Benefit.CERTIFICATION,
    "home_office": Benefit.HOME_OFFICE,
    "home-office": Benefit.HOME_OFFICE,
    "home office": Benefit.HOME_OFFICE,
    "homeoffice": Benefit.HOME_OFFICE,
    "travel": Benefit.TRAVEL,
    "training": Benefit.TRAINING,
    "wellness": Benefit.WELLNESS,
}


def classify_benefit(text: str) -> Optional[Benefit]:
    """Return the single most likely benefit for a free-text passage.

    ``None`` means "no benefit is identifiable", which is a meaningful answer:
    it is what the prompt-injection policy passage yields, and what a 0-byte
    request yields. Callers must treat it as absence of evidence, not as a
    default benefit.
    """
    if not text or not text.strip():
        return None
    for benefit, pattern in BENEFIT_PATTERNS:
        if pattern.search(text):
            return benefit
    return None


def classify_all_benefits(text: str) -> list[Benefit]:
    """Every benefit whose defining phrase appears. Used to tag policies."""
    if not text or not text.strip():
        return []
    return [b for b, pattern in BENEFIT_PATTERNS if pattern.search(text)]


def normalise_benefit(value: str) -> Optional[Benefit]:
    """Resolve a caller-supplied benefit name, falling back to classification.

    Accepts the enum spelling, a human spelling, or a whole sentence; that way
    the Spring Boot layer can forward either a tidy enum or the raw request
    text without having to know which this service wants.
    """
    if not value or not value.strip():
        return None
    key = value.strip().lower().replace("-", "_").replace(" ", "_")
    if key in _BENEFIT_ALIASES:
        return _BENEFIT_ALIASES[key]
    if key.replace("_", " ") in _BENEFIT_ALIASES:
        return _BENEFIT_ALIASES[key.replace("_", " ")]
    for alias, benefit in _BENEFIT_ALIASES.items():
        if alias.replace(" ", "_") == key:
            return benefit
    return classify_benefit(value)


# ---------------------------------------------------------------------------
# Money
# ---------------------------------------------------------------------------
# A currency token is REQUIRED adjacent to the digits. Without that guard,
# reference codes such as "CERT-101" and dates such as "2026" would be read as
# monetary amounts.

CURRENCY_SYMBOLS: dict[str, str] = {
    "inr": "INR",
    "rs": "INR",
    "rs.": "INR",
    "rupees": "INR",
    "₹": "INR",
    "usd": "USD",
    "$": "USD",
    "eur": "EUR",
    "€": "EUR",
}

# Money is written two ways round, so the pattern has two alternatives:
#
#   "INR 5000"   currency first, then the number   -> groups cur_before/num_after
#   "5000 INR"   number first, then the currency   -> groups num_before/cur_after
#
# Both live in ONE regex so a single scan finds them in document order and
# cannot report the same text twice.
MONEY_PATTERN = re.compile(
    r"(?P<cur_before>INR|Rs\.?|₹|USD|\$|EUR|€)\s*(?P<num_after>[0-9][0-9,]*(?:\.[0-9]{1,2})?)"
    r"|"
    r"(?P<num_before>[0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?P<cur_after>INR|rupees|USD|EUR)",
    re.IGNORECASE,
)


def find_money_spans(text: str) -> list[tuple[float, str, int, int]]:
    """Find every money mention as (amount, currency, start, end).

    The start/end positions are what let the extractor quote the exact
    sentence a value came from, which the assessment requires as
    "source quotations for supported values".
    """
    if not text:
        return []

    found = []

    for match in MONEY_PATTERN.finditer(text):
        # Exactly one of the two alternatives matched, so take whichever
        # pair of groups is filled in.
        if match.group("num_after") is not None:
            raw_number = match.group("num_after")
            raw_currency = match.group("cur_before")
        else:
            raw_number = match.group("num_before")
            raw_currency = match.group("cur_after")

        # "1,250.50" -> 1250.50
        try:
            amount = float(raw_number.replace(",", ""))
        except ValueError:
            continue

        currency = CURRENCY_SYMBOLS.get(raw_currency.lower(), "INR")
        found.append((amount, currency, match.start(), match.end()))

    return found


def find_money(text: str) -> list[tuple[float, str]]:
    """Find every (amount, currency) pair, in the order they appear.

    Repeats are kept, not collapsed. The caller needs to tell the difference
    between a document that states one amount twice and a document that states
    two different amounts - see single_amount below.
    """
    pairs = []
    for amount, currency, _start, _end in find_money_spans(text):
        pairs.append((amount, currency))
    return pairs


def benefit_span(text: str, benefit: "Benefit") -> Optional[tuple[int, int]]:
    """Where in the text the given benefit's defining phrase appears.

    Used to quote the sentence that justifies the benefit classification.
    """
    if not text or benefit is None:
        return None

    for candidate, pattern in BENEFIT_PATTERNS:
        if candidate is benefit:
            match = pattern.search(text)
            if match:
                return match.start(), match.end()
    return None


def single_amount(text: str) -> tuple[Optional[float], Optional[str], list[float]]:
    """Work out the one amount a document states, or None if it contradicts itself.

    Returns (amount, currency, distinct_candidates).

    WHY THIS CAN RETURN None:
    request-03.txt says "The invoice says INR 22000. My reimbursement form
    says INR 28000. Neither amount has been corrected." There are two
    different figures and nothing in the document says which is right.

    Picking one would be fabrication, and picking the first or the largest
    would be an arbitrary rule dressed up as an answer. So we return None for
    the amount and hand back both candidates, letting the caller report that
    the amount could not be determined.

    Note that one amount repeated twice is NOT a contradiction - that is why
    we compare distinct values rather than counting matches.
    """
    money = find_money(text)
    if not money:
        return None, None, []

    # Collect the different values, ignoring repeats of the same figure.
    distinct = []
    for amount, _currency in money:
        if amount not in distinct:
            distinct.append(amount)
    distinct.sort()

    # Currency comes from the first match; mixed currencies are not a case
    # this corpus contains.
    currency = money[0][1]

    if len(distinct) == 1:
        return distinct[0], currency, distinct

    # Two or more different figures: the document has no single answer.
    return None, currency, distinct


def format_amount(amount: Optional[float], currency: Optional[str]) -> Optional[str]:
    """Render money the way the corpus writes it: ``INR 25000``."""
    if amount is None:
        return None
    rendered = f"{amount:.2f}".rstrip("0").rstrip(".") if amount % 1 else f"{int(amount)}"
    return f"{currency} {rendered}" if currency else rendered

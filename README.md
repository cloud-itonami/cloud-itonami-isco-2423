# cloud-itonami-isco-2423

**Community Careers Practice** — the ISCO-08 2423 (Personnel and
Careers Professionals) actor, an ISCO **Wave 0** occupation per
ADR-2607121000. This is the actor that feeds the **7810
labour-exchange lane** (ADR-2607121000 P2): placements are how
liberated work transmits into continued income (ADR-2607122100's R4
loop).

**Maturity: `:implemented`** — CareersAdvisor ⊣ CareersGovernor as a
langgraph StateGraph (`intake → advise → govern → decide →
commit/hold`, human-approval interrupt), modeled on
cloud-itonami-isco-4311's bookkeeping actor. 13 tests / 28 assertions
green.

The careers-specific HARD invariants:

1. **Consent** — a candidate is NEVER referred without their recorded
   consent. A human approver cannot approve away a person's missing
   consent; the remedy is asking the person. Dignity is a hard line,
   not a preference.
2. **Real people, real jobs** — placements must cite a REGISTERED
   candidate and vacancy belonging to this client (no invented people,
   no invented openings).
3. **Skills basis** — the vacancy's required skills must be a subset
   of the candidate's REGISTERED skills (deterministic set inclusion).
   The remedy for a gap is updating the skill record with evidence or
   training (see isco-2424), not approving harder.

Escalations (always human sign-off): `:send-referral` (external-send
to an employer), low confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.

# OWNER matrix — pending MFA-assisted session

The following items require a **FULL** JWT session as **OWNER** (not MFA_CHALLENGE):

- Timeline per property (`GET /api/properties/{id}/timeline`)
- Monthly / annual reports per property (`GET /api/properties/{id}/reports/monthly|annual`)
- Agreements (convenios) — endpoints under payment agreement flow
- Maintenance — tickets / quotes / expenses as owner
- Vacancy / commercial activity
- Numeric consistency: Owner summary (`/api/owner/accounting-summary` or equivalent), property detail, accountant views, reports

**Status:** Not executed in this pass because completing MFA requires a live TOTP from the authenticator app. No secrets written to this repository.

After authentication is completed in a follow-up (user-assisted MFA), re-run calls and store redacted JSON under this folder per scenario.

# ADR-003: Billing as Entitlement Control

## Status

Accepted

## Context

KnowledgeFlow serves professional firms (legal, tax, engineering, consulting) that onboard
their own clients onto the platform. Each client has a commercial relationship with the firm
that governs what they can access and how much they can consume.

Payment processing is handled externally in the initial phase. However, the platform must
enforce access rules derived from each client's commercial plan and record consumption
data to support external invoicing.

## Decision

The `billing` module is responsible for **entitlement control and consumption tracking**,
not payment processing.

### Commercial Plans

Each `Client` is associated with a commercial plan that defines:

- **Plan type** — the billing model in use:
  - `TICKET` — per formal case opened or delivered
  - `MONTHLY` — flat access regardless of volume
  - `INTERACTIONS` — per assistive circuit interaction consumed
  - `HYBRID` — combination of a base subscription with variable consumption
- **Entitlements** — what the client is allowed to access and up to what limits
- **Period** — the validity window of the plan (start date, end date or open-ended)

### Access Control

Before allowing a billable action (opening a case, submitting an assistive interaction,
accessing the client portal), the platform checks the client's active plan and remaining
entitlements. If the plan does not permit the action, access is denied with a clear reason.

### Consumption Tracking

Every billable event is recorded as a `ConsumptionEvent` linked to:

- the client
- the organization
- the action type
- the plan at the time of the event
- the timestamp

This record supports external invoicing, audit, and future reporting.

### Payment Processing

Payment processing is explicitly out of scope for the MVP. Invoicing is handled externally
by the firm using consumption reports exported from the platform. A future integration
with a payment provider (e.g. Stripe) is anticipated but not a current dependency.

## Consequences

- `Client` gains a reference to an active `CommercialPlan`.
- The `billing` module exposes an internal `EntitlementService` consumed by other modules before billable actions.
- Billable actions are defined per module and checked at the service layer, not the controller layer.
- `ConsumptionEvent` is an append-only audit trail; records are never deleted.
- External billing is supported by exportable consumption reports.
- When a plan expires or limits are exceeded, the client loses access until the plan is renewed or upgraded — the firm is notified.
- Payment processing integration is deferred to a post-MVP release.

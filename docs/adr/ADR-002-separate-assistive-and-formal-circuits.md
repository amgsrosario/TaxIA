# ADR-002: Separate Assistive and Formal Knowledge Circuits

## Status

Accepted

## Context

KnowledgeFlow must support two distinct professional needs:

1. Fast access to technical context, sources and AI-generated guidance.
2. Slow, deliberate creation and publication of formal technical assessments.

Treating every AI response as a draft formal assessment would make daily use cumbersome.
Treating formal assessments as ordinary AI responses would weaken accountability, traceability and professional control.

## Decision

KnowledgeFlow has two parallel circuits.

### Assistive Circuit

The assistive circuit provides rapid technical support:

```text
Question
-> source retrieval
-> AI-generated answer
-> source citations and confidence
-> optional human feedback
```

The AI may answer autonomously in this circuit.

Each relevant interaction must preserve:

- organization;
- user;
- question;
- answer;
- source references and excerpts;
- source versions or retrieval timestamp;
- confidence and risk indicators;
- model/provider metadata;
- user feedback;
- later corrections, when applicable.

Assistive answers must be clearly represented as contextual AI-supported guidance.
They are not formal publications and do not become validated institutional knowledge automatically.

### Formal Circuit

The formal circuit creates publishable professional artefacts:

```text
Knowledge case
-> investigation
-> sources
-> optional AI pre-analysis
-> human editing
-> review
-> human validation
-> publication
-> institutional reuse
```

`KnowledgeCase` is the central aggregate of the formal circuit.

Only an authorized human may publish or validate a formal assessment.
AI services may generate drafts, summaries, classifications and risk suggestions, but may not publish formal artefacts.

### Promotion Bridge

An assistive interaction may be explicitly promoted into a formal knowledge case.

Promotion should preserve:

- original question;
- answer;
- sources;
- retrieval metadata;
- feedback;
- promoting user;
- promotion timestamp.

Promotion creates a traceable starting point. It does not validate or publish the resulting case.

## Human Feedback

Human feedback is a first-class asset.

The platform should capture structured feedback such as:

```text
USEFUL
INCORRECT
INCOMPLETE
OUTDATED_SOURCE
INADEQUATE_SOURCE
CORRECTION_PROPOSED
EXPERT_CONFIRMED
```

Initially, feedback improves prompts, retrieval ranking, evaluation datasets and institutional memory.
Model fine-tuning is a later option, not an MVP dependency.

## Consequences

- Assistive answers and formal assessments use separate aggregates, tables and endpoints.
- Both circuits share organizations, users, documents, retrieval infrastructure, audit and AI provider abstractions.
- `KnowledgeCase` remains the formal workflow aggregate.
- A future `AssistedInteraction` aggregate owns quick AI-supported answers and feedback.
- Formal publication always requires explicit human action.

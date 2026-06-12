# ADR-001: Generalize the Platform Core

## Status

Accepted

## Context

The initial implementation used TaxIA-specific naming, centered on fiscal and legal opinions.
The product direction now requires a reusable platform for validated technical know-how across multiple domains.

## Decision

Use KnowledgeFlow as the neutral internal platform name.

Rename the core domain concept:

```text
Opinion        -> KnowledgeCase
OpinionVersion -> KnowledgeCaseVersion
```

TaxIA becomes a future vertical specialization providing terminology, prompts, taxonomies, documents and domain-specific rules.

Keep the architecture as a modular monolith. Do not introduce dynamic schemas, configurable workflow engines or microservices at this stage.

The formal workflow represented by `KnowledgeCase` is not the only interaction mode.
ADR-002 defines a separate assistive circuit for autonomous contextual AI answers with sources and human feedback.

## Consequences

- Core APIs use `/api/v1/knowledge-cases`.
- Core tables use `knowledge_cases` and `knowledge_case_versions`.
- Existing databases are upgraded through Flyway `V5`.
- Domain-specific terminology belongs in vertical configuration, not the platform core.
- Assistive interactions and formal knowledge cases remain separate aggregates.

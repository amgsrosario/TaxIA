# KnowledgeFlow Technical Roadmap

## Product Principle

KnowledgeFlow transforms technical sources, assistive interactions and human validation into traceable institutional knowledge.

AI may answer autonomously in assistive contexts.
Formal artefacts and validated institutional knowledge require explicit human publication.

## Implemented Foundation

### Platform Base

- Java 21 and Spring Boot
- PostgreSQL and Flyway
- DTO-only REST architecture
- MapStruct
- Docker Compose
- health endpoint
- OpenAPI base configuration
- common REST error contract

### Security

- organizations
- users
- roles
- organization-user memberships
- JWT login
- BCrypt password hashing
- authenticated user context
- RBAC base

### Shared Client Context

- create, read, list, update and archive clients
- organization isolation

### Formal Circuit Base

- `KnowledgeCase`
- `KnowledgeCaseVersion`
- initial `DRAFT` state
- automatic `HUMAN_DRAFT` version on creation and relevant edits
- organization isolation

## Next Delivery Blocks

### Block 1: Cross-Cutting Audit

Create:

```text
AuditEvent
AuditService
```

Capture actor, organization, action, target type, target ID, timestamp and controlled JSON metadata.

Integrate first with:

- login;
- client creation/update/archive;
- knowledge case creation/update;
- future state transitions;
- future assistive interactions.

### Block 2: Formal Workflow

Implement explicit transitions:

```text
DRAFT -> PRE_ANALYSIS_PENDING
PRE_ANALYSIS_PENDING -> PRE_ANALYSIS_GENERATED
PRE_ANALYSIS_GENERATED -> UNDER_REVIEW
UNDER_REVIEW -> VALIDATED
UNDER_REVIEW -> REJECTED
UNDER_REVIEW -> CHANGES_REQUESTED
CHANGES_REQUESTED -> UNDER_REVIEW
VALIDATED -> ARCHIVED
```

Add:

- transition service;
- validation decisions;
- review comments;
- permission tests;
- invalid-transition tests;
- validated version marking;
- explicit human publication semantics.

### Block 3: Shared Knowledge Sources

Create:

```text
KnowledgeDocument
DocumentChunk
RetrievedSource
RagService
```

Start with simple retrieval.
Prepare for embeddings, pgvector and hybrid search without making them MVP blockers.

### Block 4: AI Abstraction

Create:

```text
AIService
AIProviderClient
AIInteraction
```

Use the same provider abstraction for both circuits.
Do not keep database transactions open during external AI calls.

### Block 5: Assistive Circuit

Create:

```text
AssistedInteraction
AssistedAnswer
InteractionFeedback
```

Initial workflow:

```text
question
-> retrieve sources
-> generate answer
-> expose citations and confidence
-> capture optional feedback
```

Add explicit promotion:

```text
POST /api/v1/assisted-interactions/{id}/promote-to-knowledge-case
```

### Block 6: Vertical Configuration

Introduce domain configuration for TaxIA without contaminating platform core:

- visible terminology;
- prompt templates;
- document collections;
- taxonomies;
- source priorities;
- domain risk criteria;
- response templates.

Future verticals may provide equivalent configuration for agriculture, engineering or compliance.

## Deferred Complexity

Do not introduce yet:

- microservices;
- dynamic database schemas;
- user-designed workflow engines;
- autonomous formal publication;
- fine-tuning as an MVP dependency;
- complex billing;
- event sourcing;
- CQRS.

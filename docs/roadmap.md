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

## Próxima fase — Produto de consultoria fiscal assistida

> Fase orientada pela visão de produto em
> [taxia-product-vision.md](taxia-product-vision.md) (commit `5ec27ee`).
> Base técnica já validada: curadoria, publicação aplicacional e RAG; correcção
> do indexer em `b79f7b8`; 2 casos MEDIUM publicados (`AT-FAQ-5930`, `AT-FAQ-2721`)
> e 2 casos HIGH validados mas **não** publicados (`AT-FAQ-0959`, `AT-FAQ-4624`).

Objectivo da fase: passar de "IA que responde" para **consultoria fiscal assistida**
— respostas documentadas, com fontes, confiança, risco e revisão humana.

### Bloco A — Modelo de resposta documentada *(primeiro)*

Definir o formato da resposta TaxIA ao utilizador final/profissional. Deve prever:
resposta curta; explicação técnica; fundamentos legais; fontes usadas; condições;
exclusões; alertas; nível de suporte; grau de confiança; necessidade de revisão
humana; e a distinção entre resposta **informativa**, **validada** e resposta com
**revisão obrigatória**.

### Bloco B — Metadados de qualidade/confiança *(depois)*

Preparar backend/DTOs/resposta RAG para transportar sinais de qualidade.
Candidatos de **desenho** (não implementação imediata): `support_level`;
`source_quality`; `answer_confidence`; `legal_basis_strength`;
`requires_professional_review`; `client_visibility`; `last_source_checked_at`;
`disclaimer_level`.

### Bloco C — Política de visibilidade e risco

Definir como `LOW`/`MEDIUM`/`HIGH` aparecem ao utilizador:

- `LOW`: potencial resposta informativa/documentada;
- `MEDIUM`: resposta validada normal;
- `HIGH`: resposta com revisão humana recomendada ou obrigatória;
- separar **publicação técnica no RAG** de **visibilidade ao cliente**;
- casos `HIGH` podem ser pesquisáveis, mas **nunca** apresentados como resposta
  autónoma final.

### Bloco D — Ingestão massiva controlada *(por último nesta fase)*

Preparar importação em volume de FAQs oficiais/fontes públicas **sem** publicação
automática indiscriminada. Deve prever: ingestão industrial; deduplicação;
detecção de alterações; classificação automática; rascunho automático; fila de
revisão; publicação controlada; relatórios por categoria/fonte/risco.

### Prioridade

1. Desenhar o **modelo de resposta documentada** (Bloco A).
2. Definir **metadados de qualidade/confiança** (Bloco B).
3. Ajustar **backend/frontend** para expor esses sinais.
4. Avançar para **ingestão massiva controlada** (Bloco D).
5. Só depois ponderar **publicação alargada**.

### Nota de decisão

**Não publicar os casos HIGH (`AT-FAQ-0959`, `AT-FAQ-4624`) como próximo passo
automático.** Antes disso, definir política explícita de visibilidade e revisão
humana (Bloco C).

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

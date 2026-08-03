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
— respostas documentadas, com fontes, confiança, risco e, quando aplicável,
encaminhamento para Pedido de parecer (a intervenção humana caso a caso vive nesse
circuito — ver Decisão C3).

### Bloco A — Modelo de resposta documentada *(primeiro)*

Definir o formato da resposta TaxIA ao utilizador final/profissional. Deve prever:
resposta curta; explicação técnica; fundamentos legais; fontes usadas; condições;
exclusões; alertas; nível de suporte; grau de confiança; necessidade de
encaminhamento para Pedido de parecer (`parecerRequirement`, ver C3); e a distinção
entre resposta **informativa**, **validada** e resposta que **exige Pedido de
parecer**.

### Bloco B — Metadados de qualidade/confiança *(depois)*

Preparar backend/DTOs/resposta RAG para transportar sinais de qualidade.
Candidatos de **desenho** (não implementação imediata): `support_level`;
`source_quality`; `answer_confidence`; `legal_basis_strength`;
`requires_professional_review`; `client_visibility`; `last_source_checked_at`;
`disclaimer_level`.

### Bloco C — Política de visibilidade e risco

> **Antes** de fixar a política de risco/visibilidade, incorporar a **Resposta-limite**
> como forma oficial de resposta (o último patamar automático antes do Pedido de
> parecer) e o campo `answerType`. Ver
> [taxia-boundary-answer.md](taxia-boundary-answer.md).
>
> A matriz deve partir de um **produto 100% profissional**: `visibility` **não**
> significa "cliente leigo *vs.* profissional", mas sim **externo profissional /
> demo / interno-admin / curadoria**. A demo é limitada comercialmente, nunca na
> qualidade da resposta. Ver [taxia-core-principles.md](taxia-core-principles.md)
> (Princípio 1-A).
>
> Documento-base do Bloco C:
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md). Decisões
> **fechadas**: **C1** (visibilidade externa = profissional; níveis
> `EXTERNAL`/`DEMO`/`INTERNAL`/`CURATION_ONLY`), **C2** (`risk_level` condiciona
> a prudência mas não decide sozinho o `answerType`), **C3** (intervenção humana
> caso a caso apenas no **Pedido de parecer**; `reviewRequirement` passa a
> `parecerRequirement` — `NONE`/`SUGGESTED`/`REQUIRED` — sem fila de revisão humana
> invisível no circuito automático), **C4** (agregação de risco pelo máximo dos
> **fundamentos relevantes usados** — `riskAggregated` — sem contaminação por casos
> recuperados mas não usados), **C5** (fronteira entre
> `CONSULTA_DOCUMENTADA_COM_LIMITACOES` — ainda há orientação prudente — e
> `RESPOSTA_LIMITE` — só há enquadramento, sem conclusão aplicável), **C6**
> (`parecerRequirement` gradua o **grau de recomendação/encaminhamento** para Pedido
> de parecer — `NONE`/`SUGGESTED`/`REQUIRED` — sem medir o valor económico abstracto
> do parecer, que continua estrutural e sempre disponível), **C7** (detalhe visível
> por nível: `EXTERNAL`/`DEMO` mostram **produto profissional limpo** e
> `INTERNAL`/`CURATION_ONLY` mostram **bastidores/diagnóstico**; a diferença entre
> níveis não é a qualidade da resposta, mas o grau de exposição dos bastidores) e
> **C8** (actualidade/origem temporal gradua força, limites e avisos da resposta —
> `freshnessStatus` `CURRENT`/`STABLE_BUT_OLD`/`UNCERTAIN`/`OUTDATED`; fonte antiga
> não é bloqueio automático e `OUTDATED` pode servir de histórico/contraste/alerta,
> não de conclusão actual) e **C9** (a robustez documental mede-se por
> **força/autoridade/aplicabilidade directa/actualidade/coerência/diversidade
> material** — `sourceQuality` — e **não** pela contagem bruta de fontes; papéis de
> fonte principal/complementar/derivada-replicada e hierarquia orientadora de fontes;
> fontes derivadas não são confirmações independentes e fontes externas não oficiais
> não sustentam sozinhas). Com a **C9**, o **Bloco C fica conceptualmente fechado
> (C1–C9)**; a **materialização técnica** (thresholds, scoring, ranking, enums
> definitivos e limiares exactos por sinal) fica para **fase de implementação
> posterior**, sem abrir novo bloco conceptual.


Definir como `LOW`/`MEDIUM`/`HIGH` aparecem ao utilizador:

- `LOW`: potencial resposta informativa/documentada;
- `MEDIUM`: resposta validada normal;
- `HIGH`: resposta com `parecerRequirement` `SUGGESTED` ou `REQUIRED`
  (encaminhamento para Pedido de parecer — ver C3);
- separar **publicação técnica no RAG** de **visibilidade ao cliente**;
- casos `HIGH` podem ser pesquisáveis, mas **nunca** apresentados como resposta
  autónoma final.

### Bloco D (desenho) — Ingestão massiva controlada *(por último nesta fase de desenho)*

> **Nota de nomenclatura.** Este "Bloco D — Ingestão massiva controlada" pertence à
> **sequência de desenho** (Blocos A–D acima). **Não** deve confundir-se com o **Bloco
> D — Materialização técnica da resposta documentada** (secção seguinte, com decisões
> D1–D11), que é a **fase técnica** que se segue ao fecho conceptual do Bloco C. A
> ingestão massiva permanece uma fase **posterior** distinta.

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
automático.** Antes disso, definir política explícita de visibilidade e de
encaminhamento para Pedido de parecer (Bloco C — Decisões C1, C2 e C3).

## Bloco D — Materialização técnica da resposta documentada

> **Fase técnica seguinte ao fecho conceptual do Bloco C (C1–C9).** O Bloco C
> permanece **conceptualmente fechado**; o Bloco D **não reabre** nenhuma das decisões
> C1–C9 — **materializa-as** tecnicamente. Documento-base:
> [taxia-documented-answer-technical-plan.md](taxia-documented-answer-technical-plan.md).

Objectivo: transformar a política conceptual (risco, visibilidade, actualidade,
parecer e qualidade das fontes) num **contrato técnico** e numa **arquitectura** de
serviços — construir primeiro a **resposta documentada completa** e depois **projectá-la**
por `visibilityLevel` (produto profissional limpo em `EXTERNAL`/`DEMO`; bastidores em
`INTERNAL`/`CURATION_ONLY`), sem alterar a qualidade conceptual da resposta.

Decisões do Bloco D:

- **D1 — concluída (documentação/planeamento):** contrato técnico preliminar da
  resposta documentada, serviços conceptuais, integração com grounding/RAG, matriz de
  decisão, projecção por visibilidade, lacunas e ordem segura de implementação. Ver
  [taxia-documented-answer-technical-plan.md](taxia-documented-answer-technical-plan.md).
- **D2 — concluída (inventário técnico do código actual):** confronto, confirmado por
  leitura directa dos ficheiros, entre o código existente (grounding/RAG, DTOs, enums,
  entidades, endpoints, frontend, testes) e o contrato D1 — o que existe, é parcial,
  falta, colide ou se reaproveita, com riscos e ordem para D3. Ver
  [taxia-documented-answer-code-inventory.md](taxia-documented-answer-code-inventory.md).
- **D3 — concluída (contrato DTO/enums final documentado):** nomes finais
  (`DocumentedTaxiaAnswer`, `SourceEvidence`, `AnswerProjection` e serviços conceptuais),
  campos com visibilidade externa/interna, enums finais propostos, compatibilidade por
  adição com `GroundedAIResponse`/`AnswerSource`/`AdminAIController`, persistência adiada,
  regras de não confusão e escopo de D4. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md).
- **D4 — concluída (implementação backend mínima e aditiva):** primeira materialização
  em código Java do contrato, no pacote `com.knowledgeflow.ai.documented` — 8 enums, 3
  DTOs (`DocumentedTaxiaAnswer`, `SourceEvidence`, `AnswerProjection`) e o
  `DocumentedTaxiaAnswerMapper` (defaults transitórios) que converte `GroundedAIResponse`
  no contrato documentado; `AdminAIController.AskResponse` ganhou o campo
  `documentedAnswer` por adição (fluxo `/ask` intacto); testes unitários novos e
  reforçados, tudo verde. Sem migrations, persistência, frontend ou algoritmos de
  scoring. Ver [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md).
- **D5 — concluída (avaliação real de fontes):** `SourceAssessmentService` (`@Service` em
  `com.knowledgeflow.ai.documented`) avalia cada `SourceEvidence` com heurísticas simples,
  determinísticas e conservadoras — autoridade (legal/FAQ/administrativa/jurisprudência/
  complementar/externa/interna), qualidade, papel, diversidade, núcleo e actualidade — a
  partir só de `title`/`reference`, sem scoring, thresholds, deduplicação semântica nem
  web; o `DocumentedTaxiaAnswerMapper` passou a delegar no serviço, deixando de usar
  defaults cegos por fonte; `sourceCore`/`sourceQuality`/`freshness` continuam transitórios
  e não persistidos; testes novos e reforçados, tudo verde. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md) §12.B.
- **D6–D11 — ainda não iniciadas** (decisão fina de `answerType`/`parecerRequirement` com
  scoring/thresholds; projecção por `visibilityLevel`; integração alargada no endpoint;
  frontend; testes; auditoria/diagnóstico interno).

A **materialização técnica** do Bloco C (thresholds, scoring, ranking, enums
definitivos, limiares por sinal) vive **aqui**, no Bloco D, e **não** abre novo bloco
conceptual.

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

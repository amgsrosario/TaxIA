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
- **D6 — concluída (decisão de prudência):** `AnswerDecisionService` (`@Service` em
  `com.knowledgeflow.ai.documented`) e o record `AnswerDecision` concentram a decisão de
  forma/prudência que antes vivia dispersa no mapper — `answerType`, `parecerRequirement`,
  `confidenceSummary`, `limitations`, `warnings`, `nextSteps` e `sourceSummary` — de forma
  simples, determinística e conservadora, sem scoring numérico nem thresholds, combinando
  `supportStatus`/`requiresHumanValidation` com os sinais D5 de cada `SourceEvidence`; o
  escalão de parecer só sobe, a ausência de fontes não rebaixa um `SUPPORTED`, a
  Resposta-limite não é não-resposta e o Pedido de parecer não é erro; o mapper delega no
  serviço; `AdminAIController`/`GroundedAIResponse`/`AnswerSource` inalterados; decisões não
  persistidas; testes novos e reforçados, tudo verde. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md) §12.C.
- **D7 — concluída (projecção por visibilidade):** `AnswerProjectionService` (`@Service` em
  `com.knowledgeflow.ai.documented`) projecta uma `DocumentedTaxiaAnswer` numa
  `AnswerProjection` conforme o `VisibilityLevel`-alvo, transformando só a apresentação —
  **sem recalcular** `answerType`/`parecerRequirement`/`supportStatus`/`aggregatedRiskLevel`/
  `freshnessStatus` (recebe-os já decididos em D6). `EXTERNAL`/`DEMO` recebem produto
  profissional limpo (ocultam diagnóstico interno, núcleo/diversidade de fontes,
  identificadores técnicos, notas internas e excertos, mas preservam limitações, avisos e
  `parecerRequirement`); `DEMO` mantém a mesma qualidade de `EXTERNAL`; `INTERNAL` preserva
  diagnóstico moderado (oculta só `notesInternal`); `CURATION_ONLY` preserva tudo. O
  `AnswerProjection` ganhou `visibleAnswerType` por adição; o `AdminAIController` devolve
  `projectedAnswer` (INTERNAL) por adição não quebrante; projecção não persistida; frontend
  inalterado; testes novos e reforçados, tudo verde. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md) §12.D.
- **D8 — concluída (resolução da lente de projecção):** `VisibilityLevelResolver`
  (`@Service` em `com.knowledgeflow.ai.documented`) centraliza — de forma simples e
  determinística — a escolha do `VisibilityLevel`-alvo por endpoint/contexto, separando "que
  vista mostrar" de "que resposta dar" (D6) e "como projectar" (D7). `resolveForAdminAsk()`
  devolve `INTERNAL` e é usado pelo `AdminAIController` (que substituiu o literal
  `VisibilityLevel.INTERNAL` pela chamada ao serviço); `resolveForExternalProfessional()`/
  `resolveForDemo()`/`resolveForCuration()` ficam preparados para integração futura, ainda
  não ligados a nenhum fluxo. D8 **não** implementa autorização (mantém-se o `@PreAuthorize`
  existente), não recalcula decisão, não persiste e não altera o frontend; o endpoint mantém
  request, campos antigos, `documentedAnswer` e `projectedAnswer`. Testes novos e reforçados,
  tudo verde. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md) §12.E.
- **D9 — concluída (frontend da resposta profissional):** componente React/TS isolado e
  reutilizável `DocumentedAnswerPanel` que apresenta um `AnswerProjection` já projectado
  pelo backend — cabeçalho com o tipo de resposta, corpo, necessidade de parecer, limitações,
  avisos e fontes visíveis — traduzindo os enums para rótulos em português de Portugal. Segue
  o lema "Backend decide, backend projecta, frontend mostra": o frontend **não** recalcula
  `answerType`/`parecerRequirement`/qualidade/actualidade nem esconde limitações ou a
  necessidade de parecer; `RESPOSTA_LIMITE` e `PEDIDO_DE_PARECER` são apresentados como
  respostas profissionais legítimas, nunca como erro. Os tipos de vista
  (`AnswerProjection`, `VisibleSource` e enums) só declaram campos seguros, pelo que os campos
  internos (sourceCore, hiddenDiagnostics, internalDiagnostics, notesInternal, excerpt, IDs
  técnicos) não têm forma de serem apresentados. O componente **não** está ligado a nenhuma
  rota nem faz chamadas à API (respeita o guard rail do cliente HTTP): não existe UI activa a
  consumir `/admin/ai/ask`, por isso a validação foi feita por `npm run build` (typecheck +
  build verdes), sem validação visual possível. Sem alterações ao backend, migrations, dados
  ou providers externos. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md) §12.F.
- **D10 — concluída (testes de cenários críticos):** `DocumentedAnswerCriticalScenariosTest`
  compõe os serviços **reais** (`SourceAssessmentService` → `AnswerDecisionService` →
  `DocumentedTaxiaAnswerMapper` → `AnswerProjectionService`) e testa a **filosofia do
  produto** (C1–C9, D1–D9), não só a mecânica: fonte forte → consulta documentada limpa;
  actualidade incerta preserva o tipo mas assinala prudência; **resposta-limite** e **pedido
  de parecer** nunca são erro nem não-resposta; fontes `OUTDATED`/fracas/externas/derivadas
  não sustentam conclusão limpa; `EXTERNAL`/`DEMO` ocultam bastidores enquanto preservam
  limitações/avisos/parecer; `INTERNAL`/`CURATION_ONLY` preservam o diagnóstico adequado; a
  **projecção nunca recalcula** a decisão (nem com fontes fortes); `aggregatedRiskLevel` não é
  inventado a partir do `riskLevel` da entidade; e `KnowledgeCurationStatus.OUTDATED` não se
  confunde com `FreshnessStatus.OUTDATED`. Sem infra de testes frontend no backoffice — a
  apresentação é validada por `npm run build` (o `DocumentedAnswerPanel` já não renderiza
  campos internos por construção dos tipos). Sem alterações a código funcional, migrations,
  dados ou providers. Suite mínima: **87 testes verdes**. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md) §12.G.
- **D11 — concluída (diagnóstico interno):** `InternalDiagnostics` (record) +
  `InternalDiagnosticsBuilder` (`@Service`) descrevem o **caminho técnico** até à conclusão —
  «a resposta externa mostra a conclusão profissional; o diagnóstico interno mostra o caminho
  técnico até lá». É **só runtime**: não persiste, não cria coluna, não faz auditoria em BD nem
  logs sensíveis. **Observa, não decide** — lê `answerType`/`parecerRequirement` da
  `AnswerDecision`, não recalcula (regras 22–24). O campo
  `DocumentedTaxiaAnswer.internalDiagnostics` passou de `String` para `InternalDiagnostics`
  (alteração compatível e contida; contrato do `AdminAIController` preservado, `documentedAnswer`
  e `projectedAnswer` intactos). Recolhe sinais de decisão, fontes, risco (assinala a ausência de
  risco agregado real), actualidade, diversidade e projecção, mais `hiddenForExternal` (só
  nomes/tipos) e `warningsInternal` — **nunca** valores sensíveis (chunks, scores, ranking,
  prompts, logs, notas internas, IDs). `EXTERNAL`/`DEMO` **nunca** o expõem; `INTERNAL`/
  `CURATION_ONLY` preservam-no. Novo `InternalDiagnosticsBuilderTest` (inclui teste de
  não-fuga de valores sensíveis); testes existentes actualizados. Sem frontend. Suite mínima:
  **98 testes verdes**. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md) §12.H.

**Bloco D — conceptualmente fechado com D11.** O modelo de resposta profissional documentada
está materializado de ponta a ponta (DTO/enums → avaliação de fontes → decisão de prudência →
projecção por visibilidade → resolução de lente → apresentação → cenários críticos →
diagnóstico interno). O **próximo passo** natural é a afinação fina *dentro* deste bloco —
scoring/thresholds definitivos, risco agregado real, auditoria persistida — e a preparação da
importação real dos casos do piloto (Etapa 9B), **sem** reabrir C1–C9 nem abrir novo bloco
conceptual.

A **materialização técnica** do Bloco C (thresholds, scoring, ranking, enums
definitivos, limiares por sinal) vive **aqui**, no Bloco D, e **não** abre novo bloco
conceptual.

## Bloco E — Ingestão e curadoria assistida da base de conhecimento

> **Fase seguinte ao fecho técnico do Bloco D (D1–D11).** Os Blocos C e D permanecem
> fechados; o Bloco E **não reabre** nenhuma das suas decisões — define **como alimentar** a
> base de conhecimento em escala sem degradar a resposta profissional documentada. Documento-base:
> [taxia-assisted-ingestion-curation-policy.md](taxia-assisted-ingestion-curation-policy.md).

Princípio central: **a ingestão pode ser automática; a publicação não tem de ser sempre humana;
mas tem de ser sempre governada.** Publicação livre/indiscriminada é **proibida**; publicação
automática **controlada** é possível apenas para casos limpos (fonte oficial, baixo risco,
suporte forte, actualidade aceitável, sem conflito, sem duplicado enganador, critérios
auditáveis e reversíveis); havendo risco, incerteza, conflito, baixa qualidade documental,
actualidade duvidosa ou impacto material, exige-se validação **assistida** ou **manual**.

Decisões conceptuais do Bloco E:

- **E1 — concluída (documentação/política):** política de ingestão e curadoria assistida —
  importar não é publicar; FAQs oficiais da AT como primeira fonte de escala; caso publicado
  exige `technicalAnswer` própria; ligação ao fundamento legal; publicação governada por
  critérios (automática controlada / assistida / manual); curadoria assistida por sistema;
  separação dos eixos de ingestão/curadoria/publicação/actualidade (com a regra
  `KnowledgeCurationStatus.OUTDATED` ≠ `FreshnessStatus.OUTDATED`); lotes pequenos auditáveis
  (20–50 FAQs); detecção de duplicados/ecos (C9); só a publicação aciona indexação/RAG. Sem
  código, pipeline, embeddings ou publicação. Ver
  [taxia-assisted-ingestion-curation-policy.md](taxia-assisted-ingestion-curation-policy.md).
- **E2 — concluída (documentação/inventário):** inventário do pipeline actual de
  ingestão FAQ AT — camada RAW (`at_faq_raw_items`), quarentena na camada Q&A
  (`KnowledgeQuestionAnswer` em `IMPORTED`), curadoria, publicação governada por guards
  (`isEligibleForRag`: validado + `technicalAnswer` + fonte + validade + revisão de
  HIGH/CRITICAL), indexação só na publicação e RAG/*grounding*. Identifica as lacunas
  face ao Bloco E (sem unidade de lote curável, sem pré-curadoria automática, sem
  publicação automática controlada por critérios), os riscos técnicos e o âmbito de E3.
  Sem código, dados, migrações ou testes alterados. Ver
  [taxia-faq-at-ingestion-pipeline-inventory.md](taxia-faq-at-ingestion-pipeline-inventory.md).
- **E3 — concluída (documentação/contrato):** contrato técnico conceptual do lote de
  ingestão FAQ AT — define `IngestionBatch` como unidade auditável de governação (não
  importação em massa), os modos conceptuais (`DISCOVER`/`DRY_RUN`/`IMPORT` existentes +
  `PRE_CURATE`/`REVIEW`/`PUBLISH_GOVERNED` futuros), estados do lote, `RawBatchItem`,
  `PreCuratedBatchItem`, `SourceCandidate`, `PublicationClassification`
  (`AUTO_CONTROLLED`/`ASSISTED`/`MANUAL_REQUIRED`/`NOT_PUBLISHABLE`, sem *scoring*),
  `BatchReport`, idempotência, reversão/neutralização, e a separação obrigatória de
  eixos (com `KnowledgeCurationStatus.OUTDATED` ≠ `FreshnessStatus.OUTDATED`). Sem
  código, migrações, embeddings ou publicação. Ver
  [taxia-faq-at-ingestion-batch-contract.md](taxia-faq-at-ingestion-batch-contract.md).
- **E4 — concluída (implementação/simulação):** primeiro lote técnico controlado sobre
  *fixtures* locais que produz um `AtFaqBatchReport` auditável, provando o fluxo de
  governação **sem tocar no reino publicável** (`published`/`indexed` sempre 0). Novo
  subpacote `com.knowledgeflow.ingestion.atfaq.batch`: enum `AtFaqBatchPublicationPath`
  (`AUTO_CONTROLLED`/`ASSISTED`/`MANUAL_REQUIRED`/`NOT_PUBLISHABLE`, sem *scoring*, regra
  mais-restritivo-ganha), *records* `AtFaqControlledBatchItem`, `AtFaqBatchReport`,
  `AtFaqBatchReportTotals`, `AtFaqBatchItemSummary`, e o serviço em memória
  `AtFaqControlledBatchService` (reutiliza `AtFaqNormalizer` para `contentHash`/
  normalização; detecta duplicados por ordem de ocorrência e conflitos por pergunta com
  respostas divergentes; execução determinística/idempotente). *Fixtures* Java em
  `ControlledBatchFixtures` (6 itens cobrindo limpo/sem-fundamento-legal/duplicado/
  conflito/alto-risco/sem-resposta-técnica) e 15 testes em
  `AtFaqControlledBatchServiceTest`. Sem *scraping*, sem HTTP externo, sem provider
  externo, sem BD, sem embeddings, sem publicação, sem indexação, sem migrações, sem
  endpoints, sem frontend. Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md).
- **E5 — concluída (implementação/pré-curadoria):** camada de pré-curadoria automática
  **determinística** sobre o lote E4, no mesmo subpacote
  `com.knowledgeflow.ingestion.atfaq.batch`. Novos *records* `AtFaqPreCurationSourceCandidate`,
  `AtFaqPreCuratedBatchItem`, `AtFaqPreCurationTotals`, `AtFaqPreCurationResult` e serviço
  `AtFaqPreCurationService`: geram propostas de curadoria por item (resposta curta/técnica,
  `KnowledgeTopic`, risco, fontes candidatas FAQ+legislação com `AuthorityLevel`/
  `SourceQuality`/`SourceRole`/`SourceDiversity`, referências legais, `FreshnessStatus`, via
  de publicação). Regras: resposta técnica **nunca inventada** (sem LLM); actualidade
  `UNCERTAIN` por defeito (`OUTDATED`/`CURRENT` só com marcador explícito); via de publicação
  **herda E4 e só sobe em prudência** (mais-restritivo-ganha). `AtFaqControlledBatchItem`
  **não alterado**. 16 testes em `AtFaqPreCurationServiceTest` (+ *fixture* opcional
  `outdatedMarked()`). **Pré-curado não é publicado, não é indexado, não entra no RAG**; sem
  BD, HTTP, *scraping*, *providers* externos, *embeddings*, endpoints, frontend ou migrações.
  Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
  (secção E5).
- **E6 — concluída (revisão governada em memória):** `AtFaqReviewDecisionType`,
  `AtFaqReviewDecision`, `AtFaqReviewItemResult`, `AtFaqReviewTotals`, `AtFaqReviewResult`
  e `AtFaqReviewService` decidem apenas o próximo portão sobre propostas E5. A regra
  mais-restritivo-ganha impede promoções indevidas; ausências, duplicados e identificadores
  desconhecidos têm tratamento determinístico e auditável. `AtFaqReviewServiceTest` cobre
  os cenários críticos. Sem BD, endpoints, frontend, providers, publicação, indexação ou
  embeddings; `published=0` e `indexed=0` sempre. Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
  (secção E6).
- **E7 — concluída (plano de publicação governada, sem execução real):**
  `AtFaqGovernedPublicationReadiness`, `AtFaqGovernedPublicationGuardResult`,
  `AtFaqGovernedPublicationCandidate`, `AtFaqGovernedPublicationTotals`,
  `AtFaqGovernedPublicationPlan` e `AtFaqGovernedPublicationPlanService` cruzam a
  pré-curadoria E5 com a revisão E6 e reaplicam, por item, todas as guardas de publicação
  futura. Planear não é publicar: o plano vive em memória, não cria
  `KnowledgeQuestionAnswer`/`KnowledgeSourceReference` persistidos, não chama
  `PublicationService` nem `EmbeddingIndexer`, e não toca em migrations, endpoints ou
  frontend; `published=0` e `indexed=0` sempre. `AtFaqGovernedPublicationPlanServiceTest`
  cobre os cenários críticos. Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
  (secção E7).
- **E8A — concluída (materialização governada sem publicação):** candidatos E7
  `READY_FOR_FUTURE_PUBLICATION` são convertidos em drafts Q&A curáveis, determinísticos e
  idempotentes, exclusivamente em memória. O estado de curadoria é `IMPORTED`; não há
  persistência, publicação, indexação, embeddings, RAG, endpoints, frontend ou chamadas
  externas. `published=0` e `indexed=0` são invariantes.
- **E8B.1 — concluída (executor DRY-RUN de publicação governada):** o
  `AtFaqGovernedPublicationDryRunExecutor` recebe o resultado da E8A, reaplica os guardas sobre
  o draft já transportado em cada item materializado e produz comandos de publicação
  **simulados** (`would*`), com `wouldIndex` sempre `false`. Zero efeitos reais: `persisted=0`,
  `published=0`, `indexed=0`; nada persistido, publicado ou indexado; nenhum
  `PublicationService`/`EmbeddingIndexer` chamado; sem BD, migrations, endpoints, frontend ou
  chamadas externas. `AtFaqGovernedPublicationDryRunExecutorTest` cobre os cenários. Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
  (secção E8B.1).
- **E8B.2 — concluída (persistência governada de drafts em BD isolada, sem publicação):** o
  `AtFaqGovernedDraftPersistenceService` recebe o resultado da E8A e o relatório DRY-RUN da E8B.1,
  reaplica os guardas e persiste os drafts limpos como `KnowledgeQuestionAnswer` em estado
  `IMPORTED` (draft curável, não publicável) mais as respectivas `KnowledgeSourceReference`, sobre
  uma BD isolada de teste (Testcontainers) e uma `Organization` de teste. Persistir draft não é
  publicar: `publishedAt`/`publishedBy` nulos, `published=0`, `indexed=0`, `embeddings=0`, nenhum
  caso elegível para RAG; persistência idempotente (sem nova migration); nenhum
  `PublicationService`/`EmbeddingIndexer` chamado; sem migrations, endpoints, frontend, chamadas
  externas ou base piloto real. A classificação de autonomia futura é registada para preparar a
  publicação automática governada de E8B.3. `AtFaqGovernedDraftPersistenceServiceIT` cobre os
  cenários sobre PostgreSQL real. Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
  (secção E8B.2).
- **E8B.3-prep — concluída (inventário do acoplamento publicação-indexação):** inventário
  técnico-documental da ligação entre publicação, indexação, embeddings e RAG, antes da E8B.3
  real. Classificação: **Caso B** (a publicação chama a indexação de forma síncrona e atómica),
  **com mitigação nativa por profile** — em `test`/`pgtest` o indexador é o
  `StubKnowledgeQaEmbeddingIndexer` (no-op), pelo que `publish(...)` corre sem gerar embeddings
  reais. Conclusão: E8B.3 pode publicar realmente em BD isolada sem alterar produção; o gargalo
  real é a transição de curadoria `IMPORTED → VALIDATED`, não o acoplamento de indexação. Tarefa
  apenas de leitura/documentação (sem código, testes, migrations, endpoints, frontend, BD real ou
  runtime). Ver
  [taxia-publication-indexing-coupling-inventory.md](taxia-publication-indexing-coupling-inventory.md).
- **E8B.3 — concluída (publicação governada real em BD isolada, sem indexação efectiva):** o
  `AtFaqGovernedPublicationExecutor` recebe o `AtFaqDraftPersistenceResult` da E8B.2, promove de
  forma governada `IMPORTED → VALIDATED` pela API de domínio real (`markPendingReview()` +
  `validate(...)`, sem reflexão nem atalhos) e chama o `KnowledgeQuestionAnswerPublicationService.publish(...)`
  **real** — mas sobre BD isolada (Testcontainers) e profile `pgtest`, onde o indexador activo é o
  `StubKnowledgeQaEmbeddingIndexer` (no-op). Resultado: só o item limpo, elegível para autonomia
  futura, chega a `publishedAt`/`publishedBy` em estado `VALIDATED`, com
  `COUNT(knowledge_qa_embeddings) == 0` — a entidade é `isEligibleForRag()`, mas **sem embedding não
  é recuperável** pelo RAG. Cobertos: idempotência (segunda execução = *já publicado*, sem
  `CONFLICT`) e todas as guardas negativas (autonomia futura ausente, intervenção humana, risco ≠
  `LOW`, sem fonte oficial, sem referência legal, organização errada). Nenhum embedding real; nada
  em produção; sem migrations, endpoints, frontend, chamadas externas ou base piloto real.
  `KnowledgeQaEmbeddingIndexerImpl`, `RagSearchService` e `GroundingService` intactos.
  `AtFaqGovernedPublicationExecutorIT` cobre os cenários sobre PostgreSQL real. As classes de
  execução usam o infixo `Execution` para não colidirem com a família E7 do plano. Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
  (secção E8B.3).
- **E9A — concluída (indexação efectiva de um único Q&A publicado em BD isolada):** o
  `AtFaqGovernedRagIndexingService` recebe o `AtFaqGovernedPublicationExecutionResult` da E8B.3 e dá
  **voz controlada a exactamente um** Q&A publicado, `VALIDATED`, `LOW` e RAG-elegível, escrevendo o
  seu embedding pelo contrato real `KnowledgeQaEmbeddingIndexer`. No IT (`pgtest`/Testcontainers) o
  indexador injectado é o `KnowledgeQaEmbeddingIndexerImpl` **real**, alimentado por um
  `EmbeddingService` determinístico de teste (768 dim, sem modelo real nem chamadas externas) —
  provando o ciclo RAG/pgvector genuíno: antes de E9A `COUNT(knowledge_qa_embeddings) == 0`, depois
  **exactamente 1** linha, e o `RagSearchService` real recupera esse Q&A para uma pergunta
  semanticamente compatível (similaridade ≈ 1.0). Cobertos: idempotência (reindexar faz *upsert* →
  continua 1 linha), política single-Q&A (vários publicados ⇒ indexa 1 e difere os restantes para
  E9B, **sem lote**) e todas as guardas negativas (não publicado, entidade `IMPORTED`/não publicada,
  organização errada, risco ≠ `LOW` mesmo quando a entidade se diz RAG-elegível) e higiene do
  relatório (sem vector bruto, HTML, prompts ou chunks). Nada em produção; sem migrations, endpoints,
  frontend, scheduler, chamadas externas ou base piloto real. `KnowledgeQaEmbeddingIndexerImpl`,
  `RagSearchService` e `GroundingService` intactos. `AtFaqGovernedRagIndexingServiceIT` cobre os
  cenários sobre PostgreSQL real. Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
  (secção E9A).
- **E9B — concluída (lote governado pequeno de indexação em BD isolada):** o mesmo
  `AtFaqGovernedRagIndexingService` ganha, de forma **aditiva**, o método `indexSmallPublishedBatch(...)`
  (o `indexSinglePublishedQa(...)` da E9A fica intacto). Dá voz controlada a um **lote pequeno** de
  Q&A publicados — **mais do que um, no máximo três** (`maxItems ∈ [2,3]`; `maxItems < 2` recusa como
  configuração inválida, `> 3` excede o teto) — sob as **mesmas guardas por Q&A** do caso único. Os
  itens elegíveis acima do limite são **diferidos** (reportados, nunca descartados em silêncio). Novo
  modo `SMALL_BATCH_TEST_ISOLATED` (o `TEST_ISOLATED_SINGLE_QA` mantém-se); os *caps* do
  `AtFaqRagIndexingTotals` passaram a ser **por modo** (single ≤ 1, lote ≤ 3), preservando o cap
  estrutural do caso único; campos aditivos `requestedMaxItems`/`effectiveMaxItems` no resultado. No IT
  (`pgtest`/Testcontainers) o indexador é o `KnowledgeQaEmbeddingIndexerImpl` **real** com o mesmo
  `EmbeddingService` determinístico (768 dim): antes 0 embeddings, depois **exactamente N** linhas
  (1 < N ≤ 3), uma por Q&A; o `RagSearchService` real recupera **os indexados** (validado por **pertença
  ao conjunto**, não por ordem, porque o embedding determinístico dá similaridade ≈ 1.0); idempotência
  (reindexar o lote mantém N); limite (4 elegíveis + `maxItems=3` ⇒ indexa 3, difere 1); mesmas guardas
  negativas do caso único (não publicado, `IMPORTED`, organização errada, risco ≠ `LOW`); higiene do
  relatório. Nada em produção; sem migrations, endpoints, frontend, scheduler, chamadas externas ou base
  piloto real. `KnowledgeQaEmbeddingIndexerImpl`, `RagSearchService` e `GroundingService` intactos.
  `AtFaqGovernedRagBatchIndexingServiceIT` (13 testes) cobre os cenários; a E9A (12 testes) continua
  verde. Ver
  [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
  (secção E9B).
- **E9C — ainda não iniciada** (lote real/piloto controlado de indexação/RAG).
- **E10 — ainda não iniciada** (rollback/despublicação/desindexação).

> **Nota.** A numeração de tarefas técnicas E2–E10 **não** se confunde com as decisões
> conceptuais E1–E10 da política (§3–§12 do documento-base).

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

# TaxIA — Bloco E — E4 — Lote controlado FAQ AT com BatchReport (implementação)

> Frase-mestra: **"Simular governação antes de mexer no reino publicável."**

E4 é a **primeira tarefa de implementação** do Bloco E. Materializa o contrato conceptual de
E3 ([taxia-faq-at-ingestion-batch-contract.md](taxia-faq-at-ingestion-batch-contract.md))
numa simulação técnica controlada que corre sobre *fixtures* locais e produz um
`AtFaqBatchReport` auditável — **sem tocar no reino publicável**.

## 1. Objectivo

Provar o fluxo de governação de um pequeno lote FAQ AT (normalizar → detectar
duplicados/conflitos → propor via de publicação → relatório) **sem publicar e sem indexar**,
de forma determinística e auditável.

## 2. Âmbito e não-âmbito

**Dentro:** classes novas num subpacote dedicado, *fixtures* locais, serviço em memória,
relatório auditável, testes.

**Fora (invariantes de E4):**

- *scraping* real ou chamadas a URLs externas;
- chamadas a *providers* externos (Anthropic ou outros);
- acesso a base de dados ou HTTP;
- publicação de `KnowledgeQuestionAnswer`;
- geração de *embeddings* ou indexação RAG;
- alteração de `KnowledgeQuestionAnswerPublicationService`, `RagSearchService`,
  `GroundingService`, migrações, endpoints ou frontend;
- *scoring* numérico ou limiares;
- reabertura de Bloco C, Bloco D ou E1–E3.

## 3. Pacote e classes criadas

Subpacote novo: `com.knowledgeflow.ingestion.atfaq.batch` (não perturba o pipeline
existente `com.knowledgeflow.ingestion.atfaq`, que continua desactivado por defeito).

| Classe | Tipo | Papel |
| --- | --- | --- |
| `AtFaqBatchPublicationPath` | enum | Via de publicação **proposta** (não acção): `AUTO_CONTROLLED`, `ASSISTED`, `MANUAL_REQUIRED`, `NOT_PUBLISHABLE`. Sem *scoring*; expõe `mostRestrictive(...)`. |
| `AtFaqControlledBatchItem` | record | Item de entrada controlado (vindo de *fixture*). Nunca é obtido por *fetch*; `sourceUrl` é apenas texto. |
| `AtFaqBatchReport` | record | Unidade auditável de governação: id determinístico, modo, estado, totais, avisos, erros bloqueantes, sumários por item, próximas acções. |
| `AtFaqBatchReportTotals` | record | Contadores agregados. `published` e `indexed` **sempre 0** em E4. |
| `AtFaqBatchItemSummary` | record | Resultado por item (seguro para *log*): pergunta normalizada, `contentHash`, via proposta, razões, avisos. Sem HTML bruto, sem *chunks*, sem *prompts*. |
| `AtFaqControlledBatchService` | @Service | Simulação em memória. Reutiliza `AtFaqNormalizer` para `contentHash`/normalização. |

## 4. Regras de classificação (mais-restritivo-ganha)

Ordem: **`NOT_PUBLISHABLE` > `MANUAL_REQUIRED` > `ASSISTED` > `AUTO_CONTROLLED`**.

- **`NOT_PUBLISHABLE`** — sem pergunta/resposta utilizável; sem resposta técnica curada; sem
  fonte identificável; duplicado exacto de item anterior no lote.
- **`MANUAL_REQUIRED`** — risco `HIGH`/`CRITICAL`; conflito (marcador explícito ou mesma
  pergunta com resposta divergente); fonte não oficial; actualidade `OUTDATED`; marcador
  manual explícito.
- **`ASSISTED`** — risco `MEDIUM`; sem fundamento legal claro; actualidade `UNCERTAIN`;
  possível duplicado não bloqueante.
- **`AUTO_CONTROLLED`** — só se **todos**: fonte oficial + risco `LOW` + resposta técnica +
  fundamento legal + sem duplicado + sem conflito + actualidade aceitável (`CURRENT` ou
  `STABLE_BUT_OLD`). Continua a exigir governação humana antes de qualquer publicação real
  (E7). Se algum critério positivo faltar, o *fallback* defensivo é `ASSISTED` — nunca
  aprovação automática silenciosa.

## 5. Duplicados e conflitos

- **Duplicado exacto** — mesma `contentHash` de um item anterior no lote (primeira ocorrência
  é canónica). Detecção **sensível à ordem**: um item limpo mantém-se `AUTO_CONTROLLED`
  mesmo que uma cópia sua apareça mais à frente.
- **Duplicado possível (não bloqueante)** — mesmo `externalId` ou `sourceUrl` de um item
  anterior, sem ser cópia exacta → `ASSISTED` com aviso.
- **Conflito** — marcador explícito na *fixture*, ou a mesma pergunta normalizada a aparecer
  com mais do que uma `contentHash` distinta no lote.

## 6. Idempotência e determinismo

- `contentHash` e `normalizedQuestion` são estáveis (reutilizam `AtFaqNormalizer`).
- `batchId` é derivado deterministicamente das `contentHash` ordenadas (`atfaq-batch-<hex16>`).
- O serviço aceita um `Clock` (construtor de teste) para tornar todo o relatório
  reproduzível; reprocessar o mesmo *input* produz um relatório igual.

## 7. Fixtures

`ControlledBatchFixtures` (Java test builders, em vez de ficheiro JSON) fornece 6 itens que
cobrem o espectro de governação: limpo (→ `AUTO_CONTROLLED`), válido sem fundamento legal
(→ `ASSISTED`), duplicado exacto (→ `NOT_PUBLISHABLE`), conflito assinalado
(→ `MANUAL_REQUIRED`), alto risco (→ `MANUAL_REQUIRED`) e sem resposta técnica
(→ `NOT_PUBLISHABLE`). Todos os `sourceUrl` são fictícios/locais e nunca são obtidos.

## 8. Testes

`AtFaqControlledBatchServiceTest` (15 testes, unitário puro, sem contexto Spring): relatório
completo do lote de 6 itens; `published == 0` e `indexed == 0`; partição total por via;
item limpo → `AUTO_CONTROLLED`; sem fundamento legal → `ASSISTED`; duplicado exacto →
`NOT_PUBLISHABLE` (nunca `AUTO_CONTROLLED`); conflito → `MANUAL_REQUIRED` (nunca
`AUTO_CONTROLLED`); alto risco → `MANUAL_REQUIRED`; sem resposta técnica →
`NOT_PUBLISHABLE`; mais-restritivo-ganha; item estruturalmente inválido → `failed` +
erro bloqueante; `contentHash`/`normalizedQuestion` estáveis; `batchId` determinístico;
reprocessamento idempotente; lote vazio válido.

Comando: `mvn -o test -Dtest=AtFaqControlledBatchServiceTest` → **15/15 verdes**.

## 9. Passo seguinte após E4 (E5)

E5 = **pré-curadoria automática**: a partir deste relatório, propor `normalizedQuestion`,
`shortAnswer`/`technicalAnswer`, `topic`, `riskLevel` e candidatos de fonte para os itens,
mantendo tudo em quarentena e **sem publicar**. E4 continua a ser apenas simulação: os modos
`PRE_CURATE`/`REVIEW`/`PUBLISH_GOVERNED` continuam por implementar.

---

# E5 — Pré-curadoria automática controlada

> Frase-mestra: **"A pré-curadoria prepara o caso. Não o autoriza a responder."**

E5 acrescenta, sobre o lote E4, uma camada de **pré-curadoria automática determinística**:
produz uma **proposta estruturada de curadoria** por item, sem publicar, sem indexar, sem
*embeddings*, sem BD, sem HTTP e **sem LLM**. A proposta não é decisão final.

## E5.1 Classes criadas (mesmo subpacote `com.knowledgeflow.ingestion.atfaq.batch`)

| Classe | Tipo | Papel |
| --- | --- | --- |
| `AtFaqPreCurationSourceCandidate` | record | Candidato de fonte **proposto** (FAQ oficial e/ou legislação): `KnowledgeSourceType`, `AuthorityLevel`, `SourceQuality`, `SourceRole`, `sourceCore`, `SourceDiversity`, `FreshnessStatus`, `official`, `primary`, `warnings`. Nunca é obtido/validado *online*. |
| `AtFaqPreCuratedBatchItem` | record | Proposta por item: `proposedShortAnswer`/`proposedTechnicalAnswer`, `proposedTopic` (`KnowledgeTopic`), `proposedRiskLevel`, `proposedSources`, `proposedLegalReferences`, `proposedFreshnessStatus`, `proposedPublicationPath`, `duplicateCandidates`, `conflictCandidates`, `confidenceSignals`, `requiredReviewReason`, `eligibleForAutoControlledCandidate`. |
| `AtFaqPreCurationTotals` | record | Contadores agregados (sem `published`/`indexed` por construção). |
| `AtFaqPreCurationResult` | record | Resultado: `batchId` (herdado de E4), `generatedAt`, `totals`, `items`, `globalWarnings`, `nextActions`. Sem HTML bruto, sem *chunks*, sem *prompts*. |
| `AtFaqPreCurationService` | @Service | Motor determinístico. Recebe o `AtFaqBatchReport` (e/ou a lista original) e produz o `AtFaqPreCurationResult`. |

`AtFaqControlledBatchItem` **não foi alterado** — já continha todos os campos necessários
(`legalReference`, `riskLevel`, `topic`, `officialSource`, `freshnessStatus`, marcadores de
conflito/manual), pelo que E4 fica intacto.

## E5.2 Regras de geração (determinísticas, sem invenção)

- **`proposedShortAnswer`** — resumo simples e determinístico da resposta existente
  (original ou técnica), truncado no fim de frase ou em fronteira de palavra (≤ 240 chars);
  nunca inventado; `null` + aviso quando não há resposta base.
- **`proposedTechnicalAnswer`** — resposta técnica *verbatim* se existir; caso contrário
  `null` + aviso. **Nunca gerada por LLM nem inventada.**
- **`proposedTopic`** — do tema explícito ou inferência simples por palavras-chave
  (`IVA`/`IRC`/`IRS`/…); *fallback* conservador `OUTROS` + aviso.
- **`proposedJurisdiction`** — `"PT"` para FAQ AT.
- **`proposedRiskLevel`** — valor explícito do item; `MEDIUM` por defeito; **nunca baixado
  por heurística**.
- **`proposedLegalReferences`** — referência explícita, ou detecção por padrões simples
  (`CIVA`/`CIRS`/`CIRC`/`CIMI`/…, `artigo N.º`, `art.`); vazio + aviso quando ausente.
- **`proposedSources`** — sempre um candidato FAQ AT quando há fonte; um candidato
  `LEGISLATION` por referência legal; `sourceCore` derivado da URL ou da referência
  normalizada; `SourceDiversity`: `MATERIAL_DIVERSITY` quando FAQ + legislação coexistem,
  `SAME_CORE` quando duplicado, `MIXED_OR_UNCLEAR` por defeito.
- **`proposedFreshnessStatus`** — `UNCERTAIN` por defeito; `OUTDATED`/`CURRENT` **apenas**
  com marcador explícito na *fixture*; nunca inferido de data isolada, nunca validado
  *online*.
- **`proposedPublicationPath`** — **herda a classificação E4 e só pode subir prudência,
  nunca descer**: sem resposta técnica → `NOT_PUBLISHABLE`; `OUTDATED` → ≥ `MANUAL_REQUIRED`;
  conflito → `MANUAL_REQUIRED`; falta de fundamento legal só transforma `AUTO_CONTROLLED` em
  `ASSISTED`.
- **`requiredReviewReason`** — preenchido sempre que a via ≠ `AUTO_CONTROLLED`, com a causa
  principal (falta resposta técnica / conflito / duplicado / risco / actualidade / falta
  fundamento legal / fonte não oficial).
- **`confidenceSignals`** — sinais textuais simples (fonte oficial, resposta técnica,
  referência legal, diversidade FAQ+legislação, risco explícito, duplicado, conflito).

## E5.3 Invariantes

- Pré-curado **não é publicado**, **não é indexado**, **não entra no RAG**.
- Nenhum `KnowledgeQuestionAnswer` persistido; nenhum *embedding*; nenhuma chamada a IA/HTTP/BD.
- Os *records* E5 **não têm** campos `published`/`indexed` (garantia em tempo de compilação).
- Resultado sem HTML bruto, sem *chunks*, sem *prompts*.
- Determinístico: mesmo *input* + `Clock` fixo → resultado idêntico (`batchId` herdado de E4).

## E5.4 Testes

`AtFaqPreCurationServiceTest` (16 testes): resultado completo do lote; `totalItems`/
`preCurated` correctos; item limpo mantém `AUTO_CONTROLLED`; sem fundamento legal →
`ASSISTED` com `requiredReviewReason`; alto risco → `MANUAL_REQUIRED`; sem resposta técnica →
`NOT_PUBLISHABLE` e resposta técnica **não inventada**; duplicado nunca `AUTO_CONTROLLED`;
conflito nunca `AUTO_CONTROLLED`; `freshness` `UNCERTAIN` por defeito; `OUTDATED`/`CURRENT`
só com marcador explícito; candidato FAQ sempre criado; candidato legal criado quando há
referência; `SourceDiversity` material quando FAQ+legislação coexistem; resultado sem
HTML/*prompts*/*chunks*; execução determinística; totais consistentes.

*Fixtures* reutilizam `ControlledBatchFixtures` (lote de 6 itens de E4, intacto) e um item
opcional `outdatedMarked()` (7.º, marcador `OUTDATED` explícito, fora do lote de 6).

Comando: `mvn -o test -Dtest=AtFaqControlledBatchServiceTest,AtFaqPreCurationServiceTest` →
**31/31 verdes** (15 E4 + 16 E5).

## E5.5 Passo seguinte (E6)

E6 = **ecrã/relatório de revisão do lote**: apresentar estas propostas a um curador humano
(agrupadas por via de publicação, com razões e sinais), mantendo tudo em quarentena. E5
continua a ser apenas proposta: os modos `REVIEW`/`PUBLISH_GOVERNED` continuam por
implementar.

---

# E6 — Revisão governada das propostas

> Frase-mestra: **"Rever não é publicar. É decidir o próximo portão."**

E6 materializa, ainda exclusivamente em memória, a revisão das propostas E5. Não existe
UI, endpoint, persistência, publicação, indexação, embedding ou chamada externa.

## E6.1 Tipos e serviço

- `AtFaqReviewDecisionType`: aceitar candidato automático controlado futuro, encaminhar
  para revisão assistida, exigir revisão manual, rejeitar ou diferir. Nenhum valor publica.
- `AtFaqReviewDecision`: decisão por `externalId`, revisor, razão e notas não sensíveis.
- `AtFaqReviewItemResult`: caminho proposto e resultante, flags de aceitação futura,
  revisão humana, rejeição e diferimento, com avisos e bloqueios.
- `AtFaqReviewTotals`: contadores auditáveis, incluindo `published=0` e `indexed=0`.
- `AtFaqReviewResult`: resultado do lote, instante, revisor, itens e próximas ações.
- `AtFaqReviewService`: aplica decisões por regras determinísticas e conservadoras.

## E6.2 Regras de governação

- O caminho resultante nunca é menos restritivo que o caminho proposto por E5.
- Só um item `AUTO_CONTROLLED`, sem `requiredReviewReason`, pode ser aceite como candidato
  a futura publicação automática controlada.
- Tentativas de promover `ASSISTED`, `MANUAL_REQUIRED` ou `NOT_PUBLISHABLE` para
  `AUTO_CONTROLLED` ficam bloqueadas e preservam o caminho mais prudente.
- Uma decisão ausente é `DEFER`; um `AUTO_CONTROLLED` diferido sobe para `ASSISTED`.
- Decisões duplicadas são resolvidas de modo determinístico pela mais restritiva, com aviso.
- Decisões para identificadores inexistentes são ignoradas com aviso global.
- Razão vazia numa decisão forte produz aviso explícito.
- `acceptedForFuturePublication=true` significa apenas elegibilidade para o portão E7;
  nunca significa publicado ou indexado.

## E6.3 Invariantes e testes

`AtFaqReviewServiceTest` cobre resultado completo, aceitação limpa, promoções proibidas,
decisão ausente, duplicada e desconhecida, razão vazia, regra mais restritiva, flags de
revisão/rejeição/diferimento, determinismo com `Clock` fixo e ausência de HTML bruto,
prompts e chunks. Em todas as decisões, `published == 0` e `indexed == 0`.

Próximo passo: E7 poderá consumir apenas candidatos explicitamente aceites e voltar a
aplicar os guards de publicação. E6 não executa esse passo.

---

# E7 — Plano de publicação governada (sem execução real)

> Frase-mestra: **"Planear publicação não é publicar. É provar que nada passa sem guardas."**

E7 cruza a pré-curadoria E5 (`AtFaqPreCurationResult`) com a revisão E6
(`AtFaqReviewResult`) e produz um **plano** de publicação futura, exclusivamente em
memória. Não há UI, endpoint, persistência, publicação, indexação, embedding, chamada
externa nem qualquer uso de `KnowledgeQuestionAnswerPublicationService` ou
`KnowledgeQaEmbeddingIndexerImpl`. Planear não é publicar.

## E7.1 Classes e serviço

- `AtFaqGovernedPublicationReadiness` (enum): `READY_FOR_FUTURE_PUBLICATION`,
  `NEEDS_ASSISTED_REVIEW`, `NEEDS_MANUAL_REVIEW`, `BLOCKED`, `DEFERRED`. Nenhum valor
  publica nem indexa.
- `AtFaqGovernedPublicationGuardResult`: detalhe da avaliação de guardas — guardas
  passadas, falhadas, avisos e razões bloqueantes.
- `AtFaqGovernedPublicationCandidate`: entrada de plano por item, com caminho proposto/
  resultante, prontidão, flags, respostas propostas (de fixtures), referências legais,
  resumos de fontes e próximas acções. Nunca HTML bruto, prompts nem chunks.
- `AtFaqGovernedPublicationTotals`: contadores auditáveis, com `published=0` e `indexed=0`.
- `AtFaqGovernedPublicationPlan`: plano do lote, instante, autor, totais, candidatos,
  avisos globais, bloqueios e próximas acções.
- `AtFaqGovernedPublicationPlanService`: aplica os guards de forma determinística
  (`Clock` injectável), sem efeitos colaterais.

## E7.2 Guardas para `READY_FOR_FUTURE_PUBLICATION`

Um candidato só fica pronto para publicação futura se **todas** as guardas passarem:
decisão de revisão presente; aceite para publicação futura; caminho resultante da revisão
`AUTO_CONTROLLED`; caminho proposto E5 `AUTO_CONTROLLED`; pergunta normalizada presente;
resposta curta presente; resposta técnica presente; fonte legal presente (aviso, não
bloqueante); pelo menos uma fonte; pelo menos uma fonte oficial; frescura não `OUTDATED`;
sem `conflictCandidates`; sem duplicado **bloqueante**; sem `requiredReviewReason` (aviso);
sem erros bloqueantes da revisão. Se faltar um critério positivo, o item nunca fica pronto.

Nota sobre duplicados: o item canónico mantém, como referência informativa, os
`duplicateCandidates` que apontam para as suas cópias a jusante — essas cópias são elas
próprias `NOT_PUBLISHABLE`/rejeitadas. Um duplicado só é **bloqueante** para a cópia
redundante (caminho ≠ `AUTO_CONTROLLED`), nunca para o item canónico. Assim o item limpo
pode ficar pronto enquanto a cópia permanece bloqueada.

## E7.3 Invariantes e testes

`AtFaqGovernedPublicationPlanServiceTest` (17 testes) cobre a produção do plano, o item
limpo pronto, itens assistido/manual não prontos, item rejeitado e sem resposta técnica
bloqueados, conflito nunca pronto, diferimento, pré-curadoria sem revisão diferida, revisão
sem pré-curadoria com aviso global, listas de guardas passadas/falhadas, resumos de fontes
sem conteúdo bruto, `published`/`indexed` sempre zero, consistência dos totais, próximas
acções que reforçam que nada foi publicado, determinismo e ausência de HTML/prompts/chunks.
Em todos os candidatos, `published == 0` e `indexed == 0`.

Próximo passo (E8+): publicação/indexação governada real — **não iniciado**. E7 apenas
prova que nada passa sem guardas.

# E8A — Materialização governada de drafts Q&A (sem publicação)

E8A transforma exclusivamente candidatos E7 com
`READY_FOR_FUTURE_PUBLICATION` num objecto Q&A curável em memória. Materializar conhecimento
não é publicá-lo: é preparar o objecto que poderá seguir para curadoria e para um portão futuro.

## E8A.1 Classes e responsabilidades

- `AtFaqMaterializationCandidate` e `AtFaqMaterializationSourceCandidate` transportam conteúdo
  curável e fontes estruturadas, sem HTML bruto, prompts, chunks ou embeddings.
- `AtFaqKnowledgeQaDraftAssembler` reaplica guardas mínimas e produz um draft com
  `KnowledgeCurationStatus.IMPORTED`.
- `AtFaqGovernedMaterializationService` selecciona candidatos prontos, elimina duplicados por
  `externalId` e produz `AtFaqMaterializationResult`, `AtFaqMaterializationTotals` e resultados
  por item.
- O handoff E7 foi estendido de forma aditiva para transportar `topic`, `subtopic`, `riskLevel`
  e as fontes estruturadas já propostas em E5; nenhuma decisão E7 foi alterada.

## E8A.2 Invariantes

- execução exclusivamente em memória e idempotente por `externalId`;
- `persisted=false`, `knowledgeQaId=null`, `published=false` e `indexed=false`;
- `publishedAt` e `publishedBy` não existem no draft e, portanto, permanecem nulos;
- nenhum repository, `KnowledgeQuestionAnswerPublicationService` ou indexador é chamado;
- nenhum caso fica elegível para RAG;
- sem BD, migrations, endpoints, frontend, HTTP externo ou providers.

`AtFaqGovernedMaterializationServiceTest` cobre selecção, bloqueios de conteúdo/fontes/guardas,
conteúdo do draft, idempotência, determinismo e ausência de publicação/indexação.

Próximo passo: E8B poderá implementar publicação governada real, sem indexação automática.
E9 permanece reservado à indexação/RAG de conhecimento efectivamente publicado.

# E8B.1 — Executor DRY-RUN de publicação governada (ensaio sem efeitos)

E8B.1 recebe o `AtFaqMaterializationResult` da E8A e **ensaia** a publicação futura de cada
draft materializado, sem tocar na BD. Ensaiar publicação não é publicar: é validar que o
executor respeita os guardas antes de tocar na BD.

## E8B.1.1 Classes e responsabilidades

- `AtFaqPublicationDryRunMode` (enum `DRY_RUN_ONLY`, `VALIDATE_ONLY`) — qualquer valor implica
  zero efeitos reais.
- `AtFaqPublicationDryRunCommand` transporta a **intenção** de uma publicação futura: acção
  simbólica, `intendedCurationStatus` (nunca aplicado) e as flags `wouldPersistKnowledgeQa`,
  `wouldCreateSources`, `wouldPublish` e `wouldIndex` (sempre `false`).
- `AtFaqPublicationDryRunItemResult` e `AtFaqPublicationDryRunTotals` registam, por draft e no
  agregado, se o item foi simulado, ignorado (não materializado) ou bloqueado (guarda falhada).
- `AtFaqPublicationDryRunReport` é o único output do ensaio.
- `AtFaqGovernedPublicationDryRunExecutor` selecciona apenas itens `materialized == true`, lê o
  `draft` já transportado no resultado da E8A, reaplica os guardas de publicação e produz
  comandos simulados. Não é necessária qualquer alteração à E8A: o draft já viaja no item.

## E8B.1.2 Invariantes

- só itens materializados são ensaiados; item não materializado fica `skipped`, não `blocked`;
- um draft materializado que já traga `knowledgeQaId`, `persisted`, `published` ou `indexed` é
  recusado (guardas `input-*`), pois indicaria um efeito real indevido a montante;
- `persisted=0`, `published=0`, `indexed=0` e `wouldIndex=0` em todos os relatórios;
- `eligibleForDryRunPublication + skipped + blocked == totalDrafts` e `simulated == eligible`;
- nenhum `KnowledgeQuestionAnswer` ou `KnowledgeSourceReference` é persistido; `publishedAt` e
  `publishedBy` permanecem nulos;
- nenhum embedding, nenhuma indexação, nenhum caso elegível para RAG;
- `KnowledgeQuestionAnswerPublicationService` e `KnowledgeQaEmbeddingIndexerImpl` nunca são
  chamados; `RagSearchService` e `GroundingService` não são tocados;
- sem BD, migrations, endpoints, frontend, HTTP externo, scraping ou providers;
- execução determinística e idempotente (relógio injectável).

`AtFaqGovernedPublicationDryRunExecutorTest` cobre o relatório a partir do pipeline E4→E8A, o
comando simulado de um draft limpo, os totais/intenção, os casos ignorado e bloqueado, os
bloqueios por efeito real artificial no input, a reconciliação de totais, determinismo e a
ausência de HTML/prompts/chunks e de qualquer publicação/indexação.

Próximo passo: E8B.2 persiste os drafts governados numa BD isolada, ainda sem publicação. E9
permanece reservado à indexação/RAG de conhecimento efectivamente publicado.

# E8B.2 — Persistência governada de drafts em BD isolada (sem publicação)

E8B.2 recebe o `AtFaqMaterializationResult` da E8A e o `AtFaqPublicationDryRunReport` da E8B.1 e
**persiste** os drafts curáveis numa base de dados isolada de teste (Testcontainers), sobre uma
`Organization` de teste. Persistir draft não é publicar: o conhecimento sobrevive à BD como
rascunho curável (`IMPORTED`), mantendo a classificação de autonomia futura, mas sem entrar no
circuito de publicação, indexação ou RAG.

> `IMPORTED` é usado aqui como *draft curável persistido*, não como conhecimento publicável. Só
> o estado `VALIDATED` alimenta o RAG; um draft `IMPORTED` com `publishedAt`/`publishedBy` nulos
> nunca é elegível (`isEligibleForRag() == false`).

## E8B.2.1 Classes e responsabilidades

- `AtFaqDraftPersistenceMode` (enum `TEST_ISOLATED`, `DRY_RUN_VERIFIED`) — nenhum modo publica ou
  indexa.
- `AtFaqDraftPersistenceCommand` transporta a **intenção** de persistir um draft: `persistKnowledgeQa`,
  `persistSources` e as flags `publish`/`index` (invariante `false`).
- `AtFaqDraftPersistenceItemResult` regista, por draft, o resultado real (`persisted`,
  `sourcesPersisted`, `knowledgeQaId`) e a classificação de autonomia futura
  (`eligibleForAutoPublicationFuture`, `requiresHumanIntervention`, `autonomySignals`).
- `AtFaqDraftPersistenceTotals` agrega os contadores, com invariantes de zero para
  `published`/`indexed`/`embeddings` e reconciliação `persisted + skipped + blocked == totalDrafts`.
- `AtFaqDraftPersistenceResult` é o único output da persistência.
- `AtFaqGovernedDraftPersistenceService` (`SOURCE_SYSTEM = "at-faq-governed-batch"`,
  `DRAFT_CURATION_STATUS = IMPORTED`) itera os itens materializados, cruza-os com o relatório
  DRY-RUN por `externalId`, reaplica os guardas e persiste via repositórios JPA
  (`KnowledgeQuestionAnswerRepository`, `KnowledgeSourceReferenceRepository`) — nunca via
  `KnowledgeQuestionAnswerPublicationService`. A idempotência assenta em
  `findByOrganizationIdAndSourceSystemAndExternalKey` (sem nova coluna nem migration).

## E8B.2.2 Invariantes

- só itens materializados e verificados em DRY-RUN são persistidos; item não materializado fica
  `skipped`, não `blocked`;
- cada draft é escrito como `KnowledgeQuestionAnswer` em estado `IMPORTED`, com `publishedAt` e
  `publishedBy` nulos, mais as respectivas `KnowledgeSourceReference` (incluindo referência legal);
- persistência idempotente: uma segunda execução reutiliza o draft existente (`persisted=0`,
  `skipped=1`), sem duplicar; isolamento por teste garante reversibilidade;
- `published=0`, `indexed=0`, `embeddings=0` em todos os relatórios; nenhum caso elegível para RAG;
- a classificação de autonomia futura é registada mas **não** desencadeia publicação: um item
  `eligibleForAutoPublicationFuture` (fonte oficial + fundamento legal + resposta técnica + baixo
  risco) prepara publicação automática governada futura (E8B.3), não a executa aqui;
- `KnowledgeQuestionAnswerPublicationService` e `KnowledgeQaEmbeddingIndexerImpl` nunca são
  chamados; `RagSearchService` e `GroundingService` não são tocados;
- sem migrations, endpoints, frontend, HTTP externo, scraping, providers ou auditoria persistida;
- BD isolada de teste (Testcontainers) e `Organization` de teste — a base piloto real não é usada;
- execução determinística (relógio injectável).

`AtFaqGovernedDraftPersistenceServiceIT` (Testcontainers, PostgreSQL real) cobre, a partir do
pipeline E4→E8B.1: persistência apenas do item limpo, estado conservador `IMPORTED`, não
elegibilidade para RAG, fontes persistidas com referência legal, ausência de embeddings,
classificação de autonomia futura, idempotência, presença de um único QA na organização,
ausência total de publicação na BD e ausência de HTML/prompts/chunks no relatório.

Próximo passo: E8B.3 poderá implementar a publicação governada real (sem indexação automática). E9
permanece reservado à indexação/RAG de conhecimento efectivamente publicado.

> **Nota — E8B.3-prep (inventário do acoplamento publicação-indexação).** Antes da E8B.3 real,
> foi feito um inventário técnico-documental da ligação entre publicação, indexação, embeddings e
> RAG: `KnowledgeQuestionAnswerPublicationService.publish(...)` chama o indexador de forma síncrona
> e atómica (**Caso B**), mas em `test`/`pgtest` o indexador é um stub no-op, pelo que a publicação
> corre sem gerar embeddings reais. A E8B.3-prep **não publica nem indexa**; apenas documenta e
> recomenda. Ver
> [taxia-publication-indexing-coupling-inventory.md](taxia-publication-indexing-coupling-inventory.md).

## E8B.3.1 Classes e responsabilidades

> Frase-mestra: *"Publicar em teste não é dar voz ao conhecimento. É provar que a promoção
> governada até `publishedAt`/`publishedBy` respeita todos os guardas."*

As classes de execução usam o infixo `Execution` para se distinguirem da **família E7 do plano de
publicação** (`AtFaqGovernedPublicationPlan`, `AtFaqGovernedPublicationTotals`, …), que partilha o
prefixo `AtFaqGovernedPublication`.

- `AtFaqGovernedPublicationExecutionMode` (enum, valor único `TEST_ISOLATED_WITH_STUB_INDEXER`) —
  não existe modo de produção nesta etapa.
- `AtFaqGovernedPublicationExecutionCommand` — decisão inspecionável, por draft, de *tentar* publicar;
  invariantes `indexExpected == false` e `requiresStubIndexer == true`, e `publish == true` só sem
  razões bloqueadoras.
- `AtFaqGovernedPublicationExecutionItemResult` — resultado por draft (`published`, `validated`,
  `publishedAt`/`publishedBy`, `curationStatus`, `eligibleForRagByEntityRules`); invariantes de
  `indexed == false`, `embeddingPresent == false`, `ragExpectedToRetrieve == false`.
- `AtFaqGovernedPublicationExecutionTotals` — contadores agregados, com invariantes de zero para
  `indexed`, `embeddings` e `ragExpected`.
- `AtFaqGovernedPublicationExecutionResult` — único output; sem embeddings, chunks, prompts, HTML
  bruto ou logs sensíveis.
- `AtFaqGovernedPublicationExecutor` (`@Service`) — recebe um `AtFaqDraftPersistenceResult`, localiza
  os Q&A persistidos, confirma a autonomia futura, promove de forma governada `IMPORTED → VALIDATED`
  pela **API de domínio real** (`markPendingReview()` + `validate(reviewedBy)` — sem reflexão nem
  atalhos) e chama o **`KnowledgeQuestionAnswerPublicationService.publish(...)` real**. O
  `actingUserId` de auditoria é derivado de forma determinística de `publishedBy`
  (`deterministicActor`), para reutilização entre execuções.

## E8B.3.2 Invariantes

- publicação **apenas** em BD isolada (Testcontainers) sob o profile `pgtest`, onde o indexador
  activo é o `StubKnowledgeQaEmbeddingIndexer` (no-op) — é isto que permite ao `publish(...)` real,
  que indexa de forma síncrona antes de marcar publicado, chegar a `publishedAt`/`publishedBy`
  **sem** gerar qualquer embedding;
- só são publicados drafts `eligibleForAutoPublicationFuture == true` e
  `requiresHumanIntervention == false`; cada guarda é reaplicada sobre a entidade real (estado
  `IMPORTED` antes da promoção, risco `LOW`, resposta técnica presente, sinal `OFFICIAL_SOURCE_PRESENT`,
  referência legal numa fonte persistida, ≥ 1 fonte);
- item publicado fica em `curationStatus == VALIDATED` com `publishedAt`/`publishedBy` não nulos; a
  entidade considera-o `isEligibleForRag()`, mas **sem embedding não é recuperável** pelo RAG;
- `indexed == 0`, `embeddings == 0`, `ragExpected == 0`; `COUNT(knowledge_qa_embeddings) == 0`
  mesmo após publicação real;
- idempotência: uma segunda execução encontra o Q&A já publicado e classifica-o como *ignorado (já
  publicado)*, sem deixar o `publish(...)` lançar `CONFLICT`;
- guardas negativas bloqueiam/ignoram sem publicar: não elegível para autonomia futura, intervenção
  humana exigida, risco ≠ `LOW`, sem fonte oficial, sem referência legal, organização errada;
- `KnowledgeQaEmbeddingIndexerImpl` nunca é usado; `RagSearchService`, `GroundingService` e o
  próprio `KnowledgeQaEmbeddingIndexerImpl` não são tocados;
- sem migrations, endpoints, frontend, HTTP externo, scraping, providers ou base piloto real;
- execução determinística (relógio injectável).

`AtFaqGovernedPublicationExecutorIT` (Testcontainers, PostgreSQL real) cobre, a partir do pipeline
E4→E8B.2: publicação apenas do item limpo, estado `VALIDATED` + publicado, elegibilidade RAG pela
entidade sem recuperabilidade efectiva, `COUNT(knowledge_qa_embeddings) == 0` apesar da publicação,
idempotência, e todas as guardas negativas (autonomia futura ausente, intervenção humana, risco não
`LOW`, sem fonte oficial, sem referência legal, organização errada), mais a higiene do relatório.

Próximo passo: E9A — indexação/RAG efectiva de um único Q&A publicado, em BD isolada, sob o
mecanismo de embeddings controlado do projecto.

## E9A Indexação efectiva de um único Q&A publicado (BD isolada)

Frase-mestra: **"Indexar não é escalar. É dar voz controlada a um único conhecimento publicado."**

E8B.3 provou que a publicação governada chega a `publishedAt`/`publishedBy` **sem** voz no RAG
(zero embeddings sob o stub). E9A dá essa voz — a **exactamente um** Q&A publicado — escrevendo o seu
embedding pelo contrato real, e prova que o RAG passa a recuperá-lo. Não é lote, não é produção, não
é a base piloto real.

### E9A.1 Classes e responsabilidades

- `AtFaqRagIndexingMode` — enum de modo; valor único `TEST_ISOLATED_SINGLE_QA` (não há modo de
  produção; E9A prova o ciclo em isolamento, tal como E8B.3).
- `AtFaqRagIndexingCommand` — decisão inspeccionável por item (guardas baratas pré-BD);
  invariantes estruturais: `index && !blockingReasons.isEmpty()` é ilegal, `singleQaOnly` tem de ser
  `true`, `productionDataAllowed` tem de ser `false`.
- `AtFaqRagIndexingItemResult` — desfecho por Q&A; invariantes: `embeddingRows ∈ {0,1}`,
  `embeddingPresent == (embeddingRows ≥ 1)`, um item `indexed` tem `embeddingRows == 1`, e
  `ragExpectedToRetrieve` implica `indexed`.
- `AtFaqRagIndexingTotals` — contadores com *hard caps* `indexed ≤ 1`, `embeddingRows ≤ 1`,
  `ragExpected ≤ 1` — a frase-mestra codificada em tipos: nenhuma execução pode indexar mais do que um.
- `AtFaqRagIndexingResult` — relatório; não transporta vectores, passagens, prompts, chunks, HTML nem
  logs sensíveis.
- `AtFaqGovernedRagIndexingService` — orquestrador. Recebe o `AtFaqGovernedPublicationExecutionResult`
  da E8B.3, filtra os itens publicados, escolhe o **primeiro** como alvo (difere os restantes para
  E9B), reaplica as guardas de BD e, se todas passarem, chama `indexer.index(...)` e confirma a linha
  de embedding por SQL. Tem construtor `@Autowired` de produção e um *test seam* com `Clock` injectável.

### E9A.2 Guardas de indexação (indexa só se **todas** se verificarem)

1. o item foi publicado em E8B.3 (`published == true`) e tem `knowledgeQaId`;
2. o item de publicação não trazia razões bloqueadoras e era elegível para publicação governada;
3. o Q&A existe e pertence à organização fornecida;
4. `isPublished() == true`;
5. `curationStatus == VALIDATED`;
6. risco `== LOW` (E9A é mais restrita do que a entidade: recusa `HIGH`/`CRITICAL` mesmo quando
   `isEligibleForRag()` é `true`);
7. resposta técnica presente;
8. ≥ 1 fonte associada;
9. `isEligibleForRag() == true`.

### E9A.3 Invariantes

- indexação efectiva **apenas** em BD isolada (Testcontainers); no IT o indexador injectado é o
  `KnowledgeQaEmbeddingIndexerImpl` **real**, alimentado por um `EmbeddingService` determinístico de
  teste (768 dim, `[1,0,…]`) — SQL de *upsert* de produção, **zero** chamadas externas, sem OpenAI,
  Anthropic, scraping ou modelo de embeddings real;
- antes de E9A `COUNT(knowledge_qa_embeddings) == 0`; depois **exactamente 1** linha para esse Q&A;
- o `RagSearchService` real (construído no IT com o mesmo embedding determinístico) recupera o Q&A
  indexado para uma pergunta semanticamente compatível (`KNOWLEDGE_QA`, similaridade ≈ 1.0);
- idempotência: reindexar o mesmo Q&A faz *upsert* sobre `knowledge_qa_id` → continua 1 linha;
- política single-Q&A (não-lote): com vários publicados, indexa 1 e difere os restantes para E9B;
- guardas negativas recusam sem indexar: nenhum item publicado, entidade `IMPORTED`/não publicada,
  organização errada, risco ≠ `LOW`;
- relatório sem vector bruto, HTML, prompts nem chunks;
- `RagSearchService`, `GroundingService` e o `KnowledgeQaEmbeddingIndexerImpl` de produção não são
  alterados; sem migrations, endpoints, frontend, scheduler, providers ou base piloto real;
- execução determinística (relógio injectável).

`AtFaqGovernedRagIndexingServiceIT` (Testcontainers, PostgreSQL real) cobre, a partir do pipeline
E4→E8B.3: pré-condição (publicado mas 0 embeddings), indexação de exactamente 1 Q&A, *exactamente 1*
linha em `knowledge_qa_embeddings`, recuperação pelo RAG real, idempotência, política single-Q&A,
todas as guardas negativas e a higiene do relatório, mais a prova de ausência de chamadas externas
(datasource ligado ao container local).

Próximo passo: E9B — lote governado pequeno de indexação/RAG sob o mesmo mecanismo controlado;
E10 — rollback/despublicação/desindexação governada.

## E9B Indexação efectiva de um lote pequeno governado (BD isolada)

Frase-mestra: *"Indexar vários não é escalar livremente. É provar que o lote obedece aos mesmos
guardas do caso único."* A E9B é a transição controlada entre o caso único (E9A) e a escala — um
**lote pequeno**, nunca uma automação massiva.

### E9B.1 Classes e responsabilidades (evolução aditiva da E9A)

- `AtFaqGovernedRagIndexingService` — **estendido aditivamente**: novo método
  `indexSmallPublishedBatch(AtFaqGovernedPublicationExecutionResult, Organization, String indexedBy, int maxItems)`.
  O `indexSinglePublishedQa(...)` da E9A mantém-se **intacto**. As guardas por Q&A foram extraídas para
  um avaliador interno partilhado (`evaluateGuards`) usado por **ambos** os fluxos, garantindo que o
  lote obedece exactamente às mesmas guardas do caso único; a escrita efectiva vive em `doIndex(...)`.
- `AtFaqRagIndexingMode` — novo valor `SMALL_BATCH_TEST_ISOLATED`; o `TEST_ISOLATED_SINGLE_QA` mantém-se.
- `AtFaqRagIndexingTotals` — passa a carregar o `mode` e os *caps* de `indexed`/`embeddingRows`/
  `ragExpected` tornam-se **por modo**: single ≤ 1 (cap estrutural do caso único preservado), lote ≤ 3
  (`MAX_SMALL_BATCH`).
- `AtFaqRagIndexingResult` — campos aditivos `requestedMaxItems`/`effectiveMaxItems` (o limite pedido e
  o limite efectivamente aplicado após validação). `AtFaqRagIndexingCommand` **não** foi alterado: cada
  comando descreve a decisão sobre **um** Q&A (`singleQaOnly == true` por item), e um lote são N dessas
  decisões.

### E9B.2 Regra do `maxItems` e limite do lote

- `maxItems ∈ [2, 3]`. `maxItems < 2` é recusado como **configuração inválida** (para um único Q&A
  usa-se o fluxo single); `maxItems > 3` **excede o teto** do lote pequeno e é recusado. Em ambos os
  casos nada é indexado e o resultado traz `blockingErrors`.
- Nunca se indexa mais do que `maxItems` nem mais do que 3. Com mais elegíveis do que o limite, os
  excedentes são **diferidos** (`eligibleForIndexing == true`, `indexed == false`, aviso "diferido pelo
  limite"), nunca descartados em silêncio.

### E9B.3 Invariantes

- indexação efectiva **apenas** em BD isolada (Testcontainers); indexador `KnowledgeQaEmbeddingIndexerImpl`
  **real** com `EmbeddingService` determinístico (768 dim), **zero** chamadas externas;
- antes do lote `COUNT(knowledge_qa_embeddings) == 0`; depois **exactamente N** linhas para N elegíveis
  publicados com 1 < N ≤ 3, **uma linha por Q&A**;
- o `RagSearchService` real recupera **apenas** os Q&A indexados (validados/publicados/elegíveis);
  validação por **pertença ao conjunto**, não por ordem exacta (o embedding determinístico dá
  similaridade ≈ 1.0 a todos — ver aviso §11 do prompt sobre empates);
- idempotência: reindexar o lote faz *upsert* → continua N linhas;
- limite respeitado: 4 elegíveis + `maxItems = 3` ⇒ indexa 3, difere 1;
- mesmas guardas negativas do caso único recusam sem indexar: não publicado, entidade `IMPORTED`,
  organização errada, risco ≠ `LOW`;
- relatório sem vector bruto, HTML, prompts nem chunks;
- `RagSearchService`, `GroundingService` e o `KnowledgeQaEmbeddingIndexerImpl` de produção não são
  alterados; sem migrations, endpoints, frontend, scheduler, providers ou base piloto real;
- execução determinística (relógio injectável).

`AtFaqGovernedRagBatchIndexingServiceIT` (Testcontainers, PostgreSQL real, 13 testes) cobre:
pré-condição (três publicados, 0 embeddings), indexação do lote de três, *exactamente 1* linha por Q&A
(3 no total), recuperação dos três pelo RAG (pertença ao conjunto), idempotência do lote, limite
(4 + `maxItems=3` ⇒ 3 indexados / 1 diferido), configurações inválidas (`maxItems=1` e `maxItems=4`
recusadas), lote misto sob as mesmas guardas (2 `LOW` indexados; `IMPORTED`/`HIGH`/outra-organização
bloqueados), lote sem publicados, higiene do relatório, ausência de chamadas externas e regressão do
fluxo single (continua a indexar 1 e a diferir o resto). A E9A (`AtFaqGovernedRagIndexingServiceIT`,
12 testes) permanece verde.

Próximo passo: E9C — lote **real/piloto** controlado de indexação/RAG sob o mesmo mecanismo governado;
E10 — rollback/despublicação/desindexação governada.

### E9C-prep — Matriz de decisão do lote piloto (apenas documentação)

Antes de implementar o lote piloto ou o rollback, a **E9C-prep** fixa o enquadramento de decisão:
define o que conta como **lote piloto** (apenas FAQ AT, apenas `LOW`, fonte oficial com fundamento
legal, sem conflitos nem duplicados materiais; **tamanho máximo recomendado 5–10, preferência por
5**), os **critérios de entrada/exclusão**, a **matriz de autonomia**
(`AUTO_GOVERNED`/`ASSISTED_REQUIRED`/`MANUAL_REQUIRED`/`BLOCKED`), a **matriz de intervenção humana**
(medir quando é necessária, sem a tornar condição estrutural), os **riscos a observar**, as
**métricas de aceitação** e o **rollback mínimo** exigido. Recomendação cautelosa: **preparar
primeiro o rollback (E10-prep)** antes de aumentar o lote, com abertura condicionada a um E9C-mini
(5 itens, `LOW`, FAQ AT, rollback manual documentado, sem produção). **Não** implementa o lote real
**nem** o rollback — é exclusivamente documental. Ver
[taxia-e9c-pilot-batch-decision-matrix.md](taxia-e9c-pilot-batch-decision-matrix.md).

### E10-prep — Inventário de rollback/despublicação/desindexação (apenas documentação)

Seguindo a recomendação da matriz E9C-prep, a **E10-prep** inventaria — por leitura apenas — os
mecanismos existentes de **rollback**, **despublicação**, **remoção de embeddings** e
**desindexação**: o `unpublish(...)` remove o embedding **e** limpa a publicação de forma atómica,
mantendo `curationStatus == VALIDATED` (histórico preservado), mas **sem motivo** e **sem métrica
própria**; o `remove(...)` é `DELETE` físico idempotente (sem *soft-delete*); o RAG deixa de
recuperar por **dupla porta** (embedding + `published_at`). Lacunas para E10: motivo obrigatório,
relatório próprio, distinção formal despublicar ≠ desindexar, comando governado, *batch rollback*,
métrica e teste E2E. Recomendação: **E10A primeiro** (rollback de um único Q&A, simétrico com E9A).
**Não** implementa o rollback — é exclusivamente documental. Ver
[taxia-e10-rollback-unpublish-deindex-inventory.md](taxia-e10-rollback-unpublish-deindex-inventory.md).

## E10A Rollback governado de um único Q&A publicado/indexado (BD isolada)

Frase-mestra: *"Retirar voz não é apagar conhecimento. É neutralizar a recuperação preservando rasto
e motivo."*

Seguindo a recomendação da E10-prep (**E10A primeiro**, simétrico com E9A), a E10A implementa —
apenas em BD isolada (Testcontainers) — o rollback governado de **exactamente um** Q&A já publicado
e indexado. O rollback é o inverso coordenado do ciclo E8B.3→E9A: **despublicar** (limpar
`publishedAt`/`publishedBy`) e **desindexar** (remover a linha de embedding) são efeitos
distintos-mas-coordenados, executados atomicamente pelo `unpublish(...)` **real** — nunca uma
destruição de conhecimento (a entidade permanece, `curationStatus == VALIDATED`, e a auditoria
`KNOWLEDGE_QA_UNPUBLISHED` fica registada).

### E10A.1 Classes e responsabilidades

- `AtFaqRollbackMode` — modo único `TEST_ISOLATED_SINGLE_QA` (nunca *scheduler*, nunca lote, nunca
  base real).
- `AtFaqRollbackRagProbe` — `@FunctionalInterface` (`boolean recovers(orgId, qaId)`): costura fina
  para provar a recuperação RAG antes/depois **sem** dependência dura do `RagSearchService`. O IT
  fornece um *probe* apoiado num `RagSearchService` real; **sem** *probe* o serviço recusa reverter.
- `AtFaqRollbackItemResult` / `AtFaqRollbackTotals` / `AtFaqRollbackResult` — relatório governado
  (estado antes/depois, motivo, avisos, bloqueios, próximas acções). Sem vector bruto, sem
  *prompt*/*chunk*/HTML.
- `AtFaqGovernedRollbackService` — orquestra o rollback através do
  `KnowledgeQuestionAnswerPublicationService#unpublish` **real**. Em BD isolada, esse serviço é
  construído com o `KnowledgeQaEmbeddingIndexerImpl` **real** (nunca o *stub* do perfil `pgtest`),
  pelo que o `unpublish(...)` apaga fisicamente a linha de `knowledge_qa_embeddings`. Não altera o
  `PublicationService`, o `KnowledgeQaEmbeddingIndexerImpl`, o `RagSearchService` nem migrations.

### E10A.2 Guardas de rollback (reverte só se **todas** se verificarem)

1. motivo não vazio (**obrigatório** no comando e no relatório);
2. item efectivamente indexado em E9A/E9B (`indexed && embeddingPresent`) com `knowledgeQaId`;
3. **exactamente um** item elegível na indexação (modo *single*; lote é E10B — mais de um bloqueia);
4. o Q&A existe e pertence à organização fornecida;
5. `isPublished()` com `publishedAt`/`publishedBy` consistentes;
6. `curationStatus == VALIDATED`;
7. `embeddingRowsBefore == 1`;
8. *probe* de RAG disponível **e** a confirmar recuperação **antes** do rollback.

### E10A.3 Invariantes

- **Antes:** publicado, `VALIDATED`, `publishedAt`/`publishedBy != null`, `embeddingRows == 1`, o RAG
  recupera-o.
- **Depois:** `publishedAt == null`, `publishedBy == null`, `curationStatus` **continua** `VALIDATED`
  (rollback neutraliza a recuperação, não rebaixa a curadoria), `embeddingRows == 0`, o RAG **não**
  recupera; auditoria `KNOWLEDGE_QA_UNPUBLISHED` preservada.
- **Idempotência:** um segundo rollback do mesmo Q&A (sem publicação, sem embedding) é classificado
  como *skipped (already rolled back)* — detectado **antes** de chamar `unpublish(...)`, pelo que o
  `INVALID_STATE_TRANSITION` nunca escapa, nada é recriado nem republicado.
- **Guardas negativas** (bloqueiam sem efeito destrutivo, nunca falham): motivo vazio; organização
  errada; publicado mas `embeddingRows != 1`; mais de um item elegível em modo *single*.
- **Higiene do relatório:** sem vector bruto, sem HTML, sem *prompts* nem *chunks*.
- **Lacuna documentada:** o `unpublish(...)` **não** persiste o motivo — o motivo é transportado no
  comando/relatório e a ausência de persistência formal fica assinalada como aviso global, para
  resolução em **E10B/E10-policy** (nenhuma migration nem alteração de esquema de auditoria em E10A).

Cobertura: `AtFaqGovernedRollbackServiceIT` (13 casos, BD isolada) prova a pré-condição (publicado +
indexado + RAG recupera), o rollback com motivo, o estado depois, a preservação da auditoria, o
motivo no relatório, a idempotência e todas as guardas negativas — com embedding determinístico e
zero chamadas externas.

Próximo passo: E10B — rollback governado de um **lote pequeno** sob os mesmos guardas; depois
E10-policy — persistência formal do motivo e auditoria dedicada de rollback; só então E9C — lote
piloto real.

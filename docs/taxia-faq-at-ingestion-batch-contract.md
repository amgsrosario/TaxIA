# TaxIA — Bloco E — E3 — Contrato técnico de lote de ingestão FAQ AT

> **Natureza.** Documento de **contrato conceptual** (só leitura/documentação). Não
> implementa código, não cria migrações, endpoints, testes nem UI. Transforma as
> decisões de E1 (política) e E2 (inventário) num contrato claro para futura
> implementação (E4 e seguintes). Português de Portugal.
>
> Documentos-base: [taxia-assisted-ingestion-curation-policy.md](taxia-assisted-ingestion-curation-policy.md) (E1),
> [taxia-faq-at-ingestion-pipeline-inventory.md](taxia-faq-at-ingestion-pipeline-inventory.md) (E2).
> Não reabre os Blocos C e D (fechados).

---

## 1. Objectivo

Este documento define o **contrato técnico conceptual do lote de ingestão FAQ AT**:

- **não implementa código** — descreve estruturas, estados, campos e regras que uma
  implementação futura deverá respeitar;
- serve de **ponte entre a camada RAW AT-FAQ** (`at_faq_raw_items`, já existente) **e
  a curadoria/publicação governada** (camada Q&A, já existente);
- estabelece o **lote como unidade auditável de governação**, não como mero
  agrupamento de registos importados.

> **O lote não é uma importação em massa. É uma unidade auditável de governação.**

Tudo o que aqui se descreve como entidade/campo/estado é **conceptual**. Onde já existe
um artefacto real no código (ex.: `KnowledgeQuestionAnswer`, `AtFaqIngestionRun`), o
documento nomeia-o e distingue-o do que ainda **não existe** e teria de ser construído.

## 2. Base conceptual

O contrato assenta nos blocos já fechados e nas duas primeiras tarefas do Bloco E:

- **Bloco C** — política de risco e visibilidade: `supportStatus`,
  `aggregatedRiskLevel`, `parecerRequirement`, `visibilityLevel`, `freshnessStatus`,
  `sourceQuality`, `sourceRole`, `sourceDiversity`, Resposta-limite. Ver
  [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md).
- **Bloco D** — materialização técnica: `DocumentedTaxiaAnswer`, `SourceEvidence`,
  `AnswerDecisionService`, `AnswerProjectionService`, `InternalDiagnostics`. Ver
  [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md).
- **E1** — política de ingestão e curadoria assistida (governação da publicação).
- **E2** — inventário do pipeline actual (o que existe e o que falta).

**Regra central do Bloco E:** a **ingestão pode ser automática**; a **publicação não
tem de ser sempre humana**, mas **tem de ser sempre governada**.

Consequências que o contrato torna obrigatórias:

- **Importar ≠ publicar.** Entrar na camada RAW ou em quarentena Q&A **nunca** implica
  visibilidade nem indexação.
- **O *publication gate* é obrigatório.** Nenhum item passa a publicado sem cumprir os
  critérios de elegibilidade (validado, `technicalAnswer` própria, fonte, validade,
  revisão de risco elevado).
- **A indexação/RAG só ocorre após publicação bem-sucedida.**
- **O *publication path* pode ser** automática controlada, assistida ou manual
  obrigatória — nunca publicação livre/indiscriminada.

## 3. Estado técnico de partida (resumo de E2)

- **Existe** o pacote `com.knowledgeflow.ingestion.atfaq` (piloto Etapa 10A, desligado
  por defeito, execução agendada proibida), com modos `DISCOVER`/`DRY_RUN`/`IMPORT`.
- **Existe** a camada RAW: `at_faq_raw_items`, `at_faq_ingestion_runs`,
  `at_faq_page_snapshots` (V15).
- **Existe** a camada Q&A: `KnowledgeQuestionAnswer`, `KnowledgeSourceReference`,
  `knowledge_qa_embeddings` (V14).
- **Existe** publicação manual por caso (`KnowledgeQuestionAnswerPublicationService`)
  com elegibilidade via `KnowledgeQuestionAnswer.isEligibleForRag`.
- **Existe** indexação ligada à publicação (`KnowledgeQaEmbeddingIndexerImpl`,
  *upsert* idempotente) e pesquisa RAG (`RagSearchService`) → *grounding*
  (`GroundingService`).
- **Não existe ainda:** unidade formal de **lote curável**, relatório de **lote**,
  **pré-curadoria automática governada**, **classificação** automática
  controlada/assistida/manual, **rollback formal de lote**, nem um **contrato técnico**
  explícito entre ingestão e publicação. `AtFaqIngestionRun` é relatório de execução,
  **não** uma unidade de governação de lote.

Este documento define esse contrato em falta.

## 4. Definição de lote de ingestão

**`IngestionBatch`** (conceptual) — unidade **lógica e auditável** de entrada:

- agrupa itens recolhidos/importados num **intervalo** ou **operação** identificável;
- preserva **origem, modo, data, operador/sistema e critérios usados**;
- **nunca implica publicação automática por si só**;
- deve ser **reversível** ou, no mínimo, **neutralizável** (§15).

Um lote deve permitir responder, sem ambiguidade, a:

- **de onde veio?** (fonte, autoridade, URLs, subcategorias);
- **quando entrou?** (início/fim);
- **com que modo?** (§5);
- **quantos itens trouxe?** (descobertos/importados);
- **quantos foram aceites/rejeitados?**;
- **quantos ficaram duplicados?**;
- **quantos exigem revisão?**;
- **quantos são candidatos a publicação automática controlada?**;
- **quantos exigem publicação manual?**;
- **que efeitos produziu?** (RAW criado, Q&A em quarentena, publicações, indexações);
- **como reverter ou neutralizar?** (§15).

> **Nota de implementação (futura).** O lote pode ser (a) uma **vista lógica** sobre
> `AtFaqIngestionRun` + `sourceSystem` + intervalo, ou (b) uma **entidade nova** com
> estado próprio. E3 **não decide** a via de persistência — apenas fixa o contrato que
> qualquer delas terá de cumprir. Preferência declarada: não reabrir V14/V15 sem
> necessidade real.

## 5. Modos conceptuais de lote

| Modo | Escreve RAW? | Cria conhecimento curável? | Publica? | Indexa? |
|---|---|---|---|---|
| `DISCOVER` | conforme desenho actual (run/snapshot) | Não | Não | Não |
| `DRY_RUN` | Não (só relatório) | Não | Não | Não |
| `IMPORT` | Sim | Sim (quarentena) | **Não** (por defeito) | **Não** (por defeito) |
| `PRE_CURATE` | Não | Proposta estruturada (não publicável) | Não (por defeito) | Não |
| `REVIEW` | Não | Decisão assistida/manual | Não (é decisão) | Não |
| `PUBLISH_GOVERNED` | Não | — | Só itens que cumpram critérios | Só após publicação |

Detalhe:

- **`DISCOVER`** — descobre/enumera candidatos; **não grava** knowledge QA; pode gravar
  run/snapshot/raw conforme o desenho existente; **não publica**.
- **`DRY_RUN`** — simula ingestão e pré-curadoria; **produz relatório**; **não cria**
  conhecimento publicável; **não publica**; **não indexa**.
- **`IMPORT`** — cria/actualiza camada RAW e/ou conhecimento curável (quarentena);
  **não publica** por defeito; **não indexa** por defeito.
- **`PRE_CURATE`** — produz **proposta estruturada** (sugere tema, risco, fontes,
  `shortAnswer`, `technicalAnswer`, etc.); **não publica** por defeito.
- **`REVIEW`** — etapa de **decisão assistida/manual**; confirma, corrige, rejeita ou
  encaminha.
- **`PUBLISH_GOVERNED`** — só aplicável a itens que **cumpram critérios**; pode resultar
  em publicação automática controlada, assistida ou manual; **aciona indexação apenas
  após publicação**.

> **Nota.** Os modos técnicos **hoje existentes** são apenas `DISCOVER`/`DRY_RUN`/`IMPORT`
> (`AtFaqRunMode`). `PRE_CURATE`, `REVIEW` e `PUBLISH_GOVERNED` são **modos/fases
> conceptuais futuros** do fluxo, **não** necessariamente valores imediatos de enum.
> E4+ decide se são novos valores de enum, novas operações ou fases orquestradas.

## 6. Estados conceptuais do lote

| Estado | Significado |
|---|---|
| `CREATED` | Lote registado, ainda não executado |
| `RUNNING` | Em execução |
| `COMPLETED` | Terminou sem avisos relevantes |
| `COMPLETED_WITH_WARNINGS` | Terminou, mas com avisos a rever |
| `FAILED` | Falhou (pode ter efeitos parciais — ver nota) |
| `CANCELLED` | Interrompido por decisão antes de concluir |
| `REVERTED` | Efeitos totalmente revertidos/neutralizados |
| `PARTIALLY_REVERTED` | Só parte dos efeitos foi revertida — **tem de ficar explícito** |
| `ARCHIVED` | Encerrado para histórico |

Regras:

- **O estado do lote não é o estado dos itens.** Um lote pode estar `COMPLETED` com
  itens em `IMPORTED`, `PENDING_REVIEW`, `REJECTED`, etc.
- **Lote concluído não significa casos publicados.** `COMPLETED` descreve a execução,
  não a publicação.
- **Lote falhado pode ter produzido efeitos parciais** se a operação não for uma
  transacção total (a ingestão AT usa uma transacção pequena por item, precisamente
  para não misturar I/O de rede com BD — ver E2 §7).
- **A reversão deve ser documentada** (motivo, âmbito, itens afectados) — §15.

## 7. Item bruto do lote

**`RawBatchItem`** (conceptual) — o item tal como recolhido, antes de ser conhecimento
TaxIA. Espelha a camada `at_faq_raw_items` já existente.

Campos conceptuais:

- `batchId` — lote a que pertence;
- `rawItemId` — id do item bruto (`at_faq_raw_items.id`);
- `externalId` — identificador oficial na fonte (ex.: `officialFaqId`);
- `sourceSystem` — ex.: `at-faq`;
- `sourceUrl`, `sourceTitle`, `sourceSection` (categoria/subcategoria);
- `originalQuestion`, `originalAnswer` — texto original **imutável**;
- `capturedAt` — data de recolha;
- `contentHash` — hash estável do par pergunta/resposta;
- `pageSnapshotId` — se existir (`at_faq_page_snapshots.id`);
- `rawStatus` — estado bruto (`AtFaqIngestionStatus`);
- `rawWarnings` — avisos de recolha/parsing.

Regras:

- **o item bruto preserva a fonte** — é evidência, não resposta;
- **não é ainda conhecimento TaxIA**;
- **não tem `answerType`** (isso pertence à resposta documentada, Bloco C/D);
- **não tem `parecerRequirement`**;
- **não deve entrar no RAG** em circunstância alguma.

## 8. Item pré-curado

**`PreCuratedBatchItem`** (conceptual) — a **proposta** estruturada derivada de um item
bruto. Hoje **não existe**: a importação AT deixa os campos curados vazios.

Campos conceptuais:

- `batchId`, `rawItemId`;
- `proposedKnowledgeQaId` — Q&A candidato (se já criado em quarentena);
- `normalizedQuestion`;
- `proposedShortAnswer`, `proposedTechnicalAnswer`;
- `proposedTopic`, `proposedSubtopic`, `proposedJurisdiction`;
- `proposedRiskLevel` (`KnowledgeRiskLevel`);
- `proposedSources` (lista de `SourceCandidate`, §10);
- `proposedLegalReferences`;
- `proposedSourceQuality` (`SourceQuality`);
- `proposedSourceRole` (`SourceRole`);
- `proposedSourceCore` — núcleo material da fonte (relaciona-se com
  `SourceDiversity.SAME_CORE`); **conceito**, não necessariamente enum persistido;
- `proposedSourceDiversity` (`SourceDiversity`);
- `proposedFreshnessStatus` (`FreshnessStatus`);
- `duplicateCandidates` — itens materialmente semelhantes;
- `conflictCandidates` — itens que contradizem;
- `confidenceSignals` — sinais de confiança (qualitativos, **sem *score* numérico**);
- `warnings`;
- `requiredReviewReason` — porque exige revisão (se exigir);
- `proposedPublicationPath` — via proposta (§12).

Regras:

- **proposta não é publicação**;
- a proposta pode ser gerada por **regras, *templates* ou IA controlada** no futuro
  (E5), nunca por publicação directa;
- **qualquer proposta tem de ser auditável** — deve registar como foi gerada (versão de
  regra/*template*, sinais usados).

## 9. Candidato a KnowledgeQuestionAnswer

Como um `PreCuratedBatchItem` se transforma num candidato a `KnowledgeQuestionAnswer`
publicável.

Critérios mínimos (alinhados com `isEligibleForRag` e os guards da entidade):

- **pergunta normalizada**;
- **`shortAnswer`** presente;
- **`technicalAnswer`** presente (requisito de publicação — a `shortAnswer` sozinha
  nunca sustenta publicação);
- **pelo menos uma fonte** (`KnowledgeSourceReference`);
- **origem rastreável** (`sourceSystem`/`externalKey`/fonte oficial);
- **`riskLevel`** definido (e, se `HIGH`/`CRITICAL`, `reviewedBy` presente);
- **estado de curadoria** = `VALIDATED` antes de publicar;
- **estado de publicação separado** do estado de curadoria;
- **validade/actualidade tratada** (`validFrom`/`validTo`; sinais de `freshnessStatus`);
- **não duplicado material sem decisão** explícita.

> **Reforço.** *A FAQ oficial é fonte. O `KnowledgeQuestionAnswer` é conhecimento
> TaxIA.* A síntese própria (a `technicalAnswer`) é o que distingue conhecimento
> publicável de mera cópia da fonte.

## 10. Contrato de fontes do lote

**`SourceCandidate`** (conceptual) — candidato a `KnowledgeSourceReference`.

Campos conceptuais:

- `type` (`KnowledgeSourceType`: `LEGISLATION`, `ADMINISTRATIVE_GUIDANCE`, `CASE_LAW`,
  `OFFICIAL_FAQ`, `INTERNAL_OPINION`, `ACCOUNTING_STANDARD`, `OTHER`);
- `title`, `url`, `legalReference`;
- `authorityLevel` (`AuthorityLevel`: `LEGAL`, `OFFICIAL_ADMINISTRATIVE`,
  `OFFICIAL_FAQ`, `JURISPRUDENCE`, `OFFICIAL_COMPLEMENTARY`, `INTERNAL_CURATED`,
  `EXTERNAL_NON_OFFICIAL`);
- `sourceQuality` (`SourceQuality`: `STRONG`, `ADEQUATE`, `LIMITED`, `WEAK`);
- `sourceRole` (`SourceRole`: `PRIMARY`, `COMPLEMENTARY`, `DERIVATIVE_REPLICATED`);
- `sourceCore` — núcleo material (para aferir diversidade real);
- `sourceDiversity` (`SourceDiversity`: `MATERIAL_DIVERSITY`, `SAME_CORE`,
  `MIXED_OR_UNCLEAR`);
- `freshnessStatus` (`FreshnessStatus`: `CURRENT`, `STABLE_BUT_OLD`, `UNCERTAIN`,
  `OUTDATED`);
- `isOfficial`, `isPrimary`;
- `duplicateOf` — outra fonte de que é réplica;
- `notes`.

Regras:

- **FAQ AT** deve ser `OFFICIAL_FAQ` (ou equivalente `AuthorityLevel.OFFICIAL_FAQ`);
- **legislação correspondente** deve ser `LEGISLATION` / `AuthorityLevel.LEGAL`;
- **fontes derivadas/replicadas** (`DERIVATIVE_REPLICATED`, `SAME_CORE`) **não aumentam
  diversidade material**;
- **fonte sem URL ou referência clara exige aviso**;
- **fundamento legal ausente** pode **impedir** a via `AUTO_CONTROLLED` (§12).

## 11. Separação obrigatória de eixos

Estes eixos são **distintos** e não podem ser colapsados uns nos outros:

1. **Estado do lote** — §6 (`CREATED`…`ARCHIVED`).
2. **Estado bruto do item** — `AtFaqIngestionStatus` (`DISCOVERED`…`FAILED`).
3. **Estado de curadoria do Q&A** — `KnowledgeCurationStatus` (`IMPORTED`…`ARCHIVED`).
4. **Estado de publicação** — publicado / não publicado (`publishedAt` ≠ null).
5. **`FreshnessStatus`** da fonte/resposta — actualidade textual (Bloco C/D).
6. **`SupportStatus`** da resposta documentada — `AnswerSupportStatus`.
7. **`RiskLevel`** do conhecimento — `KnowledgeRiskLevel` persistido no caso.
8. **`AggregatedRiskLevel`** da resposta — risco agregado em *runtime* (Bloco C/D).

> **Regra obrigatória:** `KnowledgeCurationStatus.OUTDATED` **não é**
> `FreshnessStatus.OUTDATED`.

Perigos concretos se os eixos forem confundidos:

- uma FAQ pode estar **importada e actual** (curadoria `IMPORTED`, fonte `CURRENT`);
- uma FAQ pode estar **validada mas não publicada** (curadoria `VALIDATED`,
  `publishedAt = null`);
- uma **fonte pode estar `OUTDATED`** (actualidade) **sem o caso estar arquivado**
  (curadoria ainda `VALIDATED`);
- uma resposta pode ter **`aggregatedRiskLevel` diferente do `riskLevel` persistido**,
  porque o risco agregado depende do contexto da pergunta em *runtime*.

## 12. Contrato de classificação de publicação

**`PublicationClassification`** (conceptual):

- `proposedPath` — `AUTO_CONTROLLED` | `ASSISTED` | `MANUAL_REQUIRED` | `NOT_PUBLISHABLE`;
- `reasons` — razões da classificação;
- `blockingReasons` — razões que impedem publicação;
- `warnings`;
- `requiredEvidence` — evidência em falta para subir de via;
- `reversible` — se a publicação proposta é reversível;
- `auditLevel` — nível de auditoria exigido.

### `AUTO_CONTROLLED`

Só pode ser **proposto** quando **todos** se verificam:

- fonte **oficial**;
- **baixo risco**;
- **suporte forte**;
- **actualidade aceitável**;
- **sem conflito**;
- **sem duplicado material enganador**;
- **`technicalAnswer` segura** (própria e completa);
- **critérios auditáveis**;
- **reversível**.

### `ASSISTED`

Quando:

- há **risco médio**;
- há **ambiguidade**;
- há **fundamento legal provável** (mas não confirmado);
- há **possível duplicado**;
- há **actualidade incerta**;
- há **alteração material**;
- **exige confirmação** humana antes de publicar.

### `MANUAL_REQUIRED`

Quando:

- **risco alto/crítico**;
- **fonte não oficial**;
- **conflito**;
- **actualidade duvidosa**;
- **impacto material**;
- **jurisprudência divergente**;
- **apreciação profissional necessária**.

### `NOT_PUBLISHABLE`

Quando:

- **falta fonte**;
- **falta `technicalAnswer`**;
- **falta rastreabilidade**;
- há **conflito bloqueante**;
- é **duplicado sem utilidade**;
- **fonte inadequada**;
- **estado impede publicação**.

> **Restrições de desenho:** **não usar *scoring* numérico**; **não definir *thresholds*
> definitivos**. A classificação é **qualitativa, justificada e auditável** — cada via é
> acompanhada das suas `reasons`/`blockingReasons`.

## 13. Contrato de relatório de lote

**`BatchReport`** (conceptual):

- `batchId`, `mode`, `status`;
- `startedAt`, `finishedAt`, `triggeredBy`, `sourceSystem`;
- `totals`:
  - `discovered`, `importedRaw`, `preCurated`;
  - `duplicates`, `conflicts`, `rejected`;
  - `autoControlledCandidates`, `assistedCandidates`, `manualRequiredCandidates`,
    `notPublishable`;
  - `published`, `indexed`, `failed`;
- `warnings`, `blockingErrors`;
- `itemSummaries` — resumo por item (sem HTML integral);
- `rollbackPlan` — como reverter/neutralizar;
- `nextActions` — próximas acções recomendadas.

O relatório deve permitir responder:

- **o que aconteceu?**
- **o que ficou pendente?**
- **o que pode ser publicado?**
- **o que exige humano?**
- **o que deve ser rejeitado?**
- **o que pode ser revertido?**

> Alinha e **estende** o `reportJson` já produzido por `AtFaqImportService`/
> `AtFaqIngestionRun` (que hoje só cobre a fase de recolha/importação, sem
> pré-curadoria nem classificação de publicação).

## 14. Idempotência

O lote deve ser **idempotente** sempre que possível.

Chaves possíveis de identidade:

- `sourceSystem`;
- `externalId`;
- `sourceUrl`;
- `contentHash`;
- `normalizedQuestion`;
- `sourceCore`.

Regras:

- **reprocessar o mesmo lote não deve duplicar casos** (reaproveitar a idempotência já
  existente: índice único `ux_kqa_org_source_external` e o índice único parcial da
  camada RAW);
- **alteração material** (hash diferente) deve **criar nova versão** ou **sinalizar
  revisão**, nunca sobrepor silenciosamente;
- **alteração não material** (whitespace, etc.) **não deve republicar**;
- **a idempotência não deve apagar histórico sem decisão** — versões anteriores
  preservam-se (`previousVersionId`, `superseded`).

## 15. Reversão e neutralização

> **Rollback não é apenas `DELETE`.**

Acções possíveis de reversão/neutralização:

- **marcar item bruto como rejeitado**;
- **arquivar candidato** (`ARCHIVED`);
- **retirar publicação** (`unpublish`);
- **desactivar embedding** (remover do índice);
- **criar nova versão** em vez de reescrever;
- **manter histórico**;
- **registar motivo**.

Regras:

- **item publicado não deve ser apagado silenciosamente**;
- **item indexado deve poder ser despublicado/desindexado**
  (`unpublish` → `indexer.remove`, já existente);
- **lote parcialmente revertido deve ficar explícito** (`PARTIALLY_REVERTED`);
- **a reversão deve preservar rastreabilidade** (quem, quando, porquê, âmbito).

## 16. Relação com publicação e indexação

- **`IMPORT` não publica.**
- **`PRE_CURATE` não publica.**
- **`REVIEW` não indexa.**
- **`PUBLISH_GOVERNED` pode publicar** (só itens elegíveis e classificados).
- **só publicação bem-sucedida aciona indexação/RAG.**
- **despublicação deve desactivar ou remover do RAG.**

> **Reforço:** `technicalAnswer` **continua requisito** para o RAG — `RagSearchService`
> filtra por `technical_answer IS NOT NULL` **e** `published_at IS NOT NULL` **e**
> `curation_status = 'VALIDATED'` **e** janela de validade. O contrato de lote não
> afrouxa nenhum destes filtros.

## 17. Relação com DocumentedTaxiaAnswer

- **O lote não gera `DocumentedTaxiaAnswer` directamente.**
- **O lote alimenta conhecimento publicado** (casos Q&A validados + publicados +
  indexados).
- **O conhecimento publicado, quando usado numa pergunta**, alimenta em *runtime*:
  - `SourceEvidence`;
  - `AnswerDecisionService`;
  - `InternalDiagnostics`;
  - `AnswerProjectionService`.

> **A decisão de resposta acontece em *runtime*** (Bloco C/D, quando o cliente pergunta).
> **A decisão de publicação acontece antes**, no pipeline de conhecimento (Bloco E). São
> momentos e responsabilidades distintos e não devem ser fundidos.

## 18. Segurança e auditoria

Sinais mínimos que um lote deve registar (contrato — **não** implementação de auditoria
persistida nesta tarefa):

- **quem/que sistema disparou** o lote;
- **modo**;
- **origem** (fonte, autoridade, URLs);
- **versão do parser/regra/*template*** usada;
- **critérios aplicados**;
- **razões de classificação** de publicação;
- **erros**;
- **efeitos produzidos** (RAW, quarentena, publicações, indexações);
- **reversões**.

> Reaproveita os eventos de auditoria já existentes da ingestão AT
> (`AT_FAQ_*`) e da camada Q&A (`KNOWLEDGE_QA_PUBLISHED/UNPUBLISHED/REINDEXED/…`).
> **Não** se implementa auditoria persistida de lote nesta tarefa.

## 19. Critérios mínimos para futuro E4

E4 só deve implementar lote controlado **se E3 estiver aceite**. O primeiro lote técnico
deve:

- operar sobre **fonte controlada ou *fixtures* locais**;
- **não chamar URLs externas** por defeito;
- **não publicar**;
- **não indexar**;
- **produzir relatório** (`BatchReport`);
- **preservar idempotência**;
- **deixar claro o que seria candidato a cada via** (`AUTO_CONTROLLED`/`ASSISTED`/
  `MANUAL_REQUIRED`/`NOT_PUBLISHABLE`), sem publicar nenhum.

## 20. Fora de âmbito

Explicitamente **fora** desta tarefa (E3):

- *scraping* real;
- chamadas externas;
- importação real;
- publicação real;
- embeddings;
- migrações;
- endpoints novos;
- UI;
- *scoring* numérico;
- deduplicação semântica avançada;
- auditoria persistida;
- rollback implementado.

## 21. Critério de conclusão de E3

E3 considera-se concluída quando:

- o **contrato está criado** (este documento);
- estão definidos os conceitos de **lote / item bruto / item pré-curado / candidato Q&A
  / fonte / classificação de publicação / relatório**;
- a **separação de eixos** está documentada (incluindo `KnowledgeCurationStatus.OUTDATED`
  ≠ `FreshnessStatus.OUTDATED`);
- está **pronto para E4** (critérios mínimos do primeiro lote técnico, §19);
- o [roadmap.md](roadmap.md) marca E3 como concluída e referencia este documento;
- só documentação em `docs/` foi alterada.

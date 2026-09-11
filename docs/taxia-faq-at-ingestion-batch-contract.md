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

## 22. Nota de implementação — E4 (primeira simulação controlada)

Este contrato conceptual foi materializado, pela primeira vez, numa **simulação técnica
controlada** na tarefa E4. A implementação vive no subpacote
`com.knowledgeflow.ingestion.atfaq.batch` e produz um `AtFaqBatchReport` real a partir de
*fixtures* locais, **ainda sem publicação e sem indexação**:

- os conceitos deste documento passam a ter forma de código: `AtFaqBatchReport` (relatório
  auditável), `AtFaqBatchReportTotals`, `AtFaqBatchItemSummary`, `AtFaqControlledBatchItem`
  (item de entrada controlado) e o enum `AtFaqBatchPublicationPath`
  (`AUTO_CONTROLLED`/`ASSISTED`/`MANUAL_REQUIRED`/`NOT_PUBLISHABLE`);
- a classificação continua **sem *scoring*** e aplica a regra **mais-restritivo-ganha**:
  `NOT_PUBLISHABLE > MANUAL_REQUIRED > ASSISTED > AUTO_CONTROLLED`;
- a `contentHash` e a normalização reutilizam o `AtFaqNormalizer` já existente (§ inventário
  E2), garantindo o mesmo eixo de estabilidade de conteúdo;
- os invariantes do §19 são verificados por teste: `published == 0`, `indexed == 0`,
  idempotência ao nível do relatório, e nenhum item publicado/indexado;
- **os modos conceptuais `PRE_CURATE`/`REVIEW`/`PUBLISH_GOVERNED` continuam por
  implementar**: E4 opera em `DRY_RUN` e apenas *propõe* uma via de publicação; não a
  executa.

Detalhe completo de âmbito, classes, invariantes e passo seguinte (E5) em
[taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md).

## 23. Nota de implementação — E5 (pré-curadoria automática)

O conceito de `PreCuratedBatchItem` / `PreCurationResult` deste contrato foi materializado,
pela primeira vez, na tarefa E5, ainda **em memória e sem publicação/indexação**:

- os *records* `AtFaqPreCuratedBatchItem`, `AtFaqPreCurationSourceCandidate`,
  `AtFaqPreCurationTotals` e `AtFaqPreCurationResult` (subpacote
  `com.knowledgeflow.ingestion.atfaq.batch`) dão forma às "propostas estruturadas de
  curadoria" — resposta curta/técnica, tema, risco, fontes candidatas, referências legais,
  actualidade e via de publicação proposta;
- a geração é **determinística e sem LLM**: a resposta técnica nunca é inventada; a
  actualidade é `UNCERTAIN` por defeito (`OUTDATED`/`CURRENT` só com marcador explícito);
- a **via de publicação herda a classificação E4 e só sobe em prudência** — regra
  mais-restritivo-ganha, nunca relaxa;
- mantêm-se os limites: **pré-curado não é publicado, não é indexado, não entra no RAG**; os
  modos `REVIEW`/`PUBLISH_GOVERNED` continuam por implementar.

Detalhe em
[taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md)
(secção E5).

## 24. Nota de implementação — E6 (revisão governada)

E6 materializa o modo conceptual `REVIEW` através de `AtFaqReviewDecision`,
`AtFaqReviewItemResult`, `AtFaqReviewTotals`, `AtFaqReviewResult` e
`AtFaqReviewService`. A revisão decide apenas o próximo portão e aplica a regra
mais-restritivo-ganha: não pode relaxar a prudência proposta por E5.

O `ReviewResult` continua integralmente em memória. Não cria casos persistidos, fontes,
embeddings ou chunks; não chama `PUBLISH_GOVERNED`; não publica nem indexa. Os contadores
`published` e `indexed` existem para tornar a invariância explícita e permanecem sempre zero.
Uma aceitação significa somente candidatura a futura publicação governada em E7.

## 25. Nota de implementação — E7 (plano de publicação governada)

E7 cruza `AtFaqPreCurationResult` (E5) com `AtFaqReviewResult` (E6) e produz um **plano**
através de `AtFaqGovernedPublicationReadiness`, `AtFaqGovernedPublicationGuardResult`,
`AtFaqGovernedPublicationCandidate`, `AtFaqGovernedPublicationTotals`,
`AtFaqGovernedPublicationPlan` e `AtFaqGovernedPublicationPlanService`. O plano volta a
aplicar, por item, todas as guardas de publicação futura e regista se o item poderia seguir
para um passo real posterior (E8+) — que continua **fora de âmbito**.

## 26. Nota de implementação — E8A (materialização sem publicação)

E8A materializa candidatos `READY_FOR_FUTURE_PUBLICATION` como drafts Q&A curáveis em memória.
O draft usa o estado conservador `IMPORTED`, mantém resposta técnica, classificação e fontes,
mas não é persistido, validado, publicado nem indexado. Um `KnowledgeQuestionAnswer` curável
continua fora do RAG enquanto não atravessar os portões posteriores de curadoria, publicação e
indexação. A materialização é determinística e idempotente por `externalId`; os contadores
`published` e `indexed` permanecem sempre a zero.

Planear não é publicar. O `PublicationPlan` mantém-se integralmente em memória: não cria
`KnowledgeQuestionAnswer` nem `KnowledgeSourceReference` persistidos, não gera embeddings,
não corre indexação, não chama `KnowledgeQuestionAnswerPublicationService` nem
`KnowledgeQaEmbeddingIndexerImpl`, e não toca em `RagSearchService`, `GroundingService`,
migrations, endpoints ou frontend. Os contadores `published` e `indexed` do
`PublicationTotals` permanecem sempre zero. `READY_FOR_FUTURE_PUBLICATION` significa apenas
que todas as guardas passaram — nunca significa publicado ou indexado.

## 27. Nota de implementação — E8B.1 (executor DRY-RUN de publicação)

E8B.1 ensaia a publicação governada sobre o `AtFaqMaterializationResult` da E8A. Ensaiar
publicação não é publicar: `AtFaqGovernedPublicationDryRunExecutor` reaplica os guardas usando
o `draft` já transportado em cada item materializado e, quando passam, produz um
`AtFaqPublicationDryRunCommand` **simulado** — com a intenção `wouldPersistKnowledgeQa`,
`wouldCreateSources`, `wouldPublish` e `intendedCurationStatus` (nunca aplicado). `wouldIndex`
é sempre `false`: a indexação é E9 e nunca é ensaiada. Itens não materializados ficam
`skipped`; drafts que já tragam `knowledgeQaId`/`persisted`/`published`/`indexed` são
bloqueados. O relatório mantém `persisted=0`, `published=0`, `indexed=0` e `wouldIndex=0`, não
persiste `KnowledgeQuestionAnswer` nem `KnowledgeSourceReference`, não gera embeddings, não
corre indexação, não chama `KnowledgeQuestionAnswerPublicationService` nem
`KnowledgeQaEmbeddingIndexerImpl` e não toca em `RagSearchService`, `GroundingService`,
migrations, endpoints ou frontend. O ensaio é determinístico e idempotente.

## 28. Nota de implementação — E8B.2 (persistência governada de drafts em BD isolada)

E8B.2 persiste os drafts governados sobre o `AtFaqMaterializationResult` da E8A, cruzados com o
relatório DRY-RUN da E8B.1. Persistir draft não é publicar: `AtFaqGovernedDraftPersistenceService`
reaplica os guardas e escreve cada draft limpo como `KnowledgeQuestionAnswer` em estado
`IMPORTED` — draft curável persistido, não conhecimento publicável — mais as respectivas
`KnowledgeSourceReference`, através dos repositórios JPA e **nunca** de
`KnowledgeQuestionAnswerPublicationService`. `publishedAt` e `publishedBy` permanecem nulos, pelo
que o QA nunca é elegível para RAG (`isEligibleForRag() == false`). A classificação de autonomia
futura (`eligibleForAutoPublicationFuture`, `requiresHumanIntervention`) é registada para preparar
publicação automática governada em E8B.3, mas não desencadeia qualquer efeito aqui. A persistência
é idempotente (`findByOrganizationIdAndSourceSystemAndExternalKey`, sem nova migration) e corre
sobre uma BD isolada de teste (Testcontainers) com uma `Organization` de teste — a base piloto
real não é usada. Mantém `published=0`, `indexed=0`, `embeddings=0`, não gera embeddings, não
corre indexação, não chama `KnowledgeQaEmbeddingIndexerImpl` e não toca em `RagSearchService`,
`GroundingService`, migrations, endpoints, frontend, HTTP externo, scraping, providers ou
auditoria persistida. A execução é determinística (relógio injectável).

## 29. Nota de implementação — E8B.3 (publicação governada real em BD isolada, sem indexação efectiva)

E8B.3 prova a promoção governada até `publishedAt`/`publishedBy` chamando a lógica de publicação
**real**, sem dar voz efectiva ao conhecimento. O `AtFaqGovernedPublicationExecutor` recebe o
`AtFaqDraftPersistenceResult` da E8B.2, seleciona apenas os drafts `eligibleForAutoPublicationFuture`
e sem `requiresHumanIntervention`, promove-os `IMPORTED → VALIDATED` pela API de domínio real
(`markPendingReview()` + `validate(reviewedBy)` — sem reflexão nem atalhos de estado) e chama o
`KnowledgeQuestionAnswerPublicationService.publish(...)` **real**. Como `publish(...)` indexa de
forma síncrona e atómica antes de marcar publicado (**Caso B** do inventário E8B.3-prep), a etapa
corre **exclusivamente** sobre BD isolada (Testcontainers) e profile `pgtest`, onde o indexador
activo é o `StubKnowledgeQaEmbeddingIndexer` (no-op): a publicação chega a
`publishedAt`/`publishedBy` sem gerar qualquer embedding. Cada guarda é reaplicada sobre a entidade
real (estado `IMPORTED` antes da promoção, risco `LOW`, resposta técnica, sinal
`OFFICIAL_SOURCE_PRESENT`, referência legal numa fonte persistida, ≥ 1 fonte); o item publicado fica
`VALIDATED` e, embora `isEligibleForRag()`, **sem embedding não é recuperável** pelo RAG. Mantém
`indexed=0`, `embeddings=0`, `ragExpected=0` e `COUNT(knowledge_qa_embeddings)=0` mesmo após
publicação; é idempotente (segunda execução = *já publicado*, sem `CONFLICT`); e bloqueia/ignora
todas as guardas negativas (autonomia futura ausente, intervenção humana, risco ≠ `LOW`, sem fonte
oficial, sem referência legal, organização errada). Não usa `KnowledgeQaEmbeddingIndexerImpl`, não
toca em `RagSearchService`, `GroundingService`, migrations, endpoints, frontend, HTTP externo,
scraping, providers nem base piloto real. As classes de execução usam o infixo `Execution`
(`AtFaqGovernedPublicationExecution{Command,ItemResult,Totals,Result}`) para não colidirem com a
família E7 do plano de publicação. A execução é determinística (relógio injectável) e o
`actingUserId` de auditoria é derivado de forma determinística de `publishedBy`.

## 30. Nota de implementação — E9A (indexação efectiva de um único Q&A publicado, em BD isolada)

Frase-mestra: **"Indexar não é escalar. É dar voz controlada a um único conhecimento publicado."**

Onde E8B.3 publica **sem** voz no RAG (zero embeddings sob o stub), E9A dá essa voz a **exactamente
um** Q&A já publicado. O `AtFaqGovernedRagIndexingService` recebe o
`AtFaqGovernedPublicationExecutionResult` da E8B.3, filtra os itens publicados, escolhe o primeiro
como alvo (difere os restantes para E9B — política *single-Q&A*, **não-lote**) e, se o Q&A passar
todas as guardas de BD (pertence à organização, `isPublished()`, `VALIDATED`, risco `LOW`, resposta
técnica, ≥ 1 fonte, `isEligibleForRag()`), escreve o embedding pelo contrato real
`KnowledgeQaEmbeddingIndexer` e confirma a linha por SQL. No IT (`pgtest`/Testcontainers) o indexador
injectado é o `KnowledgeQaEmbeddingIndexerImpl` **real** alimentado por um `EmbeddingService`
determinístico de teste (768 dim) — o SQL de *upsert* de produção corre de verdade, mas **sem** o
modelo de embeddings real e **sem qualquer chamada externa** (sem OpenAI, Anthropic, scraping ou
providers). Prova-se o ciclo RAG/pgvector genuíno: antes de E9A `COUNT(knowledge_qa_embeddings)=0`,
depois **exactamente 1** linha, e o `RagSearchService` real (construído no IT com o mesmo embedding
determinístico) recupera esse Q&A para uma pergunta semanticamente compatível
(`KNOWLEDGE_QA`, similaridade ≈ 1.0). É idempotente (o *upsert* sobre `knowledge_qa_id` mantém 1
linha); E9A é deliberadamente mais restrita do que a entidade (recusa risco ≠ `LOW` mesmo quando
`isEligibleForRag()` é `true`); e bloqueia/ignora todas as guardas negativas (nenhum publicado,
entidade `IMPORTED`/não publicada, organização errada, risco ≠ `LOW`). Os contadores trazem *hard
caps* (`indexed ≤ 1`, `embeddingRows ≤ 1`, `ragExpected ≤ 1`) e o relatório não transporta vectores,
passagens, prompts, chunks, HTML nem logs sensíveis. Não altera o `KnowledgeQaEmbeddingIndexerImpl`
de produção, `RagSearchService`, `GroundingService`, migrations, endpoints, frontend nem a base
piloto real. As classes usam o prefixo `AtFaqRagIndexing{Mode,Command,ItemResult,Totals,Result}`. A
execução é determinística (relógio injectável). Próximo passo: E9B (lote governado pequeno sob o mesmo
mecanismo controlado); E10 (rollback/despublicação/desindexação).

## 31. Nota de implementação — E9B (indexação efectiva de um lote pequeno governado, em BD isolada)

Frase-mestra: *"Indexar vários não é escalar livremente. É provar que o lote obedece aos mesmos
guardas do caso único."* A E9B é a **transição controlada** entre o caso único (E9A) e a escala: um
**lote pequeno** — mais do que um, no máximo três — nunca uma automação massiva. Evolui a E9A de forma
**aditiva**: o `AtFaqGovernedRagIndexingService` ganha `indexSmallPublishedBatch(..., int maxItems)` e o
`indexSinglePublishedQa(...)` da E9A fica intacto; as guardas por Q&A são partilhadas pelos dois fluxos,
pelo que o lote obedece **exactamente** às mesmas guardas do caso único (item publicado em E8B.3 e com
`knowledgeQaId`; entidade existente e da organização; `isPublished()`; `VALIDATED`; `isEligibleForRag()`;
resposta técnica não vazia; ≥ 1 fonte; risco `LOW`). `maxItems ∈ [2,3]`: `< 2` recusa como configuração
inválida (o único Q&A usa o fluxo single) e `> 3` excede o teto; em ambos nada é indexado. Os elegíveis
acima do limite são **diferidos** (reportados, nunca descartados). Novo modo `SMALL_BATCH_TEST_ISOLATED`
(o `TEST_ISOLATED_SINGLE_QA` mantém-se); os *caps* do `AtFaqRagIndexingTotals` passam a ser **por modo**
(single ≤ 1, lote ≤ 3), preservando o cap estrutural do caso único; campos aditivos
`requestedMaxItems`/`effectiveMaxItems` no resultado. A indexação efectiva corre **só** em BD isolada
(Testcontainers) com o `KnowledgeQaEmbeddingIndexerImpl` **real** alimentado pelo `EmbeddingService`
determinístico de teste (768 dim), **zero** chamadas externas (sem OpenAI, Anthropic, scraping ou modelo
real). Prova-se: antes 0 embeddings, depois **exactamente N** linhas (1 < N ≤ 3, uma por Q&A); o
`RagSearchService` real recupera **apenas** os indexados, validado por **pertença ao conjunto** (não por
ordem — o embedding determinístico dá similaridade ≈ 1.0 a todos); idempotência (reindexar o lote mantém
N); limite (4 elegíveis + `maxItems=3` ⇒ 3 indexados, 1 diferido); as mesmas guardas negativas recusam
sem indexar (`IMPORTED`, organização errada, risco ≠ `LOW`). O relatório não expõe vector bruto, HTML,
prompts nem chunks. Não se alteram o `KnowledgeQaEmbeddingIndexerImpl` de produção, `RagSearchService`,
`GroundingService`, migrations, endpoints, frontend nem a base piloto real. Próximo passo: E9C (lote
**real/piloto** controlado sob o mesmo mecanismo governado); E10 (rollback/despublicação/desindexação).

## 32. Nota de decisão — E9C-prep (matriz do lote piloto, apenas documentação)

A **E9C-prep** formaliza os **critérios de transição** entre o lote pequeno de teste (E9B, `maxItems
∈ [2,3]`, determinístico) e um eventual **lote piloto** real (E9C): o lote piloto é pequeno
(**máximo recomendado 5–10, preferência por 5**), apenas FAQ AT e apenas `LOW`, com fundamento legal
claro e sem conflitos/duplicados materiais. Fixa a **matriz de autonomia**
(`AUTO_GOVERNED`/`ASSISTED_REQUIRED`/`MANUAL_REQUIRED`/`BLOCKED`) e reafirma que a **intervenção
humana é excepcional e mensurável**, não condição normal de publicação. O **rollback mínimo**
(despublicar, remover embedding, confirmar que o RAG deixou de recuperar, preservar auditoria,
registar motivo, não apagar histórico, permitir reindexação) é **pré-condição de escala**: manual
aceitável para um lote muito pequeno, governado obrigatório para lote maior. Recomendação cautelosa:
preparar o rollback (E10-prep) antes de aumentar o lote. Tarefa **apenas documental**. Ver
[taxia-e9c-pilot-batch-decision-matrix.md](taxia-e9c-pilot-batch-decision-matrix.md).

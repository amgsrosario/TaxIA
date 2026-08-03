# TaxIA — Bloco D — D3 — Contrato DTO/enums da resposta documentada

> **Natureza deste documento.** Fixa o **contrato técnico/documental** da resposta
> documentada da TaxIA — nomes finais, campos, enums, visibilidade e estratégia de
> evolução. É **preparação para implementação futura**: **não** implementa Java, **não**
> cria DTOs/enums reais, **não** altera API, **não** define migrations, **não** toca em
> frontend, dados, publicações ou embeddings. Parte do inventário técnico D2.
>
> Enquadramento: [taxia-documented-answer-technical-plan.md](taxia-documented-answer-technical-plan.md)
> (D1) · [taxia-documented-answer-code-inventory.md](taxia-documented-answer-code-inventory.md)
> (D2) · [taxia-documented-response-dto.md](taxia-documented-response-dto.md) ·
> [taxia-response-model.md](taxia-response-model.md) ·
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) (C1–C9) ·
> [grounding-policy.md](grounding-policy.md) · [roadmap.md](roadmap.md).
>
> **Convenção de leitura.** Ao longo do documento distinguem-se: **[CONTRATO FINAL]**
> (proposta a implementar em D4+); **[COMPAT. TRANSITÓRIA]** (o que se mantém para não
> quebrar consumidores actuais); **[IMPLEMENTAÇÃO FUTURA]** (fica para D4+);
> **[PERSISTÊNCIA FUTURA]** (a decidir depois de estabilizar o contrato); **[EXTERNO]**
> (campo/valor visível em `EXTERNAL`/`DEMO`); **[INTERNO]** (apenas
> `INTERNAL`/`CURATION_ONLY`).

## 1. Objectivo

Este documento **fixa o contrato técnico/documental** da resposta documentada da TaxIA,
para servir de referência estável à implementação futura (D4+). Concretamente:

- é **preparação para implementação futura** — nada é implementado nesta tarefa;
- **não** implementa Java (sem DTOs/enums reais);
- **não** altera a API nem os endpoints existentes;
- **não** define migrations nem colunas;
- parte directamente do **inventário D2**
  ([taxia-documented-answer-code-inventory.md](taxia-documented-answer-code-inventory.md)),
  que confirmou o que existe, é parcial ou falta no código actual;
- preserva integralmente as decisões conceptuais **C1–C9** e os documentos **D1** e
  **D2** — **não** reabre nenhuma decisão conceptual.

## 2. Princípio de evolução

**Regra:** *a evolução deve ser incremental, por adição, preservando o fluxo actual
enquanto se introduz o contrato documentado.* Nada é quebrado de imediato.

Concretização:

- **`GroundedAIResponse` é o ponto de compatibilidade** — evolui como base a expandir
  ou a envolver por `DocumentedTaxiaAnswer`, mantendo os campos actuais enquanto o
  frontend e os testes deles dependerem.
- **`AnswerSource` pode evoluir ou ser adaptado para `SourceEvidence`** — a estrutura
  rica nova convive com a vista simples actual (que pode passar a ser **derivada** dela).
- **Contratos existentes não são quebrados de imediato** — `AdminAIController.AskResponse`
  e o endpoint `/api/v1/admin/ai/ask` mantêm compatibilidade transitória.
- **Campos novos são introduzidos de forma aditiva** — nunca por substituição destrutiva
  de campos ainda consumidos.
- **A projecção externa/interna é uma camada própria** (`AnswerProjection` +
  `AnswerProjectionService`), não lógica dispersa dentro do grounding.
- **Decisão e projecção não se misturam** — a decisão de produto (`answerType`,
  `parecerRequirement`, risco agregado) é separada da transformação de apresentação por
  `visibilityLevel` (C7).

## 3. Nomes finais propostos

Nomes técnicos/documentais fixados como **[CONTRATO FINAL]** de referência para D4+:

**DTO principal**
- `DocumentedTaxiaAnswer`

**DTO de fonte/evidência**
- `SourceEvidence`

**DTO de projecção**
- `AnswerProjection`

**Serviços conceptuais**
- `GroundingEvidenceCollector` — recolhe candidatos/evidência a partir do RAG e do grounding.
- `SourceAssessmentService` — avalia papel, qualidade, núcleo e actualidade das fontes (C9/C8).
- `RiskAggregationService` — calcula `aggregatedRiskLevel` sobre os fundamentos usados (C4).
- `AnswerDecisionService` — decide `answerType` e `parecerRequirement` (C2/C3/C5/C6).
- `AnswerProjectionService` — projecta a resposta por `visibilityLevel` (C7).
- `BoundaryAnswerBuilder` — constrói a Resposta-limite (evolução do `SafeResponseFactory`).
- `ParecerRoutingService` — encaminhamento para Pedido de parecer (C3/C6).
- `InternalDiagnosticsBuilder` — monta o diagnóstico interno (bastidores C7).

> **Nota.** Estes nomes ficam como **contrato de referência** para D4+. A implementação
> pode optar por **compatibilidade transitória** com nomes já existentes (p. ex. manter
> `GroundingService`/`GroundedAIResponse` como base), **desde que a semântica seja
> preservada**. Renomear é decisão de implementação, não desta tarefa.

## 4. DTO principal — `DocumentedTaxiaAnswer`

**[CONTRATO FINAL]** Campos propostos, com tipo conceptual, visibilidade e origem.
Tipos são **conceptuais** (não Java). Visibilidade segue C1/C7.

| Campo | Tipo conceptual | Visibilidade | Origem / notas |
|---|---|---|---|
| `answerId` | UUID/String | **[INTERNO]**; opcional [EXTERNO] se não for ID técnico sensível | Gerado pela resposta. |
| `question` | String | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Pergunta original. |
| `normalizedQuestion` | String | **[INTERNO]** | Pergunta normalizada para pesquisa/diagnóstico. |
| `shortAnswer` | String | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Resposta curta (bloco A). |
| `technicalAnswer` | String | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Resposta técnica documentada (bloco B). |
| `answerType` | `AnswerType` | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY (linguagem profissional) | Forma da resposta (C5); campo central. |
| `supportStatus` | `AnswerSupportStatus` | EXTERNAL/DEMO em linguagem profissional; INTERNAL/CURATION_ONLY com detalhe | **Reutiliza** o enum existente (§7). |
| `aggregatedRiskLevel` | `AggregatedRiskLevel` **ou** `KnowledgeRiskLevel` reutilizado com semântica própria documentada | EXTERNAL/DEMO em linguagem legível; detalhe [INTERNO] | Risco máximo dos **fundamentos usados** (C4). **Não** é o `riskLevel` da entidade `KnowledgeQuestionAnswer`. |
| `parecerRequirement` | `ParecerRequirement` | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | `NONE`/`SUGGESTED`/`REQUIRED` (C3/C6). |
| `visibilityLevel` | `VisibilityLevel` | Usado para projecção; pode **não** ser exposto como campo cru em EXTERNAL/DEMO | Alvo/permissão de projecção (C7). |
| `freshnessStatus` | `FreshnessStatus` | EXTERNAL/DEMO em linguagem profissional; detalhe [INTERNO] | Actualidade agregada da resposta (C8). |
| `confidenceSummary` | String | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Resumo legível de confiança/prudência. |
| `limitations` | List&lt;String&gt; | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Limites da resposta. |
| `assumptions` | List&lt;String&gt; | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Pressupostos assumidos. |
| `missingFacts` | List&lt;String&gt; | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Factos/documentos em falta. |
| `sourceSummary` | String | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Síntese da robustez documental (C9). |
| `sources` | List&lt;`SourceEvidence`&gt; | Projectado conforme `visibilityLevel` | Fontes/evidência (§5). |
| `warnings` | List&lt;String&gt; | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Avisos legíveis. |
| `nextSteps` | List&lt;String&gt; | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Próximos passos (bloco H). |
| `internalDiagnostics` | `InternalDiagnostics` | **[INTERNO]** | Diagnóstico técnico (scores, ranking, contagens, prompts) — nunca EXTERNAL/DEMO. |

> `internalDiagnostics` é um agregado **[INTERNO]** (bastidores C7): scores de
> relevância, ranking, chunks, `unsupportedClaimsCount`, provider/modelo/tokens,
> estado editorial. A sua estrutura detalhada fica para **[IMPLEMENTAÇÃO FUTURA]** (D11).

## 5. DTO de fonte — `SourceEvidence`

**[CONTRATO FINAL]** Campos propostos, com tipo conceptual e visibilidade. Reaproveita
o modelo rico de `KnowledgeSourceReference` (D2) como origem natural.

| Campo | Tipo conceptual | Visibilidade | Notas |
|---|---|---|---|
| `sourceId` | UUID/String | **[INTERNO]**; opcional [EXTERNO] se referência pública não sensível | Identificador da fonte. |
| `title` | String | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Título legível. |
| `sourceType` | `SourceType` **ou** `KnowledgeSourceType` | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY (linguagem profissional) | Tipo documental (legislação, FAQ oficial, etc.). |
| `authorityLevel` | `AuthorityLevel` | **[INTERNO]**; EXTERNAL/DEMO só traduzido para linguagem profissional se útil | Autoridade da fonte (C9). |
| `sourceRole` | `SourceRole` | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY (linguagem profissional) | Papel: principal/complementar/derivada-replicada (C9). |
| `sourceQuality` | `SourceQuality` | EXTERNAL/DEMO em linguagem profissional; detalhe [INTERNO] | Força/robustez (C9). |
| `sourceCore` | String/`SourceCoreRef` | **[INTERNO]** | Núcleo material comum — evita efeito eco (C9). |
| `sourceDiversityGroup` | String/`SourceCoreRef` | **[INTERNO]** | Grupo material para diversidade documental (C9). |
| `freshnessStatus` | `FreshnessStatus` | EXTERNAL/DEMO em linguagem profissional; detalhe [INTERNO] | Actualidade da fonte (C8). |
| `legalReference` | String | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY | Referência legal. |
| `url` | String | EXTERNAL/DEMO/INTERNAL/CURATION_ONLY, quando pública e segura | URL da fonte. |
| `usedInAnswer` | Boolean | **[INTERNO]** | Distingue candidato **recuperado** de fundamento **usado** (C4). |
| `supportsConclusion` | Boolean | **[INTERNO]** | A fonte sustenta a conclusão. |
| `supportsLimitation` | Boolean | **[INTERNO]** | A fonte sustenta uma limitação. |
| `supportsWarning` | Boolean | **[INTERNO]** | A fonte sustenta um aviso. |
| `isDerivativeOrReplicated` | Boolean | **[INTERNO]** | Fonte derivada/replicada (não é confirmação independente — C9). |
| `relatedSources` | List&lt;String/UUID&gt; | **[INTERNO]** | Fontes do mesmo núcleo/relacionadas. |
| `excerpt` | String (opcional) | **[INTERNO]**; EXTERNAL apenas se jurídica/funcionalmente apropriado | Excerto de suporte. |
| `notesInternal` | String/List&lt;String&gt; | **[INTERNO]** | Notas internas de curadoria/diagnóstico. |

## 6. DTO de projecção — `AnswerProjection`

**[CONTRATO FINAL]** Campos propostos:

| Campo | Tipo conceptual | Notas |
|---|---|---|
| `targetVisibilityLevel` | `VisibilityLevel` | Nível-alvo da projecção. |
| `visibleAnswer` | estrutura projectada da resposta | Vista transformada de `DocumentedTaxiaAnswer`. |
| `visibleSources` | List&lt;`SourceEvidence` projectada&gt; | Fontes filtradas/transformadas conforme o nível. |
| `visibleWarnings` | List&lt;String&gt; | Avisos legíveis. |
| `visibleLimitations` | List&lt;String&gt; | Limitações legíveis. |
| `visibleParecerRequirement` | `ParecerRequirement` | Encaminhamento projectado. |
| `hiddenDiagnostics` | List&lt;String&gt; | Elementos **ocultados** por política C7 (registo do que foi omitido). |
| `projectionRulesApplied` | List&lt;String&gt; | Regras aplicadas — **[INTERNO]** ou logs internos. |

Regras do `AnswerProjection`:

- **não decide `answerType`** — recebe-o já decidido pelo `AnswerDecisionService`;
- **apenas transforma a apresentação** (o que se mostra e como), não o conteúdo lógico;
- **não esconde limitações relevantes** — limitações, factos em falta e
  `parecerRequirement` continuam visíveis em EXTERNAL/DEMO;
- **não reduz a qualidade em EXTERNAL/DEMO** — produto profissional limpo, apenas sem
  bastidores (C7).

## 7. Enums finais propostos

**[CONTRATO FINAL]** Enums e valores (sem thresholds, sem scoring — C22/C23).

**`AnswerType`**
- `CONSULTA_DOCUMENTADA`
- `CONSULTA_DOCUMENTADA_COM_LIMITACOES`
- `RESPOSTA_LIMITE`
- `PEDIDO_DE_PARECER`

**`ParecerRequirement`**
- `NONE`
- `SUGGESTED`
- `REQUIRED`

**`VisibilityLevel`**
- `EXTERNAL`
- `DEMO`
- `INTERNAL`
- `CURATION_ONLY`

**`FreshnessStatus`**
- `CURRENT`
- `STABLE_BUT_OLD`
- `UNCERTAIN`
- `OUTDATED`

**`SourceRole`**
- `PRIMARY`
- `COMPLEMENTARY`
- `DERIVATIVE_REPLICATED`

**`SourceQuality`** (valores conceptuais, sem thresholds)
- `STRONG`
- `ADEQUATE`
- `LIMITED`
- `WEAK`

**`SourceDiversity`** (valores conceptuais)
- `MATERIAL_DIVERSITY`
- `SAME_CORE`
- `MIXED_OR_UNCLEAR`

**`AuthorityLevel`** (valores conceptuais)
- `LEGAL`
- `OFFICIAL_ADMINISTRATIVE`
- `OFFICIAL_FAQ`
- `JURISPRUDENCE`
- `OFFICIAL_COMPLEMENTARY`
- `INTERNAL_CURATED`
- `EXTERNAL_NON_OFFICIAL`

**`AggregatedRiskLevel`**
- **Opção preferencial:** reutilizar `KnowledgeRiskLevel` (`LOW`/`MEDIUM`/`HIGH`/`CRITICAL`,
  já existente), **documentando semanticamente** que `aggregatedRiskLevel` é **calculado
  sobre os fundamentos usados** e **não** é o `riskLevel` persistido da entidade.
- **Alternativa futura:** criar enum próprio `AggregatedRiskLevel` **se** a reutilização
  gerar ambiguidade. Decisão fica para **[IMPLEMENTAÇÃO FUTURA]** (D4/D6).

**`AnswerSupportStatus`**
- **Reutilizar** o enum existente (`SUPPORTED`, `PARTIALLY_SUPPORTED`,
  `INSUFFICIENT_CONTEXT`, `REQUIRES_HUMAN_REVIEW`, `REJECTED_UNSUPPORTED`) se cobrir os
  estados necessários.
- Se **não** cobrir, propor **extensão futura** — mas **não** alterar o enum nesta tarefa.

> **Nota de terminologia (C9).** O documento
> [taxia-documented-response-dto.md](taxia-documented-response-dto.md) descreve uma
> `SourceQuality` com valores `OFFICIAL/LEGAL/INTERNAL/UNVERIFIED/MIXED` (eixo de
> **origem/autoridade**). O contrato D3 separa dois eixos: **autoridade** →
> `AuthorityLevel` (que absorve `OFFICIAL_FAQ`/`LEGAL`/`JURISPRUDENCE`/…) e **força
> qualitativa** → `SourceQuality` (`STRONG`/`ADEQUATE`/`LIMITED`/`WEAK`). A conciliação
> definitiva entre os dois eixos fica para **[IMPLEMENTAÇÃO FUTURA]** (D5); ambos são de
> **desenho**, sem thresholds.

## 8. Compatibilidade com o contrato actual

**[COMPAT. TRANSITÓRIA]** Como evoluir sem quebra:

**`GroundedAIResponse`**
- É o **ponto de compatibilidade**.
- Pode ser **expandido** ou **envolvido** por `DocumentedTaxiaAnswer` (camada acima).
- Deve **manter os campos actuais** enquanto o frontend e os testes deles dependerem.

**`AnswerSource`**
- Pode **continuar como vista simples** (`title`, `reference`, `relevanceScore`).
- `SourceEvidence` é criado como **estrutura mais rica**.
- `AnswerSource` pode passar a ser **derivado/projectado** de `SourceEvidence`.

**`AdminAIController.AskResponse`**
- Deve **manter compatibilidade transitória** (consumidores actuais: POC `qa-builder.html`,
  testes).
- Pode, em fase futura, passar a devolver a **resposta documentada** ou um **envelope
  compatível** (campos novos aditivos).
- **D4** deve propor **implementação mínima sem quebrar consumidores**.

**`AssistedInteraction`/`Portal`**
- É um **circuito paralelo** e **não fundamentado** (D2).
- **Não** deve ser alterado em D4 **sem decisão própria**.
- Pode ser **integrado no futuro** no fluxo documentado, mas **não agora**.

## 9. Campos externos vs. internos

Regra (C7): **EXTERNAL/DEMO** recebem **linguagem profissional limpa**;
**INTERNAL/CURATION_ONLY** podem receber **diagnóstico técnico**.

| Campo/conceito | EXTERNAL/DEMO | INTERNAL/CURATION_ONLY |
|---|---|---|
| `answerType` | Sim (linguagem profissional) | Sim (com detalhe) |
| `supportStatus` | Sim (traduzido, sem jargão de grounding) | Sim (valor cru) |
| `aggregatedRiskLevel` | Sim (linguagem legível) | Sim (valor + fundamentos) |
| `parecerRequirement` | Sim | Sim |
| `freshnessStatus` | Sim (linguagem profissional) | Sim (detalhe temporal) |
| `sourceQuality` | Sim (linguagem profissional) | Sim (valor + critérios) |
| `sourceRole` | Sim (linguagem profissional) | Sim |
| `sourceCore` | **Não** | Sim |
| chunks / fragmentos | **Não** | Sim |
| scores de relevância | **Não** (só a prudência que deles decorre) | Sim |
| ranking | **Não** | Sim |
| prompts internos | **Não** | Sim |
| logs | **Não** | Sim |
| `internalDiagnostics` | **Não** | Sim |
| IDs técnicos | **Não** (salvo referência pública não sensível) | Sim |

## 10. Persistência e migrations

**[PERSISTÊNCIA FUTURA]**

- **D3 não define migrations** (regra 24) — nenhuma coluna nova é decidida aqui.
- Muitos campos podem ser **calculados em runtime** inicialmente (`answerType`,
  `parecerRequirement`, `aggregatedRiskLevel`, `freshnessStatus`, `sourceQuality`,
  `sourceRole`, `visibilityLevel`), reduzindo a necessidade de migrations prematuras.
- A **persistência futura** só deve ser decidida **depois de estabilizar o contrato** —
  quando for claro o que precisa de histórico/auditoria persistente.
- `sourceCore`, `sourceQuality` e `freshnessStatus` podem **começar como avaliação
  transitória** (calculada por pedido), não como colunas.
- **Não criar colunas cedo demais.**
- `KnowledgeCurationStatus.OUTDATED` **não** deve ser reutilizado como
  `FreshnessStatus.OUTDATED` — são conceitos distintos (§11).

## 11. Regras de não confusão

Explicitamente:

- `riskLevel` da `KnowledgeQuestionAnswer` **≠** `aggregatedRiskLevel` da resposta.
  (O primeiro é por caso/persistido; o segundo é calculado sobre os fundamentos usados — C4.)
- `KnowledgeCurationStatus.OUTDATED` **≠** `FreshnessStatus.OUTDATED`.
  (O primeiro é estado editorial de curadoria; o segundo é actualidade da fonte na resposta — C8.)
- Fonte **recuperada pelo RAG** **≠** fonte **usada como fundamento**.
  (`usedInAnswer` distingue-as; só as usadas contam para risco/robustez — C4/C9.)
- **Número de fontes** **≠** **diversidade material**.
  (Várias fontes do mesmo `sourceCore` são eco documental, não confirmações independentes — C9.)
- **Projecção por `visibilityLevel`** **≠** **decisão de `answerType`**.
  (A projecção transforma apresentação; não decide a forma da resposta — C7.)
- **Pedido de parecer** **≠** **falha da resposta automática**.
  (É encaminhamento estrutural para o circuito humano, sempre disponível — C3/C6.)
- **Resposta-limite** **≠** **não resposta**.
  (É uma forma de resposta de pleno direito; "não concluir" pode ser útil — C5.)

## 12. Escopo recomendado para D4

**[IMPLEMENTAÇÃO FUTURA]** D4 deve ser **implementação mínima backend, sem frontend, por
adição**.

D4 **deve**:
- introduzir os **enums Java mínimos necessários**;
- **criar/expandir DTOs** sem quebrar `GroundedAIResponse`;
- **mapear `GroundedAIResponse`** para o contrato documentado;
- manter `AdminAIController` **compatível**;
- **não** criar migrations;
- **não** alterar `AssistedInteraction`;
- **não** alterar frontend;
- **adicionar testes unitários mínimos** se houver implementação.

D4 **não** deve ainda:
- implementar o **algoritmo completo de `sourceQuality`**;
- implementar **`sourceCore` automático sofisticado**;
- implementar **projecção no frontend**;
- **persistir** `freshness`/`sourceQuality`;
- **mexer na ingestão massiva**.

## 12.A. Estado de D4 — primeira implementação concluída

**[CONCLUÍDO]** D4 materializou a **primeira implementação backend mínima e aditiva** do
contrato, sem quebrar o fluxo actual. Localização: pacote
`com.knowledgeflow.ai.documented`.

Implementado:
- **Enums Java** (8): `AnswerType`, `ParecerRequirement`, `VisibilityLevel`,
  `FreshnessStatus`, `SourceRole`, `SourceQuality`, `SourceDiversity`, `AuthorityLevel`.
- **DTOs** (3 `record`): `DocumentedTaxiaAnswer`, `SourceEvidence`, `AnswerProjection`
  (este último preparado para D7, **sem** serviço de projecção — regra 21).
- **Mapper** (`@Component` `DocumentedTaxiaAnswerMapper`): converte `GroundedAIResponse`
  no contrato documentado com **defaults transitórios** (não o algoritmo definitivo).
- **`AdminAIController.AskResponse`**: campo `documentedAnswer` acrescentado **por
  adição**; todos os campos antigos preservados.
- **Testes**: `DocumentedTaxiaAnswerMapperTest` (8) + reforço de `AdminAIControllerTest`
  (documentedAnswer presente + campos antigos mantidos).

Defaults transitórios aplicados (a substituir em D5–D7):
- `answerType`/`parecerRequirement` derivados só de `supportStatus` +
  `requiresHumanValidation` (sem scoring nem thresholds).
- `aggregatedRiskLevel = null` (sem dados de risco em `GroundedAIResponse` — não se
  inventa risco; C4).
- `visibilityLevel = INTERNAL`; `freshnessStatus = UNCERTAIN`.
- Fontes → `SourceEvidence` com `sourceRole = PRIMARY`, `sourceQuality = ADEQUATE`,
  `authorityLevel = INTERNAL_CURATED`, `sourceDiversity = MIXED_OR_UNCLEAR`.

**Não** implementado em D4 (mantém-se para fases seguintes): algoritmo real de
`sourceQuality`/`sourceCore`, agregação de risco, projecção por visibilidade,
persistência de campos novos, migrations, frontend.

## 12.B. Estado de D5 — avaliação real de fontes (SourceAssessmentService)

**[CONCLUÍDO]** D5 introduziu o `SourceAssessmentService` (`@Service`, pacote
`com.knowledgeflow.ai.documented`) — avaliação **simples, determinística e conservadora**
de cada `SourceEvidence`, substituindo os defaults cegos por fonte da D4. Não há scoring
numérico, thresholds, deduplicação semântica, análise de núcleo sofisticada, chamadas a
providers nem validação online de URLs (regras 18–21). As heurísticas leem apenas os
campos reais de `AnswerSource` (`title`, `reference`).

Heurísticas implementadas:
- **`authorityLevel`** (por sinais textuais claros, precedência do mais distintivo ao mais
  genérico): jurisprudência (acórdão/tribunal/CAAD/STA/TCAS/TCAN…) → `JURISPRUDENCE`;
  FAQ/perguntas frequentes → `OFFICIAL_FAQ`; ofício circulado/informação vinculativa/
  instrução/despacho/circular/orientação → `OFFICIAL_ADMINISTRATIVE`; código/decreto-lei/
  abreviaturas fiscais (CIRS, CIVA, CIMI…) ou "artigo N.º"/"lei n.º" → `LEGAL`; portal das
  finanças/autoridade tributária/DRE → `OFFICIAL_COMPLEMENTARY`; URL http não oficial →
  `EXTERNAL_NON_OFFICIAL`; sem sinais → `INTERNAL_CURATED` (default conservador).
- **`sourceQuality`** (a partir da autoridade, sem scoring): LEGAL/OFFICIAL_FAQ/
  OFFICIAL_ADMINISTRATIVE → `STRONG`; JURISPRUDENCE/OFFICIAL_COMPLEMENTARY → `ADEQUATE`;
  INTERNAL_CURATED → `ADEQUATE` (com referência) ou `LIMITED` (sem referência);
  EXTERNAL_NON_OFFICIAL → `WEAK`.
- **`sourceRole`**: `PRIMARY` por defeito (fonte devolvida/usada na resposta actual);
  `DERIVATIVE_REPLICATED` só com evidência textual simples de cópia/derivação;
  `COMPLEMENTARY` reservado para quando houver visão do conjunto.
- **`sourceDiversity`/`sourceCore`**: `sourceCore` = referência legal normalizada, senão
  título normalizado, senão `null` (normalização mínima: apara/colapsa espaços/minúsculas);
  `sourceDiversityGroup` = `sourceCore` nesta fase; `sourceDiversity` = `SAME_CORE` com
  evidência de derivação, `MATERIAL_DIVERSITY` com núcleo próprio claro, senão
  `MIXED_OR_UNCLEAR`. Sem fingir diversidade entre fontes (o mapper não tem visão do
  conjunto).
- **`freshnessStatus`**: `UNCERTAIN` por defeito; `OUTDATED` só com marcadores explícitos
  de revogação/caducidade/substituição/"sem efeito" — nunca por data antiga isolada nem
  por `KnowledgeCurationStatus.OUTDATED` (regra 25).

O `DocumentedTaxiaAnswerMapper` passou a injectar o serviço e a preencher cada
`SourceEvidence` com a sua avaliação (incluindo `derivativeOrReplicated` coerente com
`sourceRole`). Mantêm-se: `usedInAnswer = true` para fontes devolvidas, `visibilityLevel =
INTERNAL`, `aggregatedRiskLevel = null`, e `answerType`/`parecerRequirement` baseados no
`supportStatus` (a decisão fina fica para D6). `sourceCore`/`sourceQuality`/`freshness`
continuam **transitórios e não persistidos** (regras 22–23).

## 12.C. Estado de D6 — decisão de prudência (AnswerDecisionService)

**[CONCLUÍDO]** D6 introduziu o `AnswerDecisionService` (`@Service`, pacote
`com.knowledgeflow.ai.documented`) e o record `AnswerDecision`, concentrando a decisão de
forma/prudência que antes estava dispersa no `DocumentedTaxiaAnswerMapper`: `answerType`,
`parecerRequirement`, `confidenceSummary`, `limitations`, `warnings`, `nextSteps` e
`sourceSummary`. Decisão **simples, determinística e conservadora** — sem scoring numérico,
sem thresholds, sem contar fontes em bruto (volume não é robustez), sem projecção por
visibilidade (regras 18–21). Combina `supportStatus` e `requiresHumanValidation` com os
sinais já avaliados em cada `SourceEvidence` (D5).

Regras de decisão implementadas:
- **`answerType`**: `SUPPORTED` → `CONSULTA_DOCUMENTADA`, ou
  `CONSULTA_DOCUMENTADA_COM_LIMITACOES` se houver sinais de fraqueza documental (apenas
  fontes fracas/externas, fonte desactualizada, apenas derivadas, ou ≥2 fontes sem
  diversidade material, ou fontes sem qualidade forte/adequada); `PARTIALLY_SUPPORTED` e
  `REQUIRES_HUMAN_REVIEW` → `CONSULTA_DOCUMENTADA_COM_LIMITACOES`; `INSUFFICIENT_CONTEXT`,
  `REJECTED_UNSUPPORTED` e `null` → `RESPOSTA_LIMITE`. A **ausência de fontes não rebaixa**
  um `SUPPORTED` (evita segundo-adivinhar o grounding).
- **`parecerRequirement`**: base por estado (`SUPPORTED` → `NONE`; `PARTIALLY_SUPPORTED` →
  `SUGGESTED`; `INSUFFICIENT_CONTEXT`/`REQUIRES_HUMAN_REVIEW` → `SUGGESTED` com fontes,
  `REQUIRED` sem fontes; `REJECTED_UNSUPPORTED` → `REQUIRED`; `null` → `SUGGESTED`), depois
  **só escala, nunca desce** perante `requiresHumanValidation`, fontes desactualizadas/só
  fracas, ou `COM_LIMITACOES` (piso `SUGGESTED`).
- **`limitations`/`warnings`/`nextSteps`**: mensagens PT-PT determinísticas, deduplicadas,
  preservando limitações a montante. Resposta-limite **não é não-resposta** (regra 28): dá
  limitações e próximos passos accionáveis. Pedido de parecer **não é erro** (regra 29):
  `nextSteps` inclui submeter parecer.
- **`overallFreshnessStatus`**: `OUTDATED` se alguma fonte estiver desactualizada, senão
  `UNCERTAIN` (nunca confundido com `KnowledgeCurationStatus.OUTDATED` — regra 26).

O `DocumentedTaxiaAnswerMapper` passou a injectar também o `AnswerDecisionService` e a
delegar-lhe toda a decisão; deixou de ter os métodos privados de `answerType`/
`parecerRequirement`/`confidenceSummary`. Mantêm-se `aggregatedRiskLevel = null`,
`visibilityLevel = INTERNAL` e a **não persistência** das decisões (regras 23–24). O
`AdminAIController` (`/api/v1/admin/ai/ask`) e o `GroundedAIResponse`/`AnswerSource` ficam
inalterados (regras 14–16). A projecção por visibilidade continua reservada para D7.

## 12.D. Estado de D7 — projecção por visibilidade (AnswerProjectionService)

**[CONCLUÍDO]** D7 introduziu o `AnswerProjectionService` (`@Service`, pacote
`com.knowledgeflow.ai.documented`) — projecção **simples, determinística e conservadora** de
uma `DocumentedTaxiaAnswer` numa `AnswerProjection`, conforme o `VisibilityLevel`-alvo.
Transforma apenas a **apresentação** (o que se mostra e como): **não decide nem recalcula**
`answerType`, `parecerRequirement`, `supportStatus`, `aggregatedRiskLevel` nem
`freshnessStatus` (regras 24–26) — recebe-os já decididos pelo `AnswerDecisionService` (D6)
e limita-se a preservá-los.

Assinatura: `AnswerProjection project(DocumentedTaxiaAnswer answer, VisibilityLevel target)`.
Fallback de nível: `target` → senão `answer.visibilityLevel()` → senão `INTERNAL` (default
seguro). Não altera o objecto original nem persiste (regras 22–23).

Ao `AnswerProjection` foi acrescentado (por adição) o campo `visibleAnswerType`, para
preservar a forma já decidida — o contrato (§4) já marca `answerType` como visível em todos
os níveis; nunca é recalculado na projecção.

Regras de projecção por nível:
- **`EXTERNAL`/`DEMO`** — produto profissional limpo: `visibleSources` mantém só campos
  seguros (`title`, `sourceType`, `sourceRole`, `sourceQuality`, `freshnessStatus`,
  `legalReference`, `url`, flags `supportsConclusion/Limitation/Warning`) e **oculta**
  `sourceId`, `authorityLevel`, `sourceCore`, `sourceDiversityGroup`, `sourceDiversity`,
  `usedInAnswer`, `derivativeOrReplicated`, `relatedSources`, `excerpt` e `notesInternal`;
  `internalDiagnostics` nunca é exposto. **Preserva** sempre `visibleLimitations`,
  `visibleWarnings` e `visibleParecerRequirement` (regras 27–30). `DEMO` segue exactamente
  a mesma ocultação e qualidade de `EXTERNAL`, com a regra explícita
  `demo:same-quality-as-external` (regra 29).
- **`INTERNAL`** — diagnóstico moderado: mantém `sourceCore`/`sourceDiversityGroup`/
  `sourceDiversity`/`usedInAnswer`/`derivativeOrReplicated`/`relatedSources`/`excerpt`/
  `sourceId`, ocultando apenas `notesInternal` (reservadas a `CURATION_ONLY`).
- **`CURATION_ONLY`** — preserva os bastidores completos, incluindo `notesInternal`; nada é
  ocultado.

`hiddenDiagnostics` regista apenas os **nomes dos tipos** ocultados (nunca valores
sensíveis); `projectionRulesApplied` regista as regras aplicadas (`visibility:<nível>`,
`hide:*`, `preserve:*`) — é diagnóstico da **projecção**, não decisão de resposta (regra 31).

O `AdminAIController` (`/api/v1/admin/ai/ask`) passou a injectar o serviço e a devolver, por
adição não quebrante, o campo `projectedAnswer` no `AskResponse` — projecção `INTERNAL` por
defeito, por ser endpoint de admin; `documentedAnswer` e todos os campos antigos mantêm-se.
**Frontend ainda não foi alterado** (regra 18). A projecção **não é persistida** (regra 22).

## 12.E. Estado de D8 — resolução da lente de projecção (VisibilityLevelResolver)

**[CONCLUÍDO]** D8 introduziu o `VisibilityLevelResolver` (`@Service`, pacote
`com.knowledgeflow.ai.documented`) — ponto único, simples e determinístico, onde se decide
**que vista mostrar** (a lente de projecção), separado de "que resposta dar" (decisão, D6) e
de "como projectar" (transformação, D7). Métodos:

- `resolveForAdminAsk()` → `INTERNAL` (usado no fluxo);
- `resolveForExternalProfessional()` → `EXTERNAL`;
- `resolveForDemo()` → `DEMO`;
- `resolveForCuration()` → `CURATION_ONLY`.

Os três últimos ficam **preparados para integração futura** — ainda não estão ligados a
nenhum fluxo. O `AdminAIController` passou a obter a lente via
`visibilityLevelResolver.resolveForAdminAsk()` (em vez do literal `VisibilityLevel.INTERNAL`)
e continua a chamar o `AnswerProjectionService` com esse valor. Comportamento observável
inalterado: `/api/v1/admin/ai/ask` mantém request, campos antigos, `documentedAnswer` e
`projectedAnswer` (projecção INTERNAL).

**D8 não implementa autorização**: a autenticação/autorização continua no mecanismo já
existente (`@PreAuthorize('hasRole(''ADMIN'')')`); o resolvedor apenas traduz o contexto do
endpoint na lente adequada, sem ler roles nem decidir permissões. Não recalcula
`answerType`/`parecerRequirement` (regras 26–27), não persiste (regra 23) e **não altera o
frontend** (regra 19).

## 12.F. Estado de D9 — frontend da resposta profissional (DocumentedAnswerPanel)

**[CONCLUÍDO]** D9 materializou a **primeira apresentação** da resposta documentada
profissional no backoffice (React/Vite/TS), segundo o lema **"Backend decide. Backend
projecta. Frontend mostra."** O frontend limita-se a apresentar um `AnswerProjection` já
projectado pelo backend, traduzindo enums para rótulos em português de Portugal — **não
decide, não recalcula, não infere**.

Peças criadas:

- **Tipos de vista** em `frontend/src/api/types.ts` — `AnswerProjection`, `VisibleSource` e
  os enums `AnswerType`/`ParecerRequirement`/`VisibilityLevel`/`SourceRole`/`SourceQuality`/
  `FreshnessStatus`. Estes tipos declaram **apenas os campos seguros**: `VisibleSource`
  omite deliberadamente `sourceId`, `authorityLevel`, `sourceCore`, `sourceDiversityGroup`,
  `sourceDiversity`, flags de suporte, `excerpt` e `notesInternal`; o `AnswerProjection` de
  vista omite `hiddenDiagnostics`/`projectionRulesApplied`. Mesmo que esses campos cheguem no
  JSON, não têm forma de serem apresentados a partir daqui.
- **Componente** `frontend/src/components/DocumentedAnswerPanel.tsx` — isolado e reutilizável,
  apresenta: cabeçalho com o tipo de resposta (`CONSULTA_DOCUMENTADA` → "Consulta
  documentada"; `..._COM_LIMITACOES` → "Consulta documentada com limitações";
  `RESPOSTA_LIMITE` → "Resposta-limite"; `PEDIDO_DE_PARECER` → "Pedido de parecer"); corpo da
  resposta (ou mensagem prudente se vazio); necessidade de parecer (`NONE`/`SUGGESTED`/
  `REQUIRED` → rótulos próprios); limitações e avisos (nunca escondidos); e fontes visíveis
  (título, referência legal, papel/qualidade/actualidade traduzidos, URL como ligação só se
  `http(s)`), com estado vazio "Sem fontes visíveis nesta projecção."

`RESPOSTA_LIMITE` e `PEDIDO_DE_PARECER` são apresentados como **respostas profissionais
legítimas**, nunca como erro ou falha. O componente **não** está ligado a nenhuma rota nem
faz chamadas à API: respeita o guard rail do cliente HTTP (que intencionalmente não chama
`/admin/ai/ask` nesta etapa). Não existindo UI activa a consumir o endpoint, a validação foi
feita por `npm run build` (typecheck + build verdes); **não foi possível validação visual**.
Sem alterações a backend, migrations, dados, scripts, contrato de endpoints, ingestão,
publicação, embeddings ou providers externos (regras 3–14, 25).

## 12.G. Estado de D10 — testes de cenários críticos (DocumentedAnswerCriticalScenariosTest)

**[CONCLUÍDO]** D10 cobre os **cenários críticos** que garantem que o modelo de resposta
profissional documentada respeita a filosofia do produto (C1–C9) e o contrato (D1–D9), e não
apenas a mecânica isolada de cada peça. O novo
`DocumentedAnswerCriticalScenariosTest` (pacote `com.knowledgeflow.ai.documented`) compõe os
serviços **reais** (`SourceAssessmentService` → `AnswerDecisionService` →
`DocumentedTaxiaAnswerMapper` → `AnswerProjectionService`, com `VisibilityLevelResolver`
onde faz sentido), sem contexto Spring, de forma rápida e determinística.

Cenários cobertos:

1. **Consulta documentada com fonte forte** — fonte legal (CIRS) → `CONSULTA_DOCUMENTADA`
   limpa, `parecerRequirement=NONE`, qualidade `STRONG`/autoridade `LEGAL`; projecção INTERNAL
   preserva o diagnóstico e EXTERNAL apresenta produto profissional (autoridade/núcleo
   ocultados).
2. **Fonte forte com actualidade incerta** — reflecte o comportamento real de D6: mantém
   `CONSULTA_DOCUMENTADA`, mas assinala prudência nas limitações e no `sourceSummary`
   ("actualidade incerta"); **não** inventa `CURRENT`.
3./13. **Resposta-limite não é erro nem não-resposta** — `INSUFFICIENT_CONTEXT` →
   `RESPOSTA_LIMITE` com corpo não vazio, limitações e `parecerRequirement` ≥ `SUGGESTED`; a
   projecção mostra a resposta, não um erro.
4. **Pedido de parecer não é falha técnica** — `REQUIRES_HUMAN_REVIEW` →
   `CONSULTA_DOCUMENTADA_COM_LIMITACOES`, parecer ≠ `NONE`, avisos/próximos passos, sem
   excepção, projecção visível.
5. **Fonte OUTDATED** — degrada para `CONSULTA_DOCUMENTADA_COM_LIMITACOES`, parecer ≥
   `SUGGESTED` e aviso a tratar a fonte como histórico/contraste/alerta.
6. **Fontes fracas/externas** — `EXTERNAL_NON_OFFICIAL`/`WEAK` não sustentam conclusão limpa:
   limitação de autoridade limitada e aviso de fontes não oficiais.
7. **Fontes derivadas/SAME_CORE** — duas fontes do mesmo núcleo não contam como diversidade
   material (limitação de diversidade), volume não é robustez.
8. **EXTERNAL/DEMO ocultam bastidores** — `sourceCore`/`sourceDiversity*`/`sourceId`/
   `excerpt`/`relatedSources`/`notesInternal` ocultados; `hiddenDiagnostics` só com
   nomes/tipos; limitações, avisos e parecer preservados; DEMO com a mesma qualidade de
   EXTERNAL.
9. **INTERNAL/CURATION_ONLY** — INTERNAL preserva diagnóstico moderado e oculta
   `notesInternal`; CURATION_ONLY preserva tudo (notas e excerto incluídos).
10./14. **Projecção não recalcula decisão** — fontes fortes não fazem a projecção
   reclassificar `RESPOSTA_LIMITE`/`REQUIRED`; `PEDIDO_DE_PARECER` é preservado, nunca erro.
11. **`aggregatedRiskLevel`** — o mapper não o inventa a partir do `riskLevel` da entidade;
   permanece `null` sem cálculo de risco agregado real.
12. **`KnowledgeCurationStatus.OUTDATED` ≠ `FreshnessStatus.OUTDATED`** — a actualidade é
   textual (marcadores de revogação/caducidade); uma fonte legal comum é `UNCERTAIN`, não
   `OUTDATED`.

**Testes frontend:** não há infra-estrutura de testes no backoffice (sem Vitest/RTL); em
conformidade com a tarefa, **não** foram instaladas bibliotecas nem criada configuração — a
apresentação (`DocumentedAnswerPanel`) é validada por `npm run build`. O `DocumentedAnswerPanel`
já não renderiza campos internos por construção dos tipos de vista (§12.F).

Sem alterações a código funcional, migrations, dados, contrato de endpoints ou providers
externos. Suite mínima executada (`DocumentedAnswerCriticalScenariosTest` +
`AnswerProjectionServiceTest` + `AnswerDecisionServiceTest` + `SourceAssessmentServiceTest` +
`DocumentedTaxiaAnswerMapperTest` + `AdminAIControllerTest` + `VisibilityLevelResolverTest`):
87 testes verdes.

## 13. Testes futuros a desenhar

Cenários para D4/D10 (**[IMPLEMENTAÇÃO FUTURA]**, apenas listados):

- `CURRENT` + suporte forte → `CONSULTA_DOCUMENTADA`;
- `STABLE_BUT_OLD` → nota de actualidade;
- `UNCERTAIN` → limitações fortes;
- `OUTDATED` → histórico/`RESPOSTA_LIMITE`;
- fonte HIGH **usada** → `aggregatedRiskLevel` HIGH;
- fonte HIGH **recuperada mas não usada** → **não** contamina `aggregatedRiskLevel`;
- várias fontes `SAME_CORE` → **não** contam como diversidade independente;
- fonte externa isolada → **não** sustenta conclusão fiscal actual;
- `EXTERNAL` **oculta** chunks/scores/ranking;
- `INTERNAL` **mostra** diagnóstico;
- `parecerRequirement REQUIRED` **não** é erro;
- Resposta-limite **não** é resposta vazia.

## 14. Critério de conclusão de D3

- Documento **criado** (este ficheiro).
- **Nomes finais** propostos (DTOs e serviços — §3).
- **Enums finais** propostos (§7).
- **Campos principais** fixados (`DocumentedTaxiaAnswer` §4, `SourceEvidence` §5,
  `AnswerProjection` §6).
- **Compatibilidade** descrita (`GroundedAIResponse`, `AnswerSource`,
  `AdminAIController.AskResponse`, `AssistedInteraction` — §8).
- **Escopo de D4** preparado (§12) e **testes futuros** listados (§13).
- **Sem implementação** — nenhum código, enum, DTO, migration, API, frontend, dado,
  publicação, embedding ou teste alterado/executado.

# TaxIA — Bloco D — D2 — Inventário técnico do código actual

> **Natureza deste documento.** Inventário **de análise** do código actual face ao
> plano D1 ([taxia-documented-answer-technical-plan.md](taxia-documented-answer-technical-plan.md)).
> **Não** implementa, altera ou remove código; **não** cria DTOs/enums Java; **não**
> corre testes; **não** toca em frontend, migrations, dados, publicações ou embeddings.
>
> Legenda de estado: **EXISTE** · **PARCIAL** · **FALTA** · **A CONFIRMAR** ·
> **PROPOSTA** (só D1).
>
> Enquadramento: [taxia-documented-answer-technical-plan.md](taxia-documented-answer-technical-plan.md) ·
> [taxia-documented-response-dto.md](taxia-documented-response-dto.md) ·
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) ·
> [taxia-response-model.md](taxia-response-model.md) ·
> [grounding-policy.md](grounding-policy.md) · [roadmap.md](roadmap.md).

## 1. Objectivo

Inventariar o código backend, frontend e de testes **existente**, confrontá-lo com o
contrato técnico proposto em D1 e identificar o que se reaproveita, o que falta, o que
colide e que riscos existem — para preparar **D3** (contrato DTO/enums final). Esta
tarefa é **apenas de análise e documentação**; nada é implementado.

## 2. Estado Git e base analisada

- **Branch:** `main` (sincronizada com `origin/main`).
- **HEAD:** `c080458 — docs: plan TaxIA documented answer technical contract`.
- **Working tree:** limpo no início da análise.
- **Commits recentes relevantes:** `c080458` (D1), `9f4d94e` (C9, fecho do Bloco C).
- **Data aproximada da análise:** 2026-08-03.
- **Método:** leitura directa dos ficheiros (não inferência por nomes — regra 21).

## 3. Resumo executivo

- **Já existe** uma cadeia de resposta **fundamentada** funcional:
  `AdminAIController /ask` → `RagSearchService` → `GroundingService`
  (`ContextSufficiencyEvaluator` + `AnswerGroundingValidator` + `SafeResponseFactory`)
  → `GroundedAIResponse`. Cobre suporte (`AnswerSupportStatus`), fontes básicas,
  elementos em falta, limitações e sinal de revisão humana.
- **Parcialmente alinhado com D1:** `GroundedAIResponse` ≈ embrião do
  `DocumentedTaxiaAnswer`; `AnswerSource` ≈ embrião pobre de `SourceEvidence`;
  `KnowledgeSourceReference` (entidade) já é um modelo de fonte **rico** (tipo,
  referência legal, URL, janela de validade) que pode alimentar `SourceEvidence`.
- **Maior lacuna:** não existe nenhum dos eixos de produto do Bloco C na camada de
  resposta — **sem** `answerType`, `parecerRequirement`, `visibilityLevel`,
  `freshnessStatus`, `sourceQuality`/`sourceRole`/`sourceDiversity`/`sourceCore`, nem
  `aggregatedRiskLevel` **da resposta** (o `riskLevel` existente é **por entidade QA**,
  não agregado dos fundamentos usados). Não há **projecção** por visibilidade — a
  resposta tem uma vista única.
- **Principal risco técnico:** a decisão de suporte hoje conta **fontes em bruto**
  (distinct por `title` no `ContextSufficiencyEvaluator`), o oposto do princípio C9; e
  as `sources` devolvidas são **todos os candidatos recuperados com conteúdo**, não os
  **fundamentos efectivamente usados** (tensão com C4). Materializar o Bloco C sem
  separar decisão/projecção arrisca espalhar C1–C9 por *ifs* dispersos.
- **Recomendação para D3:** fixar primeiro o **contrato backend de resposta**
  (estender `GroundedAIResponse` ou introduzir `DocumentedTaxiaAnswer` como camada
  acima), mantendo o endpoint `/ask` compatível, **sem** migrations até ser claro o
  que precisa de persistência; separar **decisão** de **projecção**; desenhar testes
  antes da implementação pesada.

## 4. Inventário backend — DTOs e modelos de resposta

| Item | Caminho | Responsabilidade | Campos principais | Compatibilidade com `DocumentedTaxiaAnswer` | Estado |
|---|---|---|---|---|---|
| `GroundedAIResponse` | `ai/grounding/GroundedAIResponse.java` | Resposta fundamentada completa devolvida pelo grounding. | `answer`, `supportStatus`, `supportReason`, `sources`, `missingInformation`, `limitations`, `requiresHumanValidation`, `validationMessage`, `providerCalled`, `responseRejected`, `rejectionReason`, `unsupportedClaimsCount`, `provider`, `model`, tokens, `durationMillis`. | **Embrião** do `DocumentedTaxiaAnswer`: cobre `answer`, `supportStatus`, `missingFacts`, `limitations`, sinal de revisão humana e diagnóstico técnico (tokens/provider). **Falta:** `answerType`, `aggregatedRiskLevel`, `parecerRequirement`, `visibilityLevel`, `freshnessStatus`, `sourceSummary`, `shortAnswer`/`technicalAnswer` separados, `assumptions`, `nextSteps`. | **PARCIAL** |
| `AnswerSource` | `ai/grounding/AnswerSource.java` | Fonte citada na resposta. | `title`, `reference`, `relevanceScore`. | **Embrião pobre** de `SourceEvidence`. **Falta:** `sourceType`, `authorityLevel`, `sourceRole`, `sourceQuality`, `sourceCore`, `freshnessStatus`, `legalReference`, `url`, `usedInAnswer`, flags de suporte, `isDerivativeOrReplicated`, `relatedSources`. | **PARCIAL** |
| `AIResponse` | `ai/AIResponse.java` | Saída crua do provider (antes do grounding). | `provider`, `modelUsed`, `content`, tokens, `durationMillis`. | Alimenta o diagnóstico interno; não é resposta de produto. | **EXISTE** (fora do contrato de produto) |
| `AdminAIController.AskResponse` | `ai/AdminAIController.java` | Projecção HTTP actual da resposta. | Achata `sources` para `List<String>` (só títulos); expõe `supportStatus` como `String`. | **Vista única** — não há projecção por `visibilityLevel`; perde estrutura das fontes. | **PARCIAL** |
| `AssistedInteractionMessageResponse` | `interactions/dto/...` | Resposta do circuito de interação assistida (portal). | `answer`, `modelUsed`, tokens, `createdAt`. | **Não fundamentada** — não passa pelo grounding (ver §6/§8). | **A CONFIRMAR** (divergência de superfícies) |

## 5. Inventário backend — enums e estados

| Conceito D1 | Existe hoje? | Onde / valores | Estado |
|---|---|---|---|
| `supportStatus` | **Sim** | `ai/grounding/AnswerSupportStatus.java`: `SUPPORTED`, `PARTIALLY_SUPPORTED`, `INSUFFICIENT_CONTEXT`, `REQUIRES_HUMAN_REVIEW`, `REJECTED_UNSUPPORTED`. | **EXISTE** (reutilizar, não renomear) |
| `riskLevel` (entidade) | **Sim** | `knowledge/enums/KnowledgeRiskLevel.java`: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. **Por QA**, não da resposta. | **EXISTE** (mas ≠ `aggregatedRiskLevel`) |
| `aggregatedRiskLevel` (resposta) | **Não** | — | **FALTA** |
| `answerType` | **Não** | Nenhum enum `CONSULTA_DOCUMENTADA`/`RESPOSTA_LIMITE`/… | **FALTA** |
| `parecerRequirement` | **Não** | — | **FALTA** |
| `visibilityLevel` | **Não** | — | **FALTA** |
| `freshnessStatus` | **Não** | Existe janela `validFrom`/`validTo` (dados), mas **não** o estado C8. | **FALTA** |
| `sourceQuality` | **Não** (como enum de resposta) | Existe `KnowledgeSourceType` (tipo documental), que **não** é o mesmo que qualidade C9. | **FALTA** |
| `sourceRole` | **Não** | — | **FALTA** |
| `sourceDiversity` | **Não** | — | **FALTA** |
| `sourceCore` | **Não** | — | **FALTA** |
| Estados de curadoria | **Sim** | `knowledge/enums/KnowledgeCurationStatus.java`: `IMPORTED`, `PENDING_REVIEW`, `VALIDATED`, `NEEDS_UPDATE`, `OUTDATED`, `REJECTED`, `ARCHIVED`. | **EXISTE** |
| Tipo de fonte | **Sim** | `knowledge/enums/KnowledgeSourceType.java`: `LEGISLATION`, `ADMINISTRATIVE_GUIDANCE`, `CASE_LAW`, `OFFICIAL_FAQ`, `INTERNAL_OPINION`, `ACCOUNTING_STANDARD`, `OTHER`. | **EXISTE** (candidato a `authorityLevel`) |

> **Colisão de nomes a evitar (importante).** `KnowledgeCurationStatus.OUTDATED` é um
> **estado de curadoria** de uma QA (ciclo de vida editorial). **Não** é o
> `freshnessStatus.OUTDATED` da **resposta** (C8), que classifica a actualidade da
> fonte usada. São conceitos distintos e não devem ser fundidos em D3.

## 6. Inventário backend — grounding e RAG

| Item | Caminho | Função | Estado |
|---|---|---|---|
| `GroundingService` | `ai/grounding/GroundingService.java` | Orquestra: avalia contexto → (recusa ou) chama provider com prompt controlado → valida afirmações → constrói `GroundedAIResponse`. | **EXISTE** |
| `ContextSufficiencyEvaluator` | `ai/grounding/ContextSufficiencyEvaluator.java` | Decide `supportStatus` por nº de fragmentos, **nº de fontes distintas (por `title`)**, melhor score e palavras-chave de risco → `REQUIRES_HUMAN_REVIEW`. | **EXISTE** (conta fontes em bruto) |
| `ContextSufficiencyAssessment` | `ai/grounding/ContextSufficiencyAssessment.java` | `status`, `reason`, `missingElements`, `distinctSourceCount`, `fragmentCount`, `bestRelevanceScore`, `humanReviewRequired`. | **EXISTE** |
| `AnswerGroundingValidator` | `ai/grounding/AnswerGroundingValidator.java` | Detecta afirmações sensíveis (taxas/artigos/valores) e verifica suporte no contexto. | **EXISTE** |
| `SafeResponseFactory` | `ai/grounding/SafeResponseFactory.java` | Constrói `buildRefusal()` e `buildRejected()` — embriões de **Resposta-limite/recusa**. | **EXISTE** (parcial face a C5) |
| `RagSearchService` | `rag/RagSearchService.java` | pgvector cosine; `UNION` de `knowledge_cases` (`DOCUMENT`) e `knowledge_question_answers` (`KNOWLEDGE_QA`); filtra `VALIDATED`, publicado e janela `valid_from`/`valid_to`. Devolve `RetrievedCase(title, question, content, similarity, sourceKind, sourceQaId)`. | **EXISTE** |
| `KnowledgeQaEmbeddingIndexer` (+ `Impl`/`Stub`) | `knowledge/rag/...` | Indexa QA validadas/publicadas no índice de embeddings. | **EXISTE** |

**Fluxo actual candidato → evidência → resposta:**

- **O que o RAG devolve:** os `topK` casos mais similares (documentos + QA), já
  filtrados por validade temporal **no SQL** (`valid_to >= hoje`), com `similarity`.
- **O que é considerado "fonte":** no `GroundingService`, **todos** os `RetrievedCase`
  com conteúdo não vazio (dedup por `title`, mantendo o maior score) — ver
  `GroundingService.java` (construção de `sources`). **Não** distingue candidato
  recuperado de **fundamento usado** na resposta.
- **Como o `supportStatus` é calculado:** no `ContextSufficiencyEvaluator`, por
  limiares de fragmentos/fontes distintas/relevância + palavras-chave de risco; depois
  rebaixado a `PARTIALLY_SUPPORTED` se o validador achar afirmações sem suporte.
- **`sourceCore`:** **não existe** — não há agrupamento de fontes que replicam o mesmo
  núcleo material (eco documental C9).
- **`freshness`:** a validade temporal é aplicada como **filtro binário** no SQL; **não**
  há `freshnessStatus` graduado (C8) exposto por fonte/resposta.
- **Qualidade/diversidade material das fontes:** **não existe** — a "diversidade" hoje
  é contagem de `title` distintos (bruta), o oposto do princípio C9.

## 7. Inventário backend — Knowledge QA e fontes

| Item | Caminho | Campos relevantes | Relação com D1 | Estado |
|---|---|---|---|---|
| `KnowledgeQuestionAnswer` | `knowledge/entity/KnowledgeQuestionAnswer.java` | `originalQuestion/Answer`, `normalizedQuestion`, `shortAnswer`, `technicalAnswer`, `topic`, `subtopic`, `jurisdiction`, `riskLevel`, `requiresHumanValidation`, `curationStatus`, `canonical`, `validFrom/validTo`, `reviewedAt/By`, `publishedAt/By`, `previousVersionId`, auditoria, `version`; `isEligibleForRag()`, `isPublished()`. | Alimenta `question`/`normalizedQuestion`/`shortAnswer`/`technicalAnswer` e o **`riskLevel` por caso** (insumo para agregação C4). `validFrom/validTo` são **insumo** para `freshnessStatus` (C8) — mas não o próprio estado. | **PARCIAL** (insumos ricos; sem eixos de resposta) |
| `KnowledgeSourceReference` | `knowledge/entity/KnowledgeSourceReference.java` | `questionAnswer` (FK), `sourceType`, `title`, `legalReference`, `url`, `documentId`, `fragmentId`, `validFrom/validTo`, `notes`, `createdAt`. | **Modelo de fonte rico** — bom candidato a alimentar `SourceEvidence` (`sourceType`→`authorityLevel`, `legalReference`, `url`, janela→`freshnessStatus`). **Falta:** `sourceRole`, `sourceQuality`, `sourceCore`, `isDerivativeOrReplicated`, `relatedSources`. | **PARCIAL** |
| Embeddings QA | `knowledge_qa_embeddings` (via indexer) | Vector por QA publicada. | Recuperação; não transporta sinais de resposta. | **EXISTE** |
| Publicação/validação/curadoria | métodos da entidade (`validate`, `markPublished`, `markOutdated`, …) | Transições de estado editorial. | Governação editorial; **não** é a decisão de resposta (C2/C5/C6). | **EXISTE** |

> **Nota (C4).** O `riskLevel` da entidade QA é **por caso**. O `aggregatedRiskLevel`
> da resposta (C4 = risco máximo dos **fundamentos relevantes usados**) **não** existe e
> teria de ser calculado a partir dos casos efectivamente usados, não de todos os
> recuperados.

## 8. Inventário backend — controllers/endpoints

| Item | Caminho | Request / Response | Consumidores | Lacunas face a D1 |
|---|---|---|---|---|
| `AdminAIController` | `ai/AdminAIController.java` | `POST /api/v1/admin/ai/ask`, `ROLE_ADMIN`. `AskRequest(question, systemPrompt)` → `AskResponse` (achata fontes a títulos; `supportStatus` como texto). | Backoffice **não** consome hoje (guard rail deliberado — ver §9). | **Vista única**; sem `answerType`/`parecerRequirement`/`visibilityLevel`; sem projecção; perde estrutura das fontes. |
| `AssistedInteractionController` | `interactions/controller/...` | Circuito de interação assistida (mensagens Q&A). | Portal / testes. | **Não passa pelo grounding** (sem `supportStatus`/fontes/risco). Superfície de resposta **paralela** a unificar em fase futura. **A CONFIRMAR** o âmbito. |
| `PortalAssistedInteractionController` | `portal/...` | Exposição no portal do circuito assistido. | Portal de cliente. | Idem — sem contrato documentado. |

## 9. Inventário frontend

- **Stack/estrutura:** React + TypeScript (`frontend/src/`): `LoginPage`, `QaListPage`,
  `QaDetailPage`, `api/client.ts`, `api/types.ts`, `components/Badges.tsx`, `Layout`,
  `AuthContext`.
- **O que apresenta:** é um **backoffice de curadoria de Knowledge QA** — lista/detalhe
  de QA, com `riskLevel`, `curationStatus`, `sourceType` (em `api/types.ts`) e edição de
  fontes.
- **Resposta AI / grounding:** **não integra**. O próprio cliente documenta a omissão
  deliberada — o endpoint `/admin/ai/ask` "EXISTE no backend mas NÃO tem função neste
  cliente" (`frontend/src/api/client.ts`, ~linhas 203–206): "a omissão é um guard rail,
  não um esquecimento".
- **`supportStatus`/risco/fontes da resposta:** não há UI que apresente
  `supportStatus`, `answerType`, fontes estruturadas, risco agregado ou avisos de uma
  resposta documentada.
- **Projecção por nível:** **inexistente** — não há distinção
  `EXTERNAL`/`DEMO`/`INTERNAL`/`CURATION_ONLY` no frontend.
- **POCs:** `qa-builder.html` e `poc.html` (React via CDN) exercitam `/ask`
  directamente, fora do backoffice.

> **Conclusão frontend:** o fluxo de **resposta documentada** está **muito pouco
> integrado** no frontend actual; a UI existente é de curadoria, não de consulta
> profissional. Toda a camada de apresentação da resposta (D9) está por desenhar.

## 10. Inventário de testes

**Já úteis (reaproveitáveis como rede de segurança do grounding actual):**
- `ai/grounding/ContextSufficiencyEvaluatorTest`, `GroundingServiceTest`,
  `GroundingStateMatrixTest`, `GroundingOutcomeRulesTest`, `GroundingClaimFixtureTest`,
  `GroundingKnowledgeQaCompatibilityTest`, `SafeResponseFactoryTest`,
  `AnswerGroundingValidatorTest`, `MonetaryDetectionTest`.
- `ai/AdminAIControllerTest`, `ai/DefaultAIServiceTest`, `ai/AIProviderResolverTest`.
- `pgtest/RagSearchPostgresIT`, `pgtest/KnowledgeQaEmbeddingIndexerPostgresIT`,
  `rag/RagAdminServiceIntegrationTest`, `rag/EmbeddingVectorValidatorTest`.
- `knowledge/…` (curadoria, segurança, E2E, rollback) e `interactions/…`,
  `portal/PortalAssistedInteractionServiceIntegrationTest`.

**Incompletos face a D1 (cobrem o modelo antigo, não os novos eixos):**
- Nenhum teste cobre `answerType`, `parecerRequirement`, `visibilityLevel`,
  `freshnessStatus`, `sourceQuality/Role/Diversity/Core`, `aggregatedRiskLevel` de
  resposta ou **projecção** por visibilidade — porque nada disso existe ainda.

**Inexistentes a criar em D10:**
- Testes de decisão (`AnswerDecisionService`), projecção (`AnswerProjectionService`),
  avaliação de fontes (`SourceAssessmentService`), agregação de risco
  (`RiskAggregationService`), Resposta-limite (`BoundaryAnswerBuilder`) e diagnóstico
  interno vs. vista externa.

## 11. Matriz de alinhamento com D1

| Componente D1 | Existe hoje? | Equivalente actual | Reaproveitamento | Lacuna principal | Risco | Recomendação |
|---|---|---|---|---|---|---|
| `DocumentedTaxiaAnswer` | Parcial | `GroundedAIResponse` | **Alto** | Faltam 6+ eixos de produto | Inchar o record sem separar decisão/projecção | Estender de forma incremental ou envolver numa camada acima. |
| `SourceEvidence` | Parcial | `AnswerSource` + `KnowledgeSourceReference` | **Médio-alto** | Sem papel/qualidade/núcleo/uso | Confundir "recuperada" com "usada" | Unir sinais da entidade fonte + do RAG. |
| `AnswerProjection` | Não | `AskResponse` (vista única) | Baixo | Sem projecção por nível | Acoplar projecção ao grounding | Criar camada de projecção separada (C7). |
| `AnswerDecisionService` | Parcial | `GroundingService` + `ContextSufficiencyEvaluator` | **Médio** | Decide suporte, não `answerType`/`parecer` | Espalhar C1–C9 por *ifs* | Concentrar a decisão de produto num serviço. |
| `AnswerProjectionService` | Não | — | — | Inexistente | — | Desenhar após o contrato (D7). |
| `SourceAssessmentService` | Não | Sinais dispersos (`sourceType`, similaridade, validade) | Baixo-médio | Sem qualidade/diversidade material | Contagem bruta de fontes | Centralizar avaliação de fontes (C9). |
| `RiskAggregationService` | Não | `KnowledgeRiskLevel` por QA | Baixo | Sem agregação por fundamentos usados | Confundir risco de entidade com da resposta | Implementar C4 sobre os casos **usados**. |
| `BoundaryAnswerBuilder` | Parcial | `SafeResponseFactory` | **Médio** | Recusa/rejeição ≠ Resposta-limite estruturada (C5) | Tratar limite como erro | Evoluir factory para Resposta-limite. |
| `ParecerRoutingService` | Não | `requiresHumanValidation` / `REQUIRES_HUMAN_REVIEW` | Baixo-médio | Sem `NONE/SUGGESTED/REQUIRED` | Reduzir parecer a booleano | Introduzir graduação C3/C6. |
| `InternalDiagnosticsBuilder` | Parcial | Campos de diagnóstico em `GroundedAIResponse` (tokens, scores no assessment) | **Médio** | Não separado da vista externa | Vazar bastidores para EXTERNAL | Isolar diagnóstico e projectar só em INTERNAL/CURATION_ONLY (C7). |

## 12. Lacunas principais

- **`answerType`:** **FALTA** por completo (nem enum nem campo).
- **`parecerRequirement`:** **FALTA**; hoje só há booleanos (`requiresHumanValidation`,
  `REQUIRES_HUMAN_REVIEW`), sem graduação `NONE/SUGGESTED/REQUIRED` (C3/C6).
- **`visibilityLevel` + projecção:** **FALTA**; resposta tem vista única, sem
  EXTERNAL/DEMO/INTERNAL/CURATION_ONLY (C1/C7).
- **`freshnessStatus`:** **FALTA** como estado graduado; existe apenas filtro binário de
  validade no SQL do RAG (C8 por materializar).
- **`sourceQuality`/`sourceRole`/`sourceDiversity`/`sourceCore`:** **FALTAM**; a
  "diversidade" actual é contagem bruta de títulos distintos — contrário a C9.
- **`riskLevel` da QA vs. `aggregatedRiskLevel` da resposta:** existe o primeiro (por
  entidade); **falta** o segundo (máximo dos fundamentos **usados**, C4).
- **Fontes recuperadas vs. fundamentos usados:** **não há distinção**; as `sources` da
  resposta são todos os candidatos com conteúdo (tensão com C4/C9).
- **Projecção externa/interna:** inexistente; diagnóstico e resposta partilham a mesma
  estrutura.
- **Persistência:** a maioria dos eixos novos pode viver primeiro como **camada de
  resposta/DTO** (sem migração); só se precisar de histórico/auditoria persistente é
  que exigirá migração — **a confirmar em D3**.
- **Impacto no frontend:** a apresentação da resposta documentada e das suas projecções
  está **toda por construir** (D9); o backoffice actual não consome `/ask`.

## 13. Riscos técnicos

1. **Espalhar a decisão** (C1–C9) por vários serviços/*ifs* dispersos em vez de um
   `AnswerDecisionService` coeso.
2. **Acoplar projecção ao grounding** — misturar C7 com a recolha/validação de contexto.
3. **Transformar C1–C9 em *ifs*** ad-hoc sem um modelo de decisão explícito e testável.
4. **Misturar diagnóstico interno com resposta externa** — risco de vazar bastidores
   (scores, chunks, ranking) para `EXTERNAL`/`DEMO` (viola C7).
5. **Contar fontes em bruto** — perpetuar a lógica actual do
   `ContextSufficiencyEvaluator` (distinct por `title`), contrariando C9.
6. **Confundir `riskLevel` da entidade com `aggregatedRiskLevel` da resposta** (C4).
7. **Exigir migrations cedo demais** — persistir eixos que ainda são de desenho.
8. **Quebrar endpoints existentes** — alterar `AskResponse`/`/ask` de forma
   incompatível com a POC e os testes actuais.
9. **Divergência de superfícies** — o circuito `AssistedInteraction` (portal) responde
   **sem grounding**; materializar a resposta documentada só no `/ask` deixaria duas
   qualidades de resposta (**a confirmar** o plano de unificação).

## 14. Recomendações para D3

D3 deve **decidir e documentar o contrato final DTO/enums antes de implementar**.
Orientações:

- **Começar pelo contrato backend de resposta** (estender `GroundedAIResponse` ou
  introduzir `DocumentedTaxiaAnswer` como camada acima), reutilizando `AnswerSupportStatus`.
- **Manter compatibilidade** com o endpoint `/api/v1/admin/ai/ask` e a sua `AskResponse`
  (evoluir por adição, não por quebra).
- **Introduzir campos novos de forma incremental** (`answerType`, `parecerRequirement`,
  `freshnessStatus`, `sourceQuality`/`Role`/`Diversity`/`Core`, `aggregatedRiskLevel`).
- **Separar decisão de projecção** desde o contrato (dois serviços distintos).
- **Evitar migrations** até ser claro o que precisa de persistência (preferir camada de
  resposta primeiro).
- **Preservar stub/dev sem providers externos** (o grounding já sabe recusar sem chamar
  o provider quando o contexto é insuficiente).
- **Desenhar os testes antes** da implementação pesada (cenários da matriz de decisão de
  D1 §8).
- **Reaproveitar `KnowledgeSourceReference`** como base de `SourceEvidence` e decidir se
  `sourceQuality`/`sourceRole` derivam de `sourceType` + sinais, ou passam a campos
  próprios.
- **Resolver explicitamente** a colisão `curationStatus.OUTDATED` vs.
  `freshnessStatus.OUTDATED` e a divergência do circuito `AssistedInteraction`.

## 15. Fora de âmbito desta análise

- Sem alterações de código Java; sem migrations; sem frontend; sem scripts.
- Sem execução de testes backend; sem publicação de casos; sem embeddings.
- Sem alteração de dados da BD.
- Sem qualquer decisão nova que altere **C1–C9** ou **D1**.

## 16. Critério de conclusão

- Inventário **criado** (este documento).
- Classes, enums, serviços, entidades, endpoints, frontend e testes **mapeados** por
  leitura directa dos ficheiros.
- Lacunas, colisões e riscos **documentados**.
- **D3 preparado** com recomendações concretas de contrato e sequência.

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

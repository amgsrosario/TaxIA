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

## 9. Passo seguinte (E5)

E5 = **pré-curadoria automática**: a partir deste relatório, propor `normalizedQuestion`,
`shortAnswer`/`technicalAnswer`, `topic`, `riskLevel` e candidatos de fonte para os itens,
mantendo tudo em quarentena e **sem publicar**. E4 continua a ser apenas simulação: os modos
`PRE_CURATE`/`REVIEW`/`PUBLISH_GOVERNED` continuam por implementar.

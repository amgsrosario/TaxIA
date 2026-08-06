# TaxIA — Bloco E — E2 — Inventário do pipeline actual de ingestão FAQ AT

> **Natureza.** Documento de **inventário** (só leitura). Não altera código, dados,
> migrações nem comportamento. Regista o que **já existe** no backend para alimentar
> a base de conhecimento a partir das FAQs públicas da Autoridade Tributária (AT) e
> para curar/publicar/indexar casos Q&A. Serve de base factual às tarefas seguintes
> do Bloco E (E3–E10).
>
> Documento-base conceptual: [taxia-assisted-ingestion-curation-policy.md](taxia-assisted-ingestion-curation-policy.md).
> Não reabre nem contradiz os Blocos C e D (fechados).

---

## §1. Objectivo

Levantar, com nomes reais de classes, tabelas e campos, o **pipeline actual** que:

1. recolhe FAQs oficiais da AT (ingestão automática);
2. as coloca em quarentena na camada Q&A;
3. permite curadoria humana (síntese própria da TaxIA);
4. publica casos validados;
5. indexa casos publicados no RAG;
6. serve esses casos ao circuito de resposta documentada (*grounding*).

E, a partir daí, identificar **lacunas** e **riscos técnicos** face às decisões do
Bloco E, recomendando o âmbito de **E3** (contrato técnico de lote de ingestão).

Onde algo não foi confirmado por leitura directa do código, fica assinalado como
**"a confirmar"** — não é afirmado como facto.

## §2. Base conceptual

O inventário assenta nos princípios já fechados:

- **Importar ≠ publicar** ([taxia-assisted-ingestion-curation-policy.md](taxia-assisted-ingestion-curation-policy.md);
  [taxia-core-principles.md](taxia-core-principles.md) §5, §10).
- **O conteúdo da AT é fonte e evidência, nunca resposta canónica automática**
  ([at-faq-ingestion-pilot.md](at-faq-ingestion-pilot.md) §2).
- **Um caso publicado exige `technicalAnswer` própria** — a `shortAnswer` sozinha
  nunca sustenta publicação (regra editorial no próprio código, ver §7).
- **Só a publicação aciona indexação/RAG** (ver §8).
- **Eixo de curadoria ≠ eixo de actualidade textual**:
  `KnowledgeCurationStatus.OUTDATED` (estado de curadoria) **não é**
  `FreshnessStatus.OUTDATED` (actualidade da fonte, Bloco C/D). São coisas distintas
  e este inventário mantém a distinção.

## §3. Estado actual resumido

| Camada | Existe? | Módulo / artefacto |
|---|---|---|
| Ingestão automática das FAQs da AT | **Sim** (piloto técnico, **desligado por defeito**) | `com.knowledgeflow.ingestion.atfaq` (Etapa 10A) |
| Camada RAW (evidência textual) | **Sim** | `at_faq_raw_items` (V15) |
| Quarentena na camada Q&A | **Sim** | `KnowledgeQuestionAnswer` em `IMPORTED` |
| Importação manual CSV/JSON | **Sim** | `KnowledgeQuestionAnswerImportService` |
| Curadoria humana | **Sim** | `KnowledgeQuestionAnswerCurationService` |
| Publicação governada por guards | **Sim** (manual, por caso) | `KnowledgeQuestionAnswerPublicationService` |
| Indexação/embeddings no RAG | **Sim** (só na publicação) | `KnowledgeQaEmbeddingIndexer(Impl)` |
| Pesquisa RAG + *grounding* | **Sim** | `RagSearchService` → `GroundingService` |
| **Lote de ingestão como unidade de curadoria/revisão** | **Não** (lacuna) | — (só existe `AtFaqIngestionRun` como relatório de execução) |
| **Pré-curadoria automática (síntese assistida)** | **Não** (lacuna) | campos curados ficam vazios |
| **Publicação automática controlada por critérios** | **Não** (lacuna) | publicação é sempre acção humana explícita |

**Leitura de topo:** a **recolha** e a **quarentena** estão maduras; a **curadoria
assistida** e a **publicação governada por critérios** (o coração do Bloco E) ainda
**não existem** — hoje tudo o que sai da quarentena depende de acção humana caso a caso.

## §4. Modelo de dados identificado

### 4.1. `KnowledgeQuestionAnswer` (`knowledge_question_answers`, V14)

Entidade central da camada Q&A curável/publicável.
Ficheiro: [KnowledgeQuestionAnswer.java](../src/main/java/com/knowledgeflow/knowledge/entity/KnowledgeQuestionAnswer.java).

| Grupo | Campos |
|---|---|
| Identidade / origem | `id`, `organization` (multi-tenant), `externalKey`, `sourceSystem` |
| Originais imutáveis | `originalQuestion`, `originalAnswer` (nunca reescritos após 1.ª importação) |
| Curados (evoluem) | `normalizedQuestion`, `shortAnswer`, `technicalAnswer` |
| Classificação | `topic` (`KnowledgeTopic`), `subtopic`, `jurisdiction` (default `PT`), `riskLevel` (`KnowledgeRiskLevel`, default `MEDIUM`), `requiresHumanValidation` (default `false`) |
| Curadoria | `curationStatus` (`KnowledgeCurationStatus`, default `IMPORTED`), `canonical` |
| Janela de validade | `validFrom`, `validTo` |
| Revisão | `reviewedAt`, `reviewedBy`, `notes` |
| Publicação | `publishedAt`, `publishedBy` |
| Versionamento | `previousVersionId` (UUID sem FK, permite apagar o anterior) |
| Auditoria / lock | `createdAt`, `updatedAt`, `@Version version` |

Notas relevantes para o Bloco E:
- **Não há campo de "lote"/"batch"** que agrupe casos importados numa mesma execução
  para revisão conjunta. `sourceSystem` (ex.: `at-faq`) e `externalKey`
  (ex.: `AT-FAQ-4583`) identificam a origem, mas não um lote curável.
- **Não há campo de estado de publicação separado** além de `publishedAt`/`publishedBy`
  (a publicação é modelada como "tem `publishedAt` ≠ null").
- **Não há campo de actualidade textual** (`FreshnessStatus`) persistido nesta
  entidade — a actualidade vive no circuito de resposta documentada (Bloco C/D), não aqui.
- Limite de `externalKey`: `EXTERNAL_KEY_MAX_LENGTH = 255`,
  `EXTERNAL_KEY_MAX_IMPORT_LENGTH = 240` (reserva para sufixo de versão `_vN`).

### 4.2. `KnowledgeSourceReference` (`knowledge_source_references`, V14)

Fonte/evidência de um caso Q&A. FK `knowledge_qa_id` com `ON DELETE CASCADE`.
Campos: `sourceType` (`KnowledgeSourceType`), `title`, `legalReference`, `url`,
`documentId`, `fragmentId`, `validFrom`, `validTo`, `notes`, `createdAt`.
Ficheiro: [KnowledgeSourceReference.java](../src/main/java/com/knowledgeflow/knowledge/entity/KnowledgeSourceReference.java).

### 4.3. `knowledge_qa_embeddings` (V14)

Vector `vector(768)`, `UNIQUE (knowledge_qa_id)`, `ON DELETE CASCADE`, índice HNSW
(`vector_cosine_ops`, `m=16`, `ef_construction=64`). **Preenchida apenas na
publicação** por `KnowledgeQaEmbeddingIndexerImpl`. Só guarda o vector — o conteúdo
recuperável vem de `knowledge_question_answers` em tempo de consulta.

### 4.4. Camada RAW da AT (`at_faq_raw_items`, V15)

Evidência textual bruta das FAQs. Uma linha por `(organização, autoridade,
official_faq_id)` **activa** (índice único parcial `WHERE superseded = FALSE`);
versões anteriores preservadas (`superseded = TRUE`, encadeadas por
`previous_version_id`). Campos-chave: `officialFaqId`, `sourceAuthority`, `category`,
`subcategory`, `questionRaw`, `answerRaw`, `sourceUrl`, `sourceTitle`, `fetchedAt`,
`lastSeenAt`, `contentHash`, `parserVersion`, `ingestionStatus`
(`AtFaqIngestionStatus`), `detectedLegalReferences`, `detectedLinks`,
`sourceRemoved`, `sourceChanged`, `consecutiveMissCount`, `importedQaId` (liga ao
Q&A em quarentena).

### 4.5. Execuções e cache (`at_faq_ingestion_runs`, `at_faq_page_snapshots`, V15)

- `at_faq_ingestion_runs` — um registo por execução (`DISCOVER`/`DRY_RUN`/`IMPORT`),
  com `status`, `triggeredBy`, `startedAt`/`finishedAt`, `reportJson`, `errorMessage`.
  **É um relatório de execução, não uma unidade de curadoria de lote.**
- `at_faq_page_snapshots` — cache de GET condicional (ETag/Last-Modified/hash) para
  não reprocessar páginas inalteradas.

## §5. Estados e enums

| Enum | Valores reais | Ficheiro |
|---|---|---|
| `KnowledgeCurationStatus` | `IMPORTED`, `PENDING_REVIEW`, `VALIDATED`, `NEEDS_UPDATE`, `OUTDATED`, `REJECTED`, `ARCHIVED` | [enums/KnowledgeCurationStatus.java](../src/main/java/com/knowledgeflow/knowledge/enums/KnowledgeCurationStatus.java) |
| `KnowledgeRiskLevel` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` | [enums/KnowledgeRiskLevel.java](../src/main/java/com/knowledgeflow/knowledge/enums/KnowledgeRiskLevel.java) |
| `KnowledgeSourceType` | `LEGISLATION`, `ADMINISTRATIVE_GUIDANCE`, `CASE_LAW`, `OFFICIAL_FAQ`, `INTERNAL_OPINION`, `ACCOUNTING_STANDARD`, `OTHER` | [enums/KnowledgeSourceType.java](../src/main/java/com/knowledgeflow/knowledge/enums/KnowledgeSourceType.java) |
| `KnowledgeTopic` | (enum de temas fiscais; importação AT fixa `IVA`) | [enums/KnowledgeTopic.java](../src/main/java/com/knowledgeflow/knowledge/enums/KnowledgeTopic.java) |
| `AtFaqIngestionStatus` | `DISCOVERED`, `FETCHED`, `PARSED`, `NORMALIZED`, `READY_FOR_IMPORT`, `IMPORTED`, `NEEDS_REVIEW`, `CHANGED_AT_SOURCE`, `POSSIBLY_REMOVED`, `FAILED` | [atfaq/AtFaqIngestionStatus.java](../src/main/java/com/knowledgeflow/ingestion/atfaq/AtFaqIngestionStatus.java) |
| `AtFaqRunMode` | `DISCOVER`, `DRY_RUN`, `IMPORT` | [atfaq/AtFaqRunMode.java](../src/main/java/com/knowledgeflow/ingestion/atfaq/AtFaqRunMode.java) |
| `AtFaqRunStatus` | `RUNNING`, `COMPLETED`, `FAILED`, `BLOCKED` | [atfaq/AtFaqRunStatus.java](../src/main/java/com/knowledgeflow/ingestion/atfaq/AtFaqRunStatus.java) |

Transições de curadoria (na entidade, com guards `requireOneOf`):

- `IMPORTED`/`NEEDS_UPDATE` → `PENDING_REVIEW` (`markPendingReview`)
- `PENDING_REVIEW` → `VALIDATED` (`validate`: exige `reviewedBy`, `shortAnswer`
  curada e — a nível de serviço — pelo menos uma fonte)
- `PENDING_REVIEW` → `REJECTED` (`reject`)
- `VALIDATED`/`NEEDS_UPDATE` → `OUTDATED` (`markOutdated`, desmarca canónico)
- `VALIDATED`/`PENDING_REVIEW` → `NEEDS_UPDATE` (`markNeedsUpdate`)
- `REJECTED`/`OUTDATED`/`NEEDS_UPDATE` → `ARCHIVED` (`archive`)
- Publicação/despublicação (`markPublished`/`markUnpublished`) **ortogonais** ao
  `curationStatus`: mexem só em `publishedAt`/`publishedBy`.

> **Reforço (regra do Bloco E).** `KnowledgeCurationStatus.OUTDATED` é um estado de
> **curadoria** (o caso foi despromovido). **Não** é `FreshnessStatus.OUTDATED`, que
> descreve a **actualidade textual da fonte** no circuito de resposta documentada.
> Confundi-los levaria a despublicar/arquivar casos ainda válidos, ou a servir como
> actuais casos já superados. E3 deve manter os dois eixos separados.

## §6. Fontes e referências

- **Fonte de escala inicial:** FAQs públicas do Portal das Finanças (AT), área **IVA**,
  subcategorias `faqs-00929` (Direito à Dedução), `faqs-00930` (Taxas),
  `faqs-01010` (Faturação → Enquadramento Legal). Ver [at-faq-ingestion-pilot.md](at-faq-ingestion-pilot.md) §1.
- **Extracção de referências legais:** `AtFaqLegalReferenceExtractor` (regex
  conservadora: artigos, n.ºs, alíneas, CIVA/CIRC/CIRS/LGT/CPPT/RITI, diplomas, verbas,
  listas). Registadas como **evidência não validada juridicamente**, nunca inventadas.
- **Mapeamento origem → Q&A** (em `AtFaqPersistenceService.createQuarantinedQa`):
  `externalKey = AT-FAQ-{officialFaqId}`, `sourceSystem = at-faq`, `topic = IVA`,
  `subtopic = subcategoria`, `riskLevel = MEDIUM`, `requiresHumanValidation = true`,
  `curationStatus = IMPORTED`; `shortAnswer`/`technicalAnswer` **vazios**; nota a
  declarar que o texto é fonte, não resposta canónica. É criada automaticamente uma
  `KnowledgeSourceReference` do tipo `OFFICIAL_FAQ` com URL exacto e referências
  legais detectadas.

## §7. Publicação e elegibilidade

`KnowledgeQuestionAnswerPublicationService`
([ficheiro](../src/main/java/com/knowledgeflow/knowledge/service/KnowledgeQuestionAnswerPublicationService.java)):

- **`publish`** — só se `assertEligible` passar; indexa e só depois marca
  `publishedAt`/`publishedBy`; se a indexação falhar, lança e regista métrica
  `embedding_failure` (não marca publicado). Conflito se já publicado.
- **`unpublish`** — remove embedding e limpa `publishedAt`/`publishedBy`.
- **`reindex`** — reprocessa embedding de caso já publicado (idempotente, *upsert*);
  **bloqueia** se não houver `technicalAnswer`.
- **`createNewVersion`** — despublica o anterior, cria cópia com novo
  `technicalAnswer` ligada por `previousVersionId`, começa em `PENDING_REVIEW`
  (exige re-validação). Preserva histórico.

**Regra de elegibilidade** (`KnowledgeQuestionAnswer.isEligibleForRag` + `assertEligible`):

1. `curationStatus == VALIDATED`;
2. `originalQuestion` não vazio;
3. **`technicalAnswer` presente e não vazia** — *"a `shortAnswer` sozinha nunca
   sustenta publicação"* (regra editorial no próprio código);
4. `validTo` ausente ou no futuro;
5. risco `HIGH`/`CRITICAL` exige `reviewedBy`;
6. **pelo menos uma `KnowledgeSourceReference`** (verificada por
   `sourceRepository.countByQuestionAnswerId`).

> **Observação central para o Bloco E.** A publicação é hoje **exclusivamente manual e
> por caso** (endpoint `POST .../{id}/publish` com `publisherName`). **Não existe**
> nenhum mecanismo que decida "publicação automática controlada vs. assistida vs.
> manual" a partir de critérios (fonte oficial, risco, suporte, actualidade, conflito,
> duplicado). Essa governação — núcleo de E5/E7 — **está por construir**. Os guards
> actuais garantem que nada inseguro é publicado, mas **não automatizam** o que
> *pode* ser publicado.

## §8. Indexação e embeddings

- `KnowledgeQaEmbeddingIndexer` (interface) →
  `KnowledgeQaEmbeddingIndexerImpl` (`@Profile("!(test | pgtest)")`) e
  `StubKnowledgeQaEmbeddingIndexer` (perfis de teste).
- `index(qaId, question, answer, topic)`: constrói *passage* (Tema/Pergunta/Resposta
  validada), obtém vector via `EmbeddingService.embedPassage`, faz `INSERT ...
  ON CONFLICT (knowledge_qa_id) DO UPDATE` em `knowledge_qa_embeddings` (idempotente).
- `remove(qaId)`: `DELETE` da linha de embedding.
- **A indexação só acontece a partir de `publish`/`reindex`/`createNewVersion`** — nunca
  na importação nem na curadoria. Confirma a regra "só a publicação aciona o RAG".
- **`EmbeddingService` usa modelo local**, não API externa (decisão de arquitectura RAG,
  ver [memória do projecto]; dimensão do vector = 768).

## §9. RAG e grounding

- `RagSearchService.findSimilar(organizationId, question)`
  ([ficheiro](../src/main/java/com/knowledgeflow/rag/RagSearchService.java)):
  pesquisa por distância cosseno (`<=>`), **isolada por organização**, unindo duas
  fontes — `knowledge_case_embeddings` (`source_kind = DOCUMENT`) e
  `knowledge_qa_embeddings` (`source_kind = KNOWLEDGE_QA`). Para os Q&A, o `WHERE`
  exige: `curation_status = 'VALIDATED'` **e** `published_at IS NOT NULL` **e**
  `technical_answer IS NOT NULL` **e** janela `valid_from`/`valid_to` a cobrir a data
  actual. Devolve `RetrievedCase(title, question, content, similarity, sourceKind,
  sourceQaId)`.
- `GroundingService` consome `List<RetrievedCase>`, avalia suficiência de contexto
  (`ContextSufficiencyEvaluator`) e constrói um *prompt* controlado com o "CONTEXTO
  DOCUMENTAL AUTORIZADO". É a ponte entre a base curada e a resposta documentada do
  Bloco C/D.
- **Coerência de filtros:** os critérios do RAG (validado + publicado + `technicalAnswer`
  + janela de validade) são **mais estritos** que `isEligibleForRag` (que não reexige
  `published_at`, porque a própria publicação é o passo que o define). Ou seja: um caso
  só é recuperável depois de publicado — alinhado com a regra do Bloco E.

## §10. Endpoints / controllers

### 10.1. `AdminKnowledgeQaController` — `/api/v1/admin/knowledge/qa` (`ROLE_ADMIN`)

| Método | Path | Função | Risco operacional |
|---|---|---|---|
| GET | `/` | Lista Q&A (filtros `status`, `topic`, paginação) | Baixo (leitura) |
| GET | `/{id}` | Detalhe | Baixo |
| POST | `/import` (multipart) | Importa CSV/JSON (`dryRun`, `limit`) | Médio — escreve em quarentena |
| PATCH | `/{id}/curation` | Actualiza campos curados | Médio |
| POST | `/{id}/pending-review` | → `PENDING_REVIEW` | Baixo |
| POST | `/{id}/validate` (`reviewerName`) | → `VALIDATED` | **Alto** — habilita publicação |
| POST | `/{id}/reject` | → `REJECTED` | Médio |
| POST | `/{id}/outdated` | → `OUTDATED` | Médio |
| POST | `/{id}/archive` | → `ARCHIVED` | Médio |
| POST | `/{id}/canonical` (`canonical`) | Marca/desmarca canónico | Médio |
| POST | `/{id}/sources` | Adiciona fonte | Médio |
| GET | `/{id}/sources` | Lista fontes | Baixo |
| DELETE | `/{id}/sources/{sourceId}` | Remove fonte (nunca deixa validado/publicado sem fonte) | Médio |
| POST | `/{id}/publish` (`publisherName`) | **Publica no RAG** | **Alto** — torna pesquisável |
| POST | `/{id}/unpublish` | Despublica (remove embedding) | **Alto** |
| POST | `/{id}/reindex` | Reprocessa embedding (idempotente) | Médio |
| GET | `/similar` (`question`, `topK`) | Similaridade por texto livre | Baixo |
| GET | `/{id}/similar` | Similaridade a partir de caso | Baixo |
| POST | `/{id}/benchmark-draft` | Gera rascunho de benchmark | Baixo |
| POST | `/benchmark-drafts` | Gera rascunhos em lote | Baixo |

### 10.2. `AtFaqIngestionController` — `/api/v1/admin/ingestion/at-faq` (`ROLE_ADMIN`)

| Método | Path | Função | Risco operacional |
|---|---|---|---|
| POST | `/discover` | Descobre categorias autorizadas (não persiste FAQs) | Baixo |
| POST | `/dry-run` (`maxPages`,`maxItems`) | Pipeline completo **sem escritas** (além do run) | Baixo |
| POST | `/import` (`maxPages`,`maxItems`) | Importa para **quarentena** (nunca validado/publicado/embebido) | **Alto** — rede + escrita; exige `enabled=true` |
| GET | `/runs/{id}` | Relatório de um run | Baixo |

Em ambos os controllers a **organização vem do JWT**, nunca do payload; os overrides
de limite **só apertam** os máximos configurados.

## §11. Scripts, seeds e dados piloto

- **Migrações Flyway:** V13 (pgvector + embeddings), **V14** (`knowledge_question_answers`,
  `knowledge_source_references`, `knowledge_qa_embeddings`), **V15** (`at_faq_raw_items`,
  `at_faq_ingestion_runs`, `at_faq_page_snapshots`). Ver
  [src/main/resources/db/migration](../src/main/resources/db/migration).
- **Fixtures de teste da AT:** `src/test/resources/at-faq/` — **fictícias**; nenhuma
  suite toca na internet. `AtFaqRealSitePilotRunner` existe como *runner* manual (não
  faz parte da suite automática — **a confirmar** se está `@Disabled`/opt-in).
- **Scripts operacionais** ([scripts/](../scripts)): `run-dev.*`,
  `run-taxia-benchmark.ps1`, `analyse-taxia-benchmark.ps1`, e backup/restore de BD
  (`scripts/db/...`). **Não há** script de importação em massa de FAQs (a importação
  passa pelos endpoints/serviços).
- **Configuração da ingestão AT** (default seguro): `knowledgeflow.ingestion.at-faq.enabled=false`,
  `max-items=80`, `max-pages=10`, `delay-ms=1500`, allowlist de host
  `info.portaldasfinancas.gov.pt`. Execução agendada **explicitamente proibida**.
- **Casos piloto reais (20 casos):** ainda **não importados** — aguardam material do
  António (Etapa 9B, ver [memória de consolidação]). Este inventário **não** importa nada.

## §12. Testes existentes

Camada Q&A / publicação / RAG (unidade e integração):

- `KnowledgeQuestionAnswerImportServiceTest`, `KnowledgeQuestionAnswerCurationServiceTest`,
  `KnowledgeQuestionAnswerPublicationServiceTest`;
- `AdminKnowledgeQaControllerTest`, `AdminKnowledgeQaControllerSecurityTest`;
- `KnowledgeQaComplianceTest`, `KnowledgeQaPilotPreparationTest`;
- `pgtest` (Testcontainers + `pgvector`): `KnowledgeQaEndToEndPostgresIT`,
  `KnowledgeQaTransactionalRollbackPostgresIT`, `KnowledgeQaSecurityPostgresIT`,
  `KnowledgeQaEmbeddingIndexerPostgresIT`, `RagSearchPostgresIT`,
  `FlywayV13ToV14UpgradeIT`, `BackendResiliencePostgresIT`.

Ingestão AT:

- `AtFaqHtmlParserTest`, `AtFaqNormalizerTest`, `AtFaqLegalReferenceExtractorTest`,
  `AtFaqDiscoveryServiceTest`, `AtFaqHttpClientTest`, `AtFaqIngestionControllerSecurityTest`;
- `AtFaqIngestionPostgresIT` (persistência RAW, unicidade, versões, remoção provável,
  quarentena, **ausência de publicação/embeddings**, isolamento por organização,
  auditoria, idempotência, rollback atómico, zero chamadas externas).

*Grounding*/RAG: suite `com.knowledgeflow.ai.grounding.*` (incl.
`GroundingKnowledgeQaCompatibilityTest`) e `BenchmarkRegressionTest`.

**Cobertura por construir (Bloco E):** não há testes de "lote como unidade",
"pré-curadoria automática" nem "decisão de publicação por critérios" — porque essas
capacidades ainda não existem.

## §13. Lacunas face ao Bloco E

1. **Sem unidade de lote curável.** `AtFaqIngestionRun` é relatório de execução; não há
   entidade/estado que agrupe 20–50 casos importados para revisão e publicação
   conjuntas (E4/E6). Hoje só há `sourceSystem`/`externalKey` por caso.
2. **Sem pré-curadoria automática (E5).** Os campos `shortAnswer`/`technicalAnswer`
   entram **vazios** e ficam vazios até um humano os escrever. Não há síntese assistida
   nem proposta automática de `riskLevel`/`topic` para além do fixo (`IVA`/`MEDIUM`).
3. **Sem publicação automática controlada por critérios (E7).** A publicação é 100%
   manual por caso. Não existe motor que classifique um caso como "automático
   controlado / assistido / manual" a partir de fonte, risco, suporte, actualidade,
   conflito e duplicação.
4. **Sem relatório/ecrã de revisão de lote (E6).** Só existe o `reportJson` por run e o
   ecrã de curadoria caso-a-caso.
5. **Detecção de duplicados/ecos não integrada na decisão de publicação.** Existe
   `KnowledgeDuplicateDetector` e similaridade (`KnowledgeQaSimilarityService`), mas
   **a confirmar** se e como alimentam uma decisão de publicação governada (C9). Hoje
   servem sobretudo apoio ao curador.
6. **Actualidade textual (`FreshnessStatus`) não persistida na camada Q&A.** A validade
   é apenas `validFrom`/`validTo`; o eixo de actualidade do Bloco C/D não está ligado
   ao caso Q&A — E3 terá de decidir se e como o representa sem colidir com
   `KnowledgeCurationStatus.OUTDATED`.
7. **Cobertura temática estreita.** A ingestão AT fixa `topic = IVA` e três
   subcategorias; qualquer expansão exige allowlist, fixtures e revisão jurídica.

## §14. Riscos técnicos

- **Fragilidade do parser** (HTML SharePoint com estilos inline). Mitigação existente:
  falhas de parsing **não** importam conteúdo errado (falha segura), `parserVersion`
  por item, métrica `at_faq_parse_failures_total`. Risco residual: alteração estrutural
  silenciosa reduz o *recall* sem erro visível.
- **Referências legais auto-detectadas não validadas.** Regex conservadora; registadas
  como evidência. Risco de sugerir uma referência incorrecta ao curador — mitigado por
  serem sempre marcadas como "não validadas juridicamente".
- **`riskLevel = MEDIUM` fixo na importação.** Um FAQ que devesse ser `HIGH` entra como
  `MEDIUM`; só a curadoria humana corrige. E5 deve propor risco, não confiar no default.
- **Escala vs. curadoria manual.** Sem unidade de lote nem pré-curadoria, 20–50 casos
  por lote são geríveis à mão, mas centenas não — exactamente o problema que o Bloco E
  visa resolver.
- **Publicação e indexação acopladas numa transação.** Se `embedService`/BD falhar, a
  publicação aborta (bom para consistência), mas uma indexação lenta pode prolongar a
  transação — a confirmar orçamento de tempo do `embedPassage` em lote.
- **Confusão de eixos OUTDATED.** Reiterado em §5: risco de erro humano/lógico se E3–E7
  tratarem estado de curadoria e actualidade textual como o mesmo eixo.
- **Termos de utilização do Portal das Finanças.** Recolha pública identificada e
  limitada, mas expansão exige revisão jurídica (ver [at-faq-ingestion-pilot.md](at-faq-ingestion-pilot.md) §12).

## §15. Recomendações para E3 (contrato técnico de lote de ingestão)

E3 deve definir, sem implementar ainda, o **contrato de um lote de ingestão**:

1. **Unidade de lote.** Decidir se o lote é (a) uma vista lógica sobre
   `AtFaqIngestionRun`/`sourceSystem`+data, ou (b) uma entidade nova (ex.:
   `KnowledgeImportBatch`) com estado próprio (`OPEN`/`IN_REVIEW`/`PARTIALLY_PUBLISHED`/
   `CLOSED`). Preferir a opção que **não reabra** V14/V15 sem necessidade.
2. **Payload do lote.** Que campos cada caso do lote transporta para a decisão de
   publicação: fonte (`OFFICIAL_FAQ`), risco proposto, suporte, sinais de actualidade,
   sinais de duplicação/eco, e se exige síntese própria.
3. **Separação de eixos.** Manter explicitamente distintos: ingestão (RAW/quarentena),
   curadoria (`KnowledgeCurationStatus`), publicação (`publishedAt`) e actualidade
   textual (`FreshnessStatus` do Bloco C/D). Documentar que `OUTDATED` de curadoria ≠
   `OUTDATED` de actualidade.
4. **Critérios de governação (preparar E5/E7, não decidir automatismo agora).**
   Enumerar os sinais que classificam um caso como automático-controlado / assistido /
   manual, sem *scoring* mágico nem *thresholds* opacos — critérios auditáveis e
   reversíveis.
5. **Reversibilidade.** Garantir que o contrato de lote se apoia nos mecanismos já
   existentes de `unpublish`/`remove`/versão (base de E10, rollback).
6. **Idempotência e limites.** Reaproveitar a idempotência de importação
   (`ux_kqa_org_source_external`) e os limites conservadores da ingestão AT; um lote
   deve ser re-executável sem duplicar.

## §16. Fora de âmbito

Este documento **não**: importa casos, publica casos, gera embeddings, corre
indexação, chama providers externos, faz *scraping*, altera código Java, frontend,
migrações, scripts, dados de BD, endpoints ou testes. Também **não reabre** os Blocos
C e D. Qualquer implementação pertence a E3 e seguintes.

## §17. Critério de conclusão de E2

E2 considera-se concluída quando:

- existe este inventário com nomes reais de classes, tabelas, campos, enums, endpoints,
  migrações e testes;
- estão identificadas as lacunas (§13) e os riscos (§14) face ao Bloco E;
- está recomendado o âmbito de E3 (§15);
- o [roadmap.md](roadmap.md) marca E2 como concluída e referencia este documento;
- só documentação em `docs/` foi alterada; sem código, dados ou testes tocados.

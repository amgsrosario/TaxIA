# TaxIA — Bloco E — E8B.3-prep — Inventário do acoplamento publicação-indexação

## 1. Objectivo

Este documento inventaria a ligação técnica entre **publicação**, **indexação**,
**embeddings** e **RAG** no backend KnowledgeFlow, antes de implementar a E8B.3
(publicação governada real). A tarefa é **apenas de leitura e documentação**:

- não implementa publicação real;
- não executa indexação nem gera embeddings;
- não altera código Java, testes, frontend ou migrations;
- não chama serviços em runtime.

Serve para responder a uma pergunta única antes de avançar — a frase-mestra desta
etapa: **«Antes de publicar, confirmar se publicar também indexa.»**

## 2. Estado de partida

A E8B.2 deixou o pipeline no seguinte estado (verificado por
`AtFaqGovernedDraftPersistenceServiceIT`):

- drafts curáveis persistidos em BD isolada de teste (Testcontainers), sobre uma
  `Organization` de teste;
- `KnowledgeQuestionAnswer` e `KnowledgeSourceReference` persistidos;
- `curationStatus = IMPORTED`;
- `publishedAt == null`, `publishedBy == null`;
- `embeddings == 0`;
- `isEligibleForRag() == false`;
- `KnowledgeQuestionAnswerPublicationService` **não** chamado;
- indexador **não** chamado; RAG **não** activado.

Ou seja: existe conhecimento persistido como rascunho, mas nada publicado nem
indexado.

## 3. Serviço de publicação identificado

**Classe:** `com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService`
(`@Service`).

**Dependências injectadas (construtor):**

- `KnowledgeQuestionAnswerRepository qaRepository`;
- `KnowledgeSourceReferenceRepository sourceRepository`;
- `KnowledgeQaEmbeddingIndexer indexer` — **o acoplamento à indexação vive aqui**;
- `AuditService auditService`;
- `KnowledgeFlowMetrics metrics`.

**Métodos principais (todos `@Transactional`):**

- `publish(orgId, actingUserId, publisherName, id)` — publica uma entrada elegível;
- `reindex(orgId, actingUserId, id)` — reconstrói o embedding de um publicado;
- `unpublish(orgId, actingUserId, id)` — despublica e remove o embedding;
- `createNewVersion(orgId, actingUserId, publisherName, previousId, newTechnicalAnswer)`
  — despublica a versão anterior e cria uma nova em `PENDING_REVIEW`.

**Pré-condições de publicação** (documentadas no javadoc da classe e reforçadas por
`assertEligible`): `curationStatus = VALIDATED`; pelo menos uma fonte associada;
`validTo` ausente ou no futuro; para risco `HIGH`/`CRITICAL`, `reviewedBy` presente.

## 4. Fluxo de `publish(...)`

Passo a passo (linhas de `KnowledgeQuestionAnswerPublicationService.publish`):

1. `requireOwned(organizationId, id)` — carrega a entrada e confirma que pertence à
   organização (cross-org responde `NOT_FOUND`, para não revelar existência alheia).
2. Se já publicada (`isPublished()`), lança `CONFLICT` — publicar duas vezes é erro.
3. `assertEligible(qa)`:
   - `qa.isEligibleForRag()` tem de ser `true` (ver §7);
   - `sourceRepository.countByQuestionAnswerId(id) > 0`, senão `VALIDATION_ERROR`.
4. Calcula `activeAnswer` (`technicalAnswer`, com fallback para `shortAnswer`) e o rótulo
   do tópico.
5. **Chama `indexer.index(qaId, originalQuestion, activeAnswer, topicLabel)`** dentro de
   `try/catch`; se falhar, regista métrica `embedding_failure` e **relança** — a
   transacção reverte e a entrada **não** fica publicada.
6. `qa.markPublished(publisherName)` — preenche `publishedAt` e `publishedBy`. **Não
   altera `curationStatus`** (permanece `VALIDATED`).
7. `qaRepository.save(qa)`.
8. Regista auditoria `KNOWLEDGE_QA_PUBLISHED` e métrica `success`.

Conclusão do fluxo: a indexação acontece **antes** da marcação de publicação e **na
mesma transacção**; publicar e indexar são, no código de produção, uma operação
atómica única.

## 5. Acoplamento com indexação

- **A publicação chama o indexador?** Sim — `publish()` chama `indexer.index(...)`
  directamente (não através de outro `@Service`).
- **A publicação cria embeddings?** Sim, na implementação de produção
  (`KnowledgeQaEmbeddingIndexerImpl`): gera o vector e faz upsert em
  `knowledge_qa_embeddings`. Nos profiles `test`/`pgtest`, o bean activo é um **stub
  no-op** e nenhum embedding é escrito.
- **A publicação falha se a indexação falhar?** Sim — a excepção do indexador é
  relançada e a transacção reverte; não há publicação sem indexação bem-sucedida.
- **A indexação é síncrona ou separada?** Síncrona e inline, dentro da transacção de
  `publish()`. Não há fila, evento nem processamento assíncrono.
- **Existe modo de publicar sem indexar?** No código de produção, **não** existe flag
  nem porta alternativa. Existe, porém, um mecanismo por **profile**: em `test`/`pgtest`
  o indexador é o `StubKnowledgeQaEmbeddingIndexer` (no-op), pelo que `publish()` corre
  na íntegra mas sem indexação real. É exactamente assim que o
  `KnowledgeQuestionAnswerPublicationServiceTest` publica sem gerar embeddings.

## 6. Serviço de indexação identificado

**Contrato:** `com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexer` —
`index(qaId, question, answer, topic)` e `remove(qaId)`.

**Implementação de produção:** `KnowledgeQaEmbeddingIndexerImpl`
(`@Profile("!(test | pgtest)")`).

- constrói uma *passage* (`Tema` + `Pergunta` + `Resposta validada`);
- obtém o vector via `EmbeddingService.embedPassage(...)` (modelo local, não API externa);
- escreve em **`knowledge_qa_embeddings`** (esquema V14: `knowledge_qa_id`, `embedding`);
- **idempotência/upsert:** `INSERT ... ON CONFLICT (knowledge_qa_id) DO UPDATE SET
  embedding = EXCLUDED.embedding, indexed_at = NOW()` — repetir nunca duplica;
- **remoção:** `remove(qaId)` faz `DELETE FROM knowledge_qa_embeddings WHERE
  knowledge_qa_id = ?`.

**Stub de teste:** `StubKnowledgeQaEmbeddingIndexer` (`@Profile({"test", "pgtest"})`) —
`index`/`remove` são no-ops (apenas log), para evitar SQL pgvector em H2 e manter as
BD isoladas sem embeddings.

## 7. Regras de elegibilidade para RAG

**`KnowledgeQuestionAnswer.isEligibleForRag()`** exige, cumulativamente:

- `curationStatus == VALIDATED`;
- `originalQuestion` não-vazia;
- `technicalAnswer` não-vazia (regra editorial: a `shortAnswer` sozinha nunca sustenta
  publicação);
- `validTo` ausente ou não expirado;
- para risco `HIGH`/`CRITICAL`: `reviewedBy` presente.

`isPublished()` é ortogonal: devolve `publishedAt != null`. Elegibilidade não é
publicação — um caso pode ser elegível e ainda não estar publicado.

**Filtros reais do `RagSearchService.findSimilar(...)`** (ramo `KNOWLEDGE_QA` da query),
todos aplicados em SQL:

- `kqa.organization_id = ?` — isolamento por organização (multi-tenant);
- `kqa.curation_status = 'VALIDATED'`;
- `kqa.published_at IS NOT NULL` — **filtra por publicação**;
- `kqa.technical_answer IS NOT NULL`;
- `(kqa.valid_to IS NULL OR kqa.valid_to >= CURRENT_DATE)` e
  `(kqa.valid_from IS NULL OR kqa.valid_from <= CURRENT_DATE)`;
- `JOIN knowledge_qa_embeddings` — **um caso sem embedding nunca aparece**, mesmo que
  publicado.

Há, portanto, uma **dupla porta** para o RAG: estado/publicação na tabela principal
**e** existência de embedding. O `GroundingService` é apenas consumidor a jusante dos
`RetrievedCase`; não publica nem indexa.

## 8. Unpublish/reindex/versioning

- **`unpublish(...)`** — exige entrada publicada; chama `indexer.remove(id)` (apaga o
  embedding), `markUnpublished()` (limpa `publishedAt`/`publishedBy`) e guarda. Regista
  auditoria `KNOWLEDGE_QA_UNPUBLISHED`. **Remove o embedding** (não apenas desactiva).
- **`reindex(...)`** — exige publicado **e** `technicalAnswer` não-vazia (entradas
  legadas sem resposta técnica são bloqueadas); reconstrói o embedding via
  `indexer.index(...)` (upsert idempotente); não cria versão nem altera conteúdo
  validado. Auditoria `KNOWLEDGE_QA_REINDEXED`.
- **`createNewVersion(...)`** — despublica a anterior (`indexer.remove` +
  `markUnpublished`), cria cópia com `previousVersionId`, novo conteúdo técnico e estado
  `PENDING_REVIEW` (exige re-validação explícita). Preserva histórico via
  `previousVersionId` (sem FK, para permitir eliminação). Auditoria
  `KNOWLEDGE_QA_VERSION_CREATED`.

**Lacunas:** não há histórico de embeddings (o upsert/`DELETE` sobrepõe/remove sem
versionar o vector); a desindexação é sempre `DELETE` físico, sem *soft-delete*. Estas
lacunas pertencem ao âmbito de E10 (rollback/despublicação/desindexação), não a E8B.3.

## 9. Testes existentes

- **Publicação / unpublish / versioning:**
  `KnowledgeQuestionAnswerPublicationServiceTest` (`@ActiveProfiles("test")`) — cobre
  `publish` preenche `publishedAt`/`publishedBy`, duplo `publish` → `CONFLICT`,
  `unpublish` limpa `publishedAt`, publicação sem fonte → erro, `technicalAnswer`
  obrigatória (`shortAnswer` só → bloqueado), `createNewVersion` com `previousVersionId`
  e despublicação da anterior. Publica com sucesso **porque o indexador é o stub no-op**
  do profile `test`.
- **Reindex:** mesmo ficheiro — `reindex` sem `technicalAnswer` → bloqueado (sem novo
  embedding).
- **Indexação real (SQL):** `KnowledgeQaEmbeddingIndexerPostgresIT` (Testcontainers) —
  exercita o SQL real de `KnowledgeQaEmbeddingIndexerImpl` contra pgvector (o único
  teste que toca o indexador real; o resto da suíte usa stub).
- **RAG:** `RagSearchPostgresIT` (Testcontainers) — pesquisa semântica real;
  `RagAdminServiceIntegrationTest`.
- **Elegibilidade / risco:** `KnowledgeQaComplianceTest` e
  `KnowledgeQuestionAnswerCurationServiceTest` cobrem `isEligibleForRag()` e risco
  `HIGH`/`CRITICAL`; `AtFaqGovernedDraftPersistenceServiceIT` confirma
  `isEligibleForRag() == false` para o draft `IMPORTED`.
- **Fontes obrigatórias:** cobertas em `KnowledgeQuestionAnswerPublicationServiceTest`
  (publicação sem fonte) e na curadoria.

## 10. Implicações para E8B.3

**Caso identificado: Caso B — a publicação chama a indexação automaticamente** (não há
porta de publicação sem indexação no código de produção).

Contudo, o Caso B tem **mitigação nativa já existente** (opção B.1 da própria prompt):
o indexador é substituído por um **stub no-op via profile** (`test`/`pgtest`). É o
mecanismo que o `KnowledgeQuestionAnswerPublicationServiceTest` já usa para publicar sem
gerar embeddings. Logo:

- **não é preciso** criar um método/porta de publicação sem indexação (opção B.2);
- **não é preciso** alterar o `PublicationService` com um modo governado (opção B.3);
- **não é preciso** adiar a publicação real como dry-run reforçado (opção B.4).

O verdadeiro trabalho de E8B.3 **não** está no acoplamento de indexação, mas na
**transição de curadoria**: os drafts da E8B.2 estão em `IMPORTED` e `publish()` exige
`VALIDATED` com `reviewedBy`, `technicalAnswer` e pelo menos uma fonte. E8B.3 terá de
orquestrar `markPendingReview()` → `validate(reviewedBy)` (garantindo fonte) antes de
`publish(...)`, tudo em BD isolada.

## 11. Recomendação técnica

Avançar para E8B.3 como **publicação governada real em BD isolada (Testcontainers,
profile `pgtest`)**, sem alterar código de produção:

1. Reutilizar o pipeline até E8B.2 e promover apenas os drafts com autonomia elegível
   (`eligibleForAutoPublicationFuture == true`) de `IMPORTED` para `VALIDATED`, via as
   transições de domínio existentes (`markPendingReview`, `validate`), garantindo pelo
   menos uma fonte.
2. Chamar `KnowledgeQuestionAnswerPublicationService.publish(...)` real — o stub de
   indexação do profile `pgtest` assegura **zero embeddings reais**.
3. Verificar por asserção: `publishedAt`/`publishedBy` preenchidos, `curationStatus`
   permanece `VALIDATED`, `isEligibleForRag() == true`, e **`COUNT(knowledge_qa_embeddings)
   == 0`** (a indexação real é E9).

Fronteiras a respeitar:

- **indexação real fica para E9** (garantida pelo stub em E8B.3);
- **rollback/despublicação/desindexação fica para E10**;
- a intervenção humana deve ser **excepcional** — a promoção a `VALIDATED` deve ser
  governada por critérios auditáveis, não por gesto manual obrigatório;
- a automação governada é **objectivo explícito** — E8B.3 prova que a publicação
  automática controlada é segura sem accionar o RAG.

## 12. Fora de âmbito

Esta tarefa **não** inclui: publicação real, indexação, geração de embeddings,
alteração de código Java, criação/alteração de endpoints, alteração de frontend,
alteração de migrations, uso de BD real ou de dados do piloto, scraping, chamadas a URLs
ou providers externos, e alteração de `RagSearchService` ou `GroundingService`.

## 13. Critério de conclusão

- inventário criado (`docs/taxia-publication-indexing-coupling-inventory.md`);
- acoplamento publicação-indexação classificado como **Caso B com mitigação nativa por
  profile** (stub no-op em `test`/`pgtest`);
- recomendação para E8B.3 definida: publicação real em BD isolada sob profile `pgtest`,
  sem alterar produção, com o gargalo real na transição `IMPORTED → VALIDATED`;
- pronto para a próxima prompt (E8B.3 real).

# TaxIA — Bloco E — E10-prep — Inventário de rollback, despublicação e desindexação governada

> **Natureza deste documento.** É **apenas inventário técnico-documental** (Bloco E, tarefa
> E10-prep). Inventaria o que o código actual já faz — e não faz — em matéria de **rollback**,
> **despublicação**, **remoção de embeddings** e **desindexação**, antes de implementar E10.
> **Não implementa** rollback, **não** despublica casos, **não** remove embeddings em runtime,
> **não** altera código Java, testes, frontend, endpoints ou migrations, **não** usa BD nem dados
> reais, e **não** executa indexação, publicação ou RAG. Em caso de dúvida entre avançar e
> inventariar primeiro, **prevalece inventariar**.
>
> Nasce da recomendação da matriz **E9C-prep**: preparar o rollback antes de aumentar o lote. Herda
> e **não reabre** o Bloco C (C1–C9), o Bloco D (D1–D11) nem as tarefas E1–E9C-prep. Ver
> [taxia-e9c-pilot-batch-decision-matrix.md](taxia-e9c-pilot-batch-decision-matrix.md),
> [taxia-publication-indexing-coupling-inventory.md](taxia-publication-indexing-coupling-inventory.md),
> [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md),
> [taxia-faq-at-ingestion-batch-contract.md](taxia-faq-at-ingestion-batch-contract.md) e
> [roadmap.md](roadmap.md).

Frase-mestra desta etapa: **«Antes de dar mais voz ao conhecimento, provar como se retira essa
voz.»**

## 1. Objectivo

Inventariar, **por leitura apenas**, os mecanismos existentes de rollback, despublicação, remoção
de embedding e desindexação no backend KnowledgeFlow, respondendo documentalmente a um conjunto
fixo de perguntas de diagnóstico (§5) e identificando as lacunas a resolver antes de E10.

Este documento:

- **inventaria** rollback/despublicação/desindexação (não as executa);
- **não implementa** rollback;
- **prepara** a tarefa E10 (rollback governado);
- **nasce da recomendação** da matriz E9C-prep (preparar o «botão de silêncio» antes de aumentar a
  voz).

## 2. Estado de partida

O que já está provado e fechado (não se reabre):

- **E8B.3** — publicação governada **real** em BD isolada (Testcontainers, profile `pgtest`): só o
  item limpo e elegível chega a `publishedAt`/`publishedBy` em `VALIDATED`, com
  `COUNT(knowledge_qa_embeddings) == 0` (publicado, ainda sem voz no RAG).
- **E9A** — indexação efectiva de **exactamente um** Q&A publicado (`VALIDATED`, `LOW`,
  RAG-elegível), com `EmbeddingService` determinístico de teste (768 dim, sem modelo real nem
  chamadas externas): 0 → 1 linha; o `RagSearchService` real recupera o Q&A.
- **E9B** — indexação de **lote pequeno** (`maxItems ∈ [2,3]`): 0 → N linhas (1 < N ≤ 3), uma por
  Q&A; RAG recupera apenas os indexados; idempotência por *upsert*; diferimento acima do limite.
- **E9C-prep** — matriz de decisão que **recomendou preparar o rollback (E10-prep) antes de
  aumentar o lote**.

Os **dados reais continuam intocados**: tudo foi provado em BD isolada, sem produção, sem base
piloto real, sem dados reais. Esta tarefa (E10-prep) mantém essa fronteira.

## 3. Conceitos

Definições usadas neste documento (para evitar confusão de eixos):

- **rollback** — reverter um efeito de publicação/indexação, devolvendo o Q&A a um estado em que
  **não é recuperável** pelo RAG, de forma governada e auditável.
- **despublicação (*unpublish*)** — limpar `publishedAt`/`publishedBy`, retirando o Q&A do conjunto
  de «casos publicados». Eixo de **publicação**.
- **desindexação** — retirar o Q&A do índice de pesquisa, removendo a sua presença em
  `knowledge_qa_embeddings`. Eixo de **indexação/RAG**.
- **remoção de embedding** — o acto físico de apagar a linha de `knowledge_qa_embeddings` para
  aquele `knowledge_qa_id` (`DELETE`).
- **reindexação (*reindex*)** — reconstruir o embedding de um Q&A publicado (via *upsert*), sem
  alterar conteúdo validado nem criar versão.
- **neutralização** — tornar um item inofensivo para o RAG **sem** apagar histórico (por exemplo,
  despublicar/desindexar mantendo a curadoria e a auditoria). Conceito ainda **não materializado**
  como estado próprio.
- **preservação histórica** — manter o registo do que existiu (originais imutáveis, `curationStatus`,
  versões via `previousVersionId`, eventos de auditoria), mesmo depois de o item deixar de ter voz.
- **auditoria** — registo de quem fez o quê, quando e sobre que entidade (`AuditEvent`).
- **reversibilidade** — a propriedade de um efeito poder ser desfeito de forma controlada e repetível.

Clarificações obrigatórias:

> **Despublicar não é apagar.** Despublicar limpa a publicação; não elimina o Q&A nem a sua curadoria.
>
> **Desindexar não é esquecer.** Remover o embedding tira a voz no RAG; o conhecimento curado e a
> auditoria permanecem.
>
> **Rollback não é destruir histórico.** Reverter a voz não pode significar apagar o rasto do que
> existiu.

## 4. Serviço de publicação/despublicação identificado

**Classe:** `com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService`
(`@Service`).

**Dependências injectadas (construtor):**

- `KnowledgeQuestionAnswerRepository qaRepository`;
- `KnowledgeSourceReferenceRepository sourceRepository`;
- `KnowledgeQaEmbeddingIndexer indexer` — **o acoplamento à indexação/desindexação vive aqui**;
- `AuditService auditService`;
- `KnowledgeFlowMetrics metrics`.

**Métodos relevantes (todos `@Transactional`):**

- `publish(orgId, actingUserId, publisherName, id)` — indexa e marca publicado (atómico).
- `unpublish(orgId, actingUserId, id)` — **a operação de rollback existente**: remove o embedding e
  limpa a publicação.
- `reindex(orgId, actingUserId, id)` — reconstrói o embedding de um publicado (*upsert*).
- `createNewVersion(orgId, actingUserId, publisherName, previousId, newTechnicalAnswer)` —
  despublica a anterior (`indexer.remove` + `markUnpublished`) e cria cópia em `PENDING_REVIEW`.

**Responsabilidades:** orquestrar publicação/despublicação/reindexação e garantir isolamento por
organização (`requireOwned` responde `NOT_FOUND` a acessos *cross-org*, para não revelar a
existência de registos alheios).

**Campos alterados por despublicação:** `publishedAt` → `null`, `publishedBy` → `null` (via
`markUnpublished()` na entidade). **`curationStatus` não é alterado** (permanece `VALIDATED`).

**Efeitos laterais da despublicação:** `indexer.remove(qaId)` (apaga o embedding) e `qaRepository.save(qa)`.

**Auditoria/métricas:** regista `AuditAction.KNOWLEDGE_QA_UNPUBLISHED` (sem metadata). **Não** regista
métrica de despublicação (ver §9/§11).

## 5. Diagnóstico obrigatório

Respostas documentais (confirmadas por leitura directa do código):

| Pergunta | Resposta |
| --- | --- |
| Existe método `unpublish(...)`? | **Sim** — `KnowledgeQuestionAnswerPublicationService.unpublish(orgId, actingUserId, id)`. |
| `unpublish(...)` limpa `publishedAt`? | **Sim** — via `markUnpublished()` (`publishedAt = null`). |
| `unpublish(...)` limpa `publishedBy`? | **Sim** — via `markUnpublished()` (`publishedBy = null`). |
| `unpublish(...)` altera `curationStatus`? | **Não** — permanece `VALIDATED`. |
| `unpublish(...)` chama `indexer.remove(...)`? | **Sim** — antes de `markUnpublished()`/`save()`. |
| `indexer.remove(...)` apaga fisicamente a linha em `knowledge_qa_embeddings`? | **Sim** (produção) — `DELETE FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?`. No stub (`test`/`pgtest`) é *no-op*. |
| Há *soft-delete* de embeddings? | **Não** — apenas `DELETE` físico; não há coluna de estado nem histórico de vector. |
| Há auditoria de `unpublish`? | **Sim** — `KNOWLEDGE_QA_UNPUBLISHED` (actor, organização, entidade, timestamp); **sem motivo** (metadata `null`). |
| Há métricas de `unpublish`? | **Não** — `metrics.recordPublication(...)` só é chamado no `publish`. Lacuna. |
| `reindex(...)` existe? | **Sim** — `reindex(orgId, actingUserId, id)`. |
| `reindex(...)` exige que o Q&A esteja publicado? | **Sim** — senão lança `INVALID_STATE_TRANSITION`. |
| `reindex(...)` faz *upsert*? | **Sim** — chama `indexer.index(...)` (`ON CONFLICT (knowledge_qa_id) DO UPDATE`). |
| `reindex(...)` pode recriar embedding depois de remoção? | **Só se o Q&A continuar publicado.** Após `unpublish`, `isPublished()==false`, logo `reindex` é bloqueado; recriar exige **republicar** (`publish`), que reindexa. |
| `RagSearchService` deixa de recuperar se a embedding for removida? | **Sim** — o `JOIN knowledge_qa_embeddings` exclui quem não tem linha. |
| `RagSearchService` deixa de recuperar se `publishedAt` ficar `null`? | **Sim** — filtro `kqa.published_at IS NOT NULL`. |
| `RagSearchService` deixa de recuperar se `curationStatus` deixar de ser `VALIDATED`? | **Sim** — filtro `kqa.curation_status = 'VALIDATED'`. |
| Rollback actual consegue desfazer publicação + indexação? | **Sim** — `unpublish` remove embedding **e** limpa publicação, de forma atómica. |
| Rollback actual preserva histórico? | **Parcialmente** — preserva curadoria/originais/auditoria/versões; **mas apaga fisicamente o embedding** (sem histórico de vector). |
| Rollback actual exige motivo? | **Não** — `unpublish` não recebe nem regista motivo. |
| Rollback actual é idempotente? | **Parcialmente** — segundo `unpublish` lança `INVALID_STATE_TRANSITION` (não é silenciosamente idempotente); `indexer.remove(...)` em si é idempotente (`DELETE` de linha inexistente = 0 linhas, sem erro). |
| Rollback actual distingue despublicado, desindexado, removido e bloqueado? | **Não** — `unpublish` acopla despublicação + desindexação num só acto; não há estados/enum que os distingam nem conceito de «bloqueado». |
| Que lacunas existem antes de E10 implementação? | Ver §11. |

## 6. Serviço de publicação/despublicação — fluxo actual de `unpublish(...)`

Passo a passo (de `KnowledgeQuestionAnswerPublicationService.unpublish`):

1. `requireOwned(organizationId, id)` — carrega o Q&A e confirma pertença à organização
   (*cross-org* → `NOT_FOUND`).
2. **Validação de estado:** se `!qa.isPublished()`, lança `INVALID_STATE_TRANSITION` («Entry … is
   not currently published»). Despublicar o que não está publicado é erro, não *no-op*.
3. **Chama `indexer.remove(qa.getId())`** — desindexação (remove o embedding).
4. `qa.markUnpublished()` — `publishedAt = null`, `publishedBy = null` (`curationStatus` intacto).
5. `qaRepository.save(qa)` — persiste a despublicação.
6. **Auditoria:** `auditService.record(..., KNOWLEDGE_QA_UNPUBLISHED, "KnowledgeQuestionAnswer", id)`
   — **sem metadata/motivo**.
7. `log.info("Unpublished QA …")`.
8. **Métricas:** **nenhuma** (não há `recordPublication`/equivalente no caminho de despublicação).

Comportamentos de fronteira:

- **Já despublicado:** segundo `unpublish` → `INVALID_STATE_TRANSITION` (não idempotente-silencioso).
- **Sem embedding:** `indexer.remove(...)` faz `DELETE` que afecta 0 linhas — **seguro**, sem erro;
  a despublicação prossegue.
- **Atomicidade:** tudo corre numa transacção; se a remoção do embedding falhasse, a transacção
  reverteria e o Q&A continuaria publicado.

## 7. Fluxo actual de `remove(...)` no indexador

- **Interface:** `com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexer` — `index(qaId,
  question, answer, topic)` e `remove(qaId)`.
- **Implementação real:** `KnowledgeQaEmbeddingIndexerImpl` (`@Profile("!(test | pgtest)")`):
  `remove(qaId)` executa `DELETE FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid`.
- **Tabela afectada:** `knowledge_qa_embeddings` (V14): `knowledge_qa_id UNIQUE`, `embedding
  vector(768)`, `indexed_at`, **`ON DELETE CASCADE`** a partir de `knowledge_question_answers`.
- **`DELETE` ou *soft-delete*?** **`DELETE` físico** — não há *soft-delete* nem coluna de estado.
- **Idempotência:** **idempotente** — remover uma linha inexistente afecta 0 linhas, sem erro.
- **Comportamento se a linha não existir:** *no-op* seguro (0 linhas).
- **Stub (`test`/`pgtest`):** `StubKnowledgeQaEmbeddingIndexer` — `index`/`remove` são *no-ops*
  (apenas log), para evitar SQL pgvector em ambientes sem pgvector e manter BD isoladas sem embeddings.

> **Nota de cascata.** `ON DELETE CASCADE` significa que apagar um `KnowledgeQuestionAnswer`
> remove automaticamente o seu embedding. Mas o rollback governado **não** deve apagar o Q&A — deve
> despublicar/desindexar preservando o histórico (§3).

## 8. Fluxo actual de `reindex(...)`

Passo a passo (de `KnowledgeQuestionAnswerPublicationService.reindex`):

1. `requireOwned(organizationId, id)`.
2. **Pré-condição de publicação:** se `!qa.isPublished()`, lança `INVALID_STATE_TRANSITION`
   («Only published entries can be reindexed — use publish …»).
3. **Pré-condição de conteúdo:** se `technicalAnswer` for nula/vazia, lança `VALIDATION_ERROR`
   (entradas legadas sem resposta técnica são bloqueadas — nunca se regenera embedding sem
   `technicalAnswer`).
4. Reconstrói o embedding via `indexer.index(...)` (**upsert** idempotente).
5. **Não altera** `publishedAt`/`publishedBy` nem cria versão nem toca conteúdo validado.
6. **Auditoria:** `KNOWLEDGE_QA_REINDEXED` (sem metadata/motivo). **Sem métrica própria.**

Respostas específicas:

- **Exige `publishedAt`?** Sim (via `isPublished()`).
- **Exige `technicalAnswer`?** Sim.
- **Usa *upsert*?** Sim.
- **Altera `publishedAt`/`publishedBy`?** Não.
- **Pode recriar embedding removida?** Só se o Q&A continuar **publicado**. Após `unpublish`
  (que limpa `publishedAt`), `reindex` é bloqueado; a recriação passa por `publish` (republicação).
- **Lacunas:** não distingue «reconstruir por corrupção» de «reindexar após rollback»; sem motivo
  nem métrica; depende de o item estar publicado, pelo que não serve para «reactivar» um item
  despublicado (isso é republicação).

## 9. Efeito esperado no RAG

Filtros reais do `RagSearchService.findSimilar(...)` (ramo `KNOWLEDGE_QA`, tudo em SQL):

- `kqa.organization_id = ?` — **isolamento por organização** (multi-tenant);
- `kqa.curation_status = 'VALIDATED'`;
- `kqa.published_at IS NOT NULL` — **filtra por publicação**;
- `kqa.technical_answer IS NOT NULL`;
- `(kqa.valid_to IS NULL OR kqa.valid_to >= CURRENT_DATE)` e
  `(kqa.valid_from IS NULL OR kqa.valid_from <= CURRENT_DATE)` — **janela de validade**;
- `JOIN knowledge_qa_embeddings kqae ON kqae.knowledge_qa_id = kqa.id` — **sem embedding, nunca
  aparece**;
- `ORDER BY similarity DESC LIMIT topK` (topK por defeito 5).

Papéis:

- **`knowledge_qa_embeddings`** — porta de **indexação**: sem linha, não há recuperação (o `JOIN`
  exclui).
- **`published_at`** — porta de **publicação**: `null` exclui.
- **`curation_status`** — porta de **curadoria**: ≠ `VALIDATED` exclui.
- **`valid_from`/`valid_to`** — porta de **validade temporal**.
- **`organization`** — porta de **isolamento** multi-tenant.

**O que faz um Q&A deixar de aparecer no RAG (qualquer um basta):** remover o embedding; pôr
`published_at` a `null`; mudar `curation_status` para ≠ `VALIDATED`; cair fora da janela de
validade; pertencer a outra organização. O `unpublish` actual aciona **dois** destes em simultâneo
(remove embedding **e** limpa `published_at`) — **dupla porta** fechada, rollback robusto por
construção. O `GroundingService` é apenas consumidor a jusante dos `RetrievedCase`; **não** publica,
indexa nem despublica.

## 10. Auditoria e rastreabilidade

Eventos existentes (`AuditAction`, persistidos por `AuditService.record(...)` na mesma transacção):

- `KNOWLEDGE_QA_PUBLISHED` — no `publish` (metadata: `publisher=…`);
- `KNOWLEDGE_QA_UNPUBLISHED` — no `unpublish` (**sem metadata**);
- `KNOWLEDGE_QA_REINDEXED` — no `reindex` (**sem metadata**);
- `KNOWLEDGE_QA_VERSION_CREATED` — no `createNewVersion` (metadata: `previousVersionId=…`).

Cada evento regista:

- **actor** (`userId`) — sim;
- **organização** (`organizationId`) — sim;
- **timestamp** — sim (no `AuditEvent`);
- **ligação ao Q&A** (`entityType` + `entityId`) — sim.

**Motivo registado?** **Não** para `unpublish`/`reindex` (metadata `null`). Para rollback governado,
o motivo é informação essencial e hoje **não existe**.

**Métricas:** existe `recordPublication(outcome)` (`success`/`embedding_failure`) no `publish`;
**não** existe métrica de despublicação nem de reindexação.

Lacunas para rollback governado: ausência de **motivo obrigatório**; ausência de distinção de
auditoria entre **despublicar** e **desindexar** (não há `KNOWLEDGE_QA_DEINDEXED`); ausência de
**métrica própria** de rollback.

## 11. Lacunas para E10 implementação

| Lacuna | Estado |
| --- | --- |
| Motivo obrigatório na despublicação/rollback | **Em falta** — `unpublish` não recebe nem regista motivo. |
| Relatório próprio de rollback (tipo `AtFaq…Result`) | **Em falta** — não há artefacto de resultado de rollback. |
| Distinção formal entre `unpublish` e `deindex` | **Em falta** — estão acoplados num só acto; sem estados/enum que os separem. |
| Comando governado de rollback (guardas + plano) | **Em falta** — existe o método de domínio `unpublish`, mas não um comando governado simétrico à família E8B.3/E9. |
| *Batch rollback* (lote pequeno, simétrico à E9B) | **Em falta** — `unpublish` opera um Q&A de cada vez. |
| Neutralização sem apagar histórico (de embedding) | **Parcial** — a curadoria/auditoria são preservadas, **mas** o embedding é `DELETE` físico (sem *soft-delete* nem histórico de vector). |
| Enum/estado específico de rollback («despublicado»/«desindexado»/«bloqueado») | **Em falta** — não há eixo de estado de rollback. |
| Métrica própria de rollback | **Em falta** — só há métrica de publicação. |
| Teste E10 *end-to-end* (antes/depois + RAG deixa de recuperar) | **Em falta** — há testes de `unpublish`/`reindex` isolados e ITs de indexação/RAG, mas **não** um E2E de rollback governado simétrico à E9A. |

Já **resolvido** (não é lacuna):

- **Dupla porta de RAG** (embedding + `published_at`) — o RAG deixa de recuperar de forma fiável
  após `unpublish`.
- **Atomicidade** — despublicação + desindexação correm numa transacção.
- **Idempotência do `remove`** — `DELETE` de linha inexistente é seguro.
- **Isolamento multi-tenant** — `requireOwned` responde `NOT_FOUND` a acessos *cross-org*.

## 12. Opções para E10

**Cenário de opções (obrigatório assinalar).** Há pelo menos três caminhos para E10; esta prompt
**não** os implementa.

### Opção A — E10A: rollback de um único Q&A publicado/indexado

- **Objectivo:** despublicar 1 Q&A; remover o embedding; confirmar que o RAG deixa de recuperar;
  preservar a auditoria.
- **Vantagem:** mais segura; **simétrica com E9A** (um só item, máximo controlo).
- **Risco:** ainda não cobre o lote.

### Opção B — E10B: rollback de lote pequeno

- **Objectivo:** despublicar/desindexar um lote pequeno (até 3); confirmar idempotência; confirmar
  diferimento/bloqueios.
- **Vantagem:** **simétrica com E9B**.
- **Risco:** maior superfície de erro.

### Opção C — E10-policy primeiro

- **Objectivo:** definir a **política** de rollback antes do código (motivo, auditoria, estados,
  responsabilidades, o que se preserva e o que se apaga).
- **Vantagem:** clarifica motivo, auditoria, estados e responsabilidades antes de escrever código.
- **Risco:** adia a implementação.

## 13. Recomendação técnica

**Recomendação (objectiva): E10A primeiro — rollback governado de um único Q&A publicado/indexado
em BD isolada.**

Justificação:

- **simétrico com E9A** — o mesmo princípio de «um de cada vez, máximo controlo»;
- **máximo controlo** e **menor superfície de erro**;
- **prova o «botão de silêncio»** (retirar a voz) antes de o aplicar a um lote;
- a dupla porta de RAG e a atomicidade já existentes tornam o caso único verificável de forma limpa.

**Ressalva de política.** O inventário **não revelou** uma lacuna de política tão grave que obrigue
a inverter a ordem — mas revelou lacunas que E10A **tem de endereçar à cabeça**: ausência de
**motivo** no rollback e ausência de **distinção formal entre despublicar e desindexar**. Por isso,
E10A deve incorporar **desde o início** o registo de motivo e a clareza conceptual despublicar ≠
desindexar (ainda que o acto permaneça atómico). Se, ao desenhar E10A, se concluir que o motivo
obrigatório ou os estados de rollback exigem **alteração estrutural** (migration, novo enum
persistido, alteração de assinatura de serviço de produção), então **parar** e fazer **E10-policy
primeiro** (Opção C), debatendo antes de implementar.

## 14. Critérios de aceitação para E10A futura

Uma futura E10A só é aceite se:

- partir de um **Q&A publicado/indexado em BD isolada** (Testcontainers, profile `pgtest`);
- **antes do rollback:** `publishedAt != null`, `publishedBy != null`, `embeddingRows == 1`, **o RAG
  recupera** o Q&A;
- **executar rollback governado** (despublicação + desindexação) de forma auditável;
- **depois do rollback:** `publishedAt == null`, `publishedBy == null`, `embeddingRows == 0`, **o RAG
  não recupera** o Q&A;
- **auditoria registada** (`KNOWLEDGE_QA_UNPUBLISHED` ou equivalente);
- **motivo registado** (ou a sua ausência documentada como lacuna assumida);
- **idempotência** tratada de forma explícita e previsível (segundo rollback não corrompe estado);
- **sem dados reais**, sem base piloto real, sem produção;
- **sem frontend, sem endpoints, sem migrations** (salvo decisão expressa e debatida).

## 15. Critérios de paragem antes de E10 implementação

Parar e debater **antes** de implementar se qualquer um destes sinais ocorrer:

- `unpublish` **não** remover o embedding (a desindexação falhar);
- o **RAG continuar a recuperar** após despublicação/desindexação;
- o rollback exigir uma **migration não debatida**;
- a **auditoria** for insuficiente para um produto (sem actor, sem timestamp, sem ligação ao Q&A);
- tornar o **motivo obrigatório** exigir **alteração estrutural** (migration/novo enum/assinatura
  de serviço de produção);
- houver **divergência entre documentação e código** (o código deixou de corresponder a este
  inventário);
- o serviço actual **acoplar efeitos de forma perigosa** (um efeito colateral inesperado ao
  despublicar/reindexar);
- for necessário **tocar dados reais**.

## 16. Fora de âmbito

Esta tarefa (E10-prep) **não** inclui:

- **Java**, **testes**, **BD**, **dados reais** ou **base piloto real**;
- **publicação**, **despublicação**, **indexação**, **remoção de embeddings** ou **RAG** executados;
- **frontend**, **endpoints**, **migrations**;
- **scraping**, **HTTP externo**, **OpenAI**, **Anthropic** ou qualquer *provider* externo;
- reabrir o **Bloco C**, o **Bloco D** ou as tarefas **E1–E9C-prep**.

Altera-se **apenas `docs/`**.

## 17. Critério de conclusão

A tarefa E10-prep está concluída quando:

- o **documento** `docs/taxia-e10-rollback-unpublish-deindex-inventory.md` está **criado** com o
  título e as 17 secções obrigatórias;
- os **fluxos actuais** (`unpublish`, `indexer.remove`, `reindex`, efeito no RAG, auditoria) estão
  **inventariados**;
- as **lacunas** para E10 estão **identificadas** (§11);
- as **opções E10** (A/B/C) estão **descritas** (§12);
- a **recomendação** para E10A (ou E10-policy, se houver lacuna grave) está **definida** (§13);
- o **roadmap** e os dois documentos de contrato/implementação estão actualizados a referir este
  inventário, **sem** qualquer execução de código, dados ou RAG.

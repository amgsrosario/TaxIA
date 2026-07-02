# Importação e Curadoria de Perguntas e Respostas — KnowledgeFlow / TaxIA

> **Aviso:** Os ficheiros de exemplo em `examples/knowledge-import/` contêm dados exclusivamente fictícios e destinam-se apenas a testes. Não contêm pareceres reais, dados de clientes, referências legais verificadas nem informação confidencial.

---

## 1. Modelo de dados

### `KnowledgeQuestionAnswer`

| Campo | Tipo | Notas |
|---|---|---|
| `id` | UUID | Gerado em `@PrePersist` |
| `organization` | FK | Isolamento por organização |
| `externalKey` | String(255) | Chave de idempotência junto com `sourceSystem` |
| `sourceSystem` | String(120) | Identifica o lote ou origem (ex: `csv-2026-06-25`) |
| `originalQuestion` | TEXT | **Imutável** após importação |
| `originalAnswer` | TEXT | **Imutável** após importação |
| `normalizedQuestion` | TEXT | Editável pelo curador |
| `shortAnswer` | TEXT | Resposta curta validada (obrigatória para publicação) |
| `technicalAnswer` | TEXT | Resposta técnica completa (opcional) |
| `topic` | Enum | IVA, IRC, IRS, SEGURANCA_SOCIAL, TRABALHO, CONTABILIDADE, PROCEDIMENTO_TRIBUTARIO, FATURACAO, OUTROS |
| `subtopic` | String | Livre |
| `jurisdiction` | String | Padrão: PT |
| `riskLevel` | Enum | LOW, MEDIUM, HIGH, CRITICAL |
| `requiresHumanValidation` | boolean | Obrigatório `true` para HIGH e CRITICAL |
| `curationStatus` | Enum | Ver estados abaixo |
| `canonical` | boolean | Uma canónica activa por tópico por organização |
| `validFrom` / `validTo` | Date | Janela de validade fiscal |
| `reviewedBy` / `reviewedAt` | String / Timestamp | Preenchidos automaticamente na validação |
| `notes` | TEXT | Notas do curador |
| `publishedAt` / `publishedBy` | Timestamp / String | Preenchidos na publicação |
| `previousVersionId` | UUID | Ligação à versão anterior |
| `version` | int | Optimistic locking |

---

## 2. Estados de curadoria

```
IMPORTED → PENDING_REVIEW → VALIDATED → (publicação no RAG)
                          ↘ REJECTED
                          ↘ NEEDS_UPDATE → PENDING_REVIEW (re-curadoria)
                          ↘ OUTDATED → ARCHIVED
VALIDATED → NEEDS_UPDATE
VALIDATED → OUTDATED
```

| Estado | Pode entrar no RAG? |
|---|---|
| IMPORTED | **Não** |
| PENDING_REVIEW | **Não** |
| VALIDATED | Sim (se elegível e publicado) |
| NEEDS_UPDATE | **Não** |
| OUTDATED | **Não** |
| REJECTED | **Não** |
| ARCHIVED | **Não** |

---

## 3. Critérios de elegibilidade para publicação RAG

Um par só é elegível (`isEligibleForRag() = true`) quando **todas** as condições se verificam:

1. `curationStatus = VALIDATED`
2. `originalQuestion` não vazio
3. `shortAnswer` ou `technicalAnswer` não vazios
4. `validTo` ausente **ou** no futuro (≥ hoje)
5. Para `riskLevel = HIGH` ou `CRITICAL`: `reviewedBy` não vazio

Adicionalmente, a publicação via serviço exige:
- Pelo menos uma `KnowledgeSourceReference` associada
- Registo não já publicado (`publishedAt IS NULL`)

A query RAG filtra também:
- `curation_status = 'VALIDATED'`
- `published_at IS NOT NULL`
- `valid_to IS NULL OR valid_to >= CURRENT_DATE`

---

## 4. Níveis de risco

| Nível | Descrição | Regras |
|---|---|---|
| LOW | Questão de facto simples, baixo risco de erro | — |
| MEDIUM | Questão com nuances, dependente de contexto | — |
| HIGH | Questão com impacto fiscal significativo | `requiresHumanValidation = true` obrigatório; `reviewedBy` obrigatório |
| CRITICAL | Questão com risco de dano directo ao cliente | `requiresHumanValidation = true` obrigatório; `reviewedBy` obrigatório |

---

## 5. Fontes (`KnowledgeSourceReference`)

Cada par pode ter uma ou mais fontes. A publicação exige **mínimo uma fonte**.

Tipos de fonte: `LEGISLATION`, `ADMINISTRATIVE_GUIDANCE`, `CASE_LAW`, `OFFICIAL_FAQ`, `INTERNAL_OPINION`, `ACCOUNTING_STANDARD`, `OTHER`.

Campos relevantes: `title` (obrigatório), `legalReference`, `url`, `validFrom`, `validTo`, `notes`.

---

## 6. Formatos de importação

### CSV

Colunas suportadas (insensível a maiúsculas):

```
externalKey   (ou external_key, id)
question
answer
topic
subtopic
jurisdiction
riskLevel     (ou risk_level)
requiresHumanValidation  (ou requires_human_validation): true/false
sourceReference  (ou source_reference)
validFrom     (ou valid_from): yyyy-MM-dd
validTo       (ou valid_to): yyyy-MM-dd
notes
```

Campos obrigatórios: `question`, `answer`.

Limite do `externalKey`: máximo **240 caracteres** na importação (a coluna suporta 255;
os restantes ficam reservados para o sufixo de versionamento `_vN`). Linhas com chave
mais longa são marcadas `INVALID_ROW`. O `createNewVersion` valida preventivamente o
comprimento da chave versionada e devolve erro controlado (`VALIDATION_ERROR`) em vez de
depender da falha de commit no PostgreSQL.

### JSON

Array de objectos com os mesmos campos. Valores nulos aceites para campos opcionais.

---

## 7. Dry-run

Parâmetro `dryRun=true` no pedido de importação:

- Valida a estrutura de todas as linhas
- Detecta duplicados e conflitos
- Calcula o relatório completo
- **Não persiste nada** (nem Q&A, nem fontes, nem embeddings, nem auditoria de importação)

---

## 8. Limite de importação (`limit`)

- `0` (zero) = sem limite (processa todas as linhas)
- `N > 0` = para após importar/actualizar N linhas
- Valores negativos são normalizados para 0 pelo compact constructor

---

## 9. Política de duplicados e conflitos

| Situação | Comportamento |
|---|---|
| Mesmo `sourceSystem + externalKey` | Actualiza metadata; nunca sobrescreve `originalQuestion`/`originalAnswer`; registo como `updated` |
| Mesma pergunta + mesma resposta | Skip; registo como `duplicated` + issue `EXACT_DUPLICATE` |
| Mesma pergunta + resposta diferente | Importa; registo como issue `POTENTIAL_CONFLICT`; requer decisão humana |

---

## 10. Publicação e RAG

### Publicar um par

1. Executar `POST /api/v1/admin/knowledge/qa/{id}/validate?reviewerName=...` (para VALIDATED)
2. Associar pelo menos uma fonte: `POST /api/v1/admin/knowledge/qa/{id}/sources`
3. Publicar: `POST /api/v1/admin/knowledge/qa/{id}/publish?publisherName=...`

O indexer (`KnowledgeQaEmbeddingIndexerImpl`) grava o embedding no pgvector via `knowledge_qa_embeddings`.

### Despublicar

```
POST /api/v1/admin/knowledge/qa/{id}/unpublish
```

Remove o embedding do índice.

---

## 11. Versionamento

Quando uma resposta já publicada precisa de ser corrigida:

```
POST /api/v1/admin/knowledge/qa/{id}/new-version  (createNewVersion via serviço)
```

1. A versão anterior é despublicada
2. Uma nova entrada é criada com `previousVersionId` apontando para a versão anterior
3. A nova versão começa em `PENDING_REVIEW` e precisa de re-validação
4. Apenas uma versão da mesma cadeia pode estar publicada

---

## 12. Respostas canónicas

- Apenas entradas `VALIDATED` podem ser marcadas canónicas
- Apenas **uma** entrada canónica por tópico por organização
- Canónica expirada (`validTo` no passado) não é considerada elegível para RAG

```
POST /api/v1/admin/knowledge/qa/{id}/canonical?canonical=true
POST /api/v1/admin/knowledge/qa/{id}/canonical?canonical=false
```

---

## 13. Pesquisa de semelhantes

```
GET /api/v1/admin/knowledge/qa/{id}/similar?topK=10
GET /api/v1/admin/knowledge/qa/similar?question=...&topK=10
```

Retorna entradas com score de semelhança lexical (Jaccard ≥ 0.25). Nunca funde nem altera registos — apenas sugestivo. Resultado inclui: `id`, `originalQuestion`, `curationStatus`, `similarity`, `potentialConflict`.

---

## 14. Geração de benchmark

Exporta rascunhos de casos de benchmark a partir de pares validados:

```
POST /api/v1/admin/knowledge/qa/{id}/benchmark-draft
POST /api/v1/admin/knowledge/qa/benchmark-drafts  (body: lista de UUIDs)
```

O resultado contém: `caseId`, `sourceQaId`, `title`, `question`, `context`, `expectedBehaviours`, `forbiddenBehaviours`, `requiresHumanValidation`, `category`, `difficulty`.

**Os rascunhos gerados requerem revisão humana antes de entrar em qualquer dataset oficial.**

---

## 15. Integração com grounding

Os resultados RAG que provêm de `knowledge_qa_embeddings` chegam ao `GroundingService` com `sourceKind = KNOWLEDGE_QA` e `sourceQaId` preenchido. O grounding usa o conteúdo (`shortAnswer` ou `technicalAnswer`) como contexto para avaliar se a resposta do provider está suportada.

---

## 16. Auditoria

Todas as operações materiais geram um `AuditEvent`. Eventos registados:

`KNOWLEDGE_QA_IMPORTED`, `KNOWLEDGE_QA_UPDATED`, `KNOWLEDGE_QA_STATUS_CHANGED`, `KNOWLEDGE_QA_VALIDATED`, `KNOWLEDGE_QA_REJECTED`, `KNOWLEDGE_QA_ARCHIVED`, `KNOWLEDGE_QA_SOURCE_ADDED`, `KNOWLEDGE_QA_PUBLISHED`, `KNOWLEDGE_QA_UNPUBLISHED`, `KNOWLEDGE_QA_VERSION_CREATED`.

Campos registados: `organizationId`, `userId`, `action`, `entityType`, `entityId`, `timestamp`, `details` (sem conteúdo sensível completo).

---

## 17. Segurança

Todos os endpoints `GET|POST|PATCH /api/v1/admin/knowledge/qa/**` exigem role `ADMIN`.

Comportamento HTTP:

| Situação | Resposta |
|---|---|
| Não autenticado | `401 UNAUTHORIZED` |
| Autenticado sem role `ADMIN` | `403 FORBIDDEN` |
| ADMIN a aceder a entidade de outra organização | `404 NOT_FOUND` |

O isolamento por organização é imposto em todas as queries e operações de escrita.
O acesso a entidades de outra organização responde `404 NOT_FOUND` — indistinguível de um
id inexistente — para nunca revelar a existência de registos de outra organização.
A organização é sempre derivada do JWT autenticado (claim `organization_id`); o cliente
nunca escolhe a organização no payload.

---

## 18. Comandos de operação local

> Substituir `$TOKEN`, `$ORG_ID`, `$QA_ID` pelos valores reais. Nunca incluir passwords ou tokens em ficheiros de configuração.

### Dry-run CSV (verificar antes de importar)

```bash
curl -X POST http://localhost:8081/api/v1/admin/knowledge/qa/import \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@examples/knowledge-import/sample-question-answer.csv;type=text/csv" \
  -F 'request={"sourceSystem":"csv-2026-06-25","format":"CSV","dryRun":true,"limit":0};type=application/json'
```

### Importação CSV real

```bash
curl -X POST http://localhost:8081/api/v1/admin/knowledge/qa/import \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@examples/knowledge-import/sample-question-answer.csv;type=text/csv" \
  -F 'request={"sourceSystem":"csv-2026-06-25","format":"CSV","dryRun":false,"limit":0};type=application/json'
```

### Importação JSON

```bash
curl -X POST http://localhost:8081/api/v1/admin/knowledge/qa/import \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@examples/knowledge-import/sample-question-answer.json;type=application/json" \
  -F 'request={"sourceSystem":"json-2026-06-25","format":"JSON","dryRun":false,"limit":0};type=application/json'
```

### Listar pendentes (PENDING_REVIEW)

```bash
curl "http://localhost:8081/api/v1/admin/knowledge/qa?status=PENDING_REVIEW&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

### Consultar detalhe

```bash
curl "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID" \
  -H "Authorization: Bearer $TOKEN"
```

### Marcar para revisão

```bash
curl -X POST "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/pending-review" \
  -H "Authorization: Bearer $TOKEN"
```

### Associar fonte

```bash
curl -X POST "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/sources" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceType":"LEGISLATION","title":"CIVA Art. 41.º","legalReference":"Art. 41.º n.º 1 al. a) CIVA"}'
```

### Actualizar resposta curada

```bash
curl -X PATCH "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/curation" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"shortAnswer":"Resposta validada concisa.","riskLevel":"MEDIUM","requiresHumanValidation":false}'
```

### Validar

```bash
curl -X POST "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/validate?reviewerName=nome.revisor" \
  -H "Authorization: Bearer $TOKEN"
```

### Publicar no RAG

```bash
curl -X POST "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/publish?publisherName=nome.publicador" \
  -H "Authorization: Bearer $TOKEN"
```

### Pesquisar semelhantes

```bash
curl "http://localhost:8081/api/v1/admin/knowledge/qa/similar?question=Qual+a+taxa+de+IVA&topK=5" \
  -H "Authorization: Bearer $TOKEN"
```

### Exportar rascunho de benchmark

```bash
curl -X POST "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/benchmark-draft" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 19. Checklist para primeira importação real (20–50 pares)

- [ ] Confirmar que os ficheiros de origem **não contêm dados de clientes identificáveis**
- [ ] Confirmar que as respostas **não são cópias integrais de pareceres reais**
- [ ] Usar `dryRun=true` primeiro para ver o relatório de validação
- [ ] Verificar erros na lista `issues` do relatório de dry-run
- [ ] Definir um `sourceSystem` descritivo (ex: `csv-primeira-amostra-2026-07`)
- [ ] Importar com `dryRun=false` e `limit=10` na primeira corrida
- [ ] Confirmar que os registos têm `curationStatus=IMPORTED`
- [ ] Preencher `shortAnswer` e classificar `topic` e `riskLevel` para cada par
- [ ] Para risco HIGH/CRITICAL: marcar `requiresHumanValidation=true`
- [ ] Associar pelo menos uma fonte a cada par antes de validar
- [ ] Validar com `reviewerName` do especialista responsável
- [ ] Publicar apenas após validação completa e revisão humana do conteúdo
- [ ] Testar uma pergunta via `POST /api/v1/ai/ask` para confirmar que o RAG recupera o par

---

## 20. Limitações conhecidas

- A pesquisa de semelhantes é lexical (Jaccard), não semântica. Pode não detectar perguntas semanticamente equivalentes com vocabulário diferente.
- O embedding pgvector requer a extensão `vector` instalada no PostgreSQL. Em testes (H2) é substituído por um stub no-op.
- A migration `V14` usa `vector(768)` que não é suportado em H2; os testes de integração usam `ddl-auto=create-drop` sem Flyway.
- `createNewVersion` não valida automaticamente a nova versão — requer re-curadoria completa.

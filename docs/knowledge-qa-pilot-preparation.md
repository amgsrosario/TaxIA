# Preparação do Piloto Interno — Knowledge Q&A (Etapa 9A)

> **Aviso:** todos os exemplos deste documento e dos templates são **fictícios**. Nenhum
> parecer real, legislação real, dado de cliente ou informação confidencial foi usado.
> Criado em 2026-07-02.

## 1. Formato oficial de importação

Templates: [`docs/templates/knowledge-qa-pilot-template.csv`](templates/knowledge-qa-pilot-template.csv)
e [`docs/templates/knowledge-qa-pilot-template.json`](templates/knowledge-qa-pilot-template.json)
(ambos os formatos são suportados).

Estes são os **nomes reais** aceites pelo sistema (insensíveis a maiúsculas; alias em parênteses):

| Coluna | Obrigatória | Formato / valores | Máx. | Exemplo | Dry-run / importação |
|---|---|---|---|---|---|
| `externalKey` (`external_key`, `id`) | Não, mas **recomendada** | texto livre único por lote | **240** | `PILOTO-0001` | >240 → linha `INVALID_ROW`; chave repetida no mesmo `sourceSystem` → actualização idempotente (originais preservados) |
| `question` | **Sim** | texto | — | `Qual e o limite ficticio...?` | ausente → `INVALID_ROW` |
| `answer` | **Sim** | texto | — | `O limite ficticio e...` | ausente → `INVALID_ROW` |
| `topic` | Não | `IVA, IRC, IRS, SEGURANCA_SOCIAL, TRABALHO, CONTABILIDADE, PROCEDIMENTO_TRIBUTARIO, FATURACAO, OUTROS` | — | `IVA` | valor desconhecido → **WARNING** e classificado `OUTROS` |
| `subtopic` | Não | texto livre | 120 | `Taxas de ensaio` | — |
| `jurisdiction` | Não | texto | 10 | `PT` (default) | — |
| `riskLevel` (`risk_level`) | Não | `LOW, MEDIUM, HIGH, CRITICAL` | — | `MEDIUM` (default) | valor desconhecido → **WARNING** e classificado `MEDIUM`; `HIGH/CRITICAL` → **WARNING** de revisão humana obrigatória |
| `requiresHumanValidation` (`requires_human_validation`) | Não | `true`/`false`/`1` | — | `true` | `HIGH/CRITICAL` sem `true` → **WARNING** (a validação será bloqueada até corrigir) |
| `sourceReference` (`source_reference`) | Não, mas **necessária antes de validar** | texto (título da fonte) | 500 | `Parecer Interno Ficticio n. 1` | cria fonte tipo `INTERNAL_OPINION`; sem fonte, a validação é bloqueada pelo serviço |
| `validFrom` (`valid_from`) | Não | `yyyy-MM-dd` | — | `2026-01-01` | formato inválido → **WARNING** e ignorado |
| `validTo` (`valid_to`) | Não | `yyyy-MM-dd` | — | `2027-12-31` | formato inválido → **WARNING** e ignorado; no passado → inelegível para RAG |
| `notes` | Não | texto | — | `Dúvida sobre alcance` | usar para marcar dúvidas do especialista |

**Campos que NÃO existem** (não usar): `sourceType`, `sourceTitle`, `sourceUrl`,
`humanReviewRequired` — os equivalentes reais são `sourceReference` e
`requiresHumanValidation`. Fontes detalhadas (tipo, URL, referência legal) associam-se
depois via `POST /api/v1/admin/knowledge/qa/{id}/sources`.

Limites globais: máximo **1000 linhas** por ficheiro (`KNOWLEDGE_IMPORT_MAX_ROWS`),
ficheiro até **5 MB**.

## 2. Regras de duplicados e conflitos

| Situação | Resultado | Issue no relatório |
|---|---|---|
| Mesmo `sourceSystem`+`externalKey` | actualiza metadados; **nunca** sobrescreve pergunta/resposta originais | `DUPLICATE_KEY` (se conteúdo divergir) + em dry-run `would UPDATE` |
| Mesma pergunta + mesma resposta | ignorada (`duplicated`) | `EXACT_DUPLICATE` |
| Mesma pergunta + resposta diferente | importada, marcada para decisão humana | `POTENTIAL_CONFLICT` |

## 3. Dry-run (sempre primeiro)

`dryRun=true` valida tudo e **não persiste nada** — nem Q&A, nem fontes, nem embeddings,
nem auditoria, nem versões. O relatório indica **por linha** (`issues[]`): número da
linha, externalKey, tipo (`INVALID_ROW`, `WARNING`, `EXACT_DUPLICATE`,
`POTENTIAL_CONFLICT`, `DUPLICATE_KEY`, `DRY_RUN_SKIPPED`) e a acção prevista
(`would CREATE new entry` / `would UPDATE existing entry`). Avisos cobrem: datas
inválidas, riskLevel/topic desconhecidos, risco elevado e revisão humana em falta.

Resumo final (campos do `ImportReport`): `totalRows`, `imported`, `updated`, `skipped`,
`duplicated`, `invalid`, **`warnings`**, `dryRun`, `issues`.

Critério para avançar do dry-run para a importação real: `invalid = 0` e todos os
`WARNING`/`POTENTIAL_CONFLICT` compreendidos e aceites pelo curador.

## 4. Relatório da importação real

A importação real devolve o mesmo `ImportReport`: criados (`imported`), actualizados
(`updated`), ignorados (`skipped`/`duplicated`), inválidos (`invalid`), avisos
(`warnings`) e a lista `issues` com as externalKeys afectadas. Linhas com
`POTENTIAL_CONFLICT` ou `WARNING` exigem intervenção manual na curadoria. Os IDs criados
obtêm-se por `GET /api/v1/admin/knowledge/qa?status=IMPORTED`. Sem stack traces nem
detalhes internos (garantido pelo GlobalExceptionHandler).

## 5. Exemplos por categoria (todos fictícios)

| Categoria | Exemplo no conjunto sintético |
|---|---|
| Pergunta simples | SINT-0001..0004 |
| Com taxa | SINT-0005..0007 (`21/5/12 por cento` fictícios) |
| Com artigo | SINT-0008..0010 (`artigo 2/9/23 do Codigo Ficticio`) |
| Com prazo | SINT-0011..0012 (`30 dias`, `15 dias uteis`) |
| Com valor monetário | SINT-0013..0014 (`650 000 / 1 000 unidades de conta`) |
| Ambígua | SINT-0015..0016 (resposta depende de contexto; `requiresHumanValidation=true`) |
| Risco elevado | SINT-0017 (HIGH), SINT-0018 (CRITICAL) |
| Duplicado exacto intencional | SINT-0019 (= SINT-0001) |
| Conflito intencional | SINT-0020 (mesma pergunta de SINT-0005, resposta diferente) |

Conjunto completo: `src/test/resources/pilot/synthetic-pilot-20-cases.csv` (área de
testes — **não** é conhecimento real e não deve ser publicado).

## 6. Estados do piloto

```
IMPORTED → PENDING_REVIEW → VALIDATED → (publicação) → OUTDATED/NEEDS_UPDATE → ARCHIVED
                          ↘ REJECTED  → ARCHIVED
```

| Transição | Quem | Pré-condições | Efeitos / auditoria | Embedding / RAG |
|---|---|---|---|---|
| Importação → `IMPORTED` | ADMIN | ficheiro válido | `KNOWLEDGE_QA_IMPORTED` | sem embedding; invisível no RAG |
| `IMPORTED` → `PENDING_REVIEW` | ADMIN | — | `KNOWLEDGE_QA_STATUS_CHANGED` (previousStatus/newStatus) | invisível |
| `PENDING_REVIEW` → `VALIDATED` | ADMIN (reviewerName do especialista) | ≥1 fonte associada; resposta curada não vazia; HIGH/CRITICAL exige `requiresHumanValidation=true` | `KNOWLEDGE_QA_VALIDATED` (reviewer + estados) | ainda invisível (falta publicar) |
| `PENDING_REVIEW` → `REJECTED` | ADMIN | motivo registado | `KNOWLEDGE_QA_REJECTED` | invisível |
| `VALIDATED` → publicada | ADMIN (publisherName) | elegível (validade, fontes, revisão HIGH/CRITICAL) | `KNOWLEDGE_QA_PUBLISHED`; **cria embedding** (atómico) | **visível no RAG** |
| publicada → despublicada | ADMIN | está publicada | `KNOWLEDGE_QA_UNPUBLISHED`; **remove embedding** | desaparece do RAG |
| `VALIDATED` → `OUTDATED`/`NEEDS_UPDATE` | ADMIN | — | `KNOWLEDGE_QA_STATUS_CHANGED` | inelegível |
| `OUTDATED`/`REJECTED`/`NEEDS_UPDATE` → `ARCHIVED` | ADMIN | despublicada primeiro | `KNOWLEDGE_QA_ARCHIVED` | invisível; histórico, fontes e auditoria preservados |
| Nova versão | ADMIN (via serviço) | anterior elegível | `KNOWLEDGE_QA_VERSION_CREATED`; anterior despublicada | nova versão começa `PENDING_REVIEW` (re-curadoria completa) |
| Reindexação | ADMIN | está publicada | `KNOWLEDGE_QA_REINDEXED` | repõe embedding, idempotente |

## 7. Publicação nunca é automática

Quando os casos reais chegarem: importar **sempre em quarentena** (`IMPORTED`); nenhuma
validação, publicação ou canonização automática; nada entra no RAG antes de publicação
explícita por um humano. O sistema impõe isto por construção (elegibilidade + fonte
obrigatória + publicação separada).

## 8. Métricas do piloto

Calculadas a partir do `ImportReport`, das métricas Micrometer existentes e do relatório
de avaliação (sem tags de alta cardinalidade — nunca IDs de utilizador/organização/pergunta):

| Métrica | Fonte / fórmula |
|---|---|
| `import_success_rate` | `imported / totalRows` do relatório |
| `dry_run_error_rate` | `invalid / totalRows` do dry-run |
| `duplicate_rate` | `duplicated / totalRows` |
| `conflict_rate` | nº `POTENTIAL_CONFLICT` / totalRows |
| `validation_rate` | validados / importados (contagem por estado) |
| `publication_rate` | publicados / validados |
| `rag_retrieval_rate` | perguntas de teste com o caso esperado devolvido / total |
| `sufficient_context_rate` | 1 − `grounding_insufficient_context_total`/pedidos |
| `grounding_supported_rate` | respostas SUPPORTED / pedidos com provider |
| `grounding_rejection_rate` | `grounding_rejections_total` / pedidos |
| `human_review_rate` | respostas REQUIRES_HUMAN_REVIEW / pedidos |
| `source_accuracy_rate` | fontes correctas / respostas avaliadas (manual) |
| `average_response_time` | `ai_request_duration` (Micrometer) |
| `provider_error_rate` | `ai_provider_errors_total` / `ai_requests_total` |
| `embedding_error_rate` | `embedding_failures_total` / `embedding_requests_total` |

## 9. Ambiente do piloto (checklist de propriedades)

- [ ] `KNOWLEDGEFLOW_JWT_SECRET` definido (≥ 64 chars, **não** o default de dev)
- [ ] `BOOTSTRAP_ADMIN_ENABLED=true` + `BOOTSTRAP_ADMIN_SECRET` **apenas** no primeiro arranque; desactivar depois
- [ ] `CORS_ALLOWED_ORIGINS` restrito às origens reais (sem `*`)
- [ ] `ANTHROPIC_API_KEY` definido; `AI_PRIMARY_PROVIDER=anthropic`
- [ ] `EMBEDDINGS_SERVICE_URL` a apontar para o microserviço activo
- [ ] Timeouts confirmados (defaults: 5s/60s IA, 3s/20s embeddings)
- [ ] Logs com correlationId visível; métricas em `/actuator/metrics` (autenticado)
- [ ] `/actuator/health/readiness` verde (db, pgvector, aiStack)
- [ ] Directório de backups com espaço e permissões
- [ ] Organização do piloto criada e confirmada (uma só)
- [ ] Confirmado que o ambiente **não é produção pública**

## 10. Regras para preparar os 20 casos reais (instruções para a equipa)

1. **Não remover contexto essencial** da pergunta — a pergunta deve ser compreensível isolada.
2. **Nunca incluir**: dados pessoais, NIFs, nomes de clientes, moradas, valores de
   processos reais identificáveis, referências que permitam identificar o cliente.
3. Preservar a pergunta e a resposta originais (o sistema guarda-as imutáveis; a curadoria
   trabalha em campos separados).
4. Indicar a **fonte** (`sourceReference`) de cada resposta.
5. Indicar a **validade** (`validFrom`/`validTo`) quando a resposta depende de regras com prazo.
6. Classificar o **risco** (LOW/MEDIUM/HIGH/CRITICAL) — na dúvida, subir o nível.
7. Registar dúvidas no campo `notes`.
8. Marcar `requiresHumanValidation=true` em todos os casos HIGH/CRITICAL e nos ambíguos.
9. Usar um `externalKey` estável (ex: `REAL-0001`..`REAL-0020`) — permite reimportar sem duplicar.
10. Guardar o ficheiro fora do repositório Git.

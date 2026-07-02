# Resiliência e Observabilidade do Backend — TaxIA / KnowledgeFlow

> Etapa 8 da consolidação. Criado em 2026-07-02.

## 1. Timeouts

Todos os clientes HTTP externos têm timeouts explícitos, configuráveis por propriedade
(nunca hardcoded, nunca infinitos):

| Cliente | Propriedades | Defaults |
|---|---|---|
| OpenAI | `knowledgeflow.ai.http.openai.connect-timeout` / `read-timeout` | 5s / 60s |
| Anthropic | `knowledgeflow.ai.http.anthropic.connect-timeout` / `read-timeout` | 5s / 60s |
| Embeddings | `knowledgeflow.rag.embeddings.connect-timeout` / `read-timeout` | 3s / 20s |

Variáveis de ambiente: `OPENAI_CONNECT_TIMEOUT`, `OPENAI_READ_TIMEOUT`,
`ANTHROPIC_CONNECT_TIMEOUT`, `ANTHROPIC_READ_TIMEOUT`, `EMBEDDINGS_CONNECT_TIMEOUT`,
`EMBEDDINGS_READ_TIMEOUT`.

## 2. Política de retries

Implementada por `TransientRetry` (`common/resilience`). **Limitada e documentada:**

- **Repetível** (transitório): timeout, HTTP 429, 500/502/503/504, ligação recusada/indisponível.
- **Nunca repetível** (determinístico): 400, 401, 403, 404, payload inválido, resposta
  malformada (`AIInvalidResponseException`), vector inválido (`EMBEDDING_INVALID_VECTOR`),
  rejeição semântica do grounding, erros de validação.
- **Limites:** máximo 3 tentativas totais (hard cap no código — valores superiores são
  normalizados), backoff linear pequeno (300ms IA / 200ms embeddings), sem retries infinitos.

Propriedades: `knowledgeflow.ai.http.retry.max-attempts` / `backoff`;
`knowledgeflow.rag.embeddings.retry-max-attempts` / `retry-backoff`.
Env: `AI_RETRY_MAX_ATTEMPTS`, `AI_RETRY_BACKOFF`, `EMBEDDINGS_RETRY_MAX_ATTEMPTS`, `EMBEDDINGS_RETRY_BACKOFF`.

## 3. Códigos de erro estruturados

| Código | HTTP | Significado |
|---|---|---|
| `AI_PROVIDER_TIMEOUT` | 504 | Provider não respondeu dentro do timeout |
| `AI_RATE_LIMIT_ERROR` | 429 | Provider devolveu 429 |
| `AI_PROVIDER_UNAVAILABLE` | 503 | Provider 5xx / inalcançável |
| `AI_PROVIDER_INVALID_RESPONSE` | 502 | Resposta malformada (JSON inválido, campos ausentes, corpo vazio) |
| `AI_PROVIDER_ERROR` | 502 | Outro erro do provider (ex.: 400) |
| `AI_CONFIGURATION_ERROR` | 503 | Configuração inválida (chave/modelo ausente) |
| `EMBEDDING_TIMEOUT` | 504 | Serviço de embeddings não respondeu |
| `EMBEDDING_UNAVAILABLE` | 503 | Serviço de embeddings indisponível / resposta vazia |
| `EMBEDDING_INVALID_VECTOR` | 502 | Vector com dimensão errada, NaN, infinito ou vazio |

As respostas de erro **nunca** contêm stack traces, chaves de API, tokens ou headers de
autenticação (verificado por `AIExceptionMappingTest`).

## 4. Validação de embeddings

`EmbeddingVectorValidator` rejeita, antes de qualquer escrita no pgvector:
vector nulo/vazio, dimensão ≠ 768 (`knowledgeflow.rag.embeddings.expected-dimension`),
valores NaN, infinitos ou nulos. Falha determinística — nunca é repetida.

## 5. Publicação atómica e reprocessamento

- A publicação é atómica: o embedding é escrito **dentro** da transacção de publicação;
  qualquer falha (timeout, vector inválido) faz rollback total — sem `publishedAt`, sem
  embedding órfão, sem auditoria parcial (provado por `BackendResiliencePostgresIT` e
  `KnowledgeQaTransactionalRollbackPostgresIT`).
- **Reprocessamento idempotente:** `POST /api/v1/admin/knowledge/qa/{id}/reindex`
  (apenas entradas publicadas) reconstrói o embedding via upsert — repetir nunca duplica
  embeddings, nunca cria versões, nunca altera conteúdo validado. Auditado como
  `KNOWLEDGE_QA_REINDEXED`.
- Publicação interrompida antes do commit: basta repetir `publish` (o estado permaneceu
  VALIDATED não-publicado).

## 6. Correlation ID

- Header: `X-Correlation-ID`.
- Valor recebido é aceite se seguro (`[A-Za-z0-9_-]{8,64}`); caso contrário (ausente,
  demasiado longo, caracteres perigosos) é gerado um UUID.
- Sempre devolvido na resposta e colocado no MDC (`correlationId`), presente em todas as
  linhas de log (`[cid:...]`).
- Propagado para chamadas externas (providers IA e embeddings) por
  `CorrelationIdPropagation`.

## 7. Logging

O padrão de consola inclui o correlationId. Os serviços registam: provider, model,
durationMillis, supportStatus, providerCalled, responseRejected, errorCode.

**Nunca registados:** API keys, passwords, JWT completos, connection strings com password.
Prompts e respostas fiscais completas não são escritos nos logs de aplicação (apenas
comprimentos e metadados).

## 8. Métricas (Micrometer / Actuator)

Endpoint `/actuator/metrics` (autenticado). Tags limitadas a enumerações
(`provider`, `outcome`, `supportStatus`) — nunca IDs de utilizador/organização/pergunta.

| Métrica | Tags |
|---|---|
| `ai_requests_total` | provider, outcome |
| `ai_request_duration` | provider |
| `ai_provider_errors_total` | provider, outcome |
| `grounding_outcomes_total` | supportStatus |
| `grounding_rejections_total` | — |
| `grounding_insufficient_context_total` | — |
| `embedding_requests_total` / `embedding_failures_total` | outcome |
| `rag_queries_total` / `rag_query_duration` | — |
| `knowledge_publications_total` / `knowledge_publication_failures_total` | outcome |

## 9. Health checks

- **Liveness / readiness**: probes do Spring Boot activas
  (`/actuator/health/liveness`, `/actuator/health/readiness`).
- `db` (PostgreSQL) e `diskSpace`: indicadores automáticos do Spring Boot.
- `pgvector`: verifica a extensão `vector` instalada (query a `pg_extension`).
- `aiStack`: **não invasivo** — reporta apenas estado de configuração do provider primário
  e do serviço de embeddings; **nunca** faz chamadas pagas.
- Um provider externo indisponível degrada readiness; a liveness não é afectada.

## 10. CORS

Configurado por propriedade (`knowledgeflow.security.cors.*`), sem wildcard hardcoded:

- `allowed-origins` (default: localhost:3000/5173/8081), `allowed-methods`,
  `allowed-headers`, `allow-credentials` (default false).
- Wildcard (`*`) só funciona como opt-in explícito e **nunca** com credentials
  (arranque falha com erro claro).
- Env: `CORS_ALLOWED_ORIGINS`, `CORS_ALLOWED_METHODS`, `CORS_ALLOWED_HEADERS`,
  `CORS_ALLOW_CREDENTIALS`.

## 11. bootstrap-admin

Política (por ordem de verificação):

1. `knowledgeflow.auth.bootstrap.enabled` — **false por defeito**; desactivado responde
   `404` (endpoint invisível).
2. Secret obrigatório vindo do ambiente (`BOOTSTRAP_ADMIN_SECRET`), enviado no header
   `X-Bootstrap-Secret`; comparação em tempo constante; errado/ausente → `403`.
3. Uso único: bloqueado (`409`) assim que exista qualquer utilizador.
4. Auditado (`ADMIN_BOOTSTRAPPED`).

Nunca activar em produção sem configuração explícita e temporária.

## 12. Segredos

- Nenhuma chave real no Git; todas as propriedades sensíveis usam placeholders de
  ambiente (verificado automaticamente por `SecretsHygieneTest`).
- `/actuator` expõe apenas `health,info,metrics` — nunca `env`, `configprops`, `beans`,
  `mappings`, `heapdump`, `threaddump`.
- Mensagens de erro e logs não expõem valores de segredos.
- Testes usam exclusivamente valores fictícios.

## 13. Limites de payload

| Limite | Valor | Onde |
|---|---|---|
| Pergunta (`/ask`) | 4000 chars | `@Size` no DTO |
| System prompt | 8000 chars | `@Size` no DTO |
| Ficheiro de importação | 5 MB (pedido 6 MB) | `spring.servlet.multipart` |
| Linhas por importação | 1000 (`KNOWLEDGE_IMPORT_MAX_ROWS`) | Import service |
| externalKey | 240 chars | Import service (reserva para `_vN`) |
| Fonte: title/legalReference/url/notes | 500/500/2000/4000 | `@Size` no DTO |

Payloads excessivos devolvem `400 VALIDATION_ERROR` controlado.

## 14. Concorrência

Optimistic locking (`@Version`) + constraints únicos garantem, sob corrida
(provado por `BackendResiliencePostgresIT`):

- duas publicações simultâneas → exactamente 1 sucesso, 1 embedding, 1 evento;
- duas despublicações → exactamente 1 sucesso;
- versionamento concorrente → estado coerente, novas versões = sucessos;
- importações simultâneas da mesma externalKey → 1 registo (índice único).

## 15. Rate limiting

**Adiado para a etapa de produção** (decisão desta etapa): não foi introduzida
infraestrutura de rate limiting. Mitigações actuais: limites de payload, retries
bounded e autenticação obrigatória. Implementar limite configurável por
utilizador/organização nos endpoints de IA e importação antes de exposição pública.

## 16. Operação em piloto interno

1. Arrancar PostgreSQL (`docker compose up -d`) e o microserviço de embeddings.
2. Definir env: `ANTHROPIC_API_KEY`, `KNOWLEDGEFLOW_JWT_SECRET` (produção: obrigatório),
   `BOOTSTRAP_ADMIN_ENABLED=true` + `BOOTSTRAP_ADMIN_SECRET` **apenas** para o primeiro
   arranque; desactivar depois.
3. Verificar `/actuator/health/readiness` (db, pgvector, aiStack) antes de servir tráfego.
4. Monitorizar `grounding_rejections_total` e `ai_provider_errors_total` — subidas indicam
   problemas de conhecimento ou do provider.
5. Em incidentes, pesquisar logs pelo correlationId devolvido ao cliente.

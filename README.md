# KnowledgeFlow Backend

Backend generalista para criação, revisão, validação e reutilização de conhecimento técnico assistido por IA.

A TaxIA é a primeira vertical prevista. O núcleo permanece neutro para permitir especializações futuras, por exemplo agricultura, engenharia ou compliance.

## Dois Circuitos

KnowledgeFlow separa profundamente dois modos de utilização.

Circuito assistivo:

```text
pergunta
-> pesquisa de fontes
-> resposta IA imediata
-> citações e confiança
-> feedback humano opcional
```

Circuito formal:

```text
caso técnico
-> investigação
-> pré-análise opcional
-> edição humana
-> revisão
-> validação humana
-> publicação
```

A IA pode responder autonomamente no circuito assistivo.
Publicações formais e conhecimento institucional validado exigem uma ação humana explícita.

Uma interação assistiva relevante poderá ser promovida a caso formal sem perder perguntas, respostas, fontes e feedback.

Decisões e roadmap:

- [ADR-001: Generalize the Platform Core](docs/adr/ADR-001-generalize-platform-core.md)
- [ADR-002: Separate Assistive and Formal Knowledge Circuits](docs/adr/ADR-002-separate-assistive-and-formal-circuits.md)
- [Technical Roadmap](docs/roadmap.md)

## Stack

- Java 21
- Spring Boot 3.4.5
- PostgreSQL 15 + pgvector
- Spring Data JPA + Flyway
- Spring Security + JWT + RBAC
- DTO-only architecture
- MapStruct
- Docker Compose
- Anthropic Claude (Haiku) via API
- Serviço local de embeddings (sentence-transformers)

## Executar localmente

Variáveis de ambiente obrigatórias para funcionalidade completa:

```text
ANTHROPIC_API_KEY=<chave Anthropic>   # se ausente, usa StubAIService (respostas fixas)
```

```powershell
docker-compose up -d
mvn spring-boot:run
```

Serviços locais:

| Serviço            | URL / Porta                              |
|--------------------|------------------------------------------|
| Backend            | `http://localhost:8081`                  |
| PostgreSQL         | `localhost:15432`                        |
| pgAdmin            | `http://localhost:5057`                  |
| pgAdmin login      | `admin@knowledgeflow.dev` / `knowledgeflow` |
| Embeddings service | `http://localhost:8000` (Docker)         |

## Testes

Os testes correm sem serviços externos — sem PostgreSQL real, sem Anthropic, sem serviço de embeddings.

```powershell
mvn test                    # suite completa
mvn -DskipTests package     # compilar e empacotar sem testes
```

Isolamento em testes:

- `StubAIService` é ativado automaticamente quando `ANTHROPIC_API_KEY` está ausente ou vazia.
- `StubEmbeddingService` e `StubCaseEmbeddingIndexer` substituem os componentes reais no perfil `test`.
- Base de dados: H2 in-memory (modo PostgreSQL), gerida pelo Hibernate.

## Endpoints

Health:

```text
GET /api/v1/health
```

Autenticação:

```text
POST /api/v1/auth/bootstrap-admin
POST /api/v1/auth/login
GET  /api/v1/auth/me
```

Clientes:

```text
POST   /api/v1/clients
GET    /api/v1/clients
GET    /api/v1/clients/{id}
PUT    /api/v1/clients/{id}
DELETE /api/v1/clients/{id}
```

Casos de conhecimento:

```text
POST /api/v1/knowledge-cases
GET  /api/v1/knowledge-cases
GET  /api/v1/knowledge-cases/{id}
PUT  /api/v1/knowledge-cases/{id}
GET  /api/v1/knowledge-cases/{id}/versions
GET  /api/v1/knowledge-cases/{id}/comments

POST /api/v1/knowledge-cases/{id}/submit           # DRAFT → UNDER_REVIEW
POST /api/v1/knowledge-cases/{id}/validate         # UNDER_REVIEW → VALIDATED
POST /api/v1/knowledge-cases/{id}/request-changes  # UNDER_REVIEW → CHANGES_REQUESTED
POST /api/v1/knowledge-cases/{id}/reject           # UNDER_REVIEW → REJECTED
```

IA assistiva (ADMIN):

```text
POST /api/v1/admin/ai/ask
```

Indexação RAG (ADMIN):

```text
POST /api/v1/admin/rag/index/knowledge-cases/{id}  # indexar um caso VALIDATED específico
POST /api/v1/admin/rag/index/knowledge-cases/validated  # indexar todos os casos VALIDATED
```

A indexação é **best-effort**: quando um caso é validado, a indexação de embeddings é tentada automaticamente mas uma falha do serviço de embeddings não anula a validação humana. O caso fica VALIDATED e pode ser reindexado posteriormente através dos endpoints acima.

Nota técnica: a indexação automática ocorre dentro da transação de `validate()`. Em produção, isso significa que a ligação à base de dados fica retida durante a chamada HTTP ao serviço de embeddings. Para ambientes com elevada carga, recomenda-se mover a indexação para um `@TransactionalEventListener(phase = AFTER_COMMIT)` ou pipeline assíncrono numa fase seguinte.

## Fluxo Postman

Cria uma collection variable:

```text
baseUrl = http://localhost:8080
```

Faz login:

```http
POST {{baseUrl}}/api/v1/auth/login
Content-Type: application/json

{
  "email": "admin@knowledgeflow.dev",
  "password": "password-123"
}
```

Em `Scripts > Post-response`, guarda o token:

```javascript
const json = pm.response.json();
pm.collectionVariables.set("accessToken", json.accessToken);
```

Nos requests protegidos usa:

```text
Authorization > Bearer Token > {{accessToken}}
```

Ao criar um cliente, guarda também o ID:

```javascript
const json = pm.response.json();
pm.collectionVariables.set("clientId", json.id);
```

Cria um caso técnico:

```http
POST {{baseUrl}}/api/v1/knowledge-cases
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "clientId": "{{clientId}}",
  "title": "Avaliação técnica inicial",
  "question": "Qual é o enquadramento técnico do caso apresentado?",
  "content": "Rascunho humano inicial antes de pré-análise IA."
}
```

## Migrations

```text
V1   init schema
V2   security schema
V3   clients
V4   opinions (histórico anterior à generalização)
V5   generalize opinions to knowledge cases
V6   knowledge case versions
V7   knowledge case comments
V8   audit events
V9   assisted interactions
V10  billing (commercial plans, organization plans, consumption events)
V11  client portal users
V12  organization users
V13  pgvector + knowledge_case_embeddings
```

As migrations antigas não são reescritas. A `V5` converte instalações já existentes sem perder dados.

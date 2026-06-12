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
- Spring Boot
- PostgreSQL
- Spring Data JPA
- Spring Security + JWT
- DTO-only architecture
- MapStruct
- Flyway
- Docker

## Executar localmente

```powershell
docker-compose up -d
mvn spring-boot:run
```

Serviços:

- PostgreSQL: `localhost:15433`
- pgAdmin: `http://localhost:5057`
- pgAdmin login: `admin@knowledgeflow.dev` / `knowledgeflow`

## Testes

```powershell
mvn test
```

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
```

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
V1  init schema
V2  security schema
V3  clients
V4  opinions (histórico anterior à generalização)
V5  generalize opinions to knowledge cases
```

As migrations antigas não são reescritas. A `V5` converte instalações já existentes sem perder dados.

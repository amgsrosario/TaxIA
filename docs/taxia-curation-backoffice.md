# TaxIA — Backoffice Mínimo de Curadoria

**Estado:** funcional para o piloto interno · **Âmbito:** curadoria dos casos Q&A, sem publicação
**Stack:** Vite 5 + React 18 + TypeScript 5 (sem framework de UI; CSS próprio) em `frontend/`
**Data:** 2026-07-06

## Objectivo

UI mínima e segura para a equipa se familiarizar com o motor da TaxIA e curar os
19 casos reais importados (estado `IMPORTED`, BD `knowledgeflow_pilot`):
listar, filtrar, abrir detalhe, preparar resposta curada, editar notas,
associar fontes e gerir o estado de curadoria — **sem** publicar, criar
embeddings ou chamar providers de IA.

## Como arrancar

```powershell
# 1. Base de dados (se ainda não estiver a correr)
docker compose up -d db

# 2. Backend sobre a base de PILOTO (perfil dev = stub AI, zero chamadas externas)
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:15432/knowledgeflow_pilot"
mvn spring-boot:run

# 3. Frontend
cd frontend
npm install     # primeira vez
npm run dev     # http://localhost:3000
```

Build de produção: `npm run build` (inclui `tsc --noEmit`; saída em `frontend/dist/`).

## Configuração

| Variável | Default | Nota |
|---|---|---|
| `VITE_TAXIA_API_BASE_URL` | `http://localhost:8081` | definida em `frontend/.env.development`; sem segredos |

O dev server usa a porta **3000** (na allowlist CORS do backend, junto com 5173).

## Credenciais de piloto

Utilizador ADMIN criado na Etapa 9B.1: `piloto.admin@taxia.local`
(password definida no bootstrap — **rotação pendente**; nunca guardar em ficheiros).

## Fluxo de login

1. `POST /api/v1/auth/login` com email+password → JWT.
2. O token vive **em memória + sessionStorage** (morre ao fechar o separador;
   decisão deliberada de piloto — sem refresh tokens).
3. Todas as chamadas enviam `Authorization: Bearer …`.
4. Qualquer 401 limpa a sessão e volta ao login.
5. Logout local no botão "Terminar sessão".

## Fluxo de curadoria (Etapa 9B.3)

1. **Lista** (`/qa`): filtros de estado/tema no servidor; risco, revisão humana
   e texto livre filtrados no cliente (o endpoint não os expõe; escala de piloto).
2. **Detalhe** (`/qa/:id`): conteúdo importado imutável à esquerda; painel de
   curadoria à direita (respostas curada curta/técnica, notas, risco, tema,
   validade) → "Guardar alterações" (`PATCH /curation`).
3. **Fontes:** listar + associar nova (`POST /sources`) com tipo do enum real,
   título, referência legal e URL oficial. O backend **não expõe edição** de
   fontes existentes — corrige-se adicionando a fonte correcta.
4. **Estados:** Passar para revisão → Validar / Rejeitar → (Precisa de
   actualização / Arquivar), sempre com diálogo de confirmação.

### Guard rails implementados

- "Validar" fica **desactivado** (com explicação visível) enquanto faltar:
  resposta curada, pelo menos uma fonte, fonte com URL ou referência legal,
  ou o estado PENDING_REVIEW;
- validação de casos **HIGH/CRITICAL** exige escrever `VALIDAR` no diálogo;
- arquivar exige escrever `ARQUIVAR`; rejeitar pede motivo;
- botões de **publicar, reindexar e ask/IA não existem** na UI e o cliente API
  nem tem funções para esses endpoints (omissão deliberada);
- avisos claros em caso ARCHIVED;
- URLs de fonte têm de começar por `http(s)://`;
- mensagens de erro amigáveis em pt-PT para 401/403/404/400/409/rede
  (sem stack traces; o JWT nunca é logado).

## Endpoints usados (todos pré-existentes; backend não alterado)

`POST /api/v1/auth/login` · `GET /api/v1/auth/me` · `GET /api/v1/health` ·
`GET /api/v1/admin/knowledge/qa` (+`status`,`topic`,`page`,`size`) ·
`GET /api/v1/admin/knowledge/qa/{id}` · `PATCH …/{id}/curation` ·
`POST …/{id}/pending-review` · `POST …/{id}/validate` · `POST …/{id}/reject` ·
`POST …/{id}/outdated` · `POST …/{id}/archive` ·
`GET/POST …/{id}/sources`

## Limitações conhecidas / o que ainda não faz

- **Não publica, não cria embeddings, não chama IA** — por desenho desta etapa;
- sem menu de auditoria (o backend não expõe endpoint REST de auditoria);
- filtros de risco/texto são locais (o endpoint de listagem não os suporta);
- fontes não são editáveis nem removíveis pela UI (limitação do backend);
- sem gestão de utilizadores, MFA, SSO, refresh token, registo ou recuperação
  de password;
- `/actuator/health` está fora do CORS — o indicador de sistema usa `/api/v1/health`;
- sem testes automatizados de frontend (stack nova, sem infra prévia; o build
  com typecheck estrito é o gate mínimo).

## Próximos passos

1. Curadoria real dos 19 casos (Etapa 9B.3) usando este backoffice;
2. depois da curadoria: etapa de publicação controlada (expor publish com
   confirmação reforçada + embeddings caso a caso);
3. endpoint de auditoria + vista correspondente;
4. filtros de risco/texto no servidor quando a base crescer;
5. rotação da password do admin do piloto.

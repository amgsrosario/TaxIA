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

Utilizador ADMIN criado na Etapa 9B.1: `piloto.admin@taxia.local`.

- A password foi definida no bootstrap.
- **A rotação está pendente.** Faz-se depois do rollout da V17, pelo mecanismo governado do ADR-006, e não por SQL.
- Nunca guardar a password em ficheiros.

## Fluxo de login

1. `POST /api/v1/auth/login` com email+password → JWT.
2. O token vive **em memória + sessionStorage** (morre ao fechar o separador;
   decisão deliberada de piloto — sem refresh tokens).
3. Todas as chamadas enviam `Authorization: Bearer …`.
4. Qualquer 401 limpa a sessão e volta ao login.
5. Logout local no botão "Terminar sessão".
6. **Validação por pedido (ADR-006).**
   - O backend aceita o token só se:
     - o claim `tv` coincidir com o `token_version` do utilizador;
     - o utilizador estiver ACTIVE;
     - a membership estiver activa.
   - Os papéis efectivos vêm da BD.
   - Por isso, uma mudança de password, revoke, disable ou mudança de papéis termina a sessão no pedido seguinte (401).
7. **Mudança obrigatória** (`mustChangePassword`, após reposição por ADMIN ou break-glass).
   - O backoffice mostra apenas o ecrã "Mudança de password obrigatória".
   - O backend recusa todas as rotas funcionais (403) até a password ser mudada.

## Credenciais e sessões (ADR-006)

- **Perfil** (`/profile`):
  - "Alterar password": password actual + nova password com pelo menos 12 caracteres, diferente da actual e do email;
  - "Terminar todas as sessões".
  - Ambas as acções terminam também a sessão actual e obrigam a um novo login.
- **Utilizadores** (`/admin/users`, só ADMIN), sobre utilizadores da mesma organização:
  - "Invalidar sessões";
  - "Repor password" (só para contas **não-ADMIN**; o botão não aparece para ADMIN e o backend recusa com 409): password temporária, nunca pré-preenchida, entregue por um canal separado; o utilizador tem de a mudar no login seguinte. Um ADMIN muda a própria password no Perfil; um ADMIN sem acesso é recuperado por break-glass;
  - "Desactivar" / "Reactivar".
  - Todas as acções pedem um motivo, que fica na auditoria.
  - A alteração de papéis existe na API (`PUT /api/v1/admin/users/{id}/roles`), mas não na UI.
- **Guardas:**
  - não se desactiva nem se despromove o último ADMIN activo;
  - um ADMIN não se desactiva nem repõe a sua própria password por esta via.
- **Nada disto guarda passwords** em storage. O JWT nunca é mostrado.

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

- **Alterações não guardadas bloqueiam todas as mudanças de estado** (banner
  explícito + botões desactivados) — mudar de estado com texto por gravar
  descartava-o silenciosamente (bug corrigido em 2026-07-06; confirmado por
  auditoria: 0 eventos `KNOWLEDGE_QA_UPDATED` antes da correcção);
- "Guardar alterações" usa a **resposta do PATCH** como fonte de verdade — o
  ecrã mostra exactamente o que o backend confirmou ter persistido;
- "Passar para revisão" exige `short_answer` **guardada na BD** (não basta o
  texto no formulário);
- "Validar" fica **desactivado** (com explicação visível) enquanto faltar:
  `short_answer` guardada, pelo menos uma fonte, fonte com URL ou referência
  legal, o estado PENDING_REVIEW, ou houver alterações por gravar;
- validação de casos **HIGH/CRITICAL** exige escrever `VALIDAR` no diálogo;
- arquivar exige escrever `ARQUIVAR`; rejeitar pede motivo;
- botões de **publicar, reindexar e ask/IA não existem** na UI e o cliente API
  nem tem funções para esses endpoints (omissão deliberada);
- avisos claros em caso ARCHIVED;
- URLs de fonte têm de começar por `http(s)://`;
- mensagens de erro amigáveis em pt-PT para 401/403/404/400/409/rede
  (sem stack traces; o JWT nunca é logado).

## Endpoints usados

`POST /api/v1/auth/login` · `GET /api/v1/auth/me` · `GET /api/v1/health` ·
`POST /api/v1/auth/password` · `POST /api/v1/auth/logout-all` ·
`GET /api/v1/admin/users` · `POST /api/v1/admin/users/{id}/sessions/revoke` ·
`POST …/{id}/password-reset` · `POST …/{id}/disable` · `POST …/{id}/reactivate` ·
`GET /api/v1/admin/knowledge/qa` (+`status`,`topic`,`page`,`size`) ·
`GET /api/v1/admin/knowledge/qa/{id}` · `PATCH …/{id}/curation` ·
`POST …/{id}/pending-review` · `POST …/{id}/validate` · `POST …/{id}/reject` ·
`POST …/{id}/outdated` · `POST …/{id}/archive` ·
`GET/POST …/{id}/sources`

## Âmbito de aplicação (M4-SCOPE-V2, ADR-004)

Bloco "Âmbito de aplicação" no detalhe da Q&A (só admin):

- **Âmbito derivado** (só leitura): imposto, categoria e operação como o gate os vê.
- **Estado de revisão**: "Âmbito revisto" (quem/quando) ou aviso "Âmbito não revisto". A
  revisão é opcional; uma Q&A nunca revista continua válida e publicável.
- **Não se aplica a**: exclusões escolhidas do vocabulário fechado, com justificação opcional
  (≤ 500 caracteres).
- **Acrescentar** uma exclusão é efectivo de imediato, mesmo numa Q&A publicada (estreita o
  âmbito; auditado; sem nova validação nem reindex).
- **Remover** numa Q&A que não está publicada nem VALIDATED é imediato. Numa Q&A publicada ou
  VALIDATED (que pode ser republicada sem nova validação) o botão é "Pedir remoção": a exclusão
  fica "remoção pendente de validação" e continua efectiva até "Validar remoção" (validação
  humana, com confirmação e o nome do revisor). O pedido pode ser cancelado; uma remoção
  pendente não pode ser contornada por outro "Remover".
- Qualquer alteração efectiva das exclusões repõe o estado "Âmbito não revisto".
- **Marcar âmbito como revisto** regista o revisor e a data, com ou sem exclusões.

Endpoints (`/api/v1/admin/knowledge/qa/{id}/applicability`, ADMIN):
`GET`; `POST /exclusions` (`{marker, note}`); `DELETE /exclusions/{marker}` (remove ou pede
remoção); `POST /exclusions/{marker}/approve-removal?reviewerName=`;
`POST /exclusions/{marker}/cancel-removal`; `POST /reviewed?reviewerName=`. Todas as
alterações geram `KNOWLEDGE_QA_APPLICABILITY_UPDATED`. Nada disto aparece em DEMO/EXTERNAL.

## Integridade pós-validação e versões (ADR-005)

- **Versão publicada**: banner "Esta versão está publicada e validada…"; respostas, tema e
  subtema ficam só de leitura; só se aceitam notas e alterações restritivas (risco mais alto,
  validade mais curta). Acrescentar fontes exige nova versão; retirar uma fonte errada (não a
  última) continua possível. O backend recusa (409) qualquer alteração material vinda do
  backoffice ou das ferramentas do piloto; o re-import salta e reporta (`PROTECTED_SKIPPED`).
- **Criar nova versão**: cópia editável (conteúdo, fontes, exclusões) em revisão; a versão
  publicada continua activa. Uma só versão em preparação de cada vez ("Abrir versão em
  preparação").
- **Nova versão**: banner "Nova versão — não publicada. A versão anterior continua activa";
  edita-se, submete-se a revisão e valida-se como qualquer caso. Quando validada, a substituição
  ("publicar e substituir") é feita no canal de publicação governado — o backoffice não publica.
- **Validada, não publicada**: guardar uma alteração material ou expansiva (respostas, tema,
  subtema, risco mais baixo, validade alargada) ou associar uma fonte pede confirmação ("Esta
  alteração invalida a validação actual e devolverá a Q&A a revisão") e volta a PENDING_REVIEW.
- **Validar** valida a versão que o revisor tem no ecrã: se alguém alterou conteúdo, fontes ou
  exclusões entretanto, a resposta é 409 e é preciso recarregar.
- **Edição concorrente**: cada gravação envia a versão carregada; se outra pessoa gravou entretanto,
  a resposta é 409 e é preciso recarregar.

## Limitações conhecidas / o que ainda não faz

- **Não publica, não cria embeddings, não chama IA** — por desenho desta etapa;
- sem menu de auditoria (o backend não expõe endpoint REST de auditoria);
- filtros de risco/texto são locais (o endpoint de listagem não os suporta);
- fontes não são editáveis nem removíveis pela UI (limitação do backend);
- sem criação de utilizadores, MFA, SSO, refresh token, registo ou recuperação
  de password por email (a reposição é feita por um ADMIN ou por break-glass, ADR-006);
- `/actuator/health` está fora do CORS — o indicador de sistema usa `/api/v1/health`;
- sem testes automatizados de frontend (stack nova, sem infra prévia; o build
  com typecheck estrito é o gate mínimo).

## Próximos passos

1. Curadoria real dos 19 casos (Etapa 9B.3) usando este backoffice;
2. depois da curadoria: etapa de publicação controlada (expor publish com
   confirmação reforçada + embeddings caso a caso);
3. endpoint de auditoria + vista correspondente;
4. filtros de risco/texto no servidor quando a base crescer;
5. rotação da password do admin do piloto pelo mecanismo do ADR-006, no rollout da V17.

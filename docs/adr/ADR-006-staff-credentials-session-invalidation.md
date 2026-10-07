# ADR-006: Staff Credentials and Session Invalidation

## Status

Accepted (SEC-PILOT-CREDENTIALS-HARDENING, 2026-10-06).

## Context

Os tokens staff eram JWT HS256 stateless com TTL de 60 minutos.

- **Sem revogação:**
  - não tinham `jti` nem versão de sessão;
  - os papéis vinham do claim `roles`;
  - desactivar um utilizador, retirar-lhe ADMIN ou mudar-lhe a password só tinha efeito quando o token expirava.
- **Validação incompleta:**
  - o issuer não era validado;
  - o `token_type` não era imposto nas rotas staff: um token do portal era aceite em `/api/v1/auth/me`.
- **Credenciais sem fluxo governado:**
  - não havia mudança nem reposição de password para staff;
  - não havia recuperação de um ADMIN sem acesso, salvo SQL directo sem auditoria.
- **Secret de assinatura inseguro:**
  - `application.yml` tinha como valor por omissão um secret público, versionado no repositório;
  - nenhuma guarda impedia arrancar com ele.

Em 2026-10-06 confirmou-se que o backend do piloto corria sem `KNOWLEDGEFLOW_JWT_SECRET` próprio, ou seja, com o valor por omissão. Quem chegasse ao backend podia forjar tokens com papéis arbitrários. O incidente está descrito em [`taxia-project-state.md`](../taxia-project-state.md).

## Decision

1. **Sessão.**
   - Continua a ser JWT stateless.
   - Cada utilizador tem um contador monotónico `users.token_version` (V17). O token staff transporta-o no claim `tv`.
   - Não há denylist, `jti`, tabela de sessões nem refresh tokens nesta fase.

2. **Validação por pedido.** Em cada pedido staff são verificados:
   - assinatura;
   - `exp`/`nbf`, com `exp` e `sub` obrigatórios (o `iat` em falta é preenchido pelo Spring a partir do `exp`, por isso não é verificado);
   - **issuer**;
   - `token_type=ORG_USER`;
   - `tv` inteiro em [0, 2^31-1];
   - utilizador existente, ACTIVE e não apagado;
   - membership activa na organização do claim, com a organização não apagada (o claim nunca é aceite sozinho);
   - `tv == token_version`.

   Qualquer falha dá **401**, com uma mensagem genérica. Um token staff sem `tv` (formato anterior à V17) é rejeitado, sem período de compatibilidade: força um novo login, uma única vez.

3. **Papéis da BD.**
   - As authorities vêm dos papéis actuais em `organization_users`, lidos em cada pedido.
   - O claim `roles` mantém-se apenas como informação; nunca é fonte de autoridade.
   - Retirar ou acrescentar um papel tem efeito no pedido seguinte.

4. **Fronteira de `token_type`.** É imposta na `SecurityFilterChain`:
   - `/api/v1/portal/**` só aceita sessões `CLIENT_PORTAL`;
   - todas as outras rotas autenticadas (incluindo `/auth/me`) só aceitam sessões staff;
   - um token do tipo errado recebe 403;
   - o portal mantém o seu modelo (sem `tv`) e não foi redesenhado.

5. **Incrementam `token_version`:**
   - mudança ou reposição de password;
   - logout-all;
   - revoke por ADMIN;
   - disable;
   - reactivate;
   - alteração de papéis ou membership;
   - soft-delete;
   - break-glass.

   Regras do contador:
   - Reactivar **nunca** repõe um valor anterior. Também incrementa, para que tokens antigos não voltem a ser válidos.
   - O contador nunca dá a volta: ao atingir o máximo, a operação é recusada.
   - A entidade `User` não tem setter de password, de estado nem de versão.

6. **Política de password staff:**
   - mínimo de 12 caracteres;
   - máximo de 72 bytes UTF-8 (limite do BCrypt): uma password mais longa é recusada, nunca truncada;
   - diferente da actual;
   - diferente do email;
   - sem regras de composição;
   - hash BCrypt existente.

7. **Fluxos.**
   - **Mudança da própria password** (`POST /api/v1/auth/password`):
     - exige a password actual;
     - devolve 204;
     - termina todas as sessões, incluindo a actual, sem emitir um novo token (novo login obrigatório).
   - **Logout-all** (`POST /api/v1/auth/logout-all`).
   - **Operações ADMIN** em `/api/v1/admin/users`:
     - `GET` (listagem);
     - `POST /{id}/sessions/revoke`;
     - `POST /{id}/password-reset`: password temporária definida pelo ADMIN e entregue fora de banda; marca `must_change_password=true`. **Só para contas staff não-ADMIN**: um alvo com papel ADMIN activo (mesmo desactivado) é recusado com 409 (decisão do António, 2026-10-07);
     - `POST /{id}/disable`;
     - `POST /{id}/reactivate`;
     - `PUT /{id}/roles`.

     Todas estas operações:
     - exigem motivo;
     - só actuam na organização do ADMIN (um alvo de outra organização dá 404);
     - recusam contas de serviço não-login (hash sentinela);
     - recusam um alvo que pertença também a outra organização (409): password, estado e `token_version` são globais ao utilizador;
     - são auditadas.

8. **`must_change_password`.**
   - O login funciona, mas a sessão só recebe uma authority restrita, sem papéis.
   - O backend só aceita `/auth/me`, `/auth/password` e `/auth/logout-all`; o resto dá 403.
   - Mudar a password limpa a flag e incrementa `tv`.
   - O frontend mostra um ecrã obrigatório, mas a imposição é sempre do backend.

9. **Guardas.**
   - O último ADMIN activo da organização nunca é desactivado nem perde o papel ADMIN (409). Pela API, o actor é sempre outro ADMIN activo, por isso dispara primeiro a guarda do próprio. Este guard é defesa em profundidade, testado ao nível do serviço.
   - Um ADMIN não se desactiva, não se despromove e não usa o reset por ADMIN sobre si próprio (409).
   - **Contas ADMIN nunca são repostas por outro ADMIN** (409). Assim, um ADMIN não pode escolher a password de outro ADMIN e agir em seu nome (ver o risco residual aceite, relativo a contas não-ADMIN promovidas mais tarde). As vias são:
     - ADMIN autenticado: só a mudança da própria password, com a password actual;
     - ADMIN sem acesso: só o break-glass governado.
     A política do último ADMIN não muda.
   - Sob o lock da organização volta a confirmar-se que o actor continua a ser ADMIN activo e que a sessão dele continua válida (`tv` actual). Caso contrário: 403 ou 401.

10. **Concorrência.**
    - Locks de escrita por ordem fixa: primeiro organização, depois utilizador.
    - `UserRowLock` faz `refresh(PESSIMISTIC_WRITE)` para que um `User` já carregado na transacção nunca reescreva um `token_version` antigo. Uma consulta com lock não refresca uma entidade já gerida, por isso o refresh é necessário.
    - As operações próprias voltam a confirmar o `tv` do token sob lock.
    - Dois ADMIN que se desactivem ou despromovam mutuamente deixam sempre um ADMIN.

11. **Break-glass** (`StaffAdminRecoveryLauncher admin-recover --email … --reason …`):
    - CLI manual, desligado por omissão: exige `BREAK_GLASS_ENABLED=true` nessa execução;
    - perfil `pilot`, com datasource `knowledgeflow_pilot` em loopback;
    - Flyway desligado no launcher por argumento de linha de comandos (`--spring.flyway.enabled=false`, que prevalece sobre `application*.yml`): nunca aplica migrações como efeito lateral. Um schema desactualizado falha a validação do Hibernate antes de qualquer escrita (`StaffBreakGlassNoMigrationIT`);
    - exige exactamente um utilizador existente, ACTIVE, não-serviço e ADMIN activo de uma única organização; qualquer ambiguidade dá BLOCKED;
    - a password é lida duas vezes da consola, sem eco; nunca de argumento, variável de ambiente ou ficheiro;
    - aplica a mesma política de password;
    - grava BCrypt, `must_change_password=true` e `tv++`;
    - audita `USER_BREAK_GLASS_RESET` com o actor técnico `break-glass` (sem linha `users`) e o host;
    - imprime `RESULT=RECOVERED|BLOCKED` e nunca imprime password, hash, token ou secret;
    - o `bootstrap-admin` não é reactivado;
    - SQL directo passa a ser último recurso extraordinário.

12. **Secret JWT.**
    - Já não tem valor por omissão.
    - O `JwtSecretStartupGuard` recusa o arranque (sem imprimir o valor) quando o secret:
      - não está definido;
      - é o antigo valor público;
      - tem menos de 32 bytes;
      - tem menos de 10 caracteres distintos.
    - Aplica-se a todos os perfis excepto `test`/`pgtest`, e **sempre** que a datasource seja `knowledgeflow_pilot`, incluindo `dev` e os perfis de teste.
    - O encoder e o decoder dependem da guarda.

13. **Auditoria.**
    - Eventos:
      - `USER_PASSWORD_CHANGED`;
      - `USER_PASSWORD_RESET`;
      - `USER_SESSIONS_REVOKED`;
      - `USER_DISABLED`;
      - `USER_REACTIVATED`;
      - `USER_ROLES_CHANGED` (com `before`/`after`);
      - `USER_BREAK_GLASS_RESET`.
    - A metadata contém o actor, o alvo, a organização e o motivo. Nunca contém password, hash, JWT ou secret.

14. **Resposta a incidentes de credenciais.**
    - Secret exposto ou inadequado: rodar `KNOWLEDGEFLOW_JWT_SECRET`. É uma invalidação global: todos os tokens staff e portal deixam de ser válidos.
    - Conta comprometida: revoke; reset governado se for não-ADMIN; conta ADMIN: revoke e mudança da própria password pelo titular, ou break-glass se não tiver acesso (nunca reset por outro ADMIN nem SQL).
    - Registar os factos e a decisão no documento de estado, sem valores.

## Consequences

- **Custo:** cada pedido staff faz duas leituras indexadas (utilizador e memberships). É aceitável à escala do piloto; pode ter cache no futuro.
- **Rollout:** depois da V17 todos os tokens staff emitidos antes ficam inválidos (novo login uma vez).
- **Arranque:** sem um secret forte o backend não arranca. Isto vale para `run-dev.sh` sem a variável e para o launcher do piloto. Os perfis de teste usam chaves fictícias de teste.
- **Testes:** os testes MockMvc que usam `jwt()` saltam o conversor, por isso passam a declarar a authority staff. Os testes de segurança novos usam tokens reais.
- **Risco ADMIN→ADMIN fechado** (decisão do António, 2026-10-07).
  - Porquê: a password temporária é escolhida pelo ADMIN. Se o reset ADMIN→ADMIN fosse permitido, o ADMIN que o fizesse conhecia a password temporária. Podia fazer ele próprio a mudança obrigatória e ficar indefinidamente com uma sessão ADMIN na identidade do outro.
  - Por isso:
    - o reset normal fica restrito a contas não-ADMIN;
    - conceder ADMIN é recusado (409) enquanto houver uma password temporária pendente (`must_change_password`). Isto fecha a sequência despromover → repor → promover de novo.
  - Uma conta que foi ADMIN e foi despromovida pode ser reposta como qualquer conta não-ADMIN. Só volta a ADMIN depois de o titular ter definido a sua própria password.
- **Política final do reset de password** (decisão executiva, 2026-10-07):

  | Caso | Regra |
  |---|---|
  | ADMIN → ADMIN | proibido (409) |
  | ADMIN → próprio, pelo endpoint administrativo | proibido (409); usa a mudança da própria password, com a password actual |
  | ADMIN sem acesso | recuperação só por break-glass governado |
  | ADMIN → staff não-ADMIN | permitido |

  Depois do reset:
  - password temporária e `must_change_password=true`;
  - `token_version++`, ficando as sessões anteriores inválidas;
  - operação auditada (`USER_PASSWORD_RESET`).

  Enquanto `must_change_password=true`, a conta não pode receber o papel ADMIN (409). Depois de o titular concluir a mudança obrigatória, a promoção volta a ser tecnicamente permitida.

- **Risco residual — ACEITE PARA O PILOTO** (decisão executiva, 2026-10-07):
  - **Risco:** um ADMIN que repõe a password de um utilizador não-ADMIN pode, em teoria, assumir temporariamente essa identidade, concluir a mudança obrigatória e promover depois a conta.
  - **Impacto:** principalmente de atribuição e auditabilidade. Não dá ao actor privilégio superior ao que já tem, porque já é ADMIN.
  - **Controlos existentes:**
    - `USER_PASSWORD_RESET` identifica o ADMIN que actuou;
    - a promoção posterior também é auditada (`USER_ROLES_CHANGED`);
    - `must_change_password` bloqueia a promoção imediata;
    - o reset directo de contas ADMIN é proibido;
    - a recuperação de um ADMIN sem acesso usa break-glass;
    - o titular legítimo perde o acesso com as credenciais anteriores e detecta a alteração.
  - **Não adoptado nesta fase:**
    - regra de quatro olhos;
    - auditoria como fonte de estado de autorização;
    - segundo ADMIN obrigatório;
    - canal externo de entrega da password temporária;
    - código de uso único;
    - remoção do reset administrativo;
    - schema de proveniência do reset.

    Razões: complexidade desproporcionada no piloto; organizações com um só ADMIN ficariam bloqueadas; a auditoria não deve ser fonte de estado de autorização.
  - **Follow-up futuro:** avaliar um mecanismo de recuperação em que o ADMIN não conheça a credencial, quando existir infraestrutura de entrega e verificação.
  - Não é uma falha em aberto: é uma decisão de segurança institucionalizada.
- **ADMIN desactivado sem acesso:** o break-glass exige um ADMIN ACTIVE. O caminho é:
  1. outro ADMIN reactiva a conta (auditado);
  2. o titular muda a própria password, se a souber, ou é recuperado por break-glass.
  Sem SQL directo.
- **Fora de âmbito:** rate limiting, lockout e auditoria detalhada de login (follow-up); revogação de um único dispositivo; refresh tokens.
- **Rollout no piloto:** missão separada SEC-PILOT-CREDENTIALS-HARDENING-ROLLOUT.
  - Backup.
  - Aplicar a V17.
  - Reiniciar com o secret actual.
  - Rodar a password do admin pelo mecanismo governado.
  - Backup pós.

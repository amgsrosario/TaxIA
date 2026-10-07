# TaxIA — Estado do projecto e handoff

Documento de passagem: permite retomar o projecto sem depender de conversas anteriores.
Confirmar sempre no código, nas migrações e nos ADR (ver [AGENTS.md](../AGENTS.md)).

Última actualização: 2026-10-07 (SEC-PILOT-CREDENTIALS-HARDENING-ROLLOUT).

## Base e entregas recentes

| Entrega | PR | Conteúdo |
|---|---|---|
| M3 | #8 | Fontes curadas nas respostas (`sourceQaId`, resolução em batch) |
| M4 | #9 | Filtro de relevância candidato a candidato (`GROUNDING_MIN_SCORE`, 0.88) |
| M4-SCOPE | #10 | Gate determinístico de contradição de âmbito (imposto, categoria, operação) |
| M4-SCOPE-V2 | #11 | Exclusões de aplicabilidade governadas (V16, [ADR-004](adr/ADR-004-applicability-metadata-governed-exclusions.md)) |
| GOV-PUBLISHED-CONTENT-INTEGRITY | #12 (merged, `324c1f9`) | Integridade pós-validação e versões ([ADR-005](adr/ADR-005-published-content-integrity.md)) |
| SEC-PILOT-CREDENTIALS-HARDENING | #15 (merged, `f841335`); rollout no piloto concluído em 2026-10-07 | Credenciais e sessões staff (V17, [ADR-006](adr/ADR-006-staff-credentials-session-invalidation.md)) |

Migrações: V1–V17. A V17 (`users.token_version`, `users.must_change_password`) está aplicada no
piloto desde 2026-10-07 (SEC-PILOT-CREDENTIALS-HARDENING-ROLLOUT).

## Pipeline de grounding vigente

retrieval (topK 5, e5-base) → relevância ≥ 0.88 → gate de âmbito (contradições v1 + exclusões
ADR-004) → avaliação de suficiência → prompt / validação / fontes → resposta. Sem candidatos:
resposta-limite sem chamar o provider. O threshold mantém-se 0.88 (held-outs 2 e 3 não
justificaram descida).

## Lifecycle de conhecimento vigente (ADR-005)

- `VALIDATED` = o conteúdo em vigor desta versão foi validado por um humano.
- VALIDATED não publicada: alteração material/expansiva → volta a `PENDING_REVIEW`.
- Versão publicada: congelada para alterações materiais/expansivas (409); conservadoras e notas
  são imediatas e auditadas.
- Mudança material numa publicada: `POST /{id}/versions` (cópia em revisão; a publicada
  continua a responder) → validação → `POST /{newId}/publish-replacing/{previousId}`
  (substituição atómica). O backoffice não publica.
- Re-import sobre Q&A VALIDATED/publicada: salta e reporta (`PROTECTED_SKIPPED`).

## Piloto

- Base `knowledgeflow_pilot` em **V17**:
  - a V16 foi aplicada em 2026-10-02 pela API do Flyway com as migrações de `main`, depois de validar os checksums V1–V15;
  - a V17 foi aplicada em 2026-10-07 pelo Flyway normal no arranque do backend a partir de `main` `f841335`;
  - nenhuma das duas alterou conteúdo.
- 21 Q&A no total, 4 publicadas e VALIDATED, 4 embeddings (um por publicada):
  AT-FAQ-2721, AT-FAQ-5795, AT-FAQ-5930 e CIVA-CONSERVACAO-10A-001_v2.
- Exclusões de aplicabilidade (ADR-004), definidas pelo António no backoffice em 2026-10-06, todas
  com âmbito marcado como revisto:

  | Q&A | Exclusões |
  |---|---|
  | AT-FAQ-2721 | HERANCA_INDIVISA, RECLAMACAO, PAGAMENTO, CALCULO |
  | AT-FAQ-5795 | RETENCAO_OBRIGATORIA (PENSAO_ESTRANGEIRA avaliada e **não** acrescentada) |
  | AT-FAQ-5930 | INQUILINO, HABITACAO_PROPRIA |
  | CIVA-CONSERVACAO-10A-001_v2 | nenhuma (âmbito revisto) |

- **Regularização CIVA concluída** (ADR-005): nova versão `CIVA-CONSERVACAO-10A-001_v2`
  (`6e5d958f-…`, `previous_version_id` = `ba231677-…`), conteúdo idêntico, com a fonte
  "Código do IVA — Artigo 52.º"; validada em 2026-10-06 e publicada por "publicar e substituir"
  (`PUBLISHED replaces=` / `UNPUBLISHED replacedBy=`). A versão original fica VALIDATED,
  despublicada e sem embedding (histórico). A LEGACY GOVERNANCE EXCEPTION fica encerrada.
- Validação funcional do gate (2026-10-06; threshold 0.88; dicionário 2026-10-02.2; vocabulário
  2026-10-02.3; 25 perguntas dirigidas): 7/8 positivos (o restante abaixo de 0.88); as 7
  exclusões rejeitam os casos dirigidos — 6 respostas com fonte errada que existiam antes da
  curadoria passam a resposta-limite; Q45 (IRS/CIVA) e IRC/CIVA continuam rejeitadas. Residuais
  conhecidos mantêm-se: divórcio/desfazer opção AIMI e subarrendamento (fonte servida), "para o
  adicional" (falsa rejeição v1), perguntas ambíguas ("Tributação conjunta: prazo?",
  "Que despesas posso deduzir aos rendimentos?").
- Backups (fora do repositório, `/home/arosario/backups/taxia/`, com `.sha256`):
  `knowledgeflow_pilot_pre_governance_activation_2026-10-02.dump` (antes da V16) e
  `knowledgeflow_pilot_post_governance_activation_2026-10-02.dump` (estado final);
  `knowledgeflow_pilot_pre_credentials_rollout_2026-10-07.dump` (V16, antes do rollout; sha256 `ae49689f…`) e
  `knowledgeflow_pilot_post_credentials_rollout_2026-10-07.dump` (V17, depois do rollout; sha256 `7c2d2f01…`).
  Os dois de 2026-10-07 têm permissões 600.
- **Operação:**
  - `scripts/run-dev.*` não define a base. Para o piloto é preciso `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/knowledgeflow_pilot`; sem isso, o backend liga-se a `knowledgeflow`, onde o admin do piloto não existe.
  - Desde o ADR-006 também é obrigatório `KNOWLEDGEFLOW_JWT_SECRET`.
  - Em WSL, o arranque do piloto faz-se por `~/.config/taxia/start-pilot-backend.sh` (fora do repositório), que carrega o secret local sem o imprimir.

## Credenciais e sessões staff (ADR-006)

- **Modelo:**
  - JWT stateless com `token_version` por utilizador (claim `tv`);
  - validação por pedido do utilizador, da membership, do `tv`, do `token_type` e do issuer;
  - papéis efectivos lidos da BD.
- **Fluxos governados:**
  - mudança da própria password e logout-all;
  - operações ADMIN (revoke, reset com mudança obrigatória só para contas não-ADMIN, disable/reactivate, papéis);
  - contas ADMIN: só a própria mudança de password, ou break-glass se sem acesso (decisão de 2026-10-07);
  - break-glass por CLI, desligado por omissão.
- **Guardas:**
  - último ADMIN activo protegido;
  - contas ADMIN nunca repostas por outro ADMIN (409);
  - não se concede ADMIN com password temporária pendente (409);
  - o secret JWT sem valor por omissão é recusado no arranque (sempre em `knowledgeflow_pilot`).
- **Incidente de 2026-10-06** (só factos, sem valores):
  - Confirmou-se que o backend do piloto corria **sem `KNOWLEDGEFLOW_JWT_SECRET` próprio**, isto é, com o valor por omissão público versionado no repositório. Quem chegasse ao backend podia forjar tokens.
  - Contenção executada no mesmo dia:
    - o backend antigo foi parado;
    - foi gerado localmente um secret forte novo (48 bytes aleatórios), guardado fora do repositório e nunca impresso;
    - o backend foi reiniciado contra `knowledgeflow_pilot` (Flyway V16) e todos os JWT anteriores ficaram invalidados;
    - o login ADMIN foi reconfirmado pelo António.
  - O piloto permaneceu íntegro: nenhuma escrita de dados; contagens inalteradas.
  - Decisão: endurecimento duradouro pelo ADR-006.
  - A rotação da password do admin ficou para o rollout da V17, pelo mecanismo governado e não por SQL. Foi feita em 2026-10-07 (ver abaixo).
- **Rollout no piloto (SEC-PILOT-CREDENTIALS-HARDENING-ROLLOUT, 2026-10-07):**
  - backup pré-rollout válido;
  - backend antigo (V16) parado e arrancado a partir de `main` `f841335` com o launcher local do piloto, com o mesmo secret próprio de contenção, fora do repositório e nunca impresso;
  - JWT secret guard OK, V16 → V17 com sucesso, health UP;
  - os 3 utilizadores ficaram com `token_version=0` e `must_change_password=false`; as 2 contas de serviço ficaram intocadas (DISABLED);
  - acções humanas do António no backoffice: login ADMIN pós-V17; rotação da password de `piloto.admin@taxia.local` em Perfil → "Alterar password" (`token_version` 0 → 1, `USER_PASSWORD_CHANGED`); novo login com a password nova; "Terminar todas as sessões" (`token_version` 1 → 2, `USER_SESSIONS_REVOKED`); login final, com Q&A e Utilizadores acessíveis;
  - auditoria: exactamente estes 2 eventos novos (91 → 93), sem segredos na metadata; nenhum `USER_PASSWORD_RESET` nem `USER_BREAK_GLASS_RESET`;
  - break-glass não executado;
  - corpus intacto: 21 Q&A, 4 publicadas, 4 embeddings, 29 fontes, 7 exclusões; 3 utilizadores e 1 membership;
  - backup pós-rollout válido;
  - o risco residual de reset por ADMIN mantém-se aceite (ADR-006).
- **Nota operacional:** `/actuator/health/readiness` exige autenticação. Não é regressão: só `/actuator/health` e `/actuator/info` são públicos, como antes. O runbook PowerShell refere `readiness` sem token; está registado como follow-up de documentação.

## Verificação legacy do piloto (2026-10-02, só de leitura)

Auditoria, `updated_at`, texto servido e embeddings cruzados para as 4 Q&A publicadas:

- AT-FAQ-2721, AT-FAQ-5795, AT-FAQ-5930: sem alteração material posterior à validação nem à
  publicação.
- CIVA-CONSERVACAO-10A-001 — **LEGACY GOVERNANCE EXCEPTION, baixa severidade** (decisão do
  António, 2026-10-02):
  - conteúdo inalterado desde a validação e coerente com o embedding;
  - evidência alterada após a publicação (troca da fonte genérica "Código do IVA" pela fonte
    "Código do IVA — Artigo 52.º", 2026-10-01, reforço editorial M5), de forma humana,
    autorizada e auditada; anterior ao ADR-005;
  - acção imediata: nenhuma;
  - regularização: depois de a V16 estar aplicada no piloto, criar nova versão com o conteúdo e
    as fontes actuais, validar humanamente e publicar substituindo a versão anterior (ADR-005).
    **Concluída em 2026-10-06** (ver secção Piloto).

## Follow-ups abertos

- Cobertura parcial (perguntas multi-matéria) e fluxo de clarificação (perguntas ambíguas).
- Dicionário v1: "para o adicional" lido como IMI; "categoria B." seguido de nova frase.
- Situações fora do vocabulário de exclusões: divórcio/desfazer opção AIMI, subarrendamento
  (confirmadas como residuais na validação funcional do piloto).
- O backoffice regista como revisor/validador o email da sessão (`piloto.admin@taxia.local`),
  não o nome do especialista; avaliar se a validação deve pedir o nome do revisor.
- Decisão em aberto: levantar ou não o guard rail "o backoffice não publica" para expor
  "publicar e substituir" na UI.
- Runbook PowerShell: a secção 2 usa `/actuator/health/readiness` sem token (responde 401). Rever para usar `/actuator/health` ou autenticar.
- **Risco residual aceite para o piloto** (ADR-006, decisão executiva de 2026-10-07): um ADMIN que repõe a password de uma conta não-ADMIN pode, em teoria, assumir essa identidade, concluir a mudança obrigatória e promover depois a conta.
  - É sobretudo uma questão de atribuição, não de privilégio.
  - O reset e a promoção ficam auditados em nome do ADMIN real.
  - Follow-up futuro: recuperação sem que o ADMIN conheça a credencial.
- **Follow-ups de segurança** (ADR-006, fora de âmbito):
  - rate limiting e lockout no `/auth/login`;
  - auditoria detalhada de login (sucesso e falha);
  - UI para alteração de papéis (já existe na API);
  - cache da validação por pedido, se a escala o exigir.

## Limitações operacionais conhecidas

- Push por SSH a partir do ambiente do agente exige a chave do António (passphrase, sem agente);
  `gh` não está instalado. Leitura do remoto por HTTPS funciona (repositório público).

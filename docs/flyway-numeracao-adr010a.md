# Numeração Flyway na ADR-010-A

## Situação

- `main`: migrações V1 a V20, em `classpath:db/migration`.
- Ramo do piloto (`ac84c04`, só local): as mesmas V1 a V20, com conteúdo idêntico, e mais quatro migrações:
  - V21 — proveniência das chamadas ao modelo nos passos da investigação;
  - V22 — barreira de implicação (`research_entailment_checks`);
  - V23 — registo de despesa da autoridade financeira experimental;
  - V24 — análise de cenários.
- A autoridade financeira do piloto tem ainda as suas próprias migrações (`db/finance` V1 a V3), numa base separada.
  Não são migrações da aplicação.
- Em 20/11/2026 nenhuma base local tinha V21 a V24 aplicadas: `knowledgeflow_pilot` estava em V19. As bases das
  medições do piloto eram descartáveis (Testcontainers). Ambientes que esta verificação não alcança, como o da
  custódia da R-4, não foram inspeccionados.

## Regra

1. **As migrações históricas não mudam.** V1 a V20 ficam fixadas por SHA-256 no teste `MigrationNumberingTest`.
   Nenhuma é alterada, renomeada ou eliminada.
2. **V21 a V24 ficam reservadas ao conteúdo do piloto.** Só entram no `main` com o nome e os bytes exactos do
   piloto. Assim, uma base que já as tenha aplicado continua válida para o Flyway: mesma versão, mesma descrição e
   mesmo checksum. Nenhuma outra migração pode usar estes números.
3. **V21 e V22** entram, sem alterações, com a barreira de implicação, quando ela for importada.
4. **V23 e V24 não entram** sem decisão própria. A V23 pertence à autoridade financeira experimental, que não se
   importa (L6 reservado); a V24 pertence aos cenários, fora do âmbito. Os números ficam por usar.
5. **As migrações novas da ADR-010-A começam em V25** (L5: `research_ask_tickets`, posse, gatilhos). Ficam sempre
   acima de todas as versões do piloto, por isso não é preciso `outOfOrder`.

## Consequências

- **Base nova, criada a partir do `main`:** aplica V1–V20, V21–V22 (quando existirem) e V25 em diante. A falta de
  V23 e V24 não incomoda o Flyway.
- **Base que já tenha V23 ou V24 do piloto:** **o arranque falha.**
  - Por omissão, o Flyway ignoraria essas versões enquanto fossem mais recentes do que todas as locais
    (`ignoreMigrationPatterns = "*:future"`). Por isso, o `main` impõe uma validação estrita, com o
    `StrictFlywayValidation` (`FlywayConfigurationCustomizer`): nenhum tipo de migração é ignorado, e uma migração
    aplicada que não exista localmente, futura ou em falta, faz falhar a validação.
  - Para aceitar uma base nestas condições é preciso uma decisão humana: repor a cópia de segurança ou configurar a
    excepção expressamente. A aplicação nunca o faz sozinha.
  - As migrações pendentes continuam a ser aplicadas.
  - Comportamento comprovado em base descartável (H2) por `StrictFlywayValidationTest`:
    - V23 aplicada com o `main` em V22 → recusa (o controlo sem a validação estrita aceita);
    - V23 aplicada com o `main` já em V25 → recusa;
    - pendentes V22 e V25 → aplicadas;
    - arranque Spring com a base do piloto → falha.
- **Activação:** continua a ser uma acção humana separada, com cópia de segurança, verificação das migrações e plano
  de reversão.

A verificação está em `src/test/java/com/knowledgeflow/migration/MigrationNumberingTest.java` e em
`src/test/java/com/knowledgeflow/config/flyway/StrictFlywayValidationTest.java`. O primeiro exige
nomes `V<n>__<desc>.sql` com versões únicas, e os casos acima foram confirmados por mutação.

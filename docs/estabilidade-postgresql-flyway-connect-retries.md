# Estabilidade PostgreSQL nos testes de integração: ensaio de `spring.flyway.connect-retries`

Ensaio delimitado da ordem executiva de 23/11/2026, ponto 5. **Só no perfil de testes** (`src/test/resources/application-pgtest.yml`): `connect-retries: 5`, `connect-retries-interval: 2s`. A configuração de produção não muda.

## Origem (medição B.2, 23/11/2026, Docker Desktop no WSL)
Numa falha reproduzida, o PostgreSQL ficou pronto dentro do contentor em cerca de 1 s. A porta mapeada no host esteve indisponível cerca de 23 s (o Testcontainers deu o contentor por pronto aos 24,7 s) e recusou a primeira ligação JDBC do Flyway 130 ms depois. No CI (`ubuntu-latest`, Docker nativo) não se observou esta falha.

## Ensaio (`FlywayConnectRetriesIT`)
- **Montagem:** um proxy TCP entre o Flyway e um PostgreSQL descartável fecha as primeiras K ligações já aceites e encaminha as restantes. Conta separadamente as ligações recusadas e as aceites.
- **Duas camadas:**
  - **mecanismo:** Flyway configurado com os valores lidos do perfil;
  - **configuração efectiva do Spring:** `ApplicationContextRunner` com o próprio `application-pgtest.yml`, a autoconfiguração do Flyway e o `StrictFlywayValidation`. Verifica que `spring.flyway.connect-retries` chega ao Flyway criado pelo Spring (5 tentativas, intervalo 2 s).

**Resultados** (execução de 23/11/2026; ambiente: WSL2 com Docker Desktop, Engine 29.8.0, `pgvector/pgvector:pg16`):

| Cenário | Resultado | Tentativas observadas | Tempo |
|---|---|---|---|
| Situação anterior (0 tentativas), 1 recusa | **falha** à primeira | 1 recusada | 0,0–0,2 s |
| Mecanismo, transitória (2 recusas) | **recupera** | 2 recusadas, depois aceites | 3,1–3,7 s |
| Mecanismo, persistente | **falha explícita** (`FlywaySqlException`) | 6 recusadas (1 + 5) | 9,0 s |
| Spring efectivo, transitória (2 recusas) | **arranque OK** | 2 recusadas; 10 aceites (incluem as ligações que o Flyway e a pool abrem depois) | 5,8 s |
| Spring efectivo, persistente | **o arranque falha explicitamente** (`FlywaySqlException` na cadeia) | 6 recusadas | 15,7 s |

A comparação com a situação anterior mostra só que uma primeira recusa deixa de ser fatal. As operações não são idênticas em todos os cenários (`validate` e `migrate`), por isso os tempos não se comparam directamente.

## Limites
- **Espera entre tentativas:** o Flyway espera 1, 2, 2, 2 e 2 s (o intervalo duplica até ao máximo de 2 s), cerca de 9 s no total. Isto **não** é um limite de duração: cada tentativa consome também o seu próprio tempo de ligação ou timeout, e a porta tem de estar disponível no momento de uma tentativa.
- **Cobertura:** só cobre a ligação do Flyway. Não actua sobre a espera do Testcontainers (log e porta) nem sobre ligações posteriores (pool do Hibernate).
- **Simulação:** o proxy recusa fechando a ligação já aceite; não reproduz directamente a recusa TCP por porta fechada.
- **Eficácia não demonstrada:** a medição B.2 não prova que esta alteração resolveria a falha observada. Nesse caso, a indisponibilidade da porta (cerca de 23 s) aconteceu sobretudo antes de o Testcontainers dar o contentor por pronto.
- **Falhas persistentes:** continuam a falhar, como devem, depois de esgotadas as tentativas.
- O CI continua a ser a referência de validação.

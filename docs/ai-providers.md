# Configuração de providers de IA

## Visão geral

O KnowledgeFlow suporta três providers de IA, seleccionáveis por variáveis de ambiente.
A selecção é feita em tempo de arranque — não é possível mudar o provider sem reiniciar a aplicação.

O provider activo é controlado por `AI_PRIMARY_PROVIDER`.
Cada provider tem de estar explicitamente activado (`*_ENABLED=true`) e ter a sua chave configurada.

---

## Configurações por cenário

### Anthropic (produção / QA)

```env
AI_PRIMARY_PROVIDER=anthropic
ANTHROPIC_ENABLED=true
ANTHROPIC_API_KEY=<chave obtida em console.anthropic.com>
ANTHROPIC_MODEL=claude-haiku-4-5-20251001
ANTHROPIC_MAX_TOKENS=1024
OPENAI_ENABLED=false
STUB_AI_ENABLED=false
```

### OpenAI (produção / QA)

```env
AI_PRIMARY_PROVIDER=openai
OPENAI_ENABLED=true
OPENAI_API_KEY=<chave obtida em platform.openai.com/api-keys>
OPENAI_MODEL=<modelo a definir após benchmark — ex: gpt-4o>
OPENAI_MAX_TOKENS=1024
ANTHROPIC_ENABLED=false
STUB_AI_ENABLED=false
```

> **Nota:** `OPENAI_MODEL` não tem default. A aplicação falha no arranque se `OPENAI_ENABLED=true`
> e `OPENAI_MODEL` estiver em branco. Esta é uma protecção intencional: exige decisão explícita
> sobre o modelo antes de qualquer chamada real.

### Stub — desenvolvimento sem chamadas externas

```env
AI_PRIMARY_PROVIDER=stub
STUB_AI_ENABLED=true
ANTHROPIC_ENABLED=false
OPENAI_ENABLED=false
```

Ou, mais simplesmente, activar o perfil `dev`:

```bash
# IntelliJ: adicionar à run configuration
SPRING_PROFILES_ACTIVE=dev

# Maven
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

O perfil `dev` (`application-dev.yml`) já configura o stub sem necessidade de variáveis adicionais.

---

## Como activar o perfil correcto

| Ambiente | Perfil Spring | Variáveis de ambiente |
|---|---|---|
| Desenvolvimento local | `dev` | Nenhuma variável de AI necessária |
| Testes automatizados | `test` | Nenhuma variável de AI necessária |
| QA / Smoke test | *(base)* | Definir as variáveis do provider pretendido |
| Produção | *(base)* | Definir as variáveis do provider pretendido |

---

## Comportamento de arranque

A aplicação falha no arranque (`AIConfigurationException`) se:

- `AI_PRIMARY_PROVIDER` aponta para um provider que não está activado
- `AI_PRIMARY_PROVIDER=anthropic` e `ANTHROPIC_API_KEY` está em branco
- `AI_PRIMARY_PROVIDER=openai` e `OPENAI_API_KEY` ou `OPENAI_MODEL` estão em branco
- `AI_PRIMARY_PROVIDER` não está definido (sem default explícito no ambiente)

Este comportamento é intencional: a aplicação recusa-se a arrancar com configuração inválida
em vez de falhar silenciosamente na primeira chamada ao provider.

---

## Segurança — regras obrigatórias

- **Nunca versionar o ficheiro `.env`** — está no `.gitignore` por esta razão
- **Nunca colocar chaves reais em `application.yml`** ou em qualquer ficheiro versionado
- **As chaves não devem aparecer nos logs** — a implementação actual não regista chaves
- **IntelliJ run configuration:** não guardar chaves reais nas variáveis de ambiente da run
  configuration — o ficheiro `.idea/workspace.xml` fica em disco e pode ser sincronizado.
  Usar em alternativa um `.env` local e carregá-lo com o plugin [EnvFile](https://plugins.jetbrains.com/plugin/7861-envfile).

---

## Smoke tests reais

Os smoke tests com chamadas reais aos providers serão realizados numa etapa posterior,
após validação operacional do ambiente. As instruções específicas serão documentadas
nessa fase.

Os testes automáticos (`mvn test`) nunca efectuam chamadas externas — usam sempre o provider
`stub` ou mocks locais.

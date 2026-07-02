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

## Carregamento local seguro de credenciais

### Spring Boot não lê `.env` automaticamente

O projecto não inclui dependências dotenv (ex: `dotenv-java`) nem `spring.config.import` para
ficheiros `.env`. O Spring Boot não carrega `.env` por defeito.

O ficheiro `.env` está listado no `.gitignore` e nunca deve ser publicado.

### Opção A — variáveis de ambiente no terminal (método recomendado)

**Windows PowerShell:**

```powershell
$env:AI_PRIMARY_PROVIDER = "anthropic"
$env:ANTHROPIC_ENABLED   = "true"
$env:ANTHROPIC_API_KEY   = "valor-definido-localmente"
$env:ANTHROPIC_MODEL     = "claude-haiku-4-5-20251001"
mvn spring-boot:run
```

**Linux / macOS:**

```bash
export AI_PRIMARY_PROVIDER=anthropic
export ANTHROPIC_ENABLED=true
export ANTHROPIC_API_KEY='valor-definido-localmente'
export ANTHROPIC_MODEL='claude-haiku-4-5-20251001'
mvn spring-boot:run
```

As variáveis vivem apenas na sessão do terminal e não são persistidas em disco.

### Opção B — IntelliJ com plugin EnvFile

O plugin [EnvFile](https://plugins.jetbrains.com/plugin/7861-envfile) permite associar um
ficheiro `.env` local à Run Configuration sem escrever os valores directamente no
`workspace.xml`.

Requisitos:
- O ficheiro `.env` permanece local e está ignorado pelo Git (`.gitignore` já o exclui).
- Não deve ser sincronizado via JetBrains Settings Sync (ver aviso abaixo).
- A chave não deve ser escrita no campo "Environment variables" da Run Configuration
  directamente — esse campo é guardado em `workspace.xml` em texto simples.

### Opção C — perfil `dev` (desenvolvimento sem chaves)

Para desenvolvimento local sem chamadas reais a qualquer provider:

```powershell
# PowerShell
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
```

```bash
# Bash / Linux / macOS
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

Ou usar o script incluído no projecto:

```powershell
.\scripts\run-dev.ps1   # Windows
```

```bash
bash scripts/run-dev.sh  # Linux / macOS
```

O perfil `dev` activa o `StubAIProvider` e desactiva Anthropic e OpenAI.
Não é necessária nenhuma chave de API.

### Aviso — IntelliJ `workspace.xml`

O ficheiro `.idea/workspace.xml` é ignorado pelo Git mas existe em disco.
Se uma chave real tiver sido introduzida no campo "Environment variables" de uma
Run Configuration, essa chave fica guardada em texto simples nesse ficheiro.

**Se tal tiver ocorrido:**
1. Abra o IntelliJ → Run → Edit Configurations.
2. Seleccione a configuração afectada.
3. No campo "Environment variables", remova o valor de `ANTHROPIC_API_KEY` (e de qualquer
   outra chave real).
4. Rote a chave em [console.anthropic.com](https://console.anthropic.com) — uma chave
   exposta em disco deve ser considerada comprometida.

### Aviso — JetBrains Settings Sync

O JetBrains Settings Sync pode sincronizar ficheiros da pasta `.idea/` para a nuvem
JetBrains. Verifique se `workspace.xml` está excluído da sincronização antes de activar
esta funcionalidade. Uma chave real em `workspace.xml` pode ser enviada para servidores
externos sem aviso.

### Regras absolutas

- Nunca colocar chaves reais em scripts versionados (incluindo `run-dev.ps1` e `run-dev.sh`).
- Nunca publicar ficheiros `.env` com valores reais.
- Nunca escrever chaves reais no campo "Environment variables" do IntelliJ.
- Rotar imediatamente qualquer chave que tenha sido guardada em texto simples em disco.

---

## Smoke tests reais

Os smoke tests com chamadas reais aos providers serão realizados numa etapa posterior,
após validação operacional do ambiente. As instruções específicas serão documentadas
nessa fase.

Os testes automáticos (`mvn test`) nunca efectuam chamadas externas — usam sempre o provider
`stub` ou mocks locais.

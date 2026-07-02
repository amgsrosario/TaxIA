# TaxIA Benchmark

## Objectivo

Comparar o comportamento de providers de IA (Anthropic, OpenAI e futuros) em perguntas
representativas do domínio fiscal português, de forma controlada, auditável e reproduzível.

O benchmark não mede conhecimento jurídico actualizado — mede comportamentos estruturais:
admissão de incerteza, aderência ao contexto, ausência de invenções, clareza e prudência.

## Âmbito

- Versão actual: `v1`
- Casos: 10
- Providers validados em smoke test: `anthropic` (`claude-haiku-4-5-20251001`), `openai` (`gpt-4.1-mini-2025-04-14`)
- Fluxo testado: `AdminAIController → RagSearchService → EmbeddingClient → AIProviderResolver → Provider`

## Estrutura

```
benchmark/
├── cases/
│   └── taxia-benchmark-v1.json          — dataset canónico (única fonte)
├── schemas/
│   └── benchmark-case-schema.json       — schema JSON de validação de cada caso
├── evaluation/
│   └── evaluation-template.csv          — grelha de avaliação humana (colunas de referência)
├── results/
│   ├── local/                           — resultados brutos locais (não versionados)
│   │   └── .gitkeep
│   └── .gitkeep
├── reports/
│   └── .gitkeep                         — relatórios consolidados revistos (versionáveis)
└── README.md
```

`benchmark/results/local/*` está no `.gitignore`.
`benchmark/reports/` destina-se apenas a relatórios consolidados, revistos e aprovados.

## Pré-requisitos

1. Aplicação TaxIA a correr (ver secção abaixo)
2. Serviço de embeddings a correr: `docker compose up -d embeddings`
3. Utilizador com perfil `ADMIN` disponível
4. PowerShell 5.1 ou superior

## Como arrancar a aplicação por provider

A aplicação deve estar a correr **antes** de executar o script.
Cada provider deve ser executado numa sessão separada.
O script não arranca nem reinicia a aplicação.

**Anthropic:**
```powershell
$env:AI_PRIMARY_PROVIDER = "anthropic"
$env:ANTHROPIC_ENABLED   = "true"
$env:ANTHROPIC_API_KEY   = "<chave local — não versionar>"
$env:ANTHROPIC_MODEL     = "claude-haiku-4-5-20251001"
$env:OPENAI_ENABLED      = "false"
$env:STUB_AI_ENABLED     = "false"
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dserver.port=8082"
```

**OpenAI:**
```powershell
$env:AI_PRIMARY_PROVIDER = "openai"
$env:OPENAI_ENABLED      = "true"
$env:OPENAI_API_KEY      = "<chave local — não versionar>"
$env:OPENAI_MODEL        = "gpt-4.1-mini-2025-04-14"
$env:ANTHROPIC_ENABLED   = "false"
$env:STUB_AI_ENABLED     = "false"
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dserver.port=8082"
```

## Comandos de execução

**Todos os casos — OpenAI:**
```powershell
.\scripts\run-taxia-benchmark.ps1 -Provider openai
```

**Todos os casos — Anthropic:**
```powershell
.\scripts\run-taxia-benchmark.ps1 -Provider anthropic
```

**Caso isolado:**
```powershell
.\scripts\run-taxia-benchmark.ps1 -Provider openai -CaseId TAXIA-004
```

**Com parâmetros explícitos:**
```powershell
.\scripts\run-taxia-benchmark.ps1 `
  -Provider anthropic `
  -BaseUrl http://localhost:8082 `
  -Dataset benchmark\cases\taxia-benchmark-v1.json `
  -DelaySeconds 3 `
  -OutputDirectory benchmark\results\local
```

O script pede confirmação `EXECUTAR` antes de qualquer chamada real.
Qualquer outra resposta cancela a execução.

## Chamadas reais por execução

| Cenário | Chamadas reais |
|---|---|
| 10 casos — 1 provider | **exactamente 10** |
| 10 casos — 2 providers (sessões separadas) | **exactamente 20** |
| Caso isolado (`-CaseId TAXIA-004`) | **exactamente 1** |

Não existe retry automático. Em caso de erro, o script continua para o caso seguinte.

## Resultados

Os resultados são escritos em `benchmark/results/local/` e **não são versionados** pelo Git.
Cada execução produz dois ficheiros:

```
benchmark/results/local/taxia-benchmark-v1_openai_20260622T130500.json   ← dados objectivos
benchmark/results/local/taxia-benchmark-v1_openai_20260622T130500.csv    ← grelha de avaliação humana
```

## Procedimento de avaliação humana

1. Abrir o `.csv` num editor de folha de cálculo (Excel, LibreOffice, etc.).
2. Para cada caso, ler a resposta na coluna `answer`.
3. Comparar com os `expectedBehaviours` e `forbiddenBehaviours` do caso no dataset.
4. Preencher as 10 colunas de pontuação (escala 1–5).
5. Preencher `overall_notes`, `evaluator` e `evaluated_at`.
6. Guardar com novo nome antes de arquivar (ex: adicionar `_avaliado`).

## Comparação dos dois providers

1. Executar primeiro com `openai` (aplicação arrancada com OpenAI).
2. Parar a aplicação.
3. Arrancar de novo com `anthropic`.
4. Executar com `anthropic`.
5. Comparar os dois CSVs na grelha de avaliação.

Os ficheiros JSON de resultado contêm `expectedProvider` e `actualProvider` por caso
para facilitar a detecção de divergências.

## Relatórios consolidados

Após avaliação humana de ambos os providers, os relatórios consolidados e revistos
podem ser colocados em `benchmark/reports/`. Esta pasta é versionável.
Não colocar resultados brutos sem revisão em `benchmark/reports/`.

## Segurança

- O script não guarda passwords, JWT ou API keys em disco.
- O campo `answer` no CSV contém texto gerado — verificar antes de partilhar.
- `benchmark/results/local/` está no `.gitignore` — nunca versionar resultados locais brutos.
- `benchmark/reports/` é versionável apenas com conteúdo revisto e aprovado.
- Nunca colocar chaves reais em ficheiros versionados.

## Aviso sobre custos

Cada execução completa (10 casos) consome tokens reais.
Referência dos smoke tests: Anthropic ~235 tokens entrada + 12 saída por chamada simples;
OpenAI ~167 tokens entrada + 8 saída.
As perguntas do benchmark incluem contexto RAG — estima ~500–800 tokens de entrada por caso.

## Critérios de qualidade TaxIA

Uma boa resposta TaxIA deve:

1. Distinguir factos de inferências.
2. Identificar dados em falta antes de concluir.
3. Evitar conclusões absolutas sem fundamento explícito.
4. Separar regra geral, excepções e conclusão de forma clara.
5. Indicar risco ou necessidade de validação humana quando aplicável.
6. Ser auditável — permitir que um especialista verifique o raciocínio.
7. Não inventar fontes, artigos ou valores.
8. Não depender exclusivamente de conhecimento de memória não verificável.
9. Respeitar o contexto fornecido com prioridade sobre conhecimento genérico.
10. Ser útil para apoiar uma decisão humana — não substituí-la.

## Encoding

| Ficheiro | Encoding | BOM | Motivo |
|---|---|---|---|
| JSON de resultado | UTF-8 | Sem BOM | RFC 8259; compatível com Jackson, Python, IntelliJ, VS Code |
| CSV de avaliação | UTF-8 | Com BOM | Excel no Windows abre correctamente sem conversão manual |
| Script `.ps1` | UTF-8 | Com BOM | PowerShell 5.1 usa Windows-1252 por defeito sem BOM |

### Problema resolvido: mojibake nas respostas

**Causa:** O PowerShell 5.1 `Invoke-RestMethod` descodificava as respostas HTTP como Windows-1252 quando
o backend enviava `Content-Type: application/json` sem `charset=UTF-8` (comportamento padrão do Spring Boot 3.x).
Os bytes UTF-8 de `ó` (`0xC3 0xB3`) lidos como Latin-1 produziam `Ã³`.

**Solução:** O script usa `Invoke-JsonPostUtf8` (função interna baseada em `[System.Net.HttpWebRequest]`
com `[System.IO.StreamReader]` explicitamente em UTF-8). Os ficheiros são escritos com
`[System.IO.File]::WriteAllText` / `WriteAllLines` com `UTF8Encoding(false/true)`.

**Detecção de mojibake:** os padrões `CÃ`, `Ã§`, `Ã£`, `Âº`, `â¬` no texto
da resposta indicam descodificação incorrecta. O script `analyse-taxia-benchmark.ps1` detecta-os automaticamente.

### Como validar um resultado

```powershell
.\scripts\analyse-taxia-benchmark.ps1 -ResultFile benchmark\results\local\taxia-benchmark-v1_openai_20260623T150056.json
```

## Grelha de avaliação

Cada resposta é avaliada em 10 dimensões (escala 1–5) mais 4 campos de avaliação crítica:

| Dimensão | Tipo | Descrição |
|---|---|---|
| `correctness` | 1–5 | Correcção técnica face ao contexto fornecido |
| `context_adherence` | 1–5 | Aderência ao contexto e instrução do sistema |
| `missing_info_identified` | 1–5 | Identificação de dados em falta quando relevante |
| `exception_identified` | 1–5 | Identificação de excepções ou regimes especiais |
| `no_invention` | 1–5 | Ausência de factos, fontes ou valores inventados |
| `clarity` | 1–5 | Clareza e legibilidade da resposta |
| `structure` | 1–5 | Estrutura e organização da resposta |
| `decision_utility` | 1–5 | Utilidade para apoiar uma decisão |
| `prudence` | 1–5 | Prudência e calibração da certeza expressa |
| `human_validation_signalled` | 1–5 | Indicação correcta de necessidade de revisão humana |
| `expected_behaviours_met` | texto | Lista dos comportamentos esperados observados |
| `forbidden_behaviours_detected` | texto | Lista dos comportamentos proibidos detectados |
| `critical_failure` | true/false | Falha crítica independente da pontuação numérica |
| `critical_failure_reason` | texto | Justificação da falha crítica |

### Exemplos de falha crítica (critical_failure = true)

Uma resposta pode ter boa pontuação numérica e ainda assim falhar criticamente:

- Inventar uma fonte, artigo ou taxa sem base no contexto fornecido
- Responder com certeza quando o caso exige explicitamente recusa ou incerteza
- Ignorar informação insuficiente e avançar para uma conclusão
- Indicar um prazo ou taxa sem base fornecida
- Omitir sinalização de validação humana num caso de risco elevado (`requiresHumanValidation = true`)
- Contradizer o contexto fornecido

### Distinção entre sucesso técnico e qualidade da resposta

O campo `error` no JSON/CSV é um indicador de **sucesso técnico**: ausência de erros HTTP, provider correcto,
resposta recebida. Não mede se a resposta fiscal é correcta ou adequada.

A qualidade da resposta é avaliada nas 10 dimensões (1–5) e na grelha de falha crítica.
Estas colunas ficam em branco após a execução e devem ser preenchidas por um avaliador humano.

## Quando criar nova versão do dataset

- Alteração semântica de qualquer caso (pergunta, contexto, critérios): criar `taxia-benchmark-v2.json`
- Correcção técnica sem alterar conteúdo: manter v1
- Os resultados de execução real referenciam sempre a versão usada no campo `benchmarkVersion`
- `taxia-benchmark-v1.json` é preservado enquanto existirem resultados que o referenciam

## Processo de comparação OpenAI vs Anthropic

1. Executar com OpenAI: `.\scripts\run-taxia-benchmark.ps1 -Provider openai`
2. Parar a aplicação e arrancar com Anthropic
3. Executar com Anthropic: `.\scripts\run-taxia-benchmark.ps1 -Provider anthropic`
4. Analisar cada resultado: `.\scripts\analyse-taxia-benchmark.ps1 -ResultFile <caminho>`
5. Abrir os dois CSV no Excel (ou LibreOffice) para avaliação humana lado a lado
6. Preencher as colunas de avaliação (1–5) e as colunas de falha crítica
7. Relatórios aprovados movem para `benchmark/reports/` (versionável)

## Regras de versionamento

- Os casos existentes **não devem ser alterados** depois de terem sido executados e avaliados.
- Para adicionar novos casos: incrementar o ID (TAXIA-011, ...) no mesmo ficheiro ou criar
  `taxia-benchmark-v2.json` se a versão anterior tiver resultados registados.
- Resultados de execuções reais devem referenciar a versão do dataset usado.
- `benchmark/results/local/` — nunca versionar.
- `benchmark/reports/` — versionar apenas após revisão humana.

## Data de criação

2026-06-22

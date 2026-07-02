# Política de Resposta Fundamentada — TaxIA Grounding Layer

## Objectivo

Garantir que as respostas do sistema TaxIA sejam sempre fundamentadas no contexto documental
validado disponível, impedindo que o modelo de linguagem invente factos fiscais (taxas, artigos,
prazos, valores) sem base nos pareceres da equipa.

## Problema resolvido

Sem controlo de fundamentação, um modelo de linguagem pode:

- Inventar taxas, artigos ou prazos que não constam do contexto RAG
- Responder com aparente certeza quando o contexto é insuficiente
- Misturar conhecimento interno do modelo com conhecimento específico da equipa
- Criar risco legal para a empresa e para os clientes

## Fluxo da política

```
pergunta
  → recuperação RAG (RagSearchService.findSimilar)
  → ContextSufficiencyEvaluator.evaluate()
      → INSUFFICIENT_CONTEXT + skipProvider=true
            → SafeResponseFactory.buildRefusal()   [sem chamada ao provider]
      → SUPPORTED / REQUIRES_HUMAN_REVIEW
            → buildControlledPrompt() com INSTRUÇÕES OBRIGATÓRIAS
            → AIService.complete()
            → AnswerGroundingValidator.validate()
                  → rejected
                        → SafeResponseFactory.buildRejected()
                  → accepted (com ou sem claims não suportados)
                        → GroundedAIResponse (SUPPORTED / PARTIALLY_SUPPORTED / REQUIRES_HUMAN_REVIEW)
```

## Componentes

### `ContextSufficiencyEvaluator`

Avaliação **determinística** (sem IA) da suficiência do contexto RAG.

**Critérios de insuficiência:**
- Lista de casos vazia
- Casos sem conteúdo validado
- Número de fragmentos com conteúdo < `minimumFragments`
- Número de fontes distintas < `minimumDistinctSources`
- Melhor score de relevância < `minimumRelevanceScore`

**Critérios de revisão humana** (status `REQUIRES_HUMAN_REVIEW`):
A questão contém palavras-chave de risco elevado: `imóvel`, `imobiliário`, `contencioso`,
`impugnação`, `recurso hierárquico`, `jurisprudência`, `ofício circulado`, etc.

### `SafeResponseFactory`

Produz respostas seguras sem chamar o provider:

- **Recusa** (`INSUFFICIENT_CONTEXT`): contexto insuficiente, sugere consulta a profissional
- **Rejeição** (`REJECTED_UNSUPPORTED`): resposta gerada bloqueada por afirmações não fundamentadas

### `AnswerGroundingValidator`

Detecta afirmações sensíveis na resposta e verifica se estão no contexto.

**Tipos detectados:**

| Tipo | Exemplos |
|---|---|
| `TAX_RATE` | `23%`, `6%`, `13,5%` |
| `LEGAL_REFERENCE` | `artigo 9.º`, `decreto-lei n.º 192/2020` |
| `MONETARY_THRESHOLD` | `650 000 €`, `10 000 euros` |
| `DEADLINE` | `30 dias`, `3 meses` |
| `DATE` | `1 de janeiro de 2024` |
| `LEGAL_OBLIGATION` | `é obrigatório`, `deve liquidar IVA`, `deve registar-se`, `pode renunciar` |
| `EXEMPTION` | `está isento`, `não está sujeito`, `está dispensado` |
| `DEDUCTIBILITY` | `é dedutível`, `não é dedutível`, `tem direito à dedução`, `pode deduzir` |

**Método de verificação:** comparação lexical normalizada (minúsculas, sem marcadores ordinais,
espaços colapsados, verificação com fronteiras de palavra). Afirmações textuais (sem números)
usam multi-palavras específicas para evitar falsos positivos — a presença de palavras genéricas
no contexto não suporta automaticamente a afirmação.

### `GroundingService`

Orquestrador principal. Constrói o prompt controlado com instruções obrigatórias que precedem
sempre o sistema prompt do utilizador e o contexto documental.

**Instruções obrigatórias injectadas no prompt:**
1. O contexto é a única fonte factual autorizada
2. Não citar artigos/taxas/prazos ausentes do contexto
3. Não completar lacunas com memória
4. Indicar explicitamente quando o contexto não cobre a pergunta
5. Distinguir factos de interpretação
6. Identificar elementos em falta
7. Sinalizar necessidade de validação humana
8. Não afirmar ter consultado fontes externas
9. Não inventar fontes, artigos, taxas, prazos ou valores

## Campos na resposta (`AskResponse`)

| Campo | Tipo | Descrição |
|---|---|---|
| `answer` | String | Texto da resposta (ou mensagem de recusa/rejeição) |
| `supportStatus` | String | `SUPPORTED`, `PARTIALLY_SUPPORTED`, `INSUFFICIENT_CONTEXT`, `REQUIRES_HUMAN_REVIEW`, `REJECTED_UNSUPPORTED` |
| `requiresHumanValidation` | boolean | Verdadeiro se a questão é de risco elevado ou resposta parcialmente suportada |
| `validationMessage` | String | Mensagem de contexto sobre a validação (nullable) |
| `sources` | List<String> | Títulos dos documentos usados como contexto |
| `missingInformation` | List<String> | Elementos em falta identificados |
| `limitations` | List<String> | Limitações conhecidas da resposta |
| `providerCalled` | boolean | Indica se o provider de IA foi invocado |
| `responseRejected` | boolean | Indica se a resposta gerada foi bloqueada |
| `unsupportedClaimsCount` | int | Número de afirmações sensíveis sem suporte documental |
| `provider` | String | Provider usado (`none` se não chamado) |
| `model` | String | Modelo usado (`none` se não chamado) |
| `inputTokens` | int | Tokens de entrada (0 se provider não chamado) |
| `outputTokens` | int | Tokens de saída (0 se provider não chamado) |
| `durationMillis` | long | Duração da chamada ao provider em ms (0 se não chamado) |

## Configuração

```yaml
knowledgeflow:
  grounding:
    enabled: true                          # false desactiva validação pós-geração
    minimum-fragments: 1                   # mínimo de fragmentos com conteúdo
    minimum-distinct-sources: 1            # mínimo de títulos distintos
    minimum-relevance-score: 0.0           # score mínimo do melhor fragmento
    reject-unsupported-sensitive-claims: true   # bloquear respostas com afirmações inventadas
    skip-provider-when-context-insufficient: true  # não chamar provider sem contexto
```

**Variáveis de ambiente:** `GROUNDING_ENABLED`, `GROUNDING_MIN_FRAGMENTS`,
`GROUNDING_MIN_SOURCES`, `GROUNDING_MIN_SCORE`, `GROUNDING_REJECT_UNSUPPORTED`,
`GROUNDING_SKIP_PROVIDER`.

## Comportamentos por cenário

| Cenário | `providerCalled` | `supportStatus` | `responseRejected` |
|---|---|---|---|
| Conhecimento base vazio | false | `INSUFFICIENT_CONTEXT` | false |
| Contexto com conteúdo relevante | true | `SUPPORTED` | false |
| Resposta inventa taxa não presente | true | `REJECTED_UNSUPPORTED` | true |
| Questão sobre imóvel/contencioso | true | `REQUIRES_HUMAN_REVIEW` | false |
| Afirmações parcialmente suportadas | true | `PARTIALLY_SUPPORTED` | false |
| `enabled=false` | true | (sem validação) | false |

## Segurança e auditabilidade

- O provider nunca vê a pergunta sem as instruções obrigatórias de contenção
- `providerCalled=false` é auditável: a empresa sabe quando não houve chamada a API externa
- `responseRejected=true` é auditável: a empresa sabe quando o modelo tentou inventar factos
- Nenhum dado sensível (API keys, JWT) é registado nos logs de grounding
- O know-how da equipa permanece na BD da empresa — nunca é enviado para treino

## Data de criação

2026-06-24

# ADR-009: Unified Applicability Rules — Exclusions and Required Context

## Status

Accepted (C9-A; decisão humana final de 2026-10-15, opção A'). Altera o §1 do
[ADR-004](ADR-004-applicability-metadata-governed-exclusions.md) e mantém o resto do ADR-004 e o
[ADR-005](ADR-005-published-content-integrity.md).

## Context

O ADR-004 só previa exclusões (situações que a Q&A declara não cobrir) e decidiu explicitamente
não ter âmbito positivo ("não há `appliesTo` positivo"). Uma exclusão só actua quando a pergunta
contém o sinal contrário. Na C9 (expansão do corpus, 2026-10-14), a simulação de retrieval mostrou
casos reais em que as perguntas perigosas não têm sinal nenhum (threshold 0.88, e5-base):

- AT-FAQ-0959 (acto isolado: dedução do IVA): "Em que condições posso deduzir o IVA suportado?"
  recupera a Q&A com 0.8853. Uma pergunta genérica de actividade regular seria respondida com o
  enquadramento do acto isolado.
- AT-FAQ-4624 (inversão do sujeito passivo, fornecedor e adquirente não residentes): "Quando se
  aplica a inversão do sujeito passivo com fornecedores não residentes?" recupera-a com 0.8858. A
  conclusão ("não se aplica") é a oposta à do caso típico, em que o adquirente é residente.
- AT-FAQ-5930 (despesas dedutíveis aos rendimentos prediais) serve o subarrendamento (0.8826),
  mas o CIRS art. 41.º, n.º 6, nega qualquer dedução na sublocação.

Decisão editorial (2026-10-14): uma resposta limitada sobre o caso errado continua a ser uma
resposta errada. A mitigação por risco HIGH (M4B) não basta.

## Decision

### 1. Regras de aplicabilidade unificadas

Uma Q&A declara **regras de aplicabilidade** (`ApplicabilityRule`), cada uma com um marcador do
vocabulário fechado e um tipo (`kind`):

- `EXCLUDES` — o contexto invalida a aplicação. Se a pergunta o mostrar, o candidato é rejeitado
  (`APPLICABILITY_EXCLUDED`). É a exclusão do ADR-004.
- `REQUIRES` — contexto obrigatório. Se a pergunta não afirmar **todos** os marcadores exigidos
  (semântica AND), o candidato é rejeitado (`APPLICABILITY_CONTEXT_MISSING`). Sem outros candidatos,
  resposta-limite sem chamar o provider.

Ordem no gate (`FiscalScopeFilter`/`ScopeCompatibilityGate`):

1. contradições v1 (imposto, categoria, operação);
2. exclusões;
3. contexto obrigatório.

O contexto obrigatório é a única regra em que o silêncio da pergunta rejeita, e só existe quando a
Q&A a declara.

### 2. Uma só estrutura (opção A'), com nomes de domínio correctos

- A V19 renomeia a tabela da V16 para `knowledge_qa_applicability_rules`. Não a copia: preserva id,
  marcador, nota, autoria e pedidos de remoção pendentes.
- Acrescenta `kind VARCHAR(20) NOT NULL DEFAULT 'EXCLUDES'`, com `CHECK (kind IN ('EXCLUDES',
  'REQUIRES'))`.
- Renomeia as constraints: `knowledge_qa_applicability_rules_pkey`, FK com `ON DELETE CASCADE` e
  `ux_kqa_applicability_rule_marker`.
- As regras existentes ficam `EXCLUDES`. Nenhuma Q&A ganha `REQUIRES` automaticamente.
- No código: `KnowledgeQaApplicabilityRule`, `KnowledgeQaApplicabilityRuleRepository`,
  `KnowledgeQaApplicabilityRuleRow`, `ApplicabilityRuleRequest`/`ApplicabilityRuleResponse`,
  `ApplicabilityRuleKind` e `KnowledgeQaApplicabilityService.addRule`/`removeRule`.

Motivos para uma só estrutura, em vez de uma tabela irmã de requisitos:

- uma única governação: pedido, aprovação e cancelamento de remoção, e revisão de âmbito;
- uma única herança em `createNewVersion`;
- `UNIQUE(qa, marker)` impede, na BD, o mesmo marcador como exclusão e requisito na mesma Q&A;
- menor superfície de código e menor risco de divergência futura entre dois mecanismos.

A dívida de nomes ("exclusions" a conter requisitos) foi eliminada antes do merge, enquanto a V19
ainda não estava aplicada em nenhum ambiente persistente.

### 3. API — compatibilidade sem breaking change

- Caminhos canónicos: `/api/v1/admin/knowledge/qa/{id}/applicability/rules` (POST, com
  `{marker, note, kind}`, em que `kind` omisso é `EXCLUDES`) e `/rules/{marker}`, `/approve-removal`
  e `/cancel-removal`.
- Os caminhos `/applicability/exclusions/...` continuam como aliases legados das mesmas operações.
- Nas respostas, `rules` / `applicabilityRules` são os campos canónicos. `exclusions` /
  `applicabilityExclusions` continuam como aliases legados (mesma lista, com `kind`).
- A remoção dos aliases fica para uma decisão futura, sem urgência.
- O backoffice usa os caminhos e campos canónicos e apresenta duas secções separadas: "Não se
  aplica a" e "Só se aplica quando a pergunta indica".

### 4. Governação (ADR-004/005)

- Acrescentar uma regra (de qualquer tipo) estreita o âmbito: é imediato e auditado, também numa Q&A
  publicada, sem nova validação.
- Retirar uma regra numa Q&A publicada ou VALIDATED alarga o âmbito: fica pendente até aprovação
  humana.
- A nova versão (ADR-005) herda as regras com o tipo. Um tipo desconhecido faz falhar a criação
  (rollback) em vez de o copiar como exclusão.
- Auditoria: `KNOWLEDGE_QA_APPLICABILITY_UPDATED`. O formato das exclusões não muda; os requisitos
  levam `kind=REQUIRES`. A herança regista `rules=[...]`.
- Fail-safe: um marcador ou tipo desconhecido na BD rejeita o candidato (`TECHNICAL_FAILURE`).

### 5. Vocabulário e defesa híbrida (versão de detecção `2026-10-15.1`)

Só casos observados no piloto.

| Marcador | Pode ser REQUIRES | Uso previsto |
|---|---|---|
| `ACTO_ISOLADO` | sim | 0959: REQUIRES |
| `FORNECEDOR_NAO_RESIDENTE` | sim | 4624: REQUIRES (AND) |
| `ADQUIRENTE_NAO_RESIDENTE` | sim | 4624: REQUIRES (AND) |
| `SUBLOCACAO` | sim | 5930: EXCLUDES; futura Q&A específica: REQUIRES |
| `ACTIVIDADE_REGULAR` | **não** (só exclusão) | 0959: EXCLUDES |
| `ADQUIRENTE_RESIDENTE` | **não** (só exclusão) | 4624: EXCLUDES |

Os oito marcadores do ADR-004 continuam só exclusão: foram afinados para evitar rejeições, e num
contexto obrigatório um falso positivo aceita o caso oposto.

Defesa híbrida:

- O contexto obrigatório rejeita as perguntas genéricas, ambíguas e as de outro caso.
- As exclusões `ACTIVIDADE_REGULAR` e `ADQUIRENTE_RESIDENTE` rejeitam as perguntas mistas ou
  contraditórias que afirmam o contexto exigido e, ao mesmo tempo, o contexto oposto. Exemplos:
  "tenho actividade regular mas fiz um acto isolado"; "ambos não residentes, mas o adquirente tem
  estabelecimento estável em Portugal".
- Não se tenta resolver contradições com o LLM: rejeita-se.

### 6. Detecção — cada direcção falha de forma segura

Regras gerais:

- Detecção determinística sobre texto normalizado, sem LLM, sem regras por externalKey.
- Threshold inalterado (0.88).
- Repetições dos padrões limitadas: perguntas longas não esgotam a pilha.

Papéis:

- Os papéis invertidos não contam. "Compro a (outra) empresa estrangeira", "empresa estrangeira a
  quem prestamos" e "prestação de serviços a outra empresa…" não indicam adquirente ou fornecedor
  não residente.
- `ADQUIRENTE_RESIDENTE` exige o papel do adquirente ("cliente português", "o adquirente tem sede
  ou estabelecimento estável em Portugal", "empresa portuguesa compra"). "Portugal" sozinho não
  conta.

Negação no contexto obrigatório:

- `ACTO_ISOLADO` e `SUBLOCACAO` só contam se a pergunta não tiver nenhuma palavra de negação, em
  qualquer ponto ("não", "nunca", "sem", "deixou de", "proíbe", "impede", "recusa", "fora de
  questão", "nenhum"; "não residente" é uma qualidade e não conta).
- Custo de recall aceite: "Não tenho actividade aberta; fiz um acto isolado" recebe resposta-limite.

Negação nas exclusões:

- Conta a presença ("não é sublocação" também exclui; o pior caso é uma resposta-limite).
- Excepção: `ACTIVIDADE_REGULAR` ignora a menção negada nas três palavras anteriores da mesma
  oração ("não tenho actividade aberta", "não estou colectado"), porque essa negação descreve o caso
  oposto.

Riscos residuais:

- uma negação só lexical fora da lista (ex.: "o contrato veda…") seria aceite no contexto
  obrigatório;
- um sinal de papel fora dos padrões não dispara a exclusão.

Mitigam-se com os testes negativos, o held-out e o alargamento da lista quando observado.

## Consequences

- As Q&A sem regras e as exclusões existentes mantêm exactamente o comportamento anterior.
  Verificado: decisões do gate idênticas às da main para as 6 publicadas, com as regras do piloto.
- A configuração concreta (0959, 4624, 5930) é editorial, no backoffice, depois do merge e da V19.
  Não é código.
- Fora de âmbito, como follow-ups:
  - clarificação (perguntar o contexto em falta);
  - aliases e query expansion governada para termos coloquiais;
  - perguntas multi-matéria;
  - remoção dos aliases legados da API.
- Acrescentar um marcador continua a exigir entrada no enum, padrão de detecção, testes negativos,
  held-out e a decisão explícita sobre se pode ser `REQUIRES`.

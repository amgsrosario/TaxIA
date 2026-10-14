# ADR-009: Required Applicability Context

## Status

Accepted (C9-A, 2026-10-14). Altera o §1 do [ADR-004](ADR-004-applicability-metadata-governed-exclusions.md).

## Context

O ADR-004 só permite exclusões, isto é, situações que a Q&A declara não cobrir. Uma exclusão só
actua quando a pergunta contém o sinal contrário. Na C9 (expansão do corpus, 2026-10-14), a
simulação de retrieval mostrou casos reais em que as perguntas perigosas não têm sinal nenhum
(threshold 0.88, e5-base):

- AT-FAQ-0959 (acto isolado: dedução do IVA): "Em que condições posso deduzir o IVA suportado?"
  recupera a Q&A com 0.8853. Uma pergunta genérica de actividade regular seria respondida com o
  enquadramento do acto isolado.
- AT-FAQ-4624 (inversão do sujeito passivo, fornecedor e adquirente não residentes): "Quando se
  aplica a inversão do sujeito passivo com fornecedores não residentes?" recupera-a com 0.8858. A
  conclusão ("não se aplica") é a oposta à do caso típico, em que o adquirente é residente.
- AT-FAQ-5930 (despesas dedutíveis aos rendimentos prediais) serve o subarrendamento (0.8826),
  mas o CIRS art. 41.º, n.º 6, nega qualquer dedução na sublocação.

Decisão editorial (António, 2026-10-14): uma resposta limitada sobre o caso errado continua a ser
uma resposta errada. A mitigação por risco HIGH (M4B) não basta.

## Decision

1. **Contexto obrigatório.** Uma regra de aplicabilidade passa a ter um tipo:
   - `EXCLUDES` (ADR-004): rejeita o candidato quando a pergunta indica o marcador
     (`APPLICABILITY_EXCLUDED`);
   - `REQUIRES` (novo): rejeita o candidato quando a pergunta **não** indica todos os marcadores
     exigidos (`APPLICABILITY_CONTEXT_MISSING`).

   É a única regra em que o silêncio da pergunta rejeita, e só existe quando a Q&A a declara.
2. **Mesmo modelo, mesma governação.**
   - Mesma tabela (V19: coluna `kind` NOT NULL DEFAULT `EXCLUDES`, com CHECK `EXCLUDES`/`REQUIRES`).
   - Mesmo vocabulário fechado, mesmo detector determinístico e mesmo gate (`FiscalScopeFilter`,
     depois das contradições v1 e das exclusões).
   - `UNIQUE(qa, marker)` vale para os dois tipos.
   - Ambos estreitam o âmbito: acrescentar é imediato e auditado, também em Q&A publicada; retirar
     numa Q&A publicada ou VALIDATED fica pendente até aprovação humana.
   - A nova versão (ADR-005) herda as regras com o tipo.
   - A auditoria regista `kind=REQUIRES`; o formato das exclusões não muda.
3. **Vocabulário mínimo, só com casos observados** (versão de detecção `2026-10-14.2`):
   - `SUBLOCACAO` (sublocação/subarrendamento);
   - `ACTO_ISOLADO` (acto isolado, operação isolada, "uma só operação tributável"; a negação não
     conta);
   - `FORNECEDOR_NAO_RESIDENTE`;
   - `ADQUIRENTE_NAO_RESIDENTE`.

   "Ambos não residentes" e "duas empresas estrangeiras" indicam os dois marcadores de não
   residente. Os papéis invertidos não contam: "compro a (outra) empresa estrangeira" e "empresa
   estrangeira a quem prestamos" não indicam adquirente ou fornecedor não residente.
   Negação de `ACTO_ISOLADO` e `SUBLOCACAO`: cada direcção falha de forma segura.
   - Nas exclusões conta a simples presença: "não é sublocação" também exclui, e o pior caso é uma
     resposta-limite.
   - No contexto obrigatório só conta uma ocorrência afirmada: se a pergunta tiver uma palavra de
     negação em qualquer ponto ("não", "nunca", "sem", "deixou de", "proíbe", "impede", "recusa",
     "fora de questão", "nenhum"), estes dois marcadores não contam. Não se tenta adivinhar o alcance
     da negação ("a sublocação não é permitida", "Acto isolado? Não…").
   - Custo de recall aceite: "Não tenho actividade aberta; fiz um acto isolado" recebe resposta-limite.
   - Risco residual: uma negação só lexical fora da lista (ex.: "o contrato veda…") seria aceite;
     mitiga-se com os testes negativos, o held-out e o alargamento da lista quando observado.
   Só formas verbais indicam quem vende ("presto/vende … a outra empresa estrangeira"); substantivos
   como "prestação" ou "fornecedor" não. As repetições dos padrões são limitadas, para que perguntas longas
   não esgotem a pilha.
   Os padrões são conservadores: na dúvida, o marcador não dispara.
   - Numa exclusão, o pior caso é o comportamento v1.
   - Num contexto obrigatório, o pior caso é uma resposta-limite (falha segura).
4. **Só marcadores afinados podem ser contexto obrigatório.** Num contexto obrigatório, um falso
   positivo aceita o caso oposto. Por isso só os quatro marcadores acima (`requirable`), com testes
   negativos próprios, podem ser `REQUIRES`; os restantes foram afinados para exclusão e são
   recusados (400). O backoffice só os oferece quando o tipo é "contexto obrigatório".
5. **Sem regras por externalKey, sem LLM, threshold inalterado (0.88).** A utilização concreta
   (0959 → `REQUIRES ACTO_ISOLADO`; 4624 → `REQUIRES FORNECEDOR_NAO_RESIDENTE` +
   `ADQUIRENTE_NAO_RESIDENTE`; 5930 → `EXCLUDES SUBLOCACAO`) é uma decisão editorial no backoffice,
   não código.
6. **Fail-safe.** Um tipo ou marcador desconhecido na BD rejeita o candidato (`TECHNICAL_FAILURE`),
   como no ADR-004. A criação de nova versão falha (rollback) perante um tipo desconhecido em vez de o
   copiar como exclusão.

## Consequences

- As Q&A sem regras e as exclusões existentes mantêm exactamente o comportamento anterior (default
  `EXCLUDES`).
- Custo de recall aceite: uma pergunta legítima que não use as palavras do contexto obrigatório cai
  em resposta-limite.
- Fora de âmbito, como follow-ups: clarificação (perguntar o contexto em falta), aliases e query
  expansion governada para termos coloquiais, perguntas multi-matéria.
- Acrescentar um marcador continua a exigir entrada no enum, padrão de detecção, testes e held-out.

# ADR-007: Risk Propagation and Human Validation of Answer Foundations

## Status

Accepted (M4B, 2026-10-12). Decisões humanas: 1-B (piso SUGGESTED), 2b (CRITICAL), risco
desconhecido fail-closed.

## Context

As Q&A guardam `riskLevel` (LOW, MEDIUM, HIGH, CRITICAL) e `requiresHumanValidation`, ambos
governados na curadoria. Até esta decisão nenhum dos dois chegava à resposta:

- o RAG só lia título, pergunta, conteúdo e similaridade;
- o mapper fixava `aggregatedRiskLevel=null` ("regra 27");
- o `requiresHumanValidation` da resposta vinha apenas de uma heurística de palavras-chave na pergunta.

No piloto, a G2 (MEDIUM, `requiresHumanValidation=true`) respondia com risco `null`,
`requiresHumanValidation=false` e `parecerRequirement=NONE`.

A política do Bloco C já fixava a agregação (C4) e o efeito de HIGH no `answerType` (C2). Faltava
decidir o significado de `requiresHumanValidation` em runtime e o tratamento de CRITICAL.

## Decision

1. **Só fundamentos usados.** O risco agregado vem apenas dos fundamentos efectivamente usados:
   exactamente os candidatos que passaram o threshold de relevância e o gate de âmbito e
   aplicabilidade e foram enviados ao provider no prompt. A lista não é deduplicada por título.
2. **Mais restritivo.** O risco agregado é o nível mais restritivo desses fundamentos (C4).
3. **Descartados não contam.** Candidatos descartados por threshold, âmbito ou aplicabilidade não
   participam. Uma resposta sem fundamentos (Resposta-limite antes do LLM, resposta rejeitada) não
   tem risco agregado e mantém o comportamento próprio.
4. **`requiresHumanValidation` propaga-se.** Basta um fundamento usado com
   `requiresHumanValidation=true` para o valor agregado ser `true`.
5. **Piso SUGGESTED.** Esse valor estabelece o piso `parecerRequirement=SUGGESTED`. Não bloqueia,
   por si só, uma Consulta documentada LOW/MEDIUM. Uma Q&A com `false` não recebe SUGGESTED por
   esta regra.
6. **Sem workflow de aprovação.** Não se cria aprovação da resposta: SUGGESTED é encaminhamento
   profissional, e curadoria interna ≠ revisão humana pendente da resposta (C3).
7. **HIGH.** Nunca dá `CONSULTA_DOCUMENTADA` conclusiva automática; no máximo
   `CONSULTA_DOCUMENTADA_COM_LIMITACOES` (C2), logo pelo menos SUGGESTED.
8. **CRITICAL.** `answerType=RESPOSTA_LIMITE` e `parecerRequirement=REQUIRED`: a TaxIA não fecha
   conclusão automática. Não é tratado como HIGH.
9. **Risco desconhecido.** Risco ausente ou inválido num fundamento usado, Q&A inexistente,
   fundamento DOCUMENT sem Q&A ou falha de leitura:
   - fica identificado como **desconhecido**: `aggregatedRiskLevel=null` e diagnóstico
     `RISK_UNKNOWN`; nunca é convertido nem persistido como HIGH;
   - a decisão automática recebe tratamento fail-closed equivalente a HIGH;
   - a validação humana desse fundamento é tratada como `true`;
   - um CRITICAL conhecido prevalece (nada o excede).
10. **Só restringe.** O `supportStatus` e a aplicabilidade e o âmbito continuam independentes. A
    regra de risco só restringe a decisão base de suporte e fontes, nunca a alarga.

## Consequences

- **Leitura:**
  - `GroundedAIResponse.foundations` expõe os fundamentos usados;
  - o `FoundationGovernanceResolver` lê `risk_level` e `requires_human_validation` numa query nativa
    só de leitura, com o risco em texto;
  - a query é filtrada pela organização do pedido: uma Q&A de outra organização conta como
    inexistente (risco desconhecido);
  - não usa transacção própria: uma falha de leitura não pode escapar como rollback-only e é
    tratada como risco desconhecido;
  - o `AnswerDecisionService` aplica a decisão.
- **Exposição:**
  - `aggregatedRiskLevel` e a origem do risco ficam no diagnóstico INTERNAL;
  - DEMO e EXTERNAL preservam o `answerType`, o `parecerRequirement` e as limitações (C7);
  - o `requiresHumanValidation` do `/ask` passa a ser o agregado.
- **Corpus actual** (sem alterações de dados):
  - G1, G2, AT-FAQ-5930, 2721 e 5795 (`requiresHumanValidation=true`) passam a SUGGESTED;
  - CIVA conservação v2 (`false`) mantém NONE.
- **Curadoria.** Como o import marca `requiresHumanValidation=true` por omissão, o campo passa a ter
  efeito visível na resposta e a checklist de curadoria explica-o.
- **Fora de âmbito:** REQUIRED para HIGH com aplicação concreta (não detectável de forma
  determinística), a matriz completa C5+, e a UI.
- **Decisão pendente (sem impacto actual, porque não há CRITICAL publicado):** com um fundamento
  CRITICAL, a resposta passa a `RESPOSTA_LIMITE` + `REQUIRED`, com a limitação "não fecha conclusão
  automática" e o encaminhamento para Pedido de parecer. Mas o texto gerado pelo LLM continua a ser
  o corpo visível da resposta, porque a projecção não altera o corpo. Fica por decidir se, para
  CRITICAL, o corpo deve ser substituído por enquadramento ou mantido com a limitação.

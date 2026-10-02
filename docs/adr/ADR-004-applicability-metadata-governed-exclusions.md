# ADR-004: Applicability Metadata and Governed Exclusions

## Status

Accepted (M4-SCOPE-V2, 2026-10-02).

## Context

O grounding do TaxIA filtra candidatos por relevância vectorial (`minimum-relevance-score`
0.88) e por contradição de âmbito derivado (M4-SCOPE: imposto, categoria de rendimentos do
IRS, operação). As baterias held-out 1 e 2 mostraram zero fontes de imposto e de categoria
erradas, mas falsos positivos residuais que nenhum âmbito derivado consegue ver, porque
dependem do que a Q&A **não** cobre:

- sujeito: inquilino vs senhorio, herança indivisa vs casal;
- situação: habitação própria vs imóvel arrendado, pensão estrangeira vs nacional;
- operação vizinha no mesmo regime: opção vs cálculo, reclamação, pagamento; pedido
  voluntário vs dever de reter.

Baixar o threshold multiplicava estes erros. Esse conhecimento é editorial.

## Decision

1. **Exclusões, não âmbito positivo.** Uma Q&A pode declarar situações que não cobre,
   escolhidas de um vocabulário fechado e versionado (`ApplicabilityMarker`). O âmbito
   positivo continua derivado pelo gate v1. Não há `appliesTo` positivo: uma lista positiva
   rejeitaria perguntas legítimas ("sou reformado e…") e custaria mais curadoria sem caso
   real que a exija. Só entram exclusões materialmente relevantes, conhecidas (observadas) e
   validadas editorialmente; não se declara o que o gate v1 já rejeita.
2. **Tabela filha relacional** `knowledge_qa_applicability_exclusions` (V16): FK com
   `ON DELETE CASCADE`, `UNIQUE(knowledge_qa_id, marker)` (que serve também as pesquisas por
   Q&A), nota opcional (≤ 500), autor e data. Sem `CHECK` de vocabulário na BD: o vocabulário é validado na aplicação, para que um
   marcador novo não exija migração. Mais `applicability_reviewed_at/by` na Q&A ("revisto,
   sem exclusões" ≠ "nunca revisto").
3. **Fora do embedding.** As exclusões não entram no passage nem no vector; alterar
   exclusões não reindexa. O embedding serve a recuperação; a aplicabilidade é aplicada
   depois, com motivo explicável.
4. **Um só gate.** O `FiscalScopeFilter` existente é estendido: retrieval → threshold →
   contradições v1 (imposto, categoria, operação) → exclusões (`APPLICABILITY_EXCLUDED`) →
   evaluator. Os marcadores são detectados na pergunta de forma determinística
   (`ApplicabilityMarkerDetector`); só têm efeito quando a Q&A os declara. Exclusões
   carregadas no mesmo ciclo do âmbito (segunda query IN), só para candidatos que passaram o
   threshold.
5. **Governação de alterações.**
   - Acrescentar exclusão estreita o âmbito: efectiva de imediato, também em Q&A publicada,
     auditada, sem nova validação, sem reindex, sem mudar o estado de curadoria.
   - Remover numa Q&A não publicada e não VALIDATED: imediato e auditado.
   - Remover numa Q&A publicada — ou VALIDATED, que pode ser (re)publicada sem nova validação,
     o que fecha o atalho despublicar → remover → republicar — alarga o âmbito: o pedido fica
     registado na própria linha (`removal_requested_at/by`) e a exclusão **continua efectiva**
     até aprovação humana explícita (`approve-removal` com `reviewerName`). Uma remoção pendente
     só sai por aprovação ou fica por cancelamento. Nunca há remoção silenciosa nem
     despublicação automática. As operações bloqueiam a linha da Q&A (serializam com a
     publicação).
   - Uma alteração efectiva das exclusões repõe "âmbito não revisto".
   - `createNewVersion` copia as exclusões para a nova versão (sem pedidos pendentes, que ficam
     efectivos) e não copia a marca de revisão.
   - Todas as operações: `KNOWLEDGE_QA_APPLICABILITY_UPDATED` com `event` (ADDED,
     REMOVED, REMOVAL_REQUESTED, REMOVAL_APPROVED, REMOVAL_CANCELLED, REVIEWED), marcador,
     estado de publicação e actor.
6. **Legacy e opcionalidade.** Metadata opcional: uma Q&A sem exclusões e sem revisão
   mantém o comportamento v1 e continua publicável; o backoffice mostra "âmbito não revisto".
7. **Fail-safe.** Ausência de exclusões é legítima (ALLOW conforme v1). Um código
   desconhecido na BD, uma Q&A sem linha ou uma falha de carregamento rejeitam o candidato
   (`TECHNICAL_FAILURE`); se nada resta, resposta-limite sem chamar o provider.
8. **Threshold inalterado** (0.88).

## Consequences

- As exclusões das Q&A reais são uma operação editorial humana posterior (não migradas).
- Fora de âmbito, como follow-ups separados: cobertura parcial (perguntas multi-matéria),
  fluxo de clarificação (perguntas ambíguas), reranker/LLM verifier.
- Lacuna pré-existente registada e não corrigida aqui: GOV-PUBLISHED-CONTENT-INTEGRITY
  (campos curados de uma Q&A validada/publicada, incluindo a resposta técnica, podem ser
  editados sem nova validação). A política de remoção de exclusões não depende dela.
- Acrescentar um marcador exige: entrada no enum, padrão de detecção, testes e held-out.

# ADR-008: Source Freshness Verification

## Status

Accepted (C8 Source Freshness Governance, 2026-10-13). Decisão humana: opção C, actualidade híbrida
e governada. Implementação bloqueada para merge até autorização humana pós-BNI.

## Context

A política C8 (`taxia-risk-visibility-policy.md` §3-H) define quatro estados de actualidade —
`CURRENT`, `STABLE_BUT_OLD`, `UNCERTAIN`, `OUTDATED` — apenas conceptualmente.

A implementação D5 era "actualidade textual":
- `OUTDATED` só com marcadores de revogação/caducidade no título ou na referência;
- `UNCERTAIN` em todos os outros casos;
- `CURRENT` e `STABLE_BUT_OLD` nunca materializados.

Não existiam campos de verificação. A verificação humana já feita (G1, G2, CIVA conservação v2)
estava só em texto livre. Isto deixava duas tensões:
- todas as fontes apareciam como "Actualidade não confirmada";
- a C8 (UNCERTAIN não deve sustentar conclusão actual sem aviso forte) ficava em conflito com o D10
  (UNCERTAIN preservava o tipo de resposta).

## Decision

1. **Estado da fonte.** A actualidade é um estado governado da **fonte efectivamente usada**, não
   uma inferência da idade da Q&A. Estados: `CURRENT`, `STABLE_BUT_OLD`, `UNCERTAIN`, `OUTDATED`.
2. **Calculada, nunca escolhida.** O estado é **calculado** a partir de dados governados
   (`SourceFreshnessPolicy`). Não há escolha manual do estado: o backoffice só regista factos.
3. **UNCERTAIN por omissão (fail-safe).** Ausência de informação nunca produz `CURRENT`.
4. **CURRENT exige verificação humana estruturada:**
   - `verifiedAt` e `verifiedBy`; o responsável é uma referência governada a `users.id` com a
     capacidade de curadoria de Q&A; data e responsável vêm do servidor;
   - fonte elegível:
     - legislação (`LEGISLATION`);
     - FAQ/orientação oficial (`OFFICIAL_FAQ`, `ADMINISTRATIVE_GUIDANCE`);
   - para legislação, `consolidatedVersionConfirmed=true` (versão oficial/consolidada confirmada);
   - sem marcador de revogação/caducidade/substituição (comparado sem acentos);
   - `validTo` não expirado e `validFrom` não futuro (fonte ainda não em vigor → `UNCERTAIN`);
   - verificação com instante não posterior ao relógio;
   - dentro da janela.

   A revisão editorial da Q&A não conta.
5. **Janelas CURRENT**, contadas desde o dia civil da última verificação (Europe/Lisbon), inclusivas:
   - legislação: **90 dias**;
   - FAQ/orientação oficial: **60 dias**.

   A idade da fonte, isoladamente, nunca cria `CURRENT`.
6. **STABLE_BUT_OLD** nunca é automático. Exige:
   - verificação válida já fora da janela CURRENT;
   - `stableMatter=true` marcado por curador autorizado, com justificação textual auditável;
   - ausência de `validTo` expirado e de marcadores.

   Limite desde `verifiedAt`: **365 dias** (legislação) e **180 dias** (FAQ/orientação). Depois
   disso, `UNCERTAIN`: a marca não é validade eterna.
7. **OUTDATED** com pelo menos um sinal inequívoco:
   - marcador de revogação, caducidade, substituição, "sem efeito" ou desactualização;
   - **`validTo` da fonte anterior a hoje** (`validTo` igual a hoje ainda é válido).

   **Altera a regra "actualidade apenas textual"** do contrato D3 (§12.B/§12.G): `validTo` é
   metadado governado explícito. Nunca `OUTDATED` só por idade.
8. **Agregação.** `overallFreshnessStatus` = **pior** estado das fontes efectivamente usadas
   (`OUTDATED` > `UNCERTAIN` > `STABLE_BUT_OLD` > `CURRENT`). Fontes recuperadas mas descartadas
   não contam; uma fonte sem estado conta como `UNCERTAIN`. Quando duas Q&A usadas citam a mesma
   fonte (deduplicada para apresentação), fica a cópia com o **pior** estado: a deduplicação nunca
   esconde uma fonte desactualizada ou não verificada.
9. **Efeito na resposta** (fecha a tensão C8/D10, com `CURRENT` operacionalizado):
   - `UNCERTAIN`:
     - nunca `CONSULTA_DOCUMENTADA` conclusiva; no máximo `CONSULTA_DOCUMENTADA_COM_LIMITACOES`;
     - `parecerRequirement` pelo menos `SUGGESTED`;
     - limitação explícita de actualidade e próximo passo de confirmação do regime vigente;
     - não é Resposta-limite automática.
   - `STABLE_BUT_OLD`: mantém a forma, com nota de antiguidade/estabilidade.
   - `OUTDATED`: mantém a política mais restritiva existente.
   - `CURRENT` não elimina outros riscos (M4B/ADR-007), limitações nem pareceres.
   - Sem fontes usadas não há fonte de onde derivar actualidade; mantém-se a regra de suporte.
10. **Independência.** A validade da Q&A (`validFrom`/`validTo`, que filtra o RAG) e a actualidade
    da fonte são dimensões independentes: Q&A válida ≠ fonte `CURRENT`.

## Consequences

- **Dados.**
  - A migração aditiva V18 em `knowledge_source_references` acrescenta `verified_at`,
    `verified_by` (FK `users`), `consolidated_version_confirmed`, `stable_matter`,
    `stable_matter_reason`, `stable_matter_marked_at` e `stable_matter_marked_by` (FK `users`).
  - CHECKs de coerência: verificação com responsável; versão consolidada só com verificação;
    matéria estável justificada.
  - Sem backfill.
- **Backoffice/API** (capacidade de curadoria de Q&A; auditado):
  - registar e revogar verificação: `POST`/`DELETE …/sources/{sourceId}/verification`;
  - marcar e desmarcar matéria estável: `PUT …/stable-matter`;
  - a resposta mostra o estado calculado, o motivo, "actual até", "estável até" e se a fonte
    precisa de revalidação.
  - Permitido em Q&A publicadas: é metadado de governação, não altera o texto servido nem o estado
    de curadoria (ADR-005).
  - Uma nova versão (ADR-005) **não** herda a verificação das fontes: tem de ser verificada de novo.
  - Revogar a verificação também retira a matéria estável, que tem de ser reexaminada.
- **Impacto no corpus.** Sem verificação estruturada, todas as fontes ficam `UNCERTAIN`, e todas as
  respostas passam a `CONSULTA_DOCUMENTADA_COM_LIMITACOES` + `SUGGESTED` até haver verificação.
  Activar exige o plano de backfill humano (`docs/c8-freshness-backfill-plan.md`).
- **Testes.**
  - Relógio injectável (`SourceFreshnessPolicy(Clock)`); fronteiras 0/89/90/91, 0/59/60/61,
    365/366, 180/181; `validTo` ontem/hoje/amanhã; fronteira do dia em Lisboa.
  - Agregação; efeito na decisão; projecção; autorização e auditoria; migração em PostgreSQL.
- **Fora de âmbito:** jurisprudência e normas contabilísticas como fontes elegíveis para `CURRENT`;
  lembretes automáticos de revalidação; verificação em lote.

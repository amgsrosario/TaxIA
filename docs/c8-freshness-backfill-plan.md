# C8 — Plano de backfill pós-BNI (ADR-008)

> **Executado em 2026-10-14 (prioridade 1).** As 5 fontes de G1, G2 e
> `CIVA-CONSERVACAO-10A-001_v2` foram reconfirmadas e registadas pelo António no backoffice
> (CURRENT até 2027-01-12). A prioridade 2 (AT-FAQ) **não** foi executada. O texto livre das notas
> **não** foi convertido em verificação estruturada. Estado: [taxia-project-state.md](taxia-project-state.md).

## Pré-condições

1. Merge humano do PR C8 e restart do backend do piloto, depois da autorização pós-BNI.
2. Flyway V18 aplicada ao piloto. Por omissão todas as fontes ficam sem verificação, portanto
   `UNCERTAIN`, e todas as respostas passam a "com limitações" + `SUGGESTED` até haver verificação.
3. Backup lógico da `knowledgeflow_pilot` antes de qualquer verificação.

## Verificação humana (no backoffice, por um curador com a capacidade de curadoria de Q&A)

O curador confirma de novo cada fonte na fonte oficial e regista a verificação **nesse dia**: a data
vem do servidor, sem datas retroactivas. Para legislação, confirma também a versão
oficial/consolidada.

| Prioridade | Q&A | Fontes | Notas existentes (não convertidas) |
|---|---|---|---|
| 1 | G1 `CIRC-CONSERVACAO-10A-001` | CIRC art. 125.º (LEGISLATION); DL 28/2019 art. 19.º (LEGISLATION) | confirmado em 2026-10-12; consolidado DRE confirmado humanamente |
| 1 | G2 `CIVA-FATURA-PRAZO-EMISSAO-001` | CIVA art. 36.º; CIVA art. 7.º (LEGISLATION) | confirmado em 2026-10-12 |
| 1 | `CIVA-CONSERVACAO-10A-001_v2` | CIVA art. 52.º (LEGISLATION) | redacção DL 49/2025; confirmado em 2026-10-01 |
| 2 | AT-FAQ-5930 / 2721 / 5795 | FAQ oficial + legislação | **sem verificação estruturada suficiente**: exigem verificação nova; não marcar CURRENT sem ela |

## Depois do backfill

- Retestar G1, G2 e DEMO1: fontes `CURRENT` dão consulta documentada sem limitação de actualidade.
  O M4B mantém-se: G1 e G2 continuam `SUGGESTED` por `requiresHumanValidation=true`.
- Registar a próxima revalidação: legislação até 90 dias; FAQ até 60 dias. Ponderar matéria estável
  só com justificação.

# TaxIA — Estado do projecto e handoff

Documento de passagem: permite retomar o projecto sem depender de conversas anteriores.
Confirmar sempre no código, nas migrações e nos ADR (ver [AGENTS.md](../AGENTS.md)).

Última actualização: 2026-10-02 (GOV-PUBLISHED-CONTENT-INTEGRITY).

## Base e entregas recentes

| Entrega | PR | Conteúdo |
|---|---|---|
| M3 | #8 | Fontes curadas nas respostas (`sourceQaId`, resolução em batch) |
| M4 | #9 | Filtro de relevância candidato a candidato (`GROUNDING_MIN_SCORE`, 0.88) |
| M4-SCOPE | #10 | Gate determinístico de contradição de âmbito (imposto, categoria, operação) |
| M4-SCOPE-V2 | #11 | Exclusões de aplicabilidade governadas (V16, [ADR-004](adr/ADR-004-applicability-metadata-governed-exclusions.md)) |
| GOV-PUBLISHED-CONTENT-INTEGRITY | branch `feat/published-content-integrity` (PR contra `main`) | Integridade pós-validação e versões ([ADR-005](adr/ADR-005-published-content-integrity.md)) |

Migrações: V1–V16. GOV-PUBLISHED-CONTENT-INTEGRITY não acrescenta migração.

## Pipeline de grounding vigente

retrieval (topK 5, e5-base) → relevância ≥ 0.88 → gate de âmbito (contradições v1 + exclusões
ADR-004) → avaliação de suficiência → prompt / validação / fontes → resposta. Sem candidatos:
resposta-limite sem chamar o provider. O threshold mantém-se 0.88 (held-outs 2 e 3 não
justificaram descida).

## Lifecycle de conhecimento vigente (ADR-005)

- `VALIDATED` = o conteúdo em vigor desta versão foi validado por um humano.
- VALIDATED não publicada: alteração material/expansiva → volta a `PENDING_REVIEW`.
- Versão publicada: congelada para alterações materiais/expansivas (409); conservadoras e notas
  são imediatas e auditadas.
- Mudança material numa publicada: `POST /{id}/versions` (cópia em revisão; a publicada
  continua a responder) → validação → `POST /{newId}/publish-replacing/{previousId}`
  (substituição atómica). O backoffice não publica.
- Re-import sobre Q&A VALIDATED/publicada: salta e reporta (`PROTECTED_SKIPPED`).

## Piloto

- Base `knowledgeflow_pilot`: 4 Q&A publicadas (AT-FAQ-2721, AT-FAQ-5795, AT-FAQ-5930,
  CIVA-CONSERVACAO-10A-001). Não alterada pelas entregas M4-SCOPE, M4-SCOPE-V2 e
  GOV-PUBLISHED-CONTENT-INTEGRITY.
- A V16 ainda não foi aplicada ao piloto (decisão e acção humanas). Exclusões propostas para as
  4 Q&A estão por validar editorialmente.

## Follow-ups abertos

- Verificação só de leitura da auditoria do piloto: as 4 Q&A publicadas foram editadas depois de
  validadas? (autorizada após o merge de GOV-PUBLISHED-CONTENT-INTEGRITY).
- Aplicar V16 ao piloto e definir as exclusões reais (editorial).
- Cobertura parcial (perguntas multi-matéria) e fluxo de clarificação (perguntas ambíguas).
- Dicionário v1: "para o adicional" lido como IMI; "categoria B." seguido de nova frase.
- Situações fora do vocabulário de exclusões: divórcio/desfazer opção AIMI, subarrendamento.
- Decisão em aberto: levantar ou não o guard rail "o backoffice não publica" para expor
  "publicar e substituir" na UI.

## Limitações operacionais conhecidas

- Push por SSH a partir do ambiente do agente exige a chave do António (passphrase, sem agente);
  `gh` não está instalado. Leitura do remoto por HTTPS funciona (repositório público).

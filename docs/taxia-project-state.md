# TaxIA — Estado do projecto e handoff

Documento de passagem: permite retomar o projecto sem depender de conversas anteriores.
Confirmar sempre no código, nas migrações e nos ADR (ver [AGENTS.md](../AGENTS.md)).

Última actualização: 2026-10-06 (PILOT-GOVERNANCE-ACTIVATION).

## Base e entregas recentes

| Entrega | PR | Conteúdo |
|---|---|---|
| M3 | #8 | Fontes curadas nas respostas (`sourceQaId`, resolução em batch) |
| M4 | #9 | Filtro de relevância candidato a candidato (`GROUNDING_MIN_SCORE`, 0.88) |
| M4-SCOPE | #10 | Gate determinístico de contradição de âmbito (imposto, categoria, operação) |
| M4-SCOPE-V2 | #11 | Exclusões de aplicabilidade governadas (V16, [ADR-004](adr/ADR-004-applicability-metadata-governed-exclusions.md)) |
| GOV-PUBLISHED-CONTENT-INTEGRITY | #12 (merged, `324c1f9`) | Integridade pós-validação e versões ([ADR-005](adr/ADR-005-published-content-integrity.md)) |

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

- Base `knowledgeflow_pilot` em **V16** (aplicada em 2026-10-02 pela API do Flyway com as migrações
  de `main`, depois de validar os checksums V1–V15; sem alteração de conteúdo).
- 21 Q&A no total, 4 publicadas e VALIDATED, 4 embeddings (um por publicada):
  AT-FAQ-2721, AT-FAQ-5795, AT-FAQ-5930 e CIVA-CONSERVACAO-10A-001_v2.
- Exclusões de aplicabilidade (ADR-004), definidas pelo António no backoffice em 2026-10-06, todas
  com âmbito marcado como revisto:

  | Q&A | Exclusões |
  |---|---|
  | AT-FAQ-2721 | HERANCA_INDIVISA, RECLAMACAO, PAGAMENTO, CALCULO |
  | AT-FAQ-5795 | RETENCAO_OBRIGATORIA (PENSAO_ESTRANGEIRA avaliada e **não** acrescentada) |
  | AT-FAQ-5930 | INQUILINO, HABITACAO_PROPRIA |
  | CIVA-CONSERVACAO-10A-001_v2 | nenhuma (âmbito revisto) |

- **Regularização CIVA concluída** (ADR-005): nova versão `CIVA-CONSERVACAO-10A-001_v2`
  (`6e5d958f-…`, `previous_version_id` = `ba231677-…`), conteúdo idêntico, com a fonte
  "Código do IVA — Artigo 52.º"; validada em 2026-10-06 e publicada por "publicar e substituir"
  (`PUBLISHED replaces=` / `UNPUBLISHED replacedBy=`). A versão original fica VALIDATED,
  despublicada e sem embedding (histórico). A LEGACY GOVERNANCE EXCEPTION fica encerrada.
- Validação funcional do gate (2026-10-06; threshold 0.88; dicionário 2026-10-02.2; vocabulário
  2026-10-02.3; 25 perguntas dirigidas): 7/8 positivos (o restante abaixo de 0.88); as 7
  exclusões rejeitam os casos dirigidos — 6 respostas com fonte errada que existiam antes da
  curadoria passam a resposta-limite; Q45 (IRS/CIVA) e IRC/CIVA continuam rejeitadas. Residuais
  conhecidos mantêm-se: divórcio/desfazer opção AIMI e subarrendamento (fonte servida), "para o
  adicional" (falsa rejeição v1), perguntas ambíguas ("Tributação conjunta: prazo?",
  "Que despesas posso deduzir aos rendimentos?").
- Backups (fora do repositório, `/home/arosario/backups/taxia/`, com `.sha256`):
  `knowledgeflow_pilot_pre_governance_activation_2026-10-02.dump` (antes da V16) e
  `knowledgeflow_pilot_post_governance_activation_2026-10-02.dump` (estado final).
- Operação: `scripts/run-dev.*` não define a base; para o piloto é preciso
  `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/knowledgeflow_pilot` (sem isso o
  backend liga-se a `knowledgeflow`, onde o admin do piloto não existe).

## Verificação legacy do piloto (2026-10-02, só de leitura)

Auditoria, `updated_at`, texto servido e embeddings cruzados para as 4 Q&A publicadas:

- AT-FAQ-2721, AT-FAQ-5795, AT-FAQ-5930: sem alteração material posterior à validação nem à
  publicação.
- CIVA-CONSERVACAO-10A-001 — **LEGACY GOVERNANCE EXCEPTION, baixa severidade** (decisão do
  António, 2026-10-02):
  - conteúdo inalterado desde a validação e coerente com o embedding;
  - evidência alterada após a publicação (troca da fonte genérica "Código do IVA" pela fonte
    "Código do IVA — Artigo 52.º", 2026-10-01, reforço editorial M5), de forma humana,
    autorizada e auditada; anterior ao ADR-005;
  - acção imediata: nenhuma;
  - regularização: depois de a V16 estar aplicada no piloto, criar nova versão com o conteúdo e
    as fontes actuais, validar humanamente e publicar substituindo a versão anterior (ADR-005).
    **Concluída em 2026-10-06** (ver secção Piloto).

## Follow-ups abertos

- Cobertura parcial (perguntas multi-matéria) e fluxo de clarificação (perguntas ambíguas).
- Dicionário v1: "para o adicional" lido como IMI; "categoria B." seguido de nova frase.
- Situações fora do vocabulário de exclusões: divórcio/desfazer opção AIMI, subarrendamento
  (confirmadas como residuais na validação funcional do piloto).
- O backoffice regista como revisor/validador o email da sessão (`piloto.admin@taxia.local`),
  não o nome do especialista; avaliar se a validação deve pedir o nome do revisor.
- Decisão em aberto: levantar ou não o guard rail "o backoffice não publica" para expor
  "publicar e substituir" na UI.

## Limitações operacionais conhecidas

- Push por SSH a partir do ambiente do agente exige a chave do António (passphrase, sem agente);
  `gh` não está instalado. Leitura do remoto por HTTPS funciona (repositório público).

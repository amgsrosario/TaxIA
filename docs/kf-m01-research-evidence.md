# KF-M01 — Research → Evidence → Critical Memory → Structured Knowledge

Este documento cobre a primeira missão técnica do piloto KnowledgeFlow, executada segundo a
AR2-DEC-KF-2026-10-22 (B1–B3 e Aditamento Operacional). A decisão arquitectural está no
[ADR-010](adr/ADR-010-research-evidence-critical-memory-structured-knowledge.md), ainda
*Proposed*.

## Alinhamento estratégico (AR2-GOV-01)

| KF | CA | Capacidade | Evidência produzida |
|---|---|---|---|
| KF-02 | CA-01, CA-02, CA-07 | Investigação a partir de pergunta inédita | Passos de descoberta, consulta e confirmação; consulta real ao Portal das Finanças (demonstração) |
| KF-01 | CA-03, CA-07, CA-10 | Memória crítica | Entradas ERRONEOUS, REJECTED, SUPERSEDED e CONTRADICTORY, com histórico e recorrência; ausentes do prompt |
| KF-03 | CA-02, CA-04 | Conhecimento estruturado | Proposições com âncora literal, condições e excepções, relações de conflito |
| KF-07, KF-08 | CA-08, CA-10 | Regressão e rastreabilidade | Suite completa verde; trace JSON por investigação |

**Não avançam nesta missão:**
- KF-04 / CA-05: B4 bloqueada;
- KF-05 / CA-06: cenários;
- KF-09 / CA-09: prova D1, independente do executor;
- KF-06: generalismo para além do perímetro inicial.

## Percurso

1. **Corpus.** Mesmo limiar e gate de âmbito do `/ask`. Se há Q&A publicada relevante, não se
   investiga (`NOT_STARTED`).
2. **Descoberta.** Entram as referências explícitas da pergunta (`artigo 78.º do CIRS`) e até
   3 hipóteses do modelo. As hipóteses do modelo não são evidência. Uma hipótese já desmentida na
   memória crítica é saltada e a recorrência fica registada.
3. **Consulta.** Página oficial do artigo, obtida read-only, só por HTTPS e com allowlist. São no
   máximo 4 consultas, com 1,5 s entre pedidos. O corpo é lido com limite de 2 MB; acima disso a
   consulta é recusada (`FAILED`, auditada) e não cria evidência.
4. **Confirmação.** O bloco oficial tem de conter o cabeçalho do artigo pedido (78 ≠ 78.º-A ≠ 79).
5. **Evidência.** Guarda-se o texto oficial, o SHA-256, a vigência e a classificação. Fica sempre
   `NOT_PROMOTED`.
6. **Conhecimento estruturado.** O modelo propõe proposições, condições, excepções e conflitos. Só
   ficam `ANCHORED` as que tenham âncora literal e números presentes na evidência.
7. **Resposta.** Ver a tabela de decisão no ADR-010 §7. A resposta passa sempre pelo
   `AnswerGroundingValidator`.

## Configuração

```yaml
knowledgeflow:
  research:
    enabled: ${KNOWLEDGEFLOW_RESEARCH_ENABLED:false}   # desligado por omissão
    max-consultations: 4
    max-model-hypotheses: 3
    delay-ms: 1500
```

A allowlist por omissão é `info.portaldasfinancas.gov.pt`.

## Endpoints (ADMIN; só com `enabled=true`)

```
POST  /api/v1/admin/research/investigations            {"question": "..."} → trace completo
GET   /api/v1/admin/research/investigations/{id}       trace: passos, evidência, proposições, relações, memória crítica
GET   /api/v1/admin/research/critical-memory?referenceKey=CIRS:78
GET   /api/v1/admin/research/critical-memory/{id}/history
PATCH /api/v1/admin/research/critical-memory/{id}/status   {"status": "UNDER_REEVALUATION", "reason": "..."}
```

## Verificação

```
mvn test                                                     # unitários (H2)
mvn verify -Ppgtest                                          # inclui FlywayV20ResearchIT e ResearchPathPostgresIT
mvn verify -Ppgtest -Dit.test=ResearchLiveOfficialSourceIT -Dkfm01.live=true   # demonstração com a fonte real
```

A demonstração com a fonte real:
- consulta mesmo o Portal das Finanças, com 2 pedidos read-only;
- corre numa base PostgreSQL descartável (Testcontainers);
- grava a rastreabilidade em `target/kf-m01-live-trace.json`;
- substitui a descoberta, a estruturação e a redacção pelo modelo por respostas fixas, porque não
  usa chave de API. Com o modelo real, o mesmo percurso corre pelo endpoint num ambiente
  descartável.

## Revisão independente (Codex)

> **Fonte:** síntese executiva da Coordenação Estratégica AR2 (ordem KF-M01/ENC-02, 2026-10-22),
> fiel aos relatórios do Codex. Não é uma transcrição integral nem um relatório assinado pelo
> revisor. **Relatórios originais do Codex:** pendentes de arquivo pelo próprio revisor em
> AR2-Governance `reviews/KF-M01/`.

| Ciclo | Resultado |
|---|---|
| 1.ª revisão | `CHANGES_REQUIRED`: MAJOR, limite do corpo HTTP aplicado depois de materializar a resposta (CR-01); MINOR, HTTP sem TLS aceite (CR-02); MINOR, espaços finais nos fixtures HTML (CR-03) |
| Correcções | `53d919f` (CR-01, CR-02), `bf53cfd` (CR-03) |
| 2.ª revisão | `APPROVED_WITH_RESERVATIONS`: os três defeitos estão resolvidos. I1–I7 PASS. I8 PARTIAL, por falta de reprodução conclusiva de `ResearchPathPostgresIT`. I9 PARTIAL |
| Verificação final (2026-10-22, 16h22, HEAD `bf53cfd`, worktree limpo) | `ResearchPathPostgresIT` **PASS**: PostgreSQL descartável, Flyway V1–V20, 1 teste com 0 falhas. Confirma a persistência e a integridade das relações, a evidência não promovida, a memória crítica e o evento `RESEARCH_INVESTIGATION_COMPLETED`, sem escrita em Q&A nem em embeddings. `ResearchLiveOfficialSourceIT` **PASS**: duas consultas HTTPS públicas em base descartável; CIRS 78.º `FETCHED/200`, evidência `ACCEPTED/NOT_PROMOTED`, proposição ancorada e rejeição da proposição sem suporte; CIRS 999.º `NOT_FOUND/404` |

**Matriz final:**

| Invariante | Resultado |
|---|---|
| I1–I7 | PASS |
| I8 | PASS |
| I9 | PARTIAL |
| CA-09 / D1 | NOT_VERIFIED |

**Conclusão final: `APPROVED_WITH_RESERVATIONS`.** Nenhum finding técnico permanece aberto nas
correcções CR-01 a CR-03.

**Reserva I9:**
- **Modelo real:** não houve execução de ponta a ponta com o modelo real. A demonstração com a
  fonte real usou respostas fixas para a descoberta, a extracção e a redacção.
- **Correlação da auditoria:** o trace permite reconstruir o percurso, mas não incorpora
  necessariamente a correlação explícita dos eventos de auditoria.

A aprovação técnica **não** autoriza utilização operacional. Continuam a exigir decisão própria:
- a aceitação do ADR-010, que se mantém *Proposed*;
- o merge;
- a activação no piloto;
- a integração no `/ask`;
- o desbloqueio de B4.

## Activação no piloto

Não é automática. Exige uma acção humana separada:
- backup da `knowledgeflow_pilot`;
- aplicação e verificação da V20;
- plano de rollback;
- `KNOWLEDGEFLOW_RESEARCH_ENABLED=true` (Aditamento §3).

## Limitações conhecidas

- **Perímetro:** apenas CIRS, CIVA, CIRC e LGT consolidados pela AT. Ficam de fora:
  - o EBF;
  - o Diário da República;
  - a jurisprudência;
  - as FAQ AT como evidência de investigação.
- **Vigência:** `CONSOLIDATED_AT_RETRIEVAL` reflecte a consolidação publicada pela AT, sem
  verificação humana.
- **Conflitos:** só se detectam os que o modelo indica entre proposições ancoradas. Não há ainda
  confronto com a memória crítica de outras investigações nem com o corpus publicado.
- **Factos do caso:** as condições são apresentadas como pressupostos; não se avalia
  automaticamente se os factos da pergunta as cumprem.
- **Integração:** o `/ask` não abre investigação. Integrar a investigação na resposta ao utilizador
  é uma decisão de produto ainda não tomada.

# TaxIA — Bloco D — Contrato técnico da resposta documentada

> **Natureza deste documento.** É um **plano de arquitectura** (Bloco D, tarefa D1).
> **Não** implementa código, DTOs, enums, migrations, API nem testes. Traduz as
> decisões conceptuais **fechadas** do Bloco C (C1–C9) num **contrato técnico
> preliminar** e numa **sequência segura** de implementação futura.
>
> Enquadramento conceptual: [taxia-core-principles.md](taxia-core-principles.md) ·
> [taxia-product-vision.md](taxia-product-vision.md) ·
> [taxia-response-model.md](taxia-response-model.md) ·
> [taxia-documented-response-dto.md](taxia-documented-response-dto.md) ·
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) ·
> [taxia-boundary-answer.md](taxia-boundary-answer.md) ·
> [grounding-policy.md](grounding-policy.md) · [roadmap.md](roadmap.md).

> **Actualização (D3).** O **contrato DTO/enums final** — nomes definitivos,
> campos, enums, visibilidade externa/interna e estratégia de evolução por adição —
> está fixado em
> [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md). Este plano
> mantém-se como a **arquitectura e sequência** (D1); o contrato detalhado vive em D3 e
> **não** é aqui duplicado.

Cada afirmação deste plano é rotulada como uma de três categorias:

- **[FECHADO]** — decisão conceptual já tomada no Bloco C (C1–C9); não se reabre.
- **[PROPOSTA]** — proposta técnica/documental para implementação futura; **não** é
  implementação obrigatória imediata.
- **[LACUNA]** — estado actual do código a confirmar/desenhar/implementar mais tarde.

---

## 1. Estado de partida

- **[FECHADO]** O **Bloco C está conceptualmente fechado** (C1–C9), encerrado no commit
  `9f4d94e — docs: define TaxIA source quality policy`.
- **[FECHADO]** O produto já tem política conceptual de **risco** (C2/C4),
  **visibilidade** (C1/C7), **actualidade** (C8), **encaminhamento para parecer** (C3/C6)
  e **qualidade/diversidade das fontes** (C9).
- **[PROPOSTA]** O **Bloco D** transforma essa política num **contrato técnico** e numa
  **arquitectura** de serviços — sem alterar a substância das decisões C1–C9.
- Esta tarefa (**D1**) **não implementa código**: apenas desenha o contrato e o plano.

## 2. Princípio técnico central

> **Regra central (Bloco D).** A TaxIA deve **construir primeiro uma resposta completa
> e rica em diagnóstico interno** e **depois projectá-la** conforme o `visibilityLevel`.

- **[PROPOSTA]** Primeiro decide-se a **resposta documentada completa** (todos os
  sinais: `answerType`, suporte, risco agregado, actualidade, robustez documental,
  encaminhamento, diagnósticos internos).
- **[PROPOSTA]** Só depois se aplica a **projecção** para `EXTERNAL` / `DEMO` /
  `INTERNAL` / `CURATION_ONLY`.
- **[FECHADO]** A **qualidade conceptual** da resposta **não muda** em `EXTERNAL`/`DEMO`
  (C1/C7); apenas muda o **detalhe técnico exposto** (bastidores). Não existe
  "TaxIA-lite".

## 3. Conceitos conceptuais fechados

Todos os conceitos abaixo são **[FECHADO]** quanto ao significado; a sua
**materialização** (enum/coluna/campo) é **[PROPOSTA]**.

| Conceito | Função conceptual | Origem (Bloco C) | Externo (linguagem profissional)? | Detalhe técnico interno? |
|---|---|---|---|---|
| `answerType` | Forma de resposta ao utilizador (`CONSULTA_DOCUMENTADA`, `..._COM_LIMITACOES`, `RESPOSTA_LIMITE`, `PEDIDO_DE_PARECER`) | C2/C5/C6 | **Sim** — determina a forma visível | Sim — razões da escolha |
| `supportStatus` | Suporte técnico do *grounding* (`SUPPORTED`…`REJECTED_UNSUPPORTED`) | Existente (pré-C) | **Não** cru — traduzido em confiança/limitações | Sim — valor bruto |
| `aggregatedRiskLevel` | Risco máximo dos **fundamentos relevantes usados** | C4 | **Sim** — como prudência legível | Sim — como nível e cálculo |
| `parecerRequirement` | Grau de encaminhamento para Pedido de parecer (`NONE`/`SUGGESTED`/`REQUIRED`) | C3/C6 | **Sim** — recomendação de parecer | Sim — motivo |
| `visibilityLevel` | Nível de projecção da resposta (`EXTERNAL`/`DEMO`/`INTERNAL`/`CURATION_ONLY`) | C1/C7 | Determina a projecção (não é "conteúdo") | Sim |
| `freshnessStatus` | Actualidade/origem temporal (`CURRENT`/`STABLE_BUT_OLD`/`UNCERTAIN`/`OUTDATED`) | C8 | **Sim** — como nota de actualidade | Sim — estado por fonte |
| `sourceQuality` | Força/autoridade da base documental (`OFFICIAL`/`LEGAL`/`INTERNAL`/`UNVERIFIED`/`MIXED`) | C9 | Traduzido em confiança/autoridade | Sim — valor por fonte |
| `sourceRole` | Papel da fonte (principal / complementar / derivada-replicada) | C9 | Parcial — "fonte principal/complementar" | Sim — completo |
| `sourceDiversity` | Diversidade material real (não volume) | C9 | Traduzido em robustez | Sim — métrica interna |
| `sourceCore` | Núcleo material comum (agrupa eco documental) | C9 | **Não** — bastidor | Sim — só `CURATION_ONLY` |

> **Nota.** `supportStatus` corresponde ao enum Java **já existente**
> `AnswerSupportStatus` (`SUPPORTED`, `PARTIALLY_SUPPORTED`, `INSUFFICIENT_CONTEXT`,
> `REQUIRES_HUMAN_REVIEW`, `REJECTED_UNSUPPORTED`) — **não** se renomeia.

## 4. Contrato técnico proposto

**[PROPOSTA]** — estrutura conceptual, **sem** implementação Java. Os nomes são
proposta técnica/documental, **não** implementação obrigatória imediata.

### `DocumentedTaxiaAnswer` (resposta completa interna)

| Campo | Descrição |
|---|---|
| `answerId` | Identificador da resposta. |
| `question` | Pergunta original. |
| `normalizedQuestion` | Pergunta normalizada para *matching*/registo. |
| `shortAnswer` | Resposta curta profissional. |
| `technicalAnswer` | Explicação técnica desenvolvida. |
| `answerType` | Forma de resposta (C2/C5/C6). |
| `supportStatus` | Suporte técnico (`AnswerSupportStatus`). |
| `aggregatedRiskLevel` | Risco agregado dos fundamentos usados (C4). |
| `parecerRequirement` | Grau de encaminhamento para parecer (C3/C6). |
| `visibilityLevel` | Nível-alvo de projecção (C1/C7). |
| `freshnessStatus` | Actualidade/origem temporal (C8). |
| `confidenceSummary` | Síntese de confiança legível. |
| `limitations` | Limitações da resposta. |
| `assumptions` | Pressupostos assumidos. |
| `missingFacts` | Factos/documentos em falta. |
| `sourceSummary` | Súmula da robustez documental (C9). |
| `sources` | Lista de `SourceEvidence`. |
| `warnings` | Avisos (actualidade, risco, divergência). |
| `nextSteps` | Próximos passos (incl. recomendação de parecer). |
| `internalDiagnostics` | Diagnóstico interno (bastidores) — nunca em `EXTERNAL`/`DEMO`. |

### `SourceEvidence` (fonte usada/avaliada)

| Campo | Descrição |
|---|---|
| `sourceId` | Identificador da fonte. |
| `title` | Título. |
| `sourceType` | Tipo (legislação, doutrina oficial, FAQ, jurisprudência, interno, externo). |
| `authorityLevel` | Nível de autoridade na hierarquia orientadora (C9, secção 3-I). |
| `sourceRole` | Papel: principal / complementar / derivada-replicada (C9). |
| `sourceQuality` | Força/autoridade (C9). |
| `sourceCore` | Núcleo material comum (agrupa eco documental). |
| `freshnessStatus` | Actualidade da fonte (C8). |
| `legalReference` | Referência legal concreta, quando aplicável. |
| `url` | Ligação rastreável, quando aplicável. |
| `usedInAnswer` | Se entrou nos **fundamentos usados** (C4). |
| `supportsConclusion` | Se sustenta a conclusão. |
| `supportsLimitation` | Se fundamenta uma limitação. |
| `supportsWarning` | Se fundamenta um aviso. |
| `isDerivativeOrReplicated` | Se é derivada/replicada (não confirmação independente — C9). |
| `relatedSources` | Fontes com o mesmo `sourceCore`. |
| `notesInternal` | Notas internas de curadoria (bastidor). |

### `AnswerProjection` (vista adaptada a um nível)

| Campo | Descrição |
|---|---|
| `targetVisibilityLevel` | Nível-alvo (`EXTERNAL`/`DEMO`/`INTERNAL`/`CURATION_ONLY`). |
| `visibleAnswer` | Resposta visível nesse nível. |
| `visibleSources` | Fontes visíveis (filtradas/traduzidas). |
| `visibleWarnings` | Avisos visíveis. |
| `visibleLimitations` | Limitações visíveis. |
| `visibleParecerRequirement` | Encaminhamento para parecer visível. |
| `hiddenDiagnostics` | Diagnóstico **omitido** nesse nível. |
| `projectionRulesApplied` | Regras de projecção aplicadas (auditável). |

> **Nota obrigatória.** Estes nomes são **proposta técnica/documental**, **não**
> implementação obrigatória imediata. Os nomes/tipos definitivos ficam para D3.

## 5. Separação entre decisão e projecção

- **[PROPOSTA]** `AnswerDecisionService` **decide** `answerType`, `parecerRequirement`,
  `aggregatedRiskLevel`, `freshnessStatus` e a **robustez documental** (C9), produzindo
  o `DocumentedTaxiaAnswer` completo.
- **[PROPOSTA]** `AnswerProjectionService` **adapta** essa resposta ao
  `visibilityLevel`, produzindo uma `AnswerProjection`.
- **[FECHADO]** A **projecção não recalcula a decisão** — só filtra/traduz o detalhe
  (C7). Nunca pode "melhorar" ou "piorar" a conclusão consoante o nível.
- **[FECHADO]** `EXTERNAL`/`DEMO` recebem **produto profissional limpo**;
  `INTERNAL`/`CURATION_ONLY` recebem **detalhe técnico adicional** (C7).

## 6. Serviços conceptuais necessários

**[PROPOSTA]** — serviços de desenho, **sem** implementação:

| Serviço | Responsabilidade | Decisões aplicadas |
|---|---|---|
| `GroundingEvidenceCollector` | Recolhe evidências do RAG/BD/fontes. | — |
| `SourceAssessmentService` | Avalia qualidade, papel, diversidade, núcleo material e actualidade das fontes. | C8, C9 |
| `RiskAggregationService` | Agrega risco pelo máximo dos fundamentos relevantes usados. | C4 |
| `AnswerDecisionService` | Decide forma, encaminhamento e limitações da resposta. | C2, C5, C6, C8, C9 |
| `AnswerProjectionService` | Adapta a resposta ao nível de visibilidade. | C7 |
| `BoundaryAnswerBuilder` | Constrói a **Resposta-limite** quando aplicável. | C5 |
| `ParecerRoutingService` | Determina sinalização/encaminhamento para Pedido de parecer. | C3, C6 |
| `InternalDiagnosticsBuilder` | Reúne scores, chunks, ranking, razões técnicas e sinais internos. | C7 (bastidores) |

## 7. Integração com grounding/RAG

- **[FECHADO]** O RAG **recupera candidatos**, mas **nem todos** os candidatos
  recuperados são **fundamentos usados**.
- **[FECHADO]** Apenas os **fundamentos relevantes efectivamente usados** contam para o
  `aggregatedRiskLevel` (C4) — o ruído recuperado não eleva o risco.
- **[FECHADO]** Fontes **derivadas/replicadas** devem ser **agrupadas por `sourceCore`**
  e **não** contam como confirmações independentes (C9).
- **[FECHADO]** A **contagem bruta de fontes não basta**: pesa a **força,
  aplicabilidade e diversidade material** (C9).
- **[FECHADO]** O `freshnessStatus` deve ser avaliado **por fonte/conhecimento** (C8).
- **[FECHADO]** Uma fonte `OUTDATED` pode ser usada para **histórico/contraste/alerta**,
  **nunca** para fundamentar uma conclusão actual (C8).
- **[LACUNA]** A ponte entre o *grounding* actual (ver secção 10) e estes campos
  (papel, núcleo, diversidade, actualidade por fonte) **ainda não existe** e será
  desenhada em D5.

## 8. Matriz conceptual de decisão

**[PROPOSTA]** — orientação técnica, **não** algoritmo rígido (coerente com C2/C5/C6/C8/C9):

| Situação de suporte / fontes / risco | Tendência de `answerType` |
|---|---|
| Suporte forte + fontes actuais + risco baixo/médio | `CONSULTA_DOCUMENTADA`. |
| Suporte forte + fonte antiga estável | `CONSULTA_DOCUMENTADA` **ou** `CONSULTA_DOCUMENTADA_COM_LIMITACOES` com nota de actualidade. |
| Suporte parcial + fonte incerta | `CONSULTA_DOCUMENTADA_COM_LIMITACOES` **ou** `RESPOSTA_LIMITE`. |
| Fontes desactualizadas | Enquadramento histórico, `RESPOSTA_LIMITE` ou Pedido de parecer. |
| Fontes divergentes | `CONSULTA_DOCUMENTADA_COM_LIMITACOES`, `RESPOSTA_LIMITE` ou `parecerRequirement` `SUGGESTED`/`REQUIRED`. |
| Risco HIGH + aplicação concreta | **Nunca** conclusão automática sem limitação; possível `PEDIDO_DE_PARECER`. |

> **Nota.** Esta matriz é **orientação técnica**, não algoritmo. Os limiares exactos e
> o scoring/ranking ficam para fase técnica posterior (não D1).

## 9. Projecção por `visibilityLevel`

**[FECHADO]** (C7) quanto ao princípio; **[PROPOSTA]** quanto ao detalhe de campos.

### `EXTERNAL`
- Resposta profissional limpa;
- fontes relevantes; fundamentos; limitações; pressupostos; factos em falta;
- `parecerRequirement`; risco/prudência **legível**;
- `freshnessStatus`/`sourceQuality` **traduzidos** em linguagem profissional;
- **sem** ruído técnico interno (scores, ranking, chunks, IDs técnicos).

### `DEMO`
- **Igual qualidade** conceptual de `EXTERNAL`;
- eventuais **limites comerciais/operacionais** (extensão, número de consultas);
- **nunca** TaxIA-lite.

### `INTERNAL`
- `EXTERNAL` **+ diagnóstico moderado**;
- avaliação de suporte; sinais de risco;
- papel das fontes (principal/complementar/derivada);
- razões de prudência.

### `CURATION_ONLY`
- **Bastidores completos**: chunks; scores; ranking; `sourceCore`;
- notas de curadoria; motivos de exclusão; auditoria; elegibilidade RAG.

## 10. Lacunas técnicas a confirmar no código

**[LACUNA]** — inventário preliminar (a aprofundar em **D2**), sem alterar código.
O estado abaixo baseia-se num rastreio superficial do repositório; pontos não
verificados a fundo ficam marcados **a confirmar**.

> **Actualização (D2 concluída).** O inventário técnico aprofundado, já confirmado
> por leitura directa dos ficheiros, está em
> [taxia-documented-answer-code-inventory.md](taxia-documented-answer-code-inventory.md).
> A tabela preliminar abaixo mantém-se como registo do rastreio inicial de D1; para o
> estado detalhado (existente/parcial/inexistente, colisões e riscos) usar o documento
> de D2, que a substitui em detalhe.

| Elemento | Estado actual observado | Classificação |
|---|---|---|
| DTO de resposta AI (`GroundedAIResponse`) | Existe: `answer`, `supportStatus`, `supportReason`, `sources`, `missingInformation`, `limitations`, `requiresHumanValidation`, tokens, etc. **Não** tem `answerType`, `aggregatedRiskLevel`, `parecerRequirement`, `visibilityLevel`, `freshnessStatus`, `sourceQuality`. | **Existe parcialmente** — falta desenhar/estender. |
| Enum de suporte (`AnswerSupportStatus`) | Existe com 5 valores. | **Já existe** — reutilizar (não renomear). |
| Enums `AnswerType`/`ParecerRequirement`/`VisibilityLevel`/`FreshnessStatus`/`SourceQuality` (Java) | **Não** existem como enums Java. | **Falta desenhar/implementar** (D3/D4). |
| Fonte na resposta (`AnswerSource`) | Existe: `title`, `reference`, `relevanceScore`. **Não** tem papel/qualidade/núcleo/actualidade. | **Existe parcialmente** — falta desenhar `SourceEvidence`. |
| `GroundingService` / `GroundingConfiguration` / `GroundingProperties` | Existem. | **Já existe** — ponto de integração (D5/D6). |
| `ContextSufficiencyEvaluator` / `ContextSufficiencyAssessment` | Existem. | **Já existe** — alimenta decisão de suporte. |
| `AnswerGroundingValidator` / `SafeResponseFactory` | Existem (validação de afirmações, respostas seguras/refusal). | **Já existe** — reaproveitar em `BoundaryAnswerBuilder`. |
| `RagSearchService` / `KnowledgeQaSimilarityService` | Existem. | **Já existe** — recolha de candidatos (D5). |
| Entidade Knowledge QA + fontes associadas (`KnowledgeSourceReference`) | Existem. | **Já existe** — origem de `SourceEvidence`; **a confirmar** cobertura de `sourceQuality`/`sourceRole`. |
| Serviços de decisão/projecção (`AnswerDecisionService`, `AnswerProjectionService`, `RiskAggregationService`, `ParecerRoutingService`, `InternalDiagnosticsBuilder`) | **Não** existem. | **Falta desenhar/implementar** (D4–D7). |
| Persistência de `DocumentedTaxiaAnswer` e campos novos | **Não** existe. | **Precisa de migração futura** (a confirmar em D3/D4). |
| Frontend de consulta/admin | Existe backoffice; **a confirmar** exposição dos novos sinais. | **Falta implementar** (D9). |
| Testes existentes de grounding | Existem (várias suites). | **Precisa de testes** adicionais para os novos cenários (D10). |

## 11. Ordem segura de implementação futura

**[PROPOSTA]** — sequência, **sem** executar:

| Passo | Objectivo |
|---|---|
| **D2** | ✅ Inventário técnico do estado actual do código — ver [taxia-documented-answer-code-inventory.md](taxia-documented-answer-code-inventory.md). |
| **D3** | ✅ Contrato DTO/enums documentado final (nomes/tipos definitivos) — ver [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md). |
| **D4** | ✅ Implementação backend mínima e aditiva do `DocumentedTaxiaAnswer` — pacote `com.knowledgeflow.ai.documented` (8 enums, 3 DTOs, `DocumentedTaxiaAnswerMapper` com defaults transitórios); `AdminAIController.AskResponse` ganhou `documentedAnswer` por adição; testes verdes. Ver §12.A do [contrato](taxia-documented-answer-contract.md). |
| **D5** | Avaliação de fontes / `SourceEvidence` (papel, qualidade, núcleo, actualidade). |
| **D6** | Decisão de `answerType`/`parecerRequirement`. |
| **D7** | `AnswerProjectionService` por `visibilityLevel`. |
| **D8** | Integração no endpoint de consulta. |
| **D9** | Frontend da resposta profissional. |
| **D10** | Testes de cenários críticos. |
| **D11** | Auditoria / diagnóstico interno. |

## 12. Fora de âmbito (D1)

Explicitamente **fora** desta tarefa:

- implementação Java;
- migrations;
- alterações de frontend;
- publicação de casos;
- embeddings;
- ingestão massiva;
- provider externo;
- algoritmos definitivos de scoring;
- thresholds numéricos;
- alteração de decisões C1–C9.

## 13. Critério de conclusão do Bloco D1

D1 considera-se concluído quando:

- o **documento** está criado;
- o **Bloco C** está **traduzido** para um **contrato técnico preliminar**;
- as **lacunas** e a **sequência futura** (D2–D11) estão identificadas;
- **nenhuma implementação** foi realizada.

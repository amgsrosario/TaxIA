# TaxIA — Bloco E — Ingestão e curadoria assistida da base de conhecimento

> **Natureza deste documento.** É uma **política e plano** (Bloco E, tarefa E1). Define
> **como** a TaxIA passa a alimentar a base de conhecimento em escala **sem comprometer** a
> qualidade da resposta profissional documentada. **Não implementa** ingestão, pipeline,
> scraping, embeddings nem publicação — é exclusivamente documental. Em caso de dúvida entre
> velocidade de ingestão e estes critérios, **prevalecem os critérios**.
>
> Herda e não reabre: o **Bloco C** (política conceptual, C1–C9) e o **Bloco D**
> (materialização técnica, D1–D11). Ver
> [taxia-core-principles.md](taxia-core-principles.md),
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md),
> [taxia-documented-answer-contract.md](taxia-documented-answer-contract.md) e
> [taxia-documented-answer-technical-plan.md](taxia-documented-answer-technical-plan.md).

## 1. Estado de partida

- O **Bloco C** fechou a **política conceptual** da resposta profissional: `answerType`,
  `supportStatus`, `aggregatedRiskLevel`, `parecerRequirement`, `visibilityLevel`,
  `freshnessStatus`, `sourceQuality`, `sourceRole`, `sourceDiversity`, `sourceCore`,
  Resposta-limite e as regras de prudência, actualidade e qualidade documental. Permanece
  **conceptualmente fechado** (C1–C9 não se reabrem).
- O **Bloco D** materializou o **contrato técnico** da resposta documentada:
  `DocumentedTaxiaAnswer`, `SourceEvidence`, `AnswerProjection`, `SourceAssessmentService`,
  `AnswerDecisionService`, `AnswerProjectionService`, `VisibilityLevelResolver`,
  `InternalDiagnostics`, `DocumentedAnswerPanel` e os testes críticos da resposta documentada.
  Permanece **tecnicamente fechado** (D1–D11 não se reabrem).
- O **Bloco E** define **como alimentar a base de conhecimento** em escala sem degradar a
  qualidade da resposta documentada — ou seja, como **ingerir, curar e publicar** conhecimento
  de forma **governada**.
- **Esta tarefa (E1) é documental** e **não** implementa ingestão, pipeline nem publicação.

## 2. Princípio central do Bloco E

> **A ingestão pode ser automática.**
> **A publicação não tem de ser sempre humana.**
> **Mas tem de ser sempre governada.**

Formulação aprovada: **a publicação deve ser governada por critérios, não necessariamente por
intervenção humana caso a caso.**

Consequências:

- **Ingestão automática é aceitável** — recolher conhecimento em volume é desejável.
- **Pré-curadoria automática é desejável** — o sistema deve preparar o máximo possível.
- **Publicação livre ou indiscriminada é proibida** — nenhum caso entra na base pesquisável só
  por ter sido importado.
- **A publicação deve obedecer a critérios auditáveis** — objectivos, verificáveis e reversíveis.
- **A intervenção humana é obrigatória quando os critérios não forem suficientes** — havendo
  risco, incerteza, conflito, baixa qualidade documental, actualidade duvidosa ou impacto
  material, exige-se validação assistida ou manual.

## 3. Decisão E1 — Importar não é publicar

- **Importado** = o conhecimento **existe para curadoria**; está na base como matéria-prima,
  ainda **não** apto a sustentar resposta.
- **Publicado** = o conhecimento **pode sustentar resposta documentada/RAG**; passou pelos
  critérios de elegibilidade.
- **A importação não gera resposta automática** nem indexação para pesquisa.
- **A publicação é um acto governado** (§7), nunca um efeito colateral da importação.

> **Regra curta.** A ingestão cria conhecimento **curável**. A publicação cria conhecimento
> **pesquisável e utilizável**.

## 4. Decisão E2 — Primeira fonte de escala: FAQs oficiais da AT

- A **primeira matéria-prima de escala** deve ser a **FAQ oficial da Autoridade Tributária**,
  por ser **oficial**, **prática** e **estruturada em pergunta/resposta**.
- Ser fonte oficial **não** significa publicação automática: reduz o risco de origem, mas **não**
  dispensa a curadoria nem os critérios de publicação (§7).
- Uma FAQ AT importada é **matéria-prima**, não um caso TaxIA final.

> **Regra curta.** FAQ AT **importada** não é FAQ TaxIA **publicada**.

## 5. Decisão E3 — Caso publicado exige resposta técnica própria

A TaxIA **não** é um espelho da FAQ oficial. Para **publicação**, um caso deve ter, no mínimo:

- **pergunta original** (como veio da fonte);
- **resposta original preservada** (a FAQ tal como publicada pela fonte);
- **pergunta normalizada** (`normalizedQuestion`);
- **`shortAnswer`** (resposta curta profissional);
- **`technicalAnswer`** (resposta técnica fundamentada, própria da TaxIA);
- **tema/subtema**;
- **jurisdição** (ex.: Portugal / UE);
- **`riskLevel`**;
- **fontes** (`SourceEvidence`);
- **avaliação de suporte documental** (`supportStatus` / sinais de qualidade e diversidade);
- **`freshnessStatus`** ou sinal equivalente de actualidade;
- **notas de curadoria** quando necessário.

Explicação:

- a **FAQ oficial é fonte, não a resposta TaxIA final**;
- publicar apenas a cópia da FAQ **não** cumpre o modelo de resposta documentada — falta a
  `technicalAnswer` própria, o enquadramento e a fundamentação diversa.

## 6. Decisão E4 — Ligação ao fundamento legal

- Sempre que possível, a **FAQ oficial deve ser ligada ao fundamento legal correspondente**
  (artigo/diploma).
- **FAQ oficial + artigo legal** = **suporte mais forte e mais diverso** (reforça a diversidade
  material — C9).
- Uma **FAQ isolada** pode ser útil, mas tem **menor diversidade documental** e, por si só,
  raramente sustenta uma consulta documentada limpa.
- Se o **fundamento legal não for claro**, o caso pode ficar **importado/pré-curado**, mas
  **não necessariamente publicável** — segue para curadoria assistida ou manual.

## 7. Decisão E5 — Publicação governada por critérios

Três vias de publicação, escolhidas por **critérios objectivos** (sem scoring numérico):

### 7.1 Publicação automática controlada

Permitida **apenas** quando **todos** os critérios se verificam:

- **fonte oficial**;
- **baixo risco**;
- **suporte documental forte**;
- **actualidade aceitável**;
- **sem conflito detectado**;
- **sem duplicado material enganador**;
- **`technicalAnswer` produzida por regra/template seguro** ou validação equivalente;
- **critérios auditáveis**;
- **possibilidade de reversão** (despublicação).

### 7.2 Publicação assistida

Aplicável quando há sinais que pedem confirmação, por exemplo:

- **risco médio**;
- **referência legal provável mas não totalmente segura**;
- **fonte oficial mas questão ambígua**;
- **alteração material** face a um caso existente;
- **possível duplicado**;
- **actualidade incerta**;
- **`technicalAnswer` que exige interpretação controlada**.

### 7.3 Publicação manual obrigatória

Aplicável quando há risco ou sensibilidade elevados, por exemplo:

- **risco alto**;
- **divergência entre fontes**;
- **fonte não oficial**;
- **actualidade duvidosa**;
- **aplicação concreta sensível**;
- **impacto fiscal relevante**;
- **alteração legislativa complexa**;
- **jurisprudência contraditória**;
- **necessidade de apreciação profissional**.

> **Regra curta.** Publicação automática **livre** é proibida. Publicação automática
> **controlada** é possível. Publicação **arriscada** exige humano.

## 8. Decisão E6 — Curadoria assistida por sistema

O sistema **prepara**; os **critérios governam**; o **humano intervém quando necessário**. O
sistema pode **sugerir** (nunca decidir sozinho a publicação de casos não-limpos):

- **tema/subtema**;
- **normalização da pergunta**;
- **`shortAnswer`**;
- **`technicalAnswer`**;
- **`riskLevel`**;
- **referências legais**;
- **`sourceQuality`**;
- **`sourceRole`**;
- **`sourceCore`**;
- **`sourceDiversity`**;
- **`freshnessStatus`**;
- **duplicados**;
- **ecos documentais**;
- **conflitos**;
- **elegibilidade para publicação**.

## 9. Decisão E7 — Separação de estados

Quatro eixos **distintos**, que **não** se confundem:

- **estado de ingestão** — o caso entrou (ou não) na base;
- **estado de curadoria** — o grau de preparação/validação do caso;
- **estado de publicação** — se o caso pode ou não sustentar resposta documentada/RAG;
- **`freshnessStatus`** — a **actualidade textual** da fonte (eixo do Bloco C/D).

> **Regra obrigatória.** `KnowledgeCurationStatus.OUTDATED` **não é** `FreshnessStatus.OUTDATED`.
> O primeiro é um **estado de curadoria** (o caso já não deve sustentar respostas e precisa de
> rework); o segundo é a **actualidade da fonte** avaliada por marcadores textuais. Confundi-los
> reabriria uma regra fechada em C/D.

Estados conceptuais **sugeridos** (nomes técnicos reais podem diferir — o que importa é
**preservar a separação**):

- **INGESTED / IMPORTED** — recebido, ainda sem análise;
- **PRE_CURATED** — pré-curadoria automática aplicada;
- **PENDING_REVIEW** — pronto para revisão humana;
- **VALIDATED** — aprovado (apto a publicação segundo critérios);
- **PUBLISHED** — publicado (pesquisável/indexado);
- **RETIRED / ARCHIVED** — despublicado/arquivado (histórico/auditoria).

> **Nota de alinhamento.** O enum técnico já existente `KnowledgeCurationStatus`
> (`IMPORTED`, `PENDING_REVIEW`, `VALIDATED`, `NEEDS_UPDATE`, `OUTDATED`, `REJECTED`,
> `ARCHIVED`) é um **eixo de curadoria** e **não** deve ser sobrecarregado com o **eixo de
> publicação**. A materialização técnica destes estados fica para tarefas E posteriores; aqui
> fixa-se apenas a **separação conceptual**.

## 10. Decisão E8 — Lotes pequenos auditáveis

- Começar por **lotes pequenos**, antes de qualquer ingestão massiva.
- **Lote inicial sugerido:** **20 a 50 FAQs AT**.
- O objectivo é **validar o processo** (ingestão → curadoria → publicação → resposta
  documentada), **não** maximizar volume.
- Só **depois** de o fluxo estar validado se **escala**.

> **Regra curta.** Primeiro a **qualidade do fluxo**. Depois a **escala**.

## 11. Decisão E9 — Duplicados, ecos e núcleo material comum

Aplicação directa de **C9** (diversidade material > volume aparente):

- a **quantidade** de fontes/casos pode **enganar**;
- **duplicados** e **ecos** **não** aumentam a robustez documental;
- o **`sourceCore`** deve ajudar a **detectar o núcleo material comum**;
- casos **derivados ou replicados** devem ser **assinalados** (não somados como diversidade);
- a **publicação deve evitar multiplicar respostas equivalentes**.

**Heurísticas iniciais possíveis** (apenas listadas — **sem implementar**, sem algoritmo
definitivo de deduplicação):

- **mesma URL**;
- **mesma FAQ**;
- **mesma referência legal**;
- **pergunta normalizada igual ou muito próxima**;
- **resposta original igual**;
- **título igual**;
- **`sourceCore` igual**.

## 12. Decisão E10 — Publicação aciona indexação/RAG

- a **ingestão não gera embedding** para resposta automática;
- a **publicação valida a elegibilidade** (§7 e §13) **antes** de qualquer indexação;
- a **publicação gera ou actualiza o embedding** do caso;
- a **retirada/despublicação remove ou desactiva o embedding**;
- a **`technicalAnswer` continua a ser requisito** para publicação/RAG (§5).

## 13. Critérios conceptuais de elegibilidade para publicação

Matriz textual (qualitativa — **sem scoring numérico e sem thresholds**):

| Critério | Sinal favorável (→ automática controlada) | Sinal de cautela (→ assistida) | Sinal de bloqueio (→ manual) |
| --- | --- | --- | --- |
| **Fonte** | oficial (AT/legal) | oficial mas ambígua | não oficial / divergente |
| **Risco** | baixo | médio | alto |
| **Actualidade** | aceitável | incerta | duvidosa |
| **Suporte documental** | forte e diverso | parcial / pouco diverso | fraco / isolado |
| **Duplicação** | sem duplicado material | possível duplicado | duplicado/eco enganador |
| **Conflito** | nenhum detectado | possível | divergência entre fontes |
| **`technicalAnswer`** | por regra/template seguro | exige interpretação controlada | exige apreciação profissional |
| **Revisão/critério aplicável** | critérios objectivos satisfeitos e auditáveis | confirmação assistida | validação humana obrigatória |
| **Resultado** | **automática controlada** | **assistida** | **manual** — ou **não publicável** enquanto o bloqueio persistir |

Regra de agregação (conceptual, alinhada com C6 «a regra mais restritiva prevalece»): **basta um
sinal de bloqueio para exigir via manual**; **basta um sinal de cautela para excluir a via
automática controlada**. Na dúvida, **prudência**.

## 14. Relação com `DocumentedTaxiaAnswer`

O conhecimento **publicado** é o que alimenta o modelo de resposta documentada (Bloco D):

- as fontes do caso tornam-se **`SourceEvidence`** (com `sourceQuality`, `sourceRole`,
  `sourceCore`, `freshnessStatus` já avaliados);
- o **`SourceAssessmentService`** avalia essas fontes;
- o **`AnswerDecisionService`** decide `answerType`/`parecerRequirement` e a prudência com base
  nesses sinais — casos bem curados e diversos tendem a sustentar consultas documentadas limpas;
- o **`InternalDiagnostics`** descreve o caminho técnico (incluindo sinais de fontes/actualidade/
  diversidade herdados da curadoria) sem expor valores sensíveis;
- o **`AnswerProjectionService`** projecta por `visibilityLevel` — o produto profissional limpo
  em `EXTERNAL`/`DEMO`, os bastidores em `INTERNAL`/`CURATION_ONLY`.

Ou seja: **melhor curadoria e publicação governada → melhores sinais → melhor resposta
documentada**, sem alterar o contrato do Bloco D.

## 15. Fora de âmbito

Explicitamente **fora** desta tarefa (E1):

- **scraping real** e chamadas a sites;
- **importação massiva**;
- **migrations**;
- **alterações Java**;
- **alterações frontend**;
- **embeddings**;
- **publicação de casos**;
- **algoritmos definitivos de deduplicação**;
- **scoring numérico** e **thresholds**;
- **auditoria persistida**.

## 16. Próxima sequência técnica proposta

Sequência futura (proposta, **sem executar** nesta tarefa):

- **E2** — inventário do pipeline actual de ingestão FAQ AT;
- **E3** — contrato técnico de lote de ingestão;
- **E4** — importação controlada de pequeno lote;
- **E5** — pré-curadoria automática;
- **E6** — ecrã/relatório de revisão do lote;
- **E7** — publicação governada de casos seleccionados;
- **E8** — indexação/RAG do lote publicado;
- **E9** — validação de respostas documentadas com casos reais;
- **E10** — rollback/despublicação.

> **Nota.** Esta numeração E2–E10 designa **tarefas técnicas** do Bloco E e **não** se confunde
> com as **decisões conceptuais** E1–E10 documentadas acima (§3–§12).

## 17. Critério de conclusão de E1

- **documento criado** (`docs/taxia-assisted-ingestion-curation-policy.md`);
- **decisões E1–E10 documentadas** (§3–§12), com critérios de elegibilidade (§13) e relação com
  o Bloco D (§14);
- **Bloco E iniciado** e registado no roadmap;
- **sem implementação** — nenhum código, pipeline, embedding ou publicação.

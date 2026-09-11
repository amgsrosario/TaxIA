# TaxIA — Bloco E — E9C-prep — Matriz do lote piloto, autonomia e rollback mínimo

> **Natureza deste documento.** É **apenas documentação técnica e estratégica** (Bloco E,
> tarefa E9C-prep). Define **o que conta como lote piloto**, o tamanho máximo recomendado, os
> critérios de entrada e de exclusão, a matriz de autonomia, os critérios de intervenção
> humana, os riscos a observar, os indicadores a medir, o rollback mínimo exigido e a decisão
> recomendada entre avançar para E9C real/piloto ou preparar primeiro o rollback (E10-prep).
> **Não implementa** lote real, **não** implementa rollback, **não** altera código Java, testes,
> frontend, endpoints nem migrations, **não** usa BD nem dados reais, **não** executa indexação,
> publicação, despublicação, embeddings ou RAG, e **não** faz *scraping* nem chama *providers*
> externos. Em caso de dúvida entre velocidade e prudência, **prevalece a prudência**.
>
> Herda e **não reabre** o Bloco C (C1–C9), o Bloco D (D1–D11) nem as tarefas E1–E9B. Ver
> [taxia-assisted-ingestion-curation-policy.md](taxia-assisted-ingestion-curation-policy.md),
> [taxia-faq-at-ingestion-batch-contract.md](taxia-faq-at-ingestion-batch-contract.md),
> [taxia-faq-at-controlled-batch-implementation.md](taxia-faq-at-controlled-batch-implementation.md),
> [taxia-publication-indexing-coupling-inventory.md](taxia-publication-indexing-coupling-inventory.md)
> e [roadmap.md](roadmap.md).

Frase-mestra desta etapa: **«Antes de aumentar a voz da TaxIA, definir quantas vozes entram, com
que guardas e como se calam se for preciso.»**

## 1. Objectivo

Fixar, **antes de qualquer execução**, o enquadramento de decisão do salto seguinte do Bloco E:
passar da indexação governada em **lote pequeno de teste** (E9B, `maxItems ∈ [2,3]`, BD isolada)
para um eventual **lote piloto controlado** (E9C). Este documento responde a uma pergunta única
de governação antes de mexer em código: **quantos Q&A devem ganhar voz de cada vez, sob que
guardas entram, quando é que um humano tem de intervir e como se desfaz a indexação se for
preciso.**

O objectivo **não** é implementar o lote nem o rollback, mas sim:

- definir **o que conta como lote piloto** e o **tamanho máximo recomendado**;
- fixar **critérios de entrada** e **critérios de exclusão** objectivos e auditáveis;
- definir uma **matriz de autonomia** (quando a TaxIA publica/indexa sozinha e quando não);
- definir uma **matriz de intervenção humana** (quando o humano entra e como isso se mede);
- listar **riscos a observar** e **métricas de aceitação**;
- fixar o **rollback mínimo** exigido antes de subir de escala;
- produzir uma **decisão recomendada** cautelosa entre E9C real/piloto e E10-prep (rollback
  primeiro).

## 2. Estado de partida

O que já está provado e fechado (não se reabre):

- **E8B.3** — publicação governada **real** em BD isolada (Testcontainers, profile `pgtest`):
  só o item limpo e elegível para autonomia futura chega a `publishedAt`/`publishedBy`, em
  `VALIDATED`, com `COUNT(knowledge_qa_embeddings) == 0` (publicado, mas ainda **sem voz** no RAG).
- **E9A** — indexação efectiva de **exactamente um** Q&A publicado, `VALIDATED`, `LOW` e
  RAG-elegível, pelo contrato real `KnowledgeQaEmbeddingIndexer`, com `EmbeddingService`
  determinístico de teste (768 dim, **sem modelo real nem chamadas externas**). Provou o ciclo
  RAG/pgvector genuíno: 0 → 1 linha; `RagSearchService` real recupera o Q&A; idempotência por
  *upsert*; política single-Q&A difere os restantes.
- **E9B** — **lote governado pequeno**: o mesmo `AtFaqGovernedRagIndexingService` ganhou, de
  forma aditiva, `indexSmallPublishedBatch(...)` (o `indexSinglePublishedQa(...)` ficou intacto).
  Indexa **mais do que um, no máximo três** (`maxItems ∈ [2,3]`; `< 2` recusa como configuração
  inválida, `> 3` excede o tecto), sob **as mesmas guardas por Q&A**; itens elegíveis acima do
  limite são **diferidos** (nunca descartados em silêncio). Modo `SMALL_BATCH_TEST_ISOLATED`;
  *caps* do `AtFaqRagIndexingTotals` **por modo** (single ≤ 1, lote ≤ 3); campos aditivos
  `requestedMaxItems`/`effectiveMaxItems`. IT com 13 testes verdes; E9A continua verde.

Guardas por Q&A **idênticas** em E9A e E9B (reaplicadas item a item, sem atalhos):

- item efectivamente publicado em E8B.3, com `knowledgeQaId` conhecido;
- sem razões de bloqueio de autonomia futura;
- entidade existe e pertence à `Organization` correcta;
- `isPublished()` verdadeiro;
- `curationStatus == VALIDATED`;
- `isEligibleForRag()` verdadeiro;
- `technicalAnswer` não-vazia;
- pelo menos uma fonte (`sourceRepository.countByQuestionAnswerId > 0`);
- `riskLevel == LOW`.

Acoplamento publicação-indexação (inventário E8B.3-prep): a publicação chama a indexação de
forma **síncrona e atómica** (**Caso B**), com **mitigação nativa por profile** — em
`test`/`pgtest` o indexador é o `StubKnowledgeQaEmbeddingIndexer` (no-op). O `unpublish(...)` real
já chama `indexer.remove(id)` (**`DELETE` físico** do embedding) e limpa `publishedAt`/`publishedBy`;
**não há histórico de embeddings nem *soft-delete*** (lacuna reservada para E10).

O que **ainda não existe**: lote **real/piloto** (E9C) e **rollback/despublicação/desindexação**
(E10). Ambos estão `ainda não iniciada` no roadmap.

Restrições de segurança permanentes (em vigor): embeddings por **modelo local**, nunca API
externa; **nunca** publicar/indexar na base piloto real — apenas BD isolada de teste; nunca
expor/guardar a senha do piloto; sem segredos em texto simples no disco; não correr
reset/restore/clean/stash automaticamente.

## 3. Opções em aberto

Após a E9B, há três caminhos possíveis. Esta tarefa (E9C-prep) é a **Opção C** e serve
precisamente para escolher entre as restantes de forma informada.

### Opção A — E9C real/piloto

Avançar já para um lote controlado com **dados mais próximos do piloto** (ainda assim em BD
isolada, nunca em produção), validando o fluxo indexação → RAG em volume ligeiramente maior do
que o lote de teste E9B.

- **A favor:** prova o fluxo mais perto da realidade; antecipa problemas de escala.
- **Contra:** sem rollback governado definido, um lote maior aumenta o custo de desfazer um erro;
  a E9B ainda usa embeddings determinísticos de teste, não um modelo real.

### Opção B — E10-prep (rollback primeiro)

Preparar e provar o **rollback/despublicação/desindexação** antes de aumentar o lote — garantir
que qualquer item indexado pode ser retirado de forma governada, auditável e reversível.

- **A favor:** segue o princípio «só se aumenta a voz depois de saber calá-la»; reduz o risco do
  lote maior.
- **Contra:** adia a validação do fluxo em volume; pode ser prematuro para um lote muito pequeno,
  onde o rollback manual ainda é aceitável.

### Opção C — Matriz antes da execução (esta tarefa)

Produzir **primeiro** esta matriz de decisão (lote piloto, autonomia, intervenção humana, riscos,
métricas, rollback mínimo) e só depois escolher entre A e B com critérios objectivos.

- **A favor:** alinha-se ao perfil conservador do projecto («cautelosa por arquitectura, não lenta
  por dependência humana»); evita decidir o rumo sem critérios escritos.
- **Contra:** nenhuma execução avança nesta tarefa — é deliberadamente documental.

## 4. Definição de lote piloto

Um **lote piloto** é um conjunto **pequeno, fechado e auditável** de Q&A já publicados em E8B.3,
promovido a voz no RAG de uma só vez, com o objectivo de **validar o fluxo** (publicação →
indexação → RAG → resposta documentada), **não** de maximizar volume.

Características obrigatórias do lote piloto:

- **apenas FAQ AT** (primeira fonte de escala — decisão E2);
- **apenas `riskLevel == LOW`** (sem MEDIUM/HIGH/CRITICAL neste patamar);
- **fonte oficial** com **fundamento legal claro** (artigo/diploma ligado);
- **sem conflitos** entre fontes e **sem duplicados materiais** enganadores;
- **todos os itens individualmente elegíveis** pelas guardas por Q&A da E9B;
- **cada item reversível** por rollback mínimo definido (§11).

**Tamanho máximo recomendado:** **5 a 10 itens**, com **preferência por 5**. Recomenda-se **5** se
10 for considerado excessivo para um primeiro lote real; 10 é o tecto admissível apenas se o
rollback já estiver suficientemente garantido. Um lote piloto **não** é ingestão massiva nem a
banda 20–50 da política E1/E8 — é um patamar intermédio entre o lote de teste E9B (≤ 3,
determinístico) e qualquer escala futura.

> **Regra curta.** Primeiro a **qualidade do fluxo** num lote muito pequeno. Só depois a **escala**.

## 5. Critérios de entrada

Um Q&A só entra no lote piloto se **todos** os critérios se verificarem (agregação pelo mais
restritivo — basta um critério falhar para o item não entrar):

- **fonte oficial** (FAQ AT) identificada e preservada;
- **`technicalAnswer`** própria, não-vazia, fundamentada (nunca só a `shortAnswer`);
- **`shortAnswer`** curada presente;
- **fundamento legal** (referência legal/artigo) claro e associado;
- **`riskLevel == LOW`**;
- **actualidade** não `OUTDATED` — `FreshnessStatus` `CURRENT` ou `STABLE_BUT_OLD` (ver C8; não
  confundir com `KnowledgeCurationStatus.OUTDATED`);
- **sem candidatos a conflito** (`conflictCandidates` vazios) entre fontes;
- **sem duplicados materiais** (`duplicateCandidates` sem eco enganador — C9/E9);
- **jurisdição `PT`** identificada;
- **tema (`KnowledgeTopic`)** identificado;
- **`curationStatus`** compatível (`VALIDATED`, via transições de domínio reais
  `markPendingReview()` → `validate(...)`, com fonte garantida);
- **publicação governada possível** (item já publicado em E8B.3, elegível para autonomia futura,
  sem razões de bloqueio);
- **indexação governada possível** (guardas por Q&A da E9B satisfeitas; `isEligibleForRag()`
  verdadeiro);
- **rollback mínimo definido** para o item (§11) — sem reversão prevista, não entra.

## 6. Critérios de exclusão

Um Q&A é **excluído** do lote piloto (fica fora, para curadoria assistida/manual ou para lote
futuro) se qualquer um destes sinais ocorrer:

- **`riskLevel == HIGH`** ou **`CRITICAL`**;
- **`riskLevel == MEDIUM`**, salvo **decisão expressa** documentada (por defeito, excluído);
- **actualidade `FreshnessStatus.OUTDATED`** (fonte desactualizada);
- **`FreshnessStatus.UNCERTAIN` com impacto material** (actualidade duvidosa em matéria sensível);
- **fontes contraditórias** / divergência entre fontes;
- **sem artigo/fundamento legal** claro;
- **sem fonte oficial** (ou fonte não oficial a sustentar sozinha — C9);
- **matéria interpretativa** que exige apreciação profissional;
- **matéria dependente de factos concretos** do caso;
- **parecer obrigatório** (`parecerRequirement == REQUIRED`) ou encaminhamento para Pedido de
  parecer;
- **requer intervenção humana** (`requiresHumanValidation == true`);
- **duplicados/ecos não resolvidos**;
- **conteúdo incompleto** (falta `technicalAnswer`, `shortAnswer`, tema, jurisdição ou fonte).

> **Regra de agregação (C6/E1).** Basta **um** sinal de exclusão para o item não entrar no lote
> piloto. Na dúvida, **prudência**: fica fora.

## 7. Matriz de autonomia

Quatro resultados possíveis por Q&A, decididos por critérios objectivos (sem *scoring* numérico),
aplicando o mais-restritivo-ganha:

| Resultado | Critérios | Efeito |
| --- | --- | --- |
| **AUTO_GOVERNED** | fonte oficial + `LOW` + `technicalAnswer` própria + fundamento legal claro + actualidade aceitável (não `OUTDATED`) + sem conflito + sem duplicado material + todas as guardas por Q&A da E9B satisfeitas + rollback mínimo definido | A TaxIA **pode** publicar/indexar de forma governada, sem intervenção humana caso a caso (a governação é por critérios auditáveis, não por gesto manual). |
| **ASSISTED_REQUIRED** | qualquer sinal de **cautela**: `MEDIUM`, referência legal provável mas não totalmente segura, fonte oficial mas questão ambígua, alteração material face a caso existente, possível duplicado, `FreshnessStatus.UNCERTAIN`, `technicalAnswer` que exige interpretação controlada | Precisa de **confirmação assistida** antes de ganhar voz; não entra no lote piloto automático. |
| **MANUAL_REQUIRED** | qualquer sinal de **bloqueio**: `HIGH`, divergência entre fontes, fonte não oficial, actualidade duvidosa com impacto, matéria interpretativa/dependente de factos, `parecerRequirement == REQUIRED`, `requiresHumanValidation == true` | Exige **validação humana obrigatória**; fica fora do lote piloto. |
| **BLOCKED** | item não publicado, entidade inexistente ou de outra `Organization`, `curationStatus != VALIDATED`, `isEligibleForRag() == false`, sem fonte, `riskLevel != LOW`, `CRITICAL`, rollback mínimo indefinido, ou qualquer critério de entrada (§5) em falta | **Não pode** entrar no lote piloto em caso algum; é reportado (nunca descartado em silêncio). |

Princípio (E1/coupling-inventory): a intervenção humana deve ser **excepcional**, não condição
normal de publicação. Um item só é `AUTO_GOVERNED` quando **todos** os critérios se verificam;
basta um sinal de cautela para cair em `ASSISTED_REQUIRED` e um sinal de bloqueio para cair em
`MANUAL_REQUIRED`/`BLOCKED`.

## 8. Matriz de intervenção humana

A intervenção humana **não deve ser condição estrutural** de publicação — mas **deve ser medida**
sempre que ocorre, para detectar se a TaxIA está a depender demasiado dela. Indicadores a recolher
por lote piloto:

- **% de itens em cada resultado** da matriz de autonomia (`AUTO_GOVERNED` / `ASSISTED_REQUIRED` /
  `MANUAL_REQUIRED` / `BLOCKED`);
- **tempo médio por intervenção** humana (quando exista);
- **motivo dominante** de intervenção (risco, fundamento legal, actualidade, conflito, duplicado…);
- **tipo de erro** detectado pela intervenção (falso positivo de autonomia, falso bloqueio,
  classificação de risco errada…);
- **casos que poderiam tornar-se automáticos** no futuro (intervenções que, corrigida a causa,
  deixariam de ser necessárias).

Princípio orientador: **a TaxIA não deve depender estruturalmente de intervenção humana, mas deve
medir quando é necessária** — «cautelosa por arquitectura, não lenta por dependência humana». Uma
taxa de intervenção persistentemente alta é sinal de que os critérios de entrada/exclusão precisam
de afinação, não de que o humano deve ser removido do circuito.

## 9. Riscos a observar

Riscos a vigiar durante o lote piloto (lista de observação, não de implementação):

- **duplicados/ecos** indexados como se fossem fontes independentes (C9);
- **falsos positivos de autonomia** — item `AUTO_GOVERNED` que deveria ser assistido/manual;
- **falsos bloqueios de autonomia** — item bloqueado que era legitimamente limpo;
- **risco mal classificado** (`LOW` indevido sobre matéria sensível);
- **fonte insuficiente** (sem diversidade material real);
- **fundamento legal fraco** ou apenas aparente;
- **pergunta demasiado concreta** (dependente de factos do caso);
- **resposta técnica genérica** demais para sustentar consulta documentada;
- **RAG certo por motivo errado** — recuperação correcta por artefacto do embedding determinístico,
  não por semântica real (risco específico do ambiente de teste E9A/E9B);
- **empates de embeddings** — similaridades ≈ 1.0 que tornam a ordem não fiável (validar por
  **pertença ao conjunto**, não por ordem);
- **interferência entre temas próximos** — Q&A de temas adjacentes a recuperarem-se mutuamente;
- **actualidade duvidosa** — `UNCERTAIN`/`STABLE_BUT_OLD` tratada como `CURRENT`.

## 10. Métricas de aceitação

Um lote piloto só é considerado aceite se **todas** estas condições se verificarem:

- **0** itens `HIGH`/`CRITICAL` indexados;
- **0** itens sem fonte oficial indexados;
- **0** itens sem `legalReference`/fundamento legal indexados;
- **0** itens sem `technicalAnswer` indexados;
- **0** duplicados materiais indexados;
- **RAG recupera o esperado** numa **percentagem aceitável** (alvo a fixar antes da execução; no
  ambiente determinístico, recuperação por pertença ao conjunto dos itens indexados);
- **nenhum** caso de outra `Organization` recuperado (isolamento multi-tenant intacto);
- **nenhuma** resposta proveniente de item **não publicado**;
- **taxa de `AUTO_GOVERNED` medida** e registada;
- **taxa de intervenção humana medida** e registada.

As percentagens-alvo devem ser fixadas **antes** de executar o lote, não ajustadas *a posteriori*
para dar o resultado por bom.

## 11. Rollback mínimo antes de E9C real

Antes de indexar qualquer lote real, tem de existir um **rollback mínimo** definido. Princípio de
escala:

- para um lote **muito pequeno** (≈ 5), um **rollback manual documentado** é aceitável;
- para um lote **maior** (até 10, ou qualquer escala futura), o **rollback governado** passa a ser
  **obrigatório** (E10).

O rollback mínimo, manual ou governado, tem de cobrir:

- **despublicar** o item (`markUnpublished()` / `unpublish(...)` — limpa `publishedAt`/`publishedBy`);
- **remover o embedding** (`indexer.remove(qaId)` — `DELETE` de `knowledge_qa_embeddings`);
- **confirmar que o RAG deixou de recuperar** o item (verificação explícita pós-remoção);
- **preservar a auditoria** (eventos `KNOWLEDGE_QA_UNPUBLISHED`/equivalente);
- **registar o motivo** da reversão;
- **não apagar histórico indevidamente** (a reversão é despublicação/desindexação, não eliminação
  de histórico de curadoria);
- **permitir reindexação futura** (o item pode voltar a ganhar voz depois de corrigido).

> **Nota de lacuna (E8B.3-prep).** Hoje a desindexação é `DELETE` físico, **sem histórico de
> embeddings nem *soft-delete***. Para lote maior, esta lacuna deve ser resolvida em E10 antes de
> subir de escala.

## 12. Decisão recomendada

Opções avaliadas: **A** = E9C real/piloto já; **B** = E10-prep (rollback) primeiro; **C** =
**E9C-mini** (um lote piloto mínimo de 5, `LOW`, FAQ AT, com rollback manual documentado, ainda
em BD isolada); **D** = outra via.

**Recomendação (cautelosa): Opção B — preparar o rollback (E10-prep) antes de aumentar o lote**,
com uma ressalva de abertura para C.

Fundamentação:

- o projecto é **conservador no progresso e teme muito o erro**; a frase-mestra desta etapa é
  precisamente «saber calar antes de aumentar a voz»;
- a E9B já provou o fluxo de indexação em lote pequeno; o que **ainda não existe** é a garantia de
  **reversão** — e indexar mais itens sem rollback governado aumenta o custo de desfazer um erro;
- a lacuna conhecida (desindexação por `DELETE` físico, sem histórico) recomenda resolver o
  rollback **antes** de escalar.

**Ressalva (abertura condicionada para Opção C / E9C-mini).** Se, e só se, a escolha for avançar
para um lote real antes de E10, então o lote deve obedecer a **todas** estas condições, sem
excepção:

- **limite de 5 itens**;
- **apenas `riskLevel == LOW`**;
- **apenas FAQ AT**;
- **rollback manual mínimo documentado** (§11) antes de indexar;
- **sem produção** — exclusivamente BD isolada de teste;
- **sem base piloto real**, salvo decisão futura explícita e debatida.

Esta escolha (B *vs.* C) é **relevante** e, por isso, **deve parar e ser debatida antes de
implementar** — não se resolve dentro desta tarefa documental.

## 13. Critérios para parar antes de implementação

A implementação **não** deve avançar — e a decisão deve **parar e ser debatida** — se qualquer um
destes sinais ocorrer:

- for necessário **alterar migrations**;
- for necessário **alterar `RagSearchService`** ou **`KnowledgeQaEmbeddingIndexerImpl`** (produção);
- for necessário **usar dados reais** ou *providers*/modelos de embedding externos;
- os **critérios de rollback não estiverem claros** ou não cobrirem os sete pontos do §11;
- houver **divergência entre documentação e código** (o código deixou de corresponder ao que estes
  documentos afirmam);
- a escolha **E9C real *vs.* E10-prep não for inequívoca** — enquanto houver dúvida, prevalece a
  preparação do rollback.

## 14. Fora de âmbito

Esta tarefa (E9C-prep) **não** inclui, explicitamente:

- **alterações Java**, a **testes** ou a **frontend**;
- **uso de BD**, de **base piloto real** ou de **dados reais**;
- **publicação**, **indexação**, **despublicação**, **embeddings** ou **RAG** executados;
- **migrations**, **endpoints** novos ou alterados;
- **scraping**, **HTTP externo**, chamadas a **OpenAI**, **Anthropic** ou qualquer *provider*;
- reabrir o **Bloco C**, o **Bloco D** ou as tarefas **E1–E9B**.

Altera-se **apenas `docs/`**.

## 15. Critério de conclusão

A tarefa E9C-prep está concluída quando:

- o **documento** `docs/taxia-e9c-pilot-batch-decision-matrix.md` está **criado** com o título e as
  15 secções obrigatórias;
- as **três opções em aberto** (A/B/C) estão descritas (§3);
- a **matriz de autonomia** está definida (§7);
- a **matriz de intervenção humana** está definida (§8);
- o **rollback mínimo** está definido (§11);
- a **recomendação** cautelosa está definida (§12);
- o **roadmap** e os dois documentos de contrato/implementação estão actualizados a referir esta
  matriz, **sem** qualquer execução de código, dados ou RAG.

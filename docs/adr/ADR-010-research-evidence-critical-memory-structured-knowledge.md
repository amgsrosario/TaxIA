# ADR-010: Research, Evidence, Critical Memory and Structured Knowledge

## Status

Proposed (KF-M01, 2026-10-22). A aceitação é uma decisão humana. Concretiza tecnicamente as
decisões B1–B3 e o Aditamento Operacional da AR2-DEC-KF-2026-10-22. Não altera o
[ADR-005](ADR-005-published-content-integrity.md) nem o `AGENTS.md`: a promoção autónoma (B4)
continua bloqueada até alteração normativa própria.

## Context

A auditoria estratégica inicial (AR2-Governance, `AUDIT_KF_2026-10-21_INITIAL.md`) classificou
como ABSENT:
- a memória crítica (KF-01 / CA-03);
- a investigação autónoma a partir de pergunta inédita (KF-02 / CA-01);
- o conhecimento estruturado para além de Q&A (KF-03 / CA-04).

O `/ask` só recupera Q&A publicadas e devolve resposta-limite quando falta contexto. A AR2 aprovou
B1–B3 e um Aditamento Operacional com as regras seguintes:
- a primeira vertical pode responder com **evidência de investigação não promovida**, no mínimo
  `CONSULTA_DOCUMENTADA_COM_LIMITACOES` com parecer `SUGGESTED`;
- conflito material, vigência incerta material ou insuficiência dão `RESPOSTA_LIMITE`;
- o desenvolvimento faz-se em base descartável, sem escrita na `knowledgeflow_pilot`;
- a saída de rede é read-only, só para legislação oficial e Portal das Finanças / AT.

## Decision

### 1. Módulo `research`, separado do corpus de Q&A
Tabelas novas e aditivas (V20); nenhuma tabela existente é alterada. O módulo não escreve em
`knowledge_question_answers` nem em `knowledge_qa_embeddings`: nada é publicado nem indexado.

### 2. Investigação rastreável
`research_investigations` e `research_steps` (append-only). Cada passo tem uma fase:
- `CORPUS_CHECK`: só se investiga sem Q&A publicada relevante (mesmo limiar e gate de âmbito do
  `/ask`);
- `DISCOVERY` (hipótese): referências explícitas da pergunta, ou hipóteses do modelo, que nunca são
  evidência;
- `CONSULTATION`: obtenção da fonte;
- `CONFIRMATION`: a página tem de conter o artigo pedido.

### 3. Fontes do primeiro perímetro (B2)
Códigos consolidados publicados pela AT no Portal das Finanças:
- os códigos abrangidos são CIRS, CIVA, CIRC e LGT;
- os URLs seguem o padrão oficial verificado: `…/codigos_tributarios/cirs_rep/Pages/irs78.aspx`.

Não é um crawler: só se constrói o URL do artigo pedido.

O cliente HTTP é conservador:
- só GET, sem cookies nem autenticação;
- allowlist de hosts aplicada também em cada redirecção;
- pausa entre pedidos e limite de tamanho;
- 403/429/503 são respeitados, nunca contornados.

A arquitectura admite fontes posteriores (Diário da República, jurisprudência) como novos
catálogos ou clientes.

### 4. Evidência (`research_evidence`)
Cada evidência guarda:
- texto oficial do artigo, sem navegação, scripts nem redacções anteriores;
- URL, autoridade, referência legal, data de consulta e SHA-256 do conteúdo;
- notas de alteração;
- ligação ao passo de consulta.

`promotion_status` só admite `NOT_PROMOTED`, garantido por CHECK na base.

A vigência é determinada só por sinais explícitos da página:
- `REVOKED`: o artigo está revogado;
- `UNCERTAIN`: uma nota de alteração refere um ano posterior ao da consulta;
- `CONSOLIDATED_AT_RETRIEVAL`: nos restantes casos, ou seja, a versão consolidada publicada pela AT
  à data da consulta, sem verificação humana.

### 5. Memória crítica (B1)
`critical_memory_entries` e `critical_memory_history` (append-only). Cada entrada guarda:
- a afirmação;
- a classificação: `ERRONEOUS`, `CONTRADICTORY`, `INSUFFICIENT`, `REJECTED` ou `SUPERSEDED`;
- a razão e a proveniência;
- a versão da fonte (SHA-256);
- a relação com a evidência ou proposição que a contradiz;
- o estado e o histórico.

Uma afirmação repetida com a mesma classificação incrementa `occurrences` (recorrência). Isto
garante-se por índice único e pelo serviço.

Entram automaticamente na memória crítica:
- hipóteses desmentidas pela fonte (404);
- referências não confirmadas;
- artigos ou números revogados;
- proposições e condições sem âncora literal;
- conflitos;
- respostas rejeitadas pelo grounding.

A memória crítica nunca entra no prompt nem fundamenta respostas. É consultada para orientar a
investigação: uma hipótese já desmentida não é consultada de novo. A reavaliação humana muda só o
estado, com histórico e auditoria.

### 6. Conhecimento estruturado (B3)
`knowledge_propositions` regista, para cada regra ou proposição:
- fundamento e âmbito;
- aplicabilidade;
- vigência herdada da evidência;
- confiança: `ANCHORED` ou `UNSUPPORTED`;
- estado: `CANDIDATE`, `QUARANTINED` ou `REJECTED`. Não há estado promovido.

Tabelas associadas:
- `knowledge_proposition_supports`: âncora literal na evidência;
- `knowledge_proposition_conditions`: condições e excepções, cada uma ancorada;
- `knowledge_proposition_relations`: `CONFLICTS_WITH`, `EXCEPTION_TO` e `REFINES`.

O modelo propõe; o serviço verifica. Uma afirmação só é `ANCHORED` se:
- tiver um excerto literal, com pelo menos 20 caracteres, presente na evidência;
- todos os seus números constarem da evidência.

Um conflito entre proposições ancoradas põe ambas em quarentena.

### 7. Resposta (Aditamento §2)
A resposta usa só evidência `ACCEPTED` e proposições `CANDIDATE` ancoradas. O texto passa pelo
`AnswerGroundingValidator` do `/ask`, aplicado contra a evidência recolhida. A decisão do tipo de
resposta é:

| Situação | Tipo de resposta | Parecer |
|---|---|---|
| Sem evidência aceite | `RESPOSTA_LIMITE` | `SUGGESTED` |
| Conflito não resolvido | `RESPOSTA_LIMITE` | `REQUIRED` |
| Vigência incerta | `RESPOSTA_LIMITE` | `REQUIRED` |
| Sem proposição ancorada | `RESPOSTA_LIMITE` | `SUGGESTED` |
| Grounding rejeita a resposta | `RESPOSTA_LIMITE` | `SUGGESTED` |
| Restantes casos | `CONSULTA_DOCUMENTADA_COM_LIMITACOES` | `SUGGESTED` |

`CONSULTA_DOCUMENTADA` e `NONE` são impossíveis, por validação na entidade e CHECK na base.

### 8. Exposição
`/api/v1/admin/research/**` está disponível só para ADMIN e só com
`knowledgeflow.research.enabled=true` (desligado por omissão). Fica separado do `/ask`: integrar a
investigação na resposta ao utilizador é uma decisão de produto ainda não tomada.

## Consequences

- KF-01/02/03 passam a ter implementação demonstrável em ambiente descartável. A avaliação
  independente (D1, CA-09) continua por fazer.
- A activação no piloto exige uma acção humana separada, com backup, verificação da V20 e rollback
  (Aditamento §3). Nada é aplicado automaticamente.
- Limites conhecidos:
  - o perímetro é apenas CIRS, CIVA, CIRC e LGT. Ficam de fora o EBF (padrão de URL diferente), o
    Diário da República, a jurisprudência e as FAQ AT como evidência de investigação;
  - a vigência `CONSOLIDATED_AT_RETRIEVAL` depende da consolidação publicada pela AT;
  - a detecção de conflitos depende da estruturação pelo modelo;
  - a insuficiência de factos é apresentada como pressupostos, não decidida automaticamente.
- B4 (promoção autónoma) e o cenário CA-06 completo ficam fora desta decisão.

# ADR-005: Published Content Integrity

## Status

Accepted (GOV-PUBLISHED-CONTENT-INTEGRITY, 2026-10-02).

## Context

O RAG lê da linha da Q&A, em tempo de pergunta, a resposta servida
(`COALESCE(technical_answer, short_answer)`), o título (`normalized_question`) e a validade; o gate
de âmbito lê tema/subtema/pergunta; as fontes curadas (M3) são lidas em tempo de resposta. Só o
embedding é calculado na publicação. Até esta decisão, `updateCuration` (backoffice), o
`curate-one` das ferramentas do piloto e o re-import com a mesma externalKey podiam alterar uma
Q&A VALIDATED ou publicada sem nova validação — o conteúdo servido podia divergir do validado. O
re-import fazia-o sem auditoria. `createNewVersion` despublicava a versão anterior logo ao criar a
nova e não copiava as fontes.

## Decision

1. **Semântica.** `VALIDATED` significa que *o conteúdo actualmente efectivo desta versão* foi
   validado por um humano. Invariante: nenhum conteúdo material não validado é servido pelo RAG,
   chega ao prompt, aparece como fonte, altera o âmbito ou é indexado como publicado.
2. **Classificação central** (`CurationChangePolicy`, aplicada na entidade, logo em todos os
   caminhos):
   - MATERIAL: respostas curta e técnica, pergunta normalizada, tema, subtema, jurisdição;
   - EXPANSIVA: risco a descer; validação humana true→false; validade alargada (`validFrom` mais
     cedo ou nulo; `validTo` mais tarde ou nulo — nulo é "sem limite"); fonte nova ou editada;
   - CONSERVADORA: risco a subir; validação humana false→true; validade encurtada; retirar uma
     fonte que não seja a última; acrescentar exclusão de aplicabilidade (ADR-004);
   - LIVRE: notas; `canonical` (operacional, sem efeito no RAG).
   Na dúvida, trata-se como material.
3. **Lifecycle híbrido** (sem novo estado nem schema):
   - VALIDATED não publicada: alteração material/expansiva aplica-se in-place e a Q&A volta a
     `PENDING_REVIEW` (nova transição VALIDATED→PENDING_REVIEW, só neste contexto), com
     `reviewedBy/At` limpos e `canonical` desligado; publicação bloqueada até nova validação.
   - Versão publicada **congelada**: alteração material/expansiva é recusada (409) em
     `updateCuration`, `addSource`, `curate-one`. Conservadoras e livres aplicam-se e são
     auditadas.
   - Re-import com a mesma externalKey sobre uma Q&A VALIDATED **ou** publicada: se a linha
     alterar qualquer campo (colunas ausentes mantêm o valor actual), o item é saltado, reportado
     (`PROTECTED_SKIPPED`) e auditado; os restantes itens do lote seguem. O re-import nunca é
     caminho de curadoria de conteúdo validado.
   - Mudança material numa publicada faz-se por **nova versão**: linha nova com
     `previous_version_id`, cópia integral (conteúdo curado, fontes em linhas novas, exclusões
     efectivas — pedidos de remoção pendentes ficam efectivos), em `PENDING_REVIEW`, não
     publicada, não indexada, sem validação nem revisão de âmbito herdadas. Chave externa
     `base_vN` (N = máximo da família + 1). Uma só versão em preparação por versão publicada.
     **A versão anterior continua publicada, VALIDATED, indexada e a responder.**
   - **Publicar e substituir** (`POST /{newId}/publish-replacing/{previousId}`, acção humana):
     exige a nova VALIDATED e elegível, derivada da anterior publicada; locks nas duas linhas por
     ordem determinística; indexa a nova, publica-a, desindexa e despublica a anterior, numa só
     transacção. Se a indexação ou a desindexação falhar, tudo é revertido (a anterior mantém-se
     publicada e indexada). `publish` simples recusa publicar uma versão se outra da mesma
     linhagem estiver publicada: nunca há duas publicadas, nem zero por falha intermédia.
4. **Reindex** só para versões publicadas e VALIDATED (conteúdo congelado). Sem hash nesta fase.
5. **Concorrência e validação da versão lida.** O PATCH de curadoria e o `validate` exigem
   `expectedVersion` (`@Version`); versão diferente → 409 (lost update; validar o que não se
   leu). Fontes e exclusões de aplicabilidade também incrementam a versão da Q&A. Lock de escrita
   (`PESSIMISTIC_WRITE`) em edição, adição/remoção de fonte, validate, publish, reindex, criação
   de versão e substituição; publish e substituição bloqueiam primeiro a raiz da linhagem (ordem
   determinística), o que serializa publicações concorrentes da mesma linhagem.
6. **Auditoria.** `KNOWLEDGE_QA_UPDATED` passa a registar `changedFields` e
   `revalidationFields` (nomes, nunca conteúdo); `STATUS_CHANGED` quando a validação é limpa;
   re-import auditado (`event=REIMPORT` / `REIMPORT_SKIPPED`); `VERSION_CREATED` com
   `previousVersionId`; substituição com `PUBLISHED replaces=` e `UNPUBLISHED replacedBy=`.
7. **Relação com ADR-004.** A política das exclusões (acrescentar imediato; remover numa
   publicada/VALIDATED só após aprovação humana) é um caso da regra geral "conservador imediato,
   expansivo só após validação".
8. **Legacy.** As Q&A já publicadas mantêm-se VALIDATED, publicadas e indexadas; sem migração.
9. **Backoffice.** Mantém-se o guard rail existente: o backoffice não publica nem cria
   embeddings. Mostra o estado (publicada congelada, rascunho, "a versão anterior continua
   activa") e permite criar nova versão; "publicar e substituir" faz-se no canal de publicação
   governado (API).

## Consequences

- Sem migração: reutiliza `previous_version_id`, `published_at/by`, `curation_status`,
  `reviewed_at/by`, `@Version` e `audit_events`.
- Linhas legadas publicadas antes desta decisão podem ter sido editadas depois da validação:
  confirmar por leitura da auditoria do piloto (verificação só de leitura pós-merge).
- Fora de âmbito: hash do conteúdo validado, fontes pendentes de validação, novo estado.
- Limite conhecido: uma chave externa natural com a forma `base_vN` partilha a família de chaves
  das versões (numeração e re-import); evitar esse padrão em chaves de origem.
- Follow-up: o serviço de indexação governada em lote (`AtFaqGovernedRagIndexingService`) lê a
  Q&A sem lock; o pior caso é um embedding órfão numa versão já despublicada (não servido).

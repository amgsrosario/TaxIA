# Piloto de Ingestão das FAQs Públicas da AT (Etapa 10A)

**Estado:** piloto técnico concluído · funcionalidade **desactivada por defeito**
**Módulo:** `com.knowledgeflow.ingestion.atfaq`
**Data:** 2026-07-03

---

## 1. Âmbito

Piloto técnico de ingestão automatizada das Questões Frequentes públicas da
Autoridade Tributária e Aduaneira (AT), limitado a:

| Parâmetro | Valor |
|---|---|
| Fonte | https://info.portaldasfinancas.gov.pt/pt/apoio_contribuinte/questoes_frequentes/Pages/faqs.aspx |
| Área | IVA, exclusivamente |
| Subcategorias | Direito à Dedução (`faqs-00929`), Taxas (`faqs-00930`), Faturação → Enquadramento Legal (`faqs-01010`) |
| Limite | 80 FAQs por execução (`max-items`) |

Fora do âmbito (ver §12): IRC, IRS, Justiça Tributária, SAF-T, ATCUD, OCC,
scraping autenticado, browser automation, publicação automática, síntese por
LLM, execução agendada.

## 2. Política editorial

O conteúdo da AT é **fonte e evidência, nunca resposta canónica automática**:

- o texto original é preservado internamente na camada RAW (`at_faq_raw_items`),
  com URL, identificador oficial, categoria, data de recolha e hash;
- a AT é sempre identificada como autoridade de origem
  (`sourceAuthority = "Autoridade Tributária e Aduaneira"`), com ligação para a
  página oficial em cada referência;
- a síntese própria da TaxIA (`shortAnswer`/`technicalAnswer`) fica **vazia** na
  importação — será escrita por um curador humano em fase posterior;
- a base textual integral não é redistribuída;
- nada é publicado no RAG automaticamente: toda a importação entra em
  **quarentena** (`curationStatus = IMPORTED`, `requiresHumanValidation = true`)
  e os guards da entidade impedem `VALIDATED` sem resposta curada.

`rawAnswer` (campo `answerRaw` / `originalAnswer`) nunca pode ser confundida com
resposta validada: os campos curados estão separados e vazios, e a validação
exige uma resposta curada preenchida.

## 3. Diagnóstico da fonte (2026-07-03)

- `robots.txt` → **404** (sem restrições de recolha declaradas);
- conteúdo renderizado **no servidor** — não é necessário JavaScript nem browser;
- encoding UTF-8 declarado no `Content-Type`;
- índice: acordeão Bootstrap com uma secção por imposto
  (`a[data-bs-toggle=collapse]` → `div#collapseNN` → `ul.submenu-itens > li`
  com grupos e `ul.submenu-itens-sub li a` com links);
- páginas de categoria: `div#faqAccordion > div.card`; pergunta em
  `.card-header a`, prefixada pelo **identificador oficial numérico**
  (`"4583 - Lista I - Verba 2.36 - …"`); resposta em `.card-body`;
- sem paginação; ~20 FAQs por página de categoria;
- risco de alteração: HTML gerado por SharePoint com estilos inline — os
  selectores usados (`#faqAccordion`, `.card-header a`, `.card-body`) são os
  identificadores estruturais mais estáveis disponíveis. `parserVersion`
  regista a versão da lógica de extracção em cada item.

### Matriz de diagnóstico

| Página | Tipo | Estrutura | Selector | Identificador | Risco | Decisão |
|---|---|---|---|---|---|---|
| `faqs.aspx` | índice | acordeão por imposto | `a[data-bs-toggle]` + `#collapseNN ul.submenu-itens` | título da secção | médio | parse determinístico, só área IVA |
| `faqs-00929/00930/01010.aspx` | categoria | acordeão de Q&A | `#faqAccordion div.card` | número no cabeçalho | médio | parse determinístico |
| respostas | HTML rico | `<p>`, `<ul>/<li>`, spans | extracção de blocos | — | baixo | texto com parágrafos/listas preservados |

## 4. Política de recolha

Cliente HTTP conservador (`AtFaqHttpClient`):

- um pedido de cada vez, pausa configurável entre pedidos (default 1500 ms);
- `User-Agent: TaxIA-Research-Pilot/1.0` — identificável, nunca disfarçado;
- timeouts explícitos (connect 5 s, read 20 s);
- retry limitado (default 2) **apenas** para falhas transitórias de rede;
- **403, 429 e 503 param imediatamente o run inteiro** (estado `BLOCKED`) —
  bloqueios são respeitados, nunca contornados;
- sem cookies, sem autenticação, sem execução de JavaScript;
- GET condicional (ETag/Last-Modified) + hash de página
  (`at_faq_page_snapshots`) para não reprocessar páginas inalteradas;
- limite de tamanho de resposta (2 MB), de páginas (10), de itens (80) e de
  duração total do run (10 min).

## 5. Segurança

- endpoints exclusivos de `ADMIN`; a organização é derivada do JWT — nunca
  aceite no payload;
- allowlist de hosts: `info.portaldasfinancas.gov.pt` (SSRF);
- redirects verificados hop a hop — destino fora da allowlist → `BLOCKED` +
  evento de auditoria `AT_FAQ_SECURITY_BLOCKED`;
- URLs de descoberta têm de estar sob
  `/pt/apoio_contribuinte/questoes_frequentes/`;
- links externos dentro de respostas são **registados como dados**
  (`detectedLinks`) e nunca seguidos;
- esquemas não-HTTP(S) e URLs com credenciais são recusados.

## 6. Fluxo RAW → CURATED → PUBLISHED

```
DISCOVER  → localiza categorias autorizadas (não persiste FAQs)
DRY_RUN   → pipeline completo SEM escritas (além do registo do run)
IMPORT    → RAW (at_faq_raw_items) + Q&A em quarentena (IMPORTED)
              ↓ [humano]
           curadoria (síntese própria TaxIA) → PENDING_REVIEW → VALIDATED
              ↓ [humano]
           publicação → embeddings → RAG
```

O piloto implementa apenas até à quarentena. Curadoria/validação/publicação
usam o circuito existente da Etapa 8/9 — nenhum atalho novo foi criado.

### Estados RAW (`AtFaqIngestionStatus`)

`DISCOVERED → FETCHED → PARSED → NORMALIZED → READY_FOR_IMPORT → IMPORTED`
mais os estados de anomalia: `NEEDS_REVIEW`, `CHANGED_AT_SOURCE`,
`POSSIBLY_REMOVED`, `FAILED`.

## 7. Hash e detecção de alterações

- hash SHA-256 sobre a representação estável (NFC, LF, espaços colapsados,
  linhas aparadas) de pergunta + resposta — whitespace irrelevante nunca muda
  o hash; qualquer alteração semântica muda;
- mesma `officialFaqId` + mesmo hash → inalterada (`lastSeenAt` actualizado);
- mesma `officialFaqId` + hash diferente → **nova versão**
  (`CHANGED_AT_SOURCE`); a versão anterior é marcada `superseded` e
  **preservada para sempre** (`previousVersionId` encadeia o histórico);
- FAQ conhecida ausente numa execução → contador de faltas; só com
  **2 execuções consecutivas** em falta é marcada `POSSIBLY_REMOVED`
  (nunca arquivada automaticamente);
- runs truncados (limites atingidos) não avaliam remoções.

## 8. Mapeamento para a camada Q&A

| Campo Q&A | Valor |
|---|---|
| `externalKey` | `AT-FAQ-{officialFaqId}` |
| `sourceSystem` | `at-faq` |
| `topic` / `subtopic` | `IVA` / subcategoria oficial |
| `originalQuestion/Answer` | texto original extraído (imutável) |
| `shortAnswer` / `technicalAnswer` | **vazios** — síntese futura do curador |
| `riskLevel` | `MEDIUM` |
| `requiresHumanValidation` | `true` |
| `curationStatus` | `IMPORTED` (quarentena) |
| Referência | `OFFICIAL_FAQ` — "Portal das Finanças — Questões Frequentes", URL exacto, referências legais detectadas |

Referências legais (artigos, n.ºs, alíneas, CIVA/CIRC/CIRS/LGT/CPPT/RITI,
diplomas, verbas, listas) são extraídas por regex conservadora — registadas
como evidência, **não validadas juridicamente, nunca inventadas**.

## 9. Configuração

```properties
knowledgeflow.ingestion.at-faq.enabled=false        # master switch (default OFF)
knowledgeflow.ingestion.at-faq.max-items=80
knowledgeflow.ingestion.at-faq.max-pages=10
knowledgeflow.ingestion.at-faq.delay-ms=1500
knowledgeflow.ingestion.at-faq.connect-timeout=5s
knowledgeflow.ingestion.at-faq.read-timeout=20s
knowledgeflow.ingestion.at-faq.max-run-duration=10m
knowledgeflow.ingestion.at-faq.user-agent=TaxIA-Research-Pilot/1.0
```

Variáveis de ambiente: `AT_FAQ_INGESTION_ENABLED`, `AT_FAQ_MAX_ITEMS`,
`AT_FAQ_MAX_PAGES`, `AT_FAQ_DELAY_MS`, etc. (ver `application.yml`).

## 10. Operação manual

```
POST /api/v1/admin/ingestion/at-faq/discover      # só descoberta
POST /api/v1/admin/ingestion/at-faq/dry-run       # pipeline sem escritas
POST /api/v1/admin/ingestion/at-faq/import        # importação em quarentena
GET  /api/v1/admin/ingestion/at-faq/runs/{id}     # relatório de um run
```

Body opcional de `dry-run`/`import`: `{"maxPages": 5, "maxItems": 10}` —
os overrides **só apertam** os limites configurados, nunca os alargam.

Procedimento recomendado: `discover` → rever categorias → `dry-run` → rever
relatório → `import` → curadoria humana no circuito Q&A existente.

## 11. Auditoria e métricas

Eventos: `AT_FAQ_DISCOVERY_STARTED/COMPLETED`, `AT_FAQ_DRY_RUN_COMPLETED`,
`AT_FAQ_IMPORT_COMPLETED`, `AT_FAQ_ITEM_NEW/CHANGED/POSSIBLY_REMOVED`,
`AT_FAQ_PARSE_FAILED`, `AT_FAQ_SECURITY_BLOCKED`. Os eventos guardam
metadados curtos (ids, contagens) — nunca HTML integral.

Métricas (tags só `outcome`/`category`/`httpStatusClass`):
`at_faq_http_requests_total`, `at_faq_pages_discovered_total`,
`at_faq_pages_parsed_total`, `at_faq_parse_failures_total`,
`at_faq_items_new_total`, `at_faq_items_changed_total`,
`at_faq_items_unchanged_total`, `at_faq_items_removed_candidate_total`,
`at_faq_ingestion_duration`.

## 12. Riscos jurídicos e recomendações

- As FAQs da AT são informação pública administrativa, recolhida de forma
  identificada, limitada e com atribuição — mas **os termos de utilização do
  Portal das Finanças devem ser revistos por um jurista antes de qualquer
  expansão** para lá do piloto;
- a TaxIA usa o conteúdo como fonte interna com atribuição e ligação à página
  oficial; não redistribui a base textual integral;
- alterações de estrutura do site partem o parser de forma **segura** (falhas
  reportadas, nada importado errado) — monitorizar `at_faq_parse_failures_total`;
- a AT pode alterar/remover FAQs; o mecanismo de versões + remoção provável
  garante que a TaxIA nunca fica silenciosamente desactualizada.

### Expansão futura (fora desta etapa)

Novas áreas (IRC, IRS, …) exigem: revisão jurídica, alargamento da allowlist
de subcategorias, novos fixtures de teste e novo dry-run supervisionado.
A execução recorrente agendada fica explicitamente proibida até decisão
posterior.

## 13. Testes

- 45 testes novos na suite normal (parser, normalizador, referências legais,
  descoberta, cliente HTTP com servidor local, matriz de segurança dos
  endpoints);
- `AtFaqIngestionPostgresIT` (perfil `pgtest`, Testcontainers +
  `pgvector/pgvector:pg16` + servidor HTTP local de fixtures): persistência
  RAW, unicidade, nova/inalterada/alterada, preservação de versões, remoção
  provável, quarentena, ausência de publicação/embeddings, isolamento por
  organização, auditoria, idempotência, rollback atómico e zero chamadas
  externas;
- fixtures fictícias em `src/test/resources/at-faq/` — **nenhuma suite toca
  na internet**.

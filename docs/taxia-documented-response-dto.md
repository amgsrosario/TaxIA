# TaxIA — Contrato técnico da resposta documentada

> **Documento de desenho técnico.** Define o contrato/DTO *pretendido* para a
> resposta documentada da TaxIA. **Não implementa** nada — sem DTO Java, sem
> migrations, sem alterações de API ou frontend, sem publicação automática, sem
> ingestão massiva. Ver [Âmbito](#3-âmbito).
>
> Enquadramento: [taxia-product-vision.md](taxia-product-vision.md) ·
> [taxia-response-model.md](taxia-response-model.md) · [roadmap.md](roadmap.md)
> (Bloco B) · [grounding-policy.md](grounding-policy.md).

## 1. Objectivo

Definir a **estrutura técnica futura** da resposta documentada que a TaxIA passará
a devolver, alinhando produto, backend, RAG e frontend em torno de um contrato
único. É a ponte entre o modelo funcional (campos e escalas, já descritos em
[taxia-response-model.md](taxia-response-model.md)) e a futura implementação.

## 2. Relação com os documentos anteriores

- **Visão de produto** — *porquê* (consultoria assistida, não chatbot).
- **Modelo de resposta documentada** — *o quê* (blocos A–H, campos funcionais).
- **Este documento** — *como (contrato)*: nomes técnicos, tipos, enums,
  mapeamento a partir do estado actual e regras iniciais de tradução.

## 3. Âmbito

Este documento **define o contrato pretendido**, mas **não** implementa:

- DTO Java;
- migrations;
- alterações de API;
- alterações de frontend;
- publicação automática;
- ingestão massiva.

Os nomes de campos, tipos e enums aqui propostos são **candidatos de desenho** e
podem mudar na implementação.

## 4. DTO principal sugerido — `DocumentedTaxAnswerDto`

| Campo | Tipo | Notas |
|-------|------|-------|
| `answerType` | `AnswerType` | **Forma da resposta ao utilizador** — campo central, mais alto que `supportStatus` (ver [secção 6-A](#6-a-answertype-a-forma-da-resposta)) |
| `answerSummary` | `string` | Resposta curta (bloco A) |
| `technicalExplanation` | `string` | Enquadramento técnico (bloco B) |
| `legalBasis` | `LegalBasisDto[]` | Fundamento legal (bloco C) |
| `sources` | `DocumentedSourceDto[]` | Fontes consultadas (bloco D) |
| `conditions` | `string[]` | Condições (bloco E) |
| `exclusions` | `string[]` | Exclusões (bloco E) |
| `alerts` | `TaxAlertDto[]` | Alertas (bloco F) |
| `supportLevel` | `SupportLevel` | Força do suporte documental |
| `confidenceLevel` | `ConfidenceLevel` | Fiabilidade estimada |
| `riskLevel` | `RiskLevel` | Risco fiscal do tema (do caso individual) |
| `aggregatedRiskLevel` | `RiskLevel` | **Risco agregado da resposta** — máximo dos fundamentos relevantes usados (ver C4) |
| `parecerRequirement` | `ParecerRequirement` | Necessidade de encaminhar para Pedido de parecer (não revisão humana invisível) |
| `visibilityLevel` | `VisibilityLevel` | A quem pode ser mostrada |
| `freshnessStatus` | `FreshnessStatus` | Actualidade da fonte |
| `disclaimerLevel` | `DisclaimerLevel` | Intensidade do aviso legal |
| `nextSteps` | `string[]` | Próximos passos (bloco H) |
| `supportStatus` | `AnswerSupportStatus` | Valor actual do grounding (mantém-se) |
| `retrievedCases` | `RetrievedKnowledgeCaseDto[]` | Casos recuperados pelo RAG |
| `generatedAnswer` | `string` | Texto cru gerado pelo provider |
| `internalNotes` | `string \| null` | Notas internas (só ADMIN/PROFESSIONAL) |
| `mode` | `AnswerMode` | Modo de projecção da resposta |

> `generatedAnswer` (cru) e `answerSummary`/`technicalExplanation` (estruturados)
> coexistem: os últimos podem ser derivados/curados a partir do primeiro, mas o
> contrato mantém o texto original para auditabilidade.

## 5. DTOs auxiliares

**`LegalBasisDto`**

| Campo | Tipo |
|-------|------|
| `reference` | `string` |
| `diploma` | `string \| null` |
| `article` | `string \| null` |
| `normalizedReference` | `string \| null` |

**`DocumentedSourceDto`**

| Campo | Tipo |
|-------|------|
| `sourceType` | `string` |
| `title` | `string` |
| `legalReference` | `string \| null` |
| `url` | `string \| null` |
| `sourceQuality` | `SourceQuality` |
| `lastCheckedAt` | `datetime \| null` |

**`TaxAlertDto`**

| Campo | Tipo |
|-------|------|
| `severity` | `AlertSeverity` |
| `code` | `string` |
| `message` | `string` |
| `requiresAction` | `boolean` |

**`RetrievedKnowledgeCaseDto`**

| Campo | Tipo |
|-------|------|
| `externalKey` | `string` |
| `question` | `string` |
| `topic` | `string \| null` |
| `subtopic` | `string \| null` |
| `score` | `number` |
| `published` | `boolean` |
| `riskLevel` | `RiskLevel \| null` |

## 6. Enums sugeridos

```text
AnswerType:         CONSULTA_DOCUMENTADA | CONSULTA_DOCUMENTADA_COM_LIMITACOES | RESPOSTA_LIMITE | PEDIDO_DE_PARECER
SupportLevel:       NONE | WEAK | PARTIAL | STRONG
ConfidenceLevel:    LOW | MEDIUM | HIGH
RiskLevel:          LOW | MEDIUM | HIGH
ParecerRequirement: NONE | SUGGESTED | REQUIRED
                    # nome conceptual anterior: ReviewRequirement (descontinuado — ver C3)
VisibilityLevel:    INTERNAL_ONLY | PROFESSIONAL_ONLY | CLIENT_VISIBLE | CLIENT_VISIBLE_WITH_WARNING
FreshnessStatus:    CURRENT | NEEDS_RECHECK | STALE | UNKNOWN
DisclaimerLevel:    NONE | LIGHT | STANDARD | STRONG
SourceQuality:      OFFICIAL | LEGAL | INTERNAL | UNVERIFIED | MIXED
AlertSeverity:      INFO | WARNING | CRITICAL
AnswerMode:         CLIENT | PROFESSIONAL | ADMIN
                    # nomes conceptuais preferíveis no futuro: PROFESSIONAL | DEMO | ADMIN
```

`AnswerSupportStatus` **não** é novo — é o enum já existente (`SUPPORTED`,
`PARTIALLY_SUPPORTED`, `INSUFFICIENT_CONTEXT`, `REQUIRES_HUMAN_REVIEW`,
`REJECTED_UNSUPPORTED`), mantido no contrato.

`AnswerType` é a **decisão de produto** sobre a forma de resposta ao utilizador
(ver [taxia-boundary-answer.md](taxia-boundary-answer.md)).

`ParecerRequirement` substitui o antigo `ReviewRequirement` (Decisão C3, ver
[taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md)). **Não**
representa uma fila de revisão humana interna, e **não** mede o **valor económico ou
técnico abstracto** do parecer (o parecer pode ter valor em qualquer situação —
Decisão C6). Representa o **grau de recomendação/encaminhamento para Pedido de
parecer** naquela resposta concreta.

- **`NONE`** — resposta automática **suficiente** para a finalidade normal da
  consulta; **sem** encaminhamento especial (o profissional pode sempre pedir
  parecer se quiser).
- **`SUGGESTED`** — resposta útil e orientadora, mas a situação **pode beneficiar**
  de parecer; a TaxIA **sugere** o Pedido de parecer como opção prudente, cabendo a
  decisão ao profissional.
- **`REQUIRED`** — **não** fechar a conclusão; **encaminhar** para Pedido de parecer
  (podendo apresentar enquadramento, fontes, limites e factos em falta, ou uma
  Resposta-limite preparatória); **não** apresentar conclusão fiscal final.

Separação de eixos:

- `answerType` define a **forma** da resposta;
- `supportStatus` explica o **suporte técnico** (sinal do *grounding*);
- `aggregatedRiskLevel` sintetiza o **risco dos fundamentos usados** (Decisão C4);
- `parecerRequirement` indica a **necessidade/sugestão de Pedido de parecer**
  (Decisão C6);
- `visibilityLevel` indica **onde** a resposta pode ser apresentada.

`aggregatedRiskLevel` representa o **risco agregado da resposta** (Decisão C4, ver
[taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md)). É calculado a
partir dos **fundamentos relevantes efectivamente usados** na resposta e:

- **não** é necessariamente o risco máximo de **todos** os casos recuperados pelo RAG;
- **não** é necessariamente o risco do **primeiro** caso recuperado;
- **não** é a média dos riscos recuperados.

Regra curta: `aggregatedRiskLevel = risco máximo dos fundamentos relevantes usados`.
O `riskLevel` por caso (em `retrievedCases[]`) continua a existir por caso; o
`aggregatedRiskLevel` é o **risco da resposta como um todo**. Casos recuperados mas
não usados (ruído) não elevam o `aggregatedRiskLevel`.

## 6-A. `answerType`: a forma da resposta

O `answerType` é o **campo central** do contrato e situa-se num nível **mais alto**
que o `supportStatus`:

- **`supportStatus`** explica o **suporte técnico** — quão bem o contexto RAG
  sustenta a resposta (sinal do *grounding*).
- **`answerType`** define a **forma de resposta ao utilizador** — a decisão de
  produto sobre *como* a TaxIA responde.

Um mesmo `supportStatus` pode dar origem a `answerType` diferentes, conforme risco,
visibilidade, frescura das fontes e modo. Orientação inicial (a afinar):

| `supportStatus` | `answerType` provável |
|-----------------|-----------------------|
| `SUPPORTED` | `CONSULTA_DOCUMENTADA` (sem garantia absoluta de 100%) |
| `PARTIALLY_SUPPORTED` | `CONSULTA_DOCUMENTADA_COM_LIMITACOES` |
| `REQUIRES_HUMAN_REVIEW` | `CONSULTA_DOCUMENTADA_COM_LIMITACOES`, `RESPOSTA_LIMITE` ou `PEDIDO_DE_PARECER`, conforme contexto |
| `INSUFFICIENT_CONTEXT` | `RESPOSTA_LIMITE` |
| `REJECTED_UNSUPPORTED` | `RESPOSTA_LIMITE` ou `PEDIDO_DE_PARECER` (sem conclusão fiscal) |

Quando os sinais divergem, aplica-se a **regra mais restritiva** (princípio 6 de
[taxia-core-principles.md](taxia-core-principles.md)): na dúvida, desce-se para a
forma menos conclusiva (`RESPOSTA_LIMITE` ou `PEDIDO_DE_PARECER`).

> **Fronteira `CONSULTA_DOCUMENTADA_COM_LIMITACOES` ↔ `RESPOSTA_LIMITE` (Decisão C5).**
> Usar `RESPOSTA_LIMITE` quando **não há conclusão aplicável segura** — só resta
> enquadramento geral suportado. Enquanto ainda for possível apontar uma
> **orientação prudente condicionada**, a forma correcta é
> `CONSULTA_DOCUMENTADA_COM_LIMITACOES`. Ver
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) (Decisão C5).

## 7. Mapeamento a partir do estado actual

Como os dados de hoje podem alimentar o DTO:

| Campo do DTO | Origem actual |
|--------------|---------------|
| `generatedAnswer` | Resposta gerada pelo AI provider/stub (`AskResponse.answer`) |
| `supportStatus` | Valor já devolvido pelo grounding |
| `retrievedCases` | Casos recuperados por `RagSearchService.findSimilar` |
| `sources` | Fontes (`knowledge_source_references`) dos casos recuperados |
| `riskLevel` | Existe em `knowledge_question_answers`, mas **ainda não** é transportado pelo `RetrievedCase` — deve passar a ser |
| `aggregatedRiskLevel` | **Derivado** (C4): risco máximo dos fundamentos relevantes **usados** na resposta — não de todo o conjunto recuperado |
| `supportLevel` | **Derivado** de quantidade/qualidade de fontes e suficiência de contexto |
| `confidenceLevel` | **Derivado** de `supportLevel`, `riskLevel`, `freshnessStatus` e estado editorial |
| `parecerRequirement` | **Derivado** de `supportStatus`, `riskLevel`, keywords sensíveis e visibilidade pretendida |
| `visibilityLevel` | **Derivado** de `riskLevel`, `parecerRequirement`, estado editorial e modo |
| `freshnessStatus` | **Derivado** de `lastCheckedAt`, `valid_from`/`valid_to`, alterações detectadas na fonte e data de publicação |

> Nota-chave de desenho: vários campos são **calculados**, não persistidos — o que
> reduz a necessidade de migrations prematuras (ver [secção 15](#15-critérios-para-implementação-futura)).

## 8. Regra inicial de tradução `supportStatus` → campos novos

Primeira regra **conservadora** (a afinar com dados reais):

| `supportStatus` | `supportLevel` | `parecerRequirement` | `visibilityLevel` | `disclaimerLevel` |
|-----------------|----------------|---------------------|-------------------|-------------------|
| `SUPPORTED` | STRONG ou PARTIAL (conforme fontes) | NONE ou SUGGESTED (conforme `riskLevel`) | conforme risco (secção 9) | LIGHT ou STANDARD |
| `PARTIALLY_SUPPORTED` | PARTIAL | SUGGESTED | conforme risco | STANDARD |
| `INSUFFICIENT_CONTEXT` | WEAK ou NONE | REQUIRED | INTERNAL_ONLY ou PROFESSIONAL_ONLY | STRONG |
| `REQUIRES_HUMAN_REVIEW` | (mantém o do suporte) | REQUIRED | PROFESSIONAL_ONLY ou CLIENT_VISIBLE_WITH_WARNING | STRONG |
| `REJECTED_UNSUPPORTED` | NONE | REQUIRED | INTERNAL_ONLY | STRONG |

## 9. Regra inicial por `risk_level`

- **LOW** — pode admitir `CLIENT_VISIBLE` se `supportLevel = STRONG` e
  `freshnessStatus = CURRENT`.
- **MEDIUM** — pode admitir `CLIENT_VISIBLE` ou `CLIENT_VISIBLE_WITH_WARNING`;
  `parecerRequirement = SUGGESTED` se houver keywords sensíveis.
- **HIGH** — por defeito `PROFESSIONAL_ONLY`; se exposto ao cliente, apenas
  `CLIENT_VISIBLE_WITH_WARNING`; `parecerRequirement = REQUIRED`; **nunca** resposta
  autónoma final.

Quando `supportStatus` (secção 8) e `risk_level` (secção 9) divergirem na
visibilidade, aplica-se **a regra mais restritiva**.

## 10. Modos de resposta (`AnswerMode`)

A TaxIA é **profissional em todos os modos** — a resposta documentada é sempre a
mesma em qualidade, profundidade, fundamentação e estrutura. O modo controla apenas
o **ruído interno visível** e eventuais **limites comerciais/operacionais**, nunca a
qualidade conceptual (ver [taxia-core-principles.md](taxia-core-principles.md),
Princípio 1-A).

Esclarecimento sobre os valores actuais do enum:

- **`CLIENT`** — designa, por razões técnicas, o **utilizador externo**. **Não**
  significa utilizador leigo e **não** implica resposta simplificada.
- **`PROFESSIONAL`** — utilizador profissional com acesso a mais detalhe operacional.
- **`ADMIN`** — acrescenta diagnóstico, scores, estados, notas internas e curadoria.
- Um modo **`DEMO`**, se previsto, é um **ambiente profissional limitado
  comercialmente** — mesma resposta, com travões comerciais/operacionais.

> **Nomes conceptuais preferíveis no futuro:** `PROFESSIONAL` / `DEMO` / `ADMIN`
> (em vez de `CLIENT`), para não sugerir um utilizador leigo. Renomear é decisão de
> implementação — aqui fica apenas a orientação.

A mesma `DocumentedTaxAnswerDto` é **projectada** conforme o modo (qualidade igual,
ruído interno diferente):

| | Externo (`CLIENT`/`DEMO`) | `PROFESSIONAL` | `ADMIN` |
|---|---|---|---|
| Resposta documentada / fundamentação | Completa | Completa | Completa |
| Linguagem | Clara e rigorosa | Clara e rigorosa | Técnica + diagnóstico |
| Fontes | Completas | Completas | Completas |
| Alertas/condições | Sim | Sim | Sim |
| Casos recuperados | Não | Pode ver | Sim (com `score`, `externalKey`) |
| Notas internas | Não | Limitadas | Sim (`internalNotes`) |
| Estado editorial/scores | Não | Parcial | Sim |

Regra: a vista externa nunca expõe `internalNotes`, `score` ou estado editorial;
mostra sempre fontes, alertas e `parecerRequirement` quando aplicável — **sem baixar
a qualidade profissional da resposta**.

> **Detalhe visível por nível (Decisão C7).** `EXTERNAL` e `DEMO` **não** significam
> menor qualidade: recebem **produto profissional limpo** (resposta, enquadramento,
> fundamentos, fontes, limitações, pressupostos, factos em falta, `answerType`,
> `parecerRequirement`, risco em linguagem legível e aviso de ausência de garantia
> absoluta). `INTERNAL` e `CURATION_ONLY` podem, **por cima**, incluir campos de
> **diagnóstico** — designadamente `retrievedCases[]` (com `score`, `externalKey`),
> `internalNotes`, estado editorial, ranking e sinais de curadoria. Campos crus como
> `score`, chunks recuperados, ranking bruto, logs, prompts internos e IDs técnicos
> **não** devem ser expostos em `EXTERNAL`/`DEMO` **sem transformação** numa
> explicação profissional legível (o score não aparece, mas a prudência que dele
> decorre pode ser comunicada). A diferença entre `DEMO` e `EXTERNAL` é apenas
> **comercial/operacional** (volume, tempo, histórico, exportações, permissões,
> criação efectiva de Pedido de parecer), **nunca** de qualidade conceptual. Ver
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) (Decisão C7).

## 11. Exemplo JSON conceptual (baseado em AT-FAQ-5930)

> Exemplo **conceptual** — não é output real do sistema hoje.

```json
{
  "answerSummary": "São dedutíveis os gastos efectivamente suportados e pagos para obter ou garantir os rendimentos prediais, por prédio (ex.: certos seguros, obras de conservação). Em regra não são dedutíveis gastos financeiros, depreciações, mobiliário, electrodomésticos, artigos de conforto/decoração, nem o adicional ao IMI.",
  "technicalExplanation": "Na categoria F, deduzem-se aos rendimentos brutos os gastos suportados e pagos para obter/garantir o rendimento, por imóvel e com suporte documental. A regra aplica-se por prédio, não de forma global ao património.",
  "legalBasis": [
    {
      "reference": "Artigo 41.º do CIRS",
      "diploma": "Código do IRS",
      "article": "41.º",
      "normalizedReference": "CIRS-41"
    }
  ],
  "sources": [
    {
      "sourceType": "LEGISLATION",
      "title": "Código do IRS — Artigo 41.º — Deduções aos rendimentos prediais",
      "legalReference": "CIRS art. 41.º",
      "url": "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/cirs_rep/Pages/irs41.aspx",
      "sourceQuality": "LEGAL",
      "lastCheckedAt": "2026-07-24T00:00:00Z"
    },
    {
      "sourceType": "OFFICIAL_FAQ",
      "title": "Portal das Finanças — FAQ 5930 — Rendimentos prediais: despesas dedutíveis",
      "legalReference": null,
      "url": "https://info.portaldasfinancas.gov.pt/pt/apoio_contribuinte/questoes_frequentes/pages/faqs-00358.aspx",
      "sourceQuality": "OFFICIAL",
      "lastCheckedAt": "2026-07-24T00:00:00Z"
    }
  ],
  "conditions": [
    "Despesa efectivamente paga e suportada pelo sujeito passivo",
    "Ligação concreta entre a despesa e o imóvel gerador do rendimento",
    "Suporte documental adequado"
  ],
  "exclusions": [
    "Gastos de natureza financeira",
    "Depreciações",
    "Mobiliário, electrodomésticos e artigos de conforto/decoração",
    "Adicional ao IMI"
  ],
  "alerts": [
    {
      "severity": "WARNING",
      "code": "REAL_ESTATE_TOPIC",
      "message": "Tema com componente imobiliária — validação humana recomendada.",
      "requiresAction": false
    }
  ],
  "supportLevel": "STRONG",
  "confidenceLevel": "MEDIUM",
  "riskLevel": "MEDIUM",
  "parecerRequirement": "SUGGESTED",
  "visibilityLevel": "CLIENT_VISIBLE_WITH_WARNING",
  "freshnessStatus": "CURRENT",
  "disclaimerLevel": "STANDARD",
  "nextSteps": [
    "Confirmar por imóvel as despesas pagas e documentadas",
    "Reunir comprovativos de obras ou períodos pré-arrendamento, se aplicável",
    "Para decisão vinculada, encaminhar para consultor"
  ]
}
```

## 12. Impacto previsto no backend

- Criar os DTOs (`DocumentedTaxAnswerDto` + auxiliares).
- Enriquecer `RetrievedCase` com `riskLevel` e fontes detalhadas.
- Adaptar `RagSearchService` (ou uma camada superior) para transportar esses dados.
- Adaptar `GroundingService` para produzir `DocumentedTaxAnswerDto`.
- Adicionar um mapper (estado actual → DTO).
- Testar a tradução de `supportStatus` (secção 8).
- Testar os modos `CLIENT` / `PROFESSIONAL` / `ADMIN`.

## 13. Impacto previsto no frontend

- Componente `DocumentedAnswerCard`.
- Bloco de fontes.
- Bloco "qualidade da resposta".
- Badges de risco/confiança.
- Avisos de encaminhamento para Pedido de parecer (`parecerRequirement`) — **não**
  "avisos de revisão humana invisível" (ver C3).
- Distinção de vistas por permissões (externo profissional / demo / admin), **sem**
  simplificar a resposta.

## 14. Decisões em aberto

- Se `confidenceLevel` deve ser **calculado** ou introduzido manualmente na curadoria.
- Se o `risk_level` da BD deve **prevalecer** sobre as keywords sensíveis (ou combinar-se).
- Como tratar **múltiplos casos recuperados com riscos diferentes** (ex.: máximo, ou por caso).
- **Quando** uma resposta HIGH pode ser visível ao cliente (e com que aviso).
- Como determinar `freshnessStatus` de forma fiável (detecção de alterações da fonte).
- Como mostrar `disclaimerLevel` sem **assustar inutilmente** o utilizador.

## 15. Critérios para implementação futura

A implementação só deve avançar quando:

- o **contrato DTO estiver aceite**;
- as **regras iniciais** `supportStatus` / `risk` / `visibility` estiverem claras;
- houver **testes planeados**;
- **não** obrigar a migration prematura — os campos que puderem ser **calculados**
  (`supportLevel`, `confidenceLevel`, `parecerRequirement`, `visibilityLevel`,
  `freshnessStatus`) não devem exigir novas colunas nesta fase.

## 16. Fora do âmbito

Não implementa DTO Java, migrations, alterações de API, UI, publicação automática
nem ingestão massiva. É desenho técnico e orientação para decisões futuras.

# TaxIA — Modelo de resposta documentada

> **Documento de desenho.** Fixa *como* a TaxIA deve estruturar uma resposta
> fiscal antes de qualquer implementação. Não cria DTOs, campos, migrations, API
> nem UI — ver [Fora do âmbito](#13-fora-do-âmbito).
>
> Enquadramento: [taxia-product-vision.md](taxia-product-vision.md) ·
> [roadmap.md](roadmap.md) (Bloco A) · [grounding-policy.md](grounding-policy.md).

## 1. Objectivo do modelo

Definir como a TaxIA deve estruturar uma resposta fiscal assistida por IA, de
forma **documentada, rastreável e qualificada** — mostrando fundamento, fontes,
grau de confiança, risco e necessidade de revisão humana, em vez de um texto
único sem proveniência nem qualificação.

## 2. Princípios

- A resposta **não deve parecer uma verdade absoluta**.
- Deve **mostrar fundamento** (legal e documental).
- Deve **distinguir certeza, suporte e risco** — são coisas diferentes.
- Deve **indicar quando falta contexto**.
- Deve **indicar quando é necessário encaminhar para Pedido de parecer**
  (intervenção humana), sem criar uma "fila de revisão humana invisível" no
  circuito automático — ver [nota sobre `parecer_requirement`](#3-b-parecer_requirement-não-é-fila-de-revisão-humana).
- Deve **separar a resposta ao cliente dos dados técnicos internos**.

> **Posicionamento (obrigatório):** a TaxIA é apoio à decisão e não substitui
> aconselhamento fiscal profissional nem a responsabilidade do profissional
> qualificado. Em matérias sensíveis, a decisão final é sempre humana.

## 3. Estrutura ideal da resposta

Resposta composta por blocos (todos opcionais na apresentação, mas previstos no
modelo):

**A. Resposta curta** — resposta directa, linguagem clara, sem excesso técnico.

**B. Enquadramento técnico** — explicação fiscal, condições, pressupostos,
limitações.

**C. Fundamento legal** — artigos de lei, diplomas, referência legal normalizada.

**D. Fontes consultadas** — FAQ oficial; legislação; doutrina/instruções/ofícios
(se existirem); com URL, data de confirmação e tipo de fonte.

**E. Condições e exclusões** — elementos que têm de se verificar; situações que
mudam a resposta; exclusões expressas; casos que exigem dados adicionais.

**F. Alertas** — risco fiscal; prazos; valores relevantes; temas sensíveis;
necessidade de validação humana.

**G. Qualidade da resposta** — nível de suporte; grau de confiança; maturidade da
resposta; necessidade de Pedido de parecer; visibilidade recomendada.

**H. Próximos passos** — pedir documentos; pedir factos adicionais; encaminhar
para consultor; deixar claro que a resposta é orientação.

## 3-A. Formas de resposta (a Resposta-limite não é erro)

A estrutura acima aplica-se em graus diferentes conforme a **forma de resposta**.
São quatro, do maior para o menor grau de conclusão:

1. **Consulta documentada** — blocos A–H com fundamentação legal normal.
2. **Consulta documentada com limitações** — os mesmos blocos, com condições,
   exclusões e limites reforçados.
3. **Resposta-limite** — **último patamar automático** antes do Pedido de parecer.
4. **Pedido de parecer** — sai do circuito automático (intervenção humana obrigatória).

A **Resposta-limite** é uma forma de resposta de pleno direito, não uma mensagem de
erro: **"não concluir" também pode ser uma resposta útil**. Usa-se quando a TaxIA
não pode concluir com segurança (suporte/contexto insuficientes, factos em falta,
fontes fracas ou pergunta demasiado concreta).

> **Fronteira C5 — com limitações *vs.* Resposta-limite.** Se a TaxIA **ainda
> consegue apontar uma orientação prudente** (condicionada por limites, pressupostos
> ou excepções), deve emitir **Consulta documentada com limitações**. Se **só
> consegue explicar o enquadramento** mas **não** apontar uma conclusão aplicável
> com segurança, deve emitir **Resposta-limite**. Ou seja: *se ainda há orientação
> prudente, com limitações; se só há enquadramento sem conclusão aplicável,
> Resposta-limite.* "Não concluir" pode ser a resposta correcta quando só existe
> enquadramento. Ver [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md)
> (Decisão C5).

Estrutura padrão da Resposta-limite:

- **Estado da resposta** — declaração de que não há conclusão final;
- **Motivo da limitação** — por que não é possível concluir com segurança;
- **Elementos em falta** — factos/documentos em falta;
- **Enquadramento geral** — quadro legal/documental, sem o aplicar ao caso concreto;
- **Fontes relevantes** — referências rastreáveis do enquadramento;
- **Documentos/factos a juntar** — o que o utilizador deve reunir;
- **Recomendação de Pedido de parecer** — quando a questão exigir apreciação concreta;
- **Indicador de segurança** — sinalização de resposta limitada e não vinculativa.

A Resposta-limite reutiliza os campos do modelo (fontes, condições, alertas,
`support_level`, `parecer_requirement`), mas **omite conclusão** e **explicita o que
falta**. Documento dedicado: [taxia-boundary-answer.md](taxia-boundary-answer.md).

## 3-B. `parecer_requirement` não é fila de revisão humana *(Decisão C3)*

Fora do circuito de **Pedido de parecer**, a TaxIA **não exige intervenção humana
caso a caso** para apresentar uma resposta ao utilizador profissional. O circuito
automático de pesquisa/documentação **resolve-se sozinho** através do `answerType`
(Consulta documentada / Consulta documentada com limitações / Resposta-limite /
Pedido de parecer). Nenhuma resposta automática fica em estado **"pendente de
revisão humana"** — a intervenção humana pertence **apenas** ao serviço de Pedido
de parecer.

Por isso o campo antes chamado `review_requirement` passa a `parecer_requirement`:
ele indica **se a resposta deve encaminhar para Pedido de parecer**, não a
existência de uma fila interna de revisão. Valores:

- **`NONE`** — apresentar sem encaminhamento especial;
- **`SUGGESTED`** — apresentar, mas sugerir Pedido de parecer;
- **`REQUIRED`** — não fechar a conclusão; encaminhar para Pedido de parecer
  (podendo apresentar Resposta-limite ou enquadramento preparatório).

> **Curadoria interna ≠ revisão da resposta.** A curadoria humana da base de
> conhecimento (validar, publicar, arquivar casos) continua a existir como
> governação interna — mas **não** deve ser confundida com uma revisão humana da
> resposta concreta apresentada ao utilizador no circuito automático. Ver
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) (Decisão C3).
>
> **Graduação do encaminhamento (Decisão C6).** O **Pedido de parecer** é uma
> funcionalidade **estrutural e economicamente relevante** da TaxIA — está **sempre**
> disponível como opção, seja qual for o `parecer_requirement`. O que o
> `parecer_requirement` gradua **não** é se "o parecer tem valor" (tem, em qualquer
> situação), mas o **grau de recomendação/encaminhamento** naquela resposta concreta:
> `NONE` = a resposta automática é suficiente para a finalidade normal (o profissional
> pode na mesma pedir parecer); `SUGGESTED` = a TaxIA **sugere** o parecer como opção
> prudente; `REQUIRED` = a TaxIA **não** fecha a conclusão e **encaminha** para Pedido
> de parecer. `REQUIRED` significa encaminhamento explícito para o circuito humano —
> **não** uma revisão interna invisível da resposta automática. Ver
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) (Decisão C6).

## 4. Campos funcionais sugeridos

Nomes **funcionais**, ainda não técnicos definitivos (não são colunas nem campos
de DTO nesta fase):

| Bloco | Campo funcional | Conteúdo |
|-------|-----------------|----------|
| A | `answer_summary` | Resposta curta |
| B | `technical_explanation` | Enquadramento técnico |
| C | `legal_basis` | Fundamento legal |
| D | `sources` | Lista de fontes (ver escala de tipo/data) |
| E | `conditions` | Condições que têm de se verificar |
| E | `exclusions` | Exclusões expressas |
| F | `alerts` | Alertas de risco/prazo/valor |
| G | `support_level` | Quão bem o contexto sustenta a resposta |
| G | `confidence_level` | Fiabilidade estimada |
| G | `risk_level` | Risco fiscal do tema |
| G | `parecer_requirement` | Necessidade de encaminhar para Pedido de parecer (não revisão humana invisível) |
| G | `visibility_level` | A quem pode ser mostrada |
| G | `freshness_status` | Actualidade da fonte |
| D/G | `last_checked_at` | Data da última confirmação da fonte |
| F/G | `disclaimer_level` | Intensidade do aviso legal |
| H | `next_steps` | Passos seguintes sugeridos |

> **Risco apresentado = risco agregado dos fundamentos usados (Decisão C4).** O
> `risk_level` que a resposta apresenta deve corresponder ao **risco agregado dos
> fundamentos efectivamente usados** — não ao risco de todo o conjunto recuperado
> pelo RAG. Fontes recuperadas mas **não usadas** (ruído) **não** devem contaminar a
> classificação visível. Em sentido inverso, uma **excepção HIGH usada** na
> fundamentação **eleva a prudência** da resposta, mesmo que não seja o primeiro
> resultado. Ver [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md)
> (Decisão C4).

## 5. Escalas sugeridas

```text
support_level:       NONE | WEAK | PARTIAL | STRONG
confidence_level:    LOW | MEDIUM | HIGH
risk_level:          LOW | MEDIUM | HIGH
parecer_requirement:  NONE | SUGGESTED | REQUIRED
visibility_level:    INTERNAL_ONLY | PROFESSIONAL_ONLY | CLIENT_VISIBLE | CLIENT_VISIBLE_WITH_WARNING
freshness_status:    CURRENT | STABLE_BUT_OLD | UNCERTAIN | OUTDATED   # Decisão C8
disclaimer_level:    NONE | LIGHT | STANDARD | STRONG
```

Estas escalas são **candidatas de desenho**. Os nomes/valores definitivos e a sua
materialização (enum Java, coluna, campo de DTO) ficam para a fase de
implementação.

## 5-A. Origem temporal da resposta *(Decisão C8)*

A resposta profissional deve **indicar a origem temporal** do conhecimento em que se
baseia. O `freshness_status` **não** decide isoladamente se a TaxIA responde —
**gradua** a força, a origem temporal, os limites e os avisos da resposta:

- **fonte actual** (`CURRENT`) — pode sustentar resposta principal;
- **fonte antiga mas estável** (`STABLE_BUT_OLD`) — pode sustentar resposta principal,
  **com nota de actualidade/estabilidade**;
- **fonte de actualidade incerta** (`UNCERTAIN`) — sustenta apenas **enquadramento
  limitado**, sem conclusão actual segura sem aviso forte;
- **fonte histórica/desactualizada** (`OUTDATED`) — **não** sustenta conclusão fiscal
  actual, mas pode servir de **histórico, contraste ou alerta**; nunca produz silêncio
  automático.

O problema não é a **idade isolada** da fonte, mas a **confiança na sua validade
actual**. Uma fonte antiga sobre matéria estável continua útil.

> **Regra curta (C8).** A actualidade **não decide apenas se** a TaxIA responde.
> Decide **com que força, origem temporal e limitações** a TaxIA responde.

Quando a origem temporal é incerta ou histórica, a resposta deve tornar isso
explícito. Frase-modelo aprovada:

> Com base na fonte disponível, o enquadramento histórico era este.
>
> No entanto, a fonte não permite confirmar a aplicação actual do regime.
>
> Para uma conclusão actual, é necessário confirmar a legislação vigente ou submeter
> Pedido de parecer.

Ver [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) (Decisão C8).

## 6. Projecção da resposta (vista externa profissional vs. vista interna)

A TaxIA é **profissional em todas as vistas** — a resposta é sempre documentada,
fundamentada e estruturada. A projecção **não simplifica** a resposta nem cria uma
versão para leigo; apenas controla, por **permissões**, que **ruído interno** é
visível. Ver [taxia-core-principles.md](taxia-core-principles.md) (Princípio 1-A).

| | Externa (profissional / demo) | Interna (admin / curadoria) |
|---|---|---|
| Resposta documentada, fundamentação, estrutura | **Sim** | **Sim** |
| Fontes e alertas | **Sim** (sempre) | **Sim** |
| Necessidade de Pedido de parecer | **Indicada quando aplicável** | Indicada |
| Detalhe técnico | Completo | Completo |
| Notas de curadoria | Não expor | Pode mostrar |
| Estado editorial / scores | Não expor | Pode mostrar |

Regra-chave: a vista externa **nunca** expõe ruído interno de curadoria (notas,
scores, estado editorial), mas **mantém a mesma qualidade profissional** e mostra
sempre fontes, alertas e a necessidade de Pedido de parecer quando aplicável.
"Linguagem clara" significa rigor bem comunicado — **não** menos técnico.

> **Resposta externa/demo é profissional limpa (Decisão C7).** A diferença entre
> níveis de visibilidade **não** é a profundidade profissional da resposta, mas o
> **grau de exposição dos bastidores**. `EXTERNAL` e `DEMO` recebem **produto
> profissional limpo** — resposta, enquadramento, fundamentos, fontes, limitações,
> pressupostos, factos em falta, `answerType`, `parecerRequirement`, risco em
> linguagem legível e aviso de ausência de garantia — **sem** diagnóstico interno
> (scores, ranking, chunks, notas de curadoria, IDs técnicos). Esse diagnóstico vive
> em `INTERNAL`/`CURATION_ONLY`. A **Resposta-limite** apresentada em `EXTERNAL`/`DEMO`
> é igualmente uma **peça profissional limpa** (enquadramento, fontes e factos em
> falta organizados), nunca uma mensagem de erro nem uma versão simplificada. Ver
> [taxia-risk-visibility-policy.md](taxia-risk-visibility-policy.md) (Decisão C7) e
> [taxia-boundary-answer.md](taxia-boundary-answer.md).

## 7. Relação com os estados actuais

O ciclo `IMPORTED → PENDING_REVIEW → VALIDATED → PUBLISHED` condiciona o que a
resposta pode ser:

| Estado | Papel na resposta |
|--------|-------------------|
| **IMPORTED** | Não deve gerar resposta final ao cliente; pode servir para pesquisa interna. |
| **PENDING_REVIEW** | Não deve ser apresentado como resposta final; pode ajudar o curador. |
| **VALIDATED** | Candidato a publicação; **não entra no RAG** enquanto não estiver publicado. |
| **PUBLISHED** | Entra no RAG; pode suportar resposta, **dependendo de risco e visibilidade**. |

## 8. Relação com o `supportStatus` actual

O `supportStatus` actual (`SUPPORTED`, `PARTIALLY_SUPPORTED`,
`INSUFFICIENT_CONTEXT`, `REQUIRES_HUMAN_REVIEW`, `REJECTED_UNSUPPORTED`) é **útil
mas insuficiente**: mistura, num só sinal, suficiência de contexto, risco por
palavra-chave e resultado de validação.

Deve **continuar a existir**, mas poderá ser **complementado** por sinais mais
granulares e ortogonais:

- `support_level` — força do suporte documental;
- `confidence_level` — fiabilidade estimada;
- `parecer_requirement` — necessidade de revisão humana;
- `visibility_level` — a quem se mostra;
- `freshness_status` — actualidade da fonte.

Exemplo de tradução aproximada (a afinar na implementação): `SUPPORTED` →
`support_level=STRONG`, `parecer_requirement=NONE`; `REQUIRES_HUMAN_REVIEW` →
`parecer_requirement=SUGGESTED|REQUIRED` independentemente do `support_level`.

## 9. Casos HIGH

Orientação para casos de risco elevado (ex.: `AT-FAQ-0959`, `AT-FAQ-4624`):

- **Podem** ser tecnicamente publicados no RAG (pesquisáveis);
- **Não devem** ser apresentados como resposta autónoma final sem aviso;
- **Devem** sair com `parecer_requirement = SUGGESTED` ou `REQUIRED`;
- **Podem** ser, numa primeira fase, `visibility_level = PROFESSIONAL_ONLY`
  (visíveis apenas a profissionais/admins).

Isto operacionaliza a nota de decisão do roadmap: não publicar os HIGH como passo
automático sem política de visibilidade/revisão definida.

## 10. Exemplo de resposta TaxIA (baseado em AT-FAQ-5930)

**Pergunta:** "Que despesas posso deduzir aos rendimentos prediais?"

**A. Resposta curta**
São dedutíveis os gastos efectivamente suportados e pagos para obter ou garantir
os rendimentos prediais, avaliados por prédio ou parte de prédio (ex.: certos
seguros, obras de conservação). Em regra, **não** são dedutíveis gastos
financeiros, depreciações, mobiliário, electrodomésticos, artigos de conforto ou
decoração, nem o adicional ao IMI.

**B. Enquadramento técnico**
Na categoria F, deduzem-se aos rendimentos brutos os gastos suportados e pagos
pelo sujeito passivo para obter/garantir o rendimento, **por imóvel** (não de
forma global ao património). Exige ligação concreta despesa↔imóvel e suporte
documental adequado.

**C. Fundamento legal**
Artigo 41.º do CIRS (deduções aos rendimentos prediais — categoria F).

**D. Fontes consultadas**
- `LEGISLATION` — Código do IRS, Artigo 41.º —
  info.portaldasfinancas.gov.pt/.../irs41.aspx — confirmado em 2026-07 —
  `freshness_status: CURRENT`.
- `OFFICIAL_FAQ` — Portal das Finanças, FAQ 5930 —
  info.portaldasfinancas.gov.pt/.../faqs-00358.aspx — confirmado em 2026-07.

**E. Condições e exclusões**
- *Condições:* despesa paga e suportada; ligação ao imóvel gerador; documentação.
- *Exclusões expressas:* gastos financeiros; depreciações; mobiliário,
  electrodomésticos e artigos de conforto/decoração; adicional ao IMI.
- *Atenção:* despesas de conservação/manutenção e a regra dos 24 meses anteriores
  ao arrendamento, quando aplicável — podem exigir análise adicional.

**F. Alertas**
Tema com componente imobiliária → sinalizado para validação humana. Aplicar por
imóvel; confirmar elegibilidade documental caso a caso.

**G. Qualidade da resposta**
- `support_level`: STRONG (fonte oficial + legislação directa)
- `confidence_level`: MEDIUM
- `risk_level`: MEDIUM
- `parecer_requirement`: SUGGESTED (keyword de risco "imóvel")
- `visibility_level`: CLIENT_VISIBLE_WITH_WARNING
- `disclaimer_level`: STANDARD

**H. Próximos passos**
Confirmar por imóvel as despesas efectivamente pagas e documentadas; se houver
obras ou períodos pré-arrendamento, reunir comprovativos; para decisão vinculada,
encaminhar para consultor. Esta resposta é orientação, não parecer final.

> *Os valores de qualidade acima são ilustrativos do modelo — não são ainda
> calculados pelo sistema.*

## 11. Implicações técnicas futuras

Trabalhos seguintes (fora desta fase de desenho):

- Criar **DTO de resposta documentada**.
- Adaptar `GroundingService` / `AdminAIController`.
- Transportar **fontes detalhadas** (tipo, URL, data) na resposta.
- Ligar o **`risk_level` da BD** ao resultado da resposta.
- Calcular **`support_level`**.
- Calcular **`confidence_level`**.
- Expor **`parecer_requirement`**.
- Desenhar **componente frontend** de resposta documentada.
- Distinguir **vistas por permissões** (profissional externo / demo / interno-admin),
  **sem** baixar a qualidade conceptual da resposta.
- Criar **testes** de resposta documentada.

## 12. Alinhamento com o já existente

- O `grounding` já produz `supportStatus`, `sources` (títulos),
  `missingInformation`, `limitations`, `requiresHumanValidation` — base natural
  para `support_level`/`parecer_requirement`.
- As fontes já são **tipificadas** na BD (`OFFICIAL_FAQ`, `LEGISLATION`,
  `INTERNAL_OPINION`) com URL — base para o bloco **D** e `freshness_status`.
- O `risk_level` já existe por caso na BD (hoje não liga ao `supportStatus`).

## 13. Fora do âmbito

Este documento **não** implementa nem decide:

- novos campos na BD;
- migrations;
- alterações de API;
- UI;
- publicação automática;
- ingestão massiva.

É desenho e orientação para decisões futuras.

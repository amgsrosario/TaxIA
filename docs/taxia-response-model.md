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
- Deve **indicar quando é necessária revisão humana**.
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
resposta; revisão humana; visibilidade recomendada.

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
fontes fracas ou pergunta demasiado concreta). Estrutura padrão:

- **Estado da resposta** — declaração de que não há conclusão final;
- **Motivo da limitação** — por que não é possível concluir com segurança;
- **Elementos em falta** — factos/documentos em falta;
- **Enquadramento geral** — quadro legal/documental, sem o aplicar ao caso concreto;
- **Fontes relevantes** — referências rastreáveis do enquadramento;
- **Documentos/factos a juntar** — o que o utilizador deve reunir;
- **Recomendação de Pedido de parecer** — quando a questão exigir apreciação concreta;
- **Indicador de segurança** — sinalização de resposta limitada e não vinculativa.

A Resposta-limite reutiliza os campos do modelo (fontes, condições, alertas,
`support_level`, `review_requirement`), mas **omite conclusão** e **explicita o que
falta**. Documento dedicado: [taxia-boundary-answer.md](taxia-boundary-answer.md).

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
| G | `review_requirement` | Necessidade de revisão humana |
| G | `visibility_level` | A quem pode ser mostrada |
| G | `freshness_status` | Actualidade da fonte |
| D/G | `last_checked_at` | Data da última confirmação da fonte |
| F/G | `disclaimer_level` | Intensidade do aviso legal |
| H | `next_steps` | Passos seguintes sugeridos |

## 5. Escalas sugeridas

```text
support_level:       NONE | WEAK | PARTIAL | STRONG
confidence_level:    LOW | MEDIUM | HIGH
risk_level:          LOW | MEDIUM | HIGH
review_requirement:  NONE | RECOMMENDED | REQUIRED
visibility_level:    INTERNAL_ONLY | PROFESSIONAL_ONLY | CLIENT_VISIBLE | CLIENT_VISIBLE_WITH_WARNING
freshness_status:    CURRENT | NEEDS_RECHECK | STALE | UNKNOWN
disclaimer_level:    NONE | LIGHT | STANDARD | STRONG
```

Estas escalas são **candidatas de desenho**. Os nomes/valores definitivos e a sua
materialização (enum Java, coluna, campo de DTO) ficam para a fase de
implementação.

## 6. Projecção da resposta (vista externa profissional vs. vista interna)

A TaxIA é **profissional em todas as vistas** — a resposta é sempre documentada,
fundamentada e estruturada. A projecção **não simplifica** a resposta nem cria uma
versão para leigo; apenas controla, por **permissões**, que **ruído interno** é
visível. Ver [taxia-core-principles.md](taxia-core-principles.md) (Princípio 1-A).

| | Externa (profissional / demo) | Interna (admin / curadoria) |
|---|---|---|
| Resposta documentada, fundamentação, estrutura | **Sim** | **Sim** |
| Fontes e alertas | **Sim** (sempre) | **Sim** |
| Necessidade de revisão | **Indicada quando aplicável** | Indicada |
| Detalhe técnico | Completo | Completo |
| Notas de curadoria | Não expor | Pode mostrar |
| Estado editorial / scores | Não expor | Pode mostrar |

Regra-chave: a vista externa **nunca** expõe ruído interno de curadoria (notas,
scores, estado editorial), mas **mantém a mesma qualidade profissional** e mostra
sempre fontes, alertas e a necessidade de revisão quando aplicável. "Linguagem
clara" significa rigor bem comunicado — **não** menos técnico.

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
- `review_requirement` — necessidade de revisão humana;
- `visibility_level` — a quem se mostra;
- `freshness_status` — actualidade da fonte.

Exemplo de tradução aproximada (a afinar na implementação): `SUPPORTED` →
`support_level=STRONG`, `review_requirement=NONE`; `REQUIRES_HUMAN_REVIEW` →
`review_requirement=RECOMMENDED|REQUIRED` independentemente do `support_level`.

## 9. Casos HIGH

Orientação para casos de risco elevado (ex.: `AT-FAQ-0959`, `AT-FAQ-4624`):

- **Podem** ser tecnicamente publicados no RAG (pesquisáveis);
- **Não devem** ser apresentados como resposta autónoma final sem aviso;
- **Devem** sair com `review_requirement = RECOMMENDED` ou `REQUIRED`;
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
- `review_requirement`: RECOMMENDED (keyword de risco "imóvel")
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
- Expor **`review_requirement`**.
- Desenhar **componente frontend** de resposta documentada.
- Distinguir **vistas por permissões** (profissional externo / demo / interno-admin),
  **sem** baixar a qualidade conceptual da resposta.
- Criar **testes** de resposta documentada.

## 12. Alinhamento com o já existente

- O `grounding` já produz `supportStatus`, `sources` (títulos),
  `missingInformation`, `limitations`, `requiresHumanValidation` — base natural
  para `support_level`/`review_requirement`.
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

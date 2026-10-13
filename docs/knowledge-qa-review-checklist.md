# Checklist de Revisão Humana — Knowledge Q&A (Piloto)

Aplicar a **cada** par pergunta/resposta antes de `validate` e novamente antes de
`publish`. O revisor identifica-se sempre (`reviewerName`) — fica registado na auditoria.

## Conteúdo

- [ ] A pergunta está clara e compreensível isolada do contexto original
- [ ] A resposta responde exactamente à pergunta (nem mais, nem menos)
- [ ] A resposta não afirma nada que exceda o que a fonte suporta
- [ ] O texto não contém dados pessoais (nomes, NIFs, moradas, contactos)
- [ ] O texto não contém dados de clientes nem valores de processos identificáveis

## Fonte

- [ ] Existe pelo menos uma fonte associada (obrigatório — a validação bloqueia sem fonte)
- [ ] A fonte existe e é verificável pela equipa
- [ ] A fonte é adequada ao tipo de afirmação (legislação para regras, parecer para interpretação)
- [ ] A fonte está em vigor (não revogada nem alterada)

## Factos sensíveis (verificar um a um contra a fonte)

- [ ] Taxas confirmadas
- [ ] Artigos e diplomas confirmados
- [ ] Prazos confirmados
- [ ] Valores monetários e limiares confirmados
- [ ] Datas de validade (`validFrom`/`validTo`) correctas
- [ ] Actualidade das fontes (ADR-008): verificar cada fonte oficial e registar a verificação no
      backoffice; na legislação, confirmar a versão oficial/consolidada.
      - Sem verificação a fonte é "Actualidade não confirmada" e a resposta fica limitada.
      - Revalidar até 90 dias (legislação) ou 60 dias (FAQ/orientação).
      - Matéria estável só com justificação.
      - O estado nunca se escolhe à mão.

## Classificação

- [ ] O `riskLevel` reflecte o impacto real de uma resposta errada
- [ ] `requiresHumanValidation=true` está marcado para HIGH/CRITICAL e casos ambíguos
      — efeito na resposta (ADR-007): enquanto a Q&A for fundamento usado, a resposta sugere
      Pedido de parecer (`parecerRequirement` no mínimo `SUGGESTED`). Usar `false` só quando a
      resposta automática dispensa essa sugestão.
- [ ] Efeito do `riskLevel` na resposta (ADR-007): HIGH nunca dá consulta conclusiva automática;
      CRITICAL dá Resposta-limite com Pedido de parecer obrigatório.
- [ ] O `topic`/`subtopic` estão correctos (afectam recuperação e canónicas)

## Decisão final (escolher uma)

- [ ] **Publicar** — todos os pontos acima confirmados → `validate` + `publish`
- [ ] **Quarentena** — dúvidas por resolver → manter `PENDING_REVIEW` ou marcar `NEEDS_UPDATE`, registar a dúvida em `notes`
- [ ] **Rejeitar** — resposta incorrecta, fonte inexistente ou risco inaceitável → `reject` com motivo

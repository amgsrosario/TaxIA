# Operação do Piloto — Comandos PowerShell

> Substituir `$TOKEN`, `$QA_ID` e caminhos pelos valores reais. **Nunca** colocar
> passwords ou segredos em ficheiros — usar variáveis de ambiente da sessão.
> Criado na Etapa 9A (2026-07-02).

## 1. Arranque do ambiente

```powershell
cd C:\Projetos\TaxIA

# PostgreSQL (porta 15432)
docker compose up -d

# Microserviço de embeddings (terminal próprio; ver EMBEDDINGS_SERVICE_URL)
# <comando do microserviço Python — ver repositório do serviço de embeddings>

# Backend: exige ANTHROPIC_API_KEY, SPRING_DATASOURCE_URL do piloto e KNOWLEDGEFLOW_JWT_SECRET
# (ADR-006). Sem um secret aleatório de >= 32 bytes o backend NÃO arranca contra knowledgeflow_pilot.
$env:ANTHROPIC_API_KEY = Read-Host -AsSecureString | ForEach-Object { [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($_)) }
$env:KNOWLEDGEFLOW_JWT_SECRET = Read-Host -AsSecureString | ForEach-Object { [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($_)) }
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:15432/knowledgeflow_pilot"
.\scripts\run-dev.ps1
```

**Em WSL**, o secret do piloto vive num ficheiro local fora do repositório (`~/.config/taxia/pilot-jwt.env`, permissões 600), carregado por `~/.config/taxia/start-pilot-backend.sh`. Esse script:
- define o perfil e a datasource do piloto;
- nunca imprime o secret.

O `ANTHROPIC_API_KEY` tem de estar exportado na shell antes de correr o script.

Nunca versionar, colar em chat nem imprimir o secret.

## 2. Verificar saúde

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health/readiness
Invoke-RestMethod http://localhost:8081/actuator/health/liveness
```

Readiness deve incluir `db`, `pgvector` e `aiStack` UP antes de qualquer importação.

## 3. Autenticar

```powershell
$login = @{ email = "admin@exemplo.local"; password = (Read-Host "Password") } | ConvertTo-Json
$auth = Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/v1/auth/login `
  -ContentType "application/json" -Body $login
$TOKEN = $auth.accessToken
$H = @{ Authorization = "Bearer $TOKEN" }
```

(Primeiro arranque de sempre: `bootstrap-admin` com `BOOTSTRAP_ADMIN_ENABLED=true` e
header `X-Bootstrap-Secret` — desactivar a flag imediatamente depois.)

## 4. Backup ANTES da importação (obrigatório)

```powershell
.\scripts\db\windows\backup-taxia.ps1
.\scripts\db\windows\verify-taxia-backup.ps1   # valida arquivo + SHA-256
```

### Checklist pré-importação real

- [ ] Backup criado e verificado (SHA-256 confere)
- [ ] Versão Flyway registada: `docker exec -it <pg> psql -U knowledgeflow -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"`
- [ ] Versão da aplicação registada (commit/tag)
- [ ] Organização de destino confirmada (`GET /api/v1/auth/me` → organizationId)
- [ ] Ambiente confirmado (localhost/piloto, **não produção**)
- [ ] Restauro testado pelo menos uma vez: `.\scripts\db\windows\restore-taxia.ps1`

## 5. Dry-run (sempre antes de importar)

```powershell
curl.exe -X POST http://localhost:8081/api/v1/admin/knowledge/qa/import `
  -H "Authorization: Bearer $TOKEN" `
  -F "file=@docs/templates/knowledge-qa-pilot-template.csv;type=text/csv" `
  -F 'request={"sourceSystem":"piloto-2026-07","format":"CSV","dryRun":true,"limit":0};type=application/json'
```

Analisar: `invalid` deve ser 0; rever todos os `issues` de tipo `WARNING` e
`POTENTIAL_CONFLICT` antes de prosseguir.

## 6. Importação real

```powershell
curl.exe -X POST http://localhost:8081/api/v1/admin/knowledge/qa/import `
  -H "Authorization: Bearer $TOKEN" `
  -F "file=@docs/templates/knowledge-qa-pilot-template.csv;type=text/csv" `
  -F 'request={"sourceSystem":"piloto-2026-07","format":"CSV","dryRun":false,"limit":0};type=application/json'
```

## 7. Curadoria

```powershell
# Listar importados
Invoke-RestMethod -Headers $H "http://localhost:8081/api/v1/admin/knowledge/qa?status=IMPORTED&size=50"

# Passar a PENDING_REVIEW
Invoke-RestMethod -Method Post -Headers $H "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/pending-review"

# Preencher resposta curada
Invoke-RestMethod -Method Patch -Headers $H -ContentType "application/json" `
  -Uri "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/curation" `
  -Body '{"shortAnswer":"Resposta validada.","riskLevel":"MEDIUM","requiresHumanValidation":false}'

# Associar fonte detalhada (obrigatória antes de validar)
Invoke-RestMethod -Method Post -Headers $H -ContentType "application/json" `
  -Uri "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/sources" `
  -Body '{"sourceType":"INTERNAL_OPINION","title":"Parecer Interno n. X","legalReference":null}'

# Validar (nome do especialista fica na auditoria)
Invoke-RestMethod -Method Post -Headers $H `
  "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/validate?reviewerName=nome.revisor"
```

## 8. Publicação, reindexação e RAG

```powershell
# Publicar (cria embedding, entra no RAG)
Invoke-RestMethod -Method Post -Headers $H `
  "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/publish?publisherName=nome.publicador"

# Reindexar (reprocessamento idempotente, só publicadas)
Invoke-RestMethod -Method Post -Headers $H "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/reindex"

# Testar RAG + grounding
Invoke-RestMethod -Method Post -Headers $H -ContentType "application/json" `
  -Uri "http://localhost:8081/api/v1/admin/ai/ask" `
  -Body '{"question":"Qual e o limite ficticio do regime simplificado de teste?"}'
```

Verificar na resposta: `supportStatus`, `sources`, `providerCalled`, `responseRejected`.

## 9. Arquivo e auditoria

```powershell
# Arquivar (despublicar → outdated → archive)
Invoke-RestMethod -Method Post -Headers $H "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/unpublish"
Invoke-RestMethod -Method Post -Headers $H "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/outdated"
Invoke-RestMethod -Method Post -Headers $H "http://localhost:8081/api/v1/admin/knowledge/qa/$QA_ID/archive"

# Auditoria (via BD, inclui previousStatus/newStatus nos detalhes)
docker exec -it <pg> psql -U knowledgeflow -c `
  "SELECT action, occurred_at, metadata FROM audit_events WHERE entity_id = '$QA_ID' ORDER BY occurred_at"
```

## 10. Restauro em caso de necessidade

```powershell
# Parar o backend primeiro; depois:
.\scripts\db\windows\restore-taxia.ps1
# Verificar Flyway e reiniciar o backend; confirmar readiness antes de retomar
```

## 11. Credenciais e sessões (ADR-006)

### Secret JWT

- **Gerar** (WSL/Linux): `openssl rand -base64 48`. Gravar directamente no ficheiro local do secret, sem o mostrar.
- **Rodar o secret:**
  1. Parar o backend.
  2. Substituir o valor no ficheiro local.
  3. Reiniciar.
  4. Confirmar o login.

  A rotação invalida **todos** os tokens, staff e portal. Usar em caso de exposição ou suspeita sobre o secret.
- **Confirmar a presença sem revelar o valor:**
  `[ -n "$KNOWLEDGEFLOW_JWT_SECRET" ] && echo definido || echo AUSENTE`.

### Password e sessões (backoffice)

- **O próprio:** Perfil → "Alterar password" ou "Terminar todas as sessões".
- **ADMIN:** Utilizadores → "Invalidar sessões", "Repor password" (temporária, entregue fora de banda; só contas não-ADMIN), "Desactivar"/"Reactivar". O motivo é obrigatório.
- **Contas ADMIN nunca são repostas por outro ADMIN.** Um ADMIN autenticado usa Perfil → "Alterar password". Um ADMIN sem acesso usa o break-glass (abaixo).
- **ADMIN desactivado que perdeu a password:**
  1. Outro ADMIN reactiva a conta.
  2. O titular usa o break-glass (o break-glass exige um ADMIN activo).
- **Conceder ADMIN** é recusado enquanto a conta tiver uma password temporária pendente.
- **Rotação da password do admin do piloto** (rollout V17, acção humana):
  1. Entrar no backoffice.
  2. Perfil → "Alterar password".
  3. Confirmar que o token antigo deixou de funcionar e que o novo login funciona.
  4. Registar a operação no documento de estado, sem a password.

### Break-glass (ADMIN sem acesso)

Último recurso controlado. Nunca usar `bootstrap-admin`, nem SQL directo salvo blocker real.

1. Fazer backup da base do piloto (secção 4). O launcher não aplica migrações (Flyway desligado). Se o schema do piloto estiver atrás do jar (por exemplo, sem a V17), o arranque falha antes de qualquer escrita. Primeiro faz-se o rollout governado da migração.
2. Ter o ambiente do piloto definido: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `KNOWLEDGEFLOW_JWT_SECRET` e a decisão de provider de IA do perfil `pilot`.
3. Construir o jar: `mvn -q -DskipTests package`.
4. Executar **num terminal interactivo**. A password é pedida duas vezes, sem eco.
   ```bash
   BREAK_GLASS_ENABLED=true java -cp target/knowledgeflow-backend-*.jar \
     -Dloader.main=com.knowledgeflow.users.credentials.StaffAdminRecoveryLauncher \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     admin-recover --email piloto.admin@taxia.local --reason "motivo"
   ```
5. Confirmar o resultado:
   - `RESULT=RECOVERED`: o ADMIN entra com a password temporária e o backoffice obriga a mudá-la.
   - `RESULT=BLOCKED`: nada foi escrito (nem dados nem schema); o motivo é indicado.
6. Não deixar `BREAK_GLASS_ENABLED` exportado. Registar a operação (data, motivo, operador) no documento de estado.

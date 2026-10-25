# FIAP Tech Challenge 4

## Contratos

- [BE-01 — contrato da API de avaliações](docs/contratos/be-01-avaliacoes.md)
- [OpenAPI](docs/contratos/be-01-avaliacoes.openapi.yaml)

## Frontend (FE-01 e FE-02)

O frontend usa React, TypeScript e Vite. Em modo HTTP, envia avaliações ao BE-02 com `POST /avaliacao`, corpo JSON e cabeçalho `Idempotency-Key`; não contém credenciais AWS. A URL da API é configurada por `VITE_API_BASE_URL` (Compose usa `http://localhost:${BACKEND_PORT}`).

```bash
# Crie o arquivo apenas na primeira configuração para preservar um token já salvo.
test -f .env || cp .env.example .env
# Preencha LOCALSTACK_AUTH_TOKEN para iniciar o perfil aws.
docker compose --profile aws up --build
```

Acesse `http://localhost:5174`. Esse perfil inicia o frontend, backend Quarkus e LocalStack, com DynamoDB local. A API autoriza CORS para a origem configurada por `FRONTEND_PORT`. Se a porta estiver ocupada, altere `FRONTEND_PORT` no `.env`; se a porta da API estiver ocupada, altere `BACKEND_PORT`. O Vite atualiza a página quando os arquivos do frontend mudam.

Para executar apenas a tela com respostas simuladas, configure `VITE_API_MODE=mock` no `.env` e execute:

```bash
docker compose up --build frontend
```

O seletor **Simulação local** só aparece nesse modo. Para voltar à integração HTTP, configure `VITE_API_MODE=http` e reinicie o contêiner. Para iniciar somente o LocalStack e criar a tabela local, use `docker compose --profile aws up -d localstack`.

Se o navegador informar erro de CORS após uma alteração no backend, recrie o serviço para carregar as configurações atuais:

```bash
docker compose --profile aws up -d --build --force-recreate backend
curl -i -X OPTIONS 'http://localhost:8080/avaliacao' \
  -H 'Origin: http://localhost:5174' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: content-type,idempotency-key'
```

A resposta de preflight deve incluir `Access-Control-Allow-Origin: http://localhost:5174`. Se `FRONTEND_PORT` ou a origem do navegador for diferente, ajuste `CORS_ORIGINS` no `.env` para corresponder exatamente à origem do navegador.

Os backends seguem a estrutura em camadas simples:

| Pasta | Responsabilidade | Exemplo no projeto |
|---|---|---|
| `controller` | Endpoints REST e entrada da Lambda | `AvaliacaoResource`, `NotificarAvaliacaoCriticaHandler` |
| `service` | Regras e casos de uso | `RegistrarAvaliacao`, `ProcessarNotificacoes` |
| `model` | Entidades e modelos do sistema | `Avaliacao`, `Urgencia`, `AvaliacaoCritica` |
| `repository` | Interfaces de acesso a dados | `AvaliacaoRepository`, `RepositorioNotificacoes` |
| `infrastructure/persistence` | Implementações dos repositórios | Repositórios DynamoDB |
| `infrastructure/config` | Clientes e configuração técnica | Producers dos clientes AWS |
| `infrastructure/client` | Comunicação com serviços externos | Adaptador de envio pelo SES |
| `infrastructure/security` | Autenticação e autorização | Sem implementação: o formulário é anônimo; permissões AWS ficam na infraestrutura |

O módulo `receber-avaliacao` usa as pastas aplicáveis; `notificar-avaliacao-critica` segue a mesma organização para o handler de Stream. Pastas de responsabilidade sem implementação não são criadas artificialmente. O LocalStack cria a tabela `avaliacoes` com chave de partição `id` e Streams habilitado. A API local usa o servidor de eventos do Quarkus para simular o evento HTTP da Lambda; não é necessária uma conta AWS.

Exemplo de chamada local:

```bash
curl -X POST 'http://localhost:8080/avaliacao' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 2f1e8e77-967d-4eeb-b7b7-5ab3be62bb39' \
  -d '{"descricao":"A explicação foi clara e os exemplos ajudaram.","nota":8}'
```

Um evento HTTP API v2 de exemplo está em `events/be-02-api-gateway-v2.json`. As configurações DynamoDB do backend são `DYNAMODB_ENDPOINT`, `AWS_REGION` e `AVALIACOES_TABLE`; no Compose, o endpoint usa `http://localstack:4566`, com credenciais fictícias `test`.

Na arquitetura AWS planejada, o build estático do Vite será publicado no S3 e entregue pelo CloudFront. Essa hospedagem é independente do contêiner local e do LocalStack.

## Ambiente AWS local

O perfil `aws` do Docker Compose inicia o LocalStack para desenvolvimento e testes locais dos serviços AWS usados pela aplicação: API Gateway, DynamoDB, EventBridge, Scheduler, Lambda, S3 e SES. Os dados ficam em um volume Docker persistente.

Pré-requisitos: Docker Engine, Docker Compose v2 e uma conta gratuita do LocalStack para obter o token de autenticação. **Não é necessária uma conta AWS.** Gere o token no [painel do LocalStack](https://app.localstack.cloud), crie `.env` a partir de `.env.example` somente se ainda não existir e preencha `LOCALSTACK_AUTH_TOKEN`. O `.env` é ignorado pelo Git.

```bash
cp .env.example .env
# Preencha LOCALSTACK_AUTH_TOKEN no arquivo .env antes de iniciar o perfil aws.
docker compose --profile aws up -d localstack
docker compose ps
```

O endpoint local é `http://localhost:4566`, na região `us-east-1`. Para chamadas com AWS CLI, use as credenciais fictícias `test` / `test` e passe `--endpoint-url http://localhost:4566`. Elas são aceitas pelo emulador e não autenticam nem acessam uma conta AWS real. Exemplo:

```bash
aws --endpoint-url http://localhost:4566 dynamodb list-tables --region us-east-1
```

Ao executar a aplicação diretamente no host, configure os clientes AWS com endpoint `http://localhost:4566` e credenciais de desenvolvimento. De dentro de outro serviço Compose, use `http://localstack:4566`.

```bash
docker compose logs -f localstack
docker compose down
```

`docker compose down` preserva o volume de dados. Para apagar também os dados locais e recriar o ambiente do zero, execute `docker compose down -v`.

Este ambiente fornece emulação dos serviços AWS. Os hooks locais criam as tabelas `avaliacoes` e `notificacoes-avaliacoes`, as filas SQS de falhas, o bucket local e as identidades SES de desenvolvimento. Buckets e os recursos AWS de produção continuam sob responsabilidade da infraestrutura SAM.

## Backend BE-03 — alertar avaliação crítica

O módulo `notificar-avaliacao-critica` contém a Lambda Quarkus que recebe lotes do DynamoDB Streams. Somente eventos `INSERT` com nota de 0 a 4 e urgência `CRITICA` enviam e-mail pelo SES. O destinatário (`ADMIN_EMAIL`) e o remetente (`SES_FROM_EMAIL`) são configurados por ambiente. Em produção, configure no SES o mesmo destinatário aprovado para os resumos; não use os endereços de exemplo do Compose.

O módulo registra cada `eventID` na tabela `notificacoes-avaliacoes`, com uma reserva temporária para impedir processamento concorrente e estado `SENT` após o envio. Falhas retornadas pelo SES liberam a reserva e são propagadas para permitir nova tentativa. Se o SES aceitar o e-mail e a execução falhar antes de gravar `SENT`, um reprocessamento posterior ainda poderá enviar uma duplicata; SES não oferece chave de idempotência para eliminar essa janela. Os registros de deduplicação permanecem na tabela até uma política de retenção ser definida.

Para invocar manualmente a Lambda em modo de desenvolvimento e inspecionar o e-mail no LocalStack:

```bash
docker compose --profile aws up -d --build localstack notifier
# Se o LocalStack já estava ativo antes da inclusão do hook BE-03:
docker compose exec localstack bash /etc/localstack/init/ready.d/02-configure-alerts.sh
curl -i -X POST "http://localhost:${NOTIFIER_PORT:-8082}/_lambda_" \
  -H 'Content-Type: application/json' \
  --data-binary @events/be-03-dynamodb-stream.json
curl 'http://localhost:4566/_aws/ses?email=alertas@example.com'
```

O arquivo `events/be-03-dynamodb-stream.json` representa o evento `INSERT` crítico. Para exercitar a deduplicação, repita a chamada: o mesmo `eventID` não deve gerar outro envio. A invocação por fixture simula o evento de stream no handler; o mapeamento automático do DynamoDB Streams para Lambda, política de tentativas e fila de falhas fazem parte de INF-03.

## Infraestrutura INF-03 — acionamento e recuperação do Stream

O `template.yaml` declara os recursos AWS para o fluxo: tabela `avaliacoes` com Stream `NEW_IMAGE`, Lambda `notificar-avaliacao-critica`, integração DynamoDB→Lambda, tabela de deduplicação, identidades SES e fila SQS `notificar-avaliacao-critica-falhas`. O filtro encaminha somente eventos `INSERT`. A configuração usa lote de 1 registro, 3 tentativas adicionais, idade máxima de 86.400 segundos (24 horas, limite de retenção do DynamoDB Streams) e retenção de 1.209.600 segundos (14 dias) na fila. A retenção SQS é ajustável por `FAILURE_QUEUE_RETENTION_SECONDS`/parâmetro SAM.

Para compilar e configurar o mapeamento automático no LocalStack (não requer conta AWS):

```bash
docker compose --profile aws up -d localstack
./scripts/configurar-stream-local.sh
```

O script compila a Lambda, cria/atualiza sua função e o mapeamento do Stream, com a role local e destino SQS. Depois, uma avaliação crítica inserida na tabela `avaliacoes` deve acionar o envio SES automaticamente. Acompanhe a execução com `docker compose logs -f localstack`; consulte mensagens aceitas pelo SES em `http://localhost:4566/_aws/ses?email=admin@example.com` e a fila via AWS CLI apontando para `http://localhost:4566`.

Quando as tentativas se esgotam, a mensagem enviada ao SQS contém metadados de falha e referência do lote do Stream (`streamArn`, `shardId` e números de sequência); ela **não contém necessariamente os dados completos da avaliação**. O script de recuperação busca o registro original no Stream, invoca a Lambda novamente e apaga a mensagem SQS somente depois de uma invocação bem-sucedida:

```bash
./scripts/reprocessar-falha-stream-local.sh
```

Execute a recuperação dentro das 24 horas de retenção do Stream. A fila mantém os metadados por até 14 dias, mas isso não estende a retenção do registro original; se ele já expirou, o script mantém a mensagem na fila e informa que não pode recuperar o conteúdo automaticamente. Antes de reprocessar, corrija a causa da falha. A tabela de idempotência reduz reenvios, mas não elimina a janela de duplicidade entre o aceite do e-mail pelo SES e a gravação do estado `SENT`.

O `template.yaml` também permite build/deploy AWS com SAM depois de configurar região, `AdminEmail` e `SesFromEmail`. Em AWS, o remetente deve estar verificado no SES e, enquanto a conta estiver no sandbox, os destinatários também precisam atender às restrições do SES. Este trabalho configurou e validou o fluxo no LocalStack; não fez deploy em uma conta AWS.

## Backend BE-04 — gerar relatório semanal

O módulo `gerar-relatorio-semanal` recebe eventos `SEMANAL` ou `MANUAL`. Para `SEMANAL`, calcula a semana anterior completa de segunda-feira a domingo em `America/Sao_Paulo`; `dataReferencia` deve vir no evento do Scheduler para que uma repetição use o mesmo período. Para `MANUAL`, aceite `dataInicio`, `dataFim` ou ambos no formato `YYYY-MM-DD`: somente início consulta até hoje; somente fim consulta todo o histórico até esse dia; ambos incluem os dias inicial e final. Sem datas, com data inválida ou início posterior ao fim, a Lambda falha antes de consultar os registros.

O repositório lê todas as páginas de `avaliacoes` usando `Scan` consistente e filtra os instantes no serviço. Isso é simples para o volume acadêmico atual; o modelo ainda precisa de uma decisão de escala (D09), como um índice de data. O CSV contém uma linha `RESUMO`, linhas `POR_URGENCIA`, linhas `POR_DIA` e as linhas `AVALIACAO`. Nota média vazia aparece como “não se aplica”. O arquivo vai para bucket S3 privado com SSE-S3; o e-mail SES contém período e agregados, sem link público.

O campo `geracaoId` distingue uma nova solicitação de tentativas técnicas: repetições da mesma geração reutilizam a mesma chave S3 e o registro de controle no DynamoDB; uma execução manual nova recebe outro ID. O controle usa reserva com lease, gravando estado `SENT` depois do e-mail. Existe uma janela de duplicidade se o SES aceitar o envio e a atualização final no DynamoDB falhar. A tabela `geracoes-relatorios` é mantida até que uma política de retenção seja definida.

Para executar localmente, atualize o `.env` a partir do `.env.example` se ainda não existir e inicie o LocalStack e o serviço:

```bash
docker compose --profile aws up -d localstack reporter
# Se o LocalStack já estava ativo antes da inclusão das tabelas/bucket do relatório:
docker compose exec localstack bash /etc/localstack/init/ready.d/02-configure-alerts.sh
```

Use o script para disparar os eventos localmente:

```bash
# Cria 28 avaliações na semana alvo e 2 fora dos limites para conferir o filtro
./scripts/carregar-massa-avaliacoes-local.sh

# Semana anterior completa
./scripts/disparar-relatorio-local.sh --semanal

# Semana relativa a um instante específico (ISO-8601)
./scripts/disparar-relatorio-local.sh --semanal --referencia 2026-10-19T11:00:00Z

# Intervalo manual com as duas datas
./scripts/disparar-relatorio-local.sh --manual \
  --inicio 2026-10-01 --fim 2026-10-07 --id local-manual-001

# Também aceita somente uma data: início até hoje ou histórico até a data final
./scripts/disparar-relatorio-local.sh --manual --inicio 2026-10-01
```

Sem `--id`, o script gera e imprime um `geracaoId`; copie esse valor para `--id` ao repetir a mesma solicitação. Ele também aceita `REPORT_PORT` ou `REPORT_URL` para apontar a outra porta do serviço. Confira o bucket e o e-mail simulado:

O carregador usa UUIDs determinísticos e pode ser executado novamente sem criar IDs novos. Ele inclui notas críticas; se o mapeamento BE-03 estiver ativo no LocalStack, essas inserções também podem gerar alertas na caixa SES simulada.

```bash
aws --endpoint-url http://localhost:4566 s3 ls s3://fiap-feedback-relatorios-local/ --recursive
curl 'http://localhost:4566/_aws/ses?email=admin@example.com'
```

## Infraestrutura INF-04 — agendar relatório semanal

O `template.yaml` configura o EventBridge Scheduler para invocar `gerar-relatorio-semanal` toda segunda-feira às 08:00 no fuso `America/Sao_Paulo`, com janela flexível desativada. O payload inclui o horário agendado no campo `dataReferencia`; assim, retries continuam associados à semana originalmente solicitada. O evento é entregue de forma assíncrona à Lambda.

Há duas políticas de retry e duas filas SQS, pois correspondem a etapas distintas:

- O Scheduler tenta entregar o evento por até 3 tentativas adicionais e 1 hora. Falhas de entrega ficam em `gerar-relatorio-agendamento-falhas`.
- Depois da entrega, a Lambda tenta novamente até 2 vezes e mantém o evento por até 1 hora. Falhas de processamento ficam em `gerar-relatorio-execucao-falhas`.

As duas filas retêm mensagens por até 14 dias, configuráveis pelo parâmetro `FailureQueueRetentionSeconds`. O IAM do Scheduler permite invocar somente a Lambda de relatório e gravar somente na fila de falha de entrega. A role da Lambda pode gravar somente na fila de falha de execução, além dos acessos do relatório já descritos acima. As mensagens são evidências para investigação; elas não reprocessam automaticamente por si só.

Para configurar e executar no LocalStack, sem conta AWS, compile a Lambda e crie o agendamento semanal:

```bash
docker compose --profile aws up -d localstack
./scripts/configurar-agendamento-relatorio-local.sh
```

Para demonstrar o agendamento sem aguardar segunda-feira às 08:00, crie uma agenda temporária que dispara a cada minuto:

```bash
./scripts/configurar-agendamento-relatorio-local.sh --demo
docker compose exec localstack awslocal scheduler get-schedule --name gerar-relatorio-semanal-local
docker compose logs -f localstack
```

Após a execução, confira o CSV no bucket e o resumo SES usando os comandos da seção BE-04. O modo `--demo` atualiza a agenda local existente para `rate(1 minute)`; para restaurar o cron semanal, execute novamente o script sem opções. Para remover somente a agenda de demonstração: `docker compose exec localstack awslocal scheduler delete-schedule --name gerar-relatorio-semanal-local`.

No ambiente AWS, compile com `./gradlew --no-daemon :gerar-relatorio-semanal:quarkusBuild` e publique a stack SAM com os parâmetros `AdminEmail` e `SesFromEmail`. O horário pode ser substituído pelo parâmetro `ReportScheduleExpression` em deploys de validação. Verifique a agenda em EventBridge Scheduler e monitore a Lambda em CloudWatch Logs; consulte as filas `ReportScheduleDeliveryFailureQueueUrl` e `ReportExecutionFailureQueueUrl` nas saídas do stack quando houver falhas. O deploy AWS real e a execução na conta ainda precisam ser realizados pela equipe responsável.

## BE-05 / INF-07 — disparo manual por GitHub Actions

O workflow `.github/workflows/relatorio-manual.yml` adiciona “Run workflow” com datas opcionais. Ele rejeita datas ausentes ou malformadas, obtém credenciais temporárias por OIDC e invoca `gerar-relatorio-semanal` de modo síncrono. A Lambda valida novamente o evento, aplica as regras de período e devolve estado, período, contagem e chave S3 no sumário da execução. O identificador `github.run_id` é reutilizado nas tentativas da mesma execução e uma nova execução cria outro relatório.

Antes de habilitar o workflow na AWS:

1. Compile o artefato e faça deploy do `template.yaml` com `AdminEmail` e `SesFromEmail`; obtenha `WeeklyReportGeneratorName` e `ReportsBucketName` nas saídas do stack.
2. Garanta que a conta tenha o provedor OIDC `token.actions.githubusercontent.com`. Crie uma role usando `docs/infra/inf-07-github-oidc-trust-policy.json` (substitua `ACCOUNT_ID`). O `sub` limita a role ao repositório `Machadofabio/fiap-tech-challenge-4` e ao ambiente GitHub `dev`.
3. Anexe somente a política de invocação em `docs/infra/inf-07-github-invoke-policy.json`, substituindo `ACCOUNT_ID`.
4. No ambiente GitHub `dev`, configure as variáveis `AWS_ROLE_ARN`, `AWS_REGION=us-east-1` e `REPORT_FUNCTION_NAME` com as saídas do stack. Restrinja o ambiente à branch `main` e aos operadores autorizados (por exemplo, com reviewers obrigatórios), pois a trust policy limita o token ao ambiente `dev`.

Exemplo de build local do artefato Quarkus antes do deploy SAM:

```bash
./gradlew --no-daemon :gerar-relatorio-semanal:quarkusBuild
sam deploy --guided --parameter-overrides \
  AdminEmail=admin@example.com SesFromEmail=alertas@example.com
```

O arquivo do workflow não contém chaves AWS. A role de CI só precisa de `lambda:InvokeFunction` para a Lambda do relatório; S3, DynamoDB e SES permanecem acessíveis apenas à role da Lambda. A ação manual real depende de o integrante de infraestrutura provisionar o provedor OIDC/role, configurar o ambiente GitHub e executar o deploy AWS. O fluxo local foi incluído no Compose; este repositório não foi conectado nem executado em uma conta AWS.

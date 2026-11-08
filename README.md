# FIAP Tech Challenge 4

Aplicação para receber avaliações, alertar sobre avaliações críticas e gerar relatórios semanais. O frontend usa React, TypeScript e Vite; os serviços backend usam Java e Quarkus.

## Rodar localmente

Pré-requisitos: Docker Engine e Docker Compose v2. Para iniciar o ambiente completo, crie `.env` uma única vez, informe o token do LocalStack e suba os serviços:

```bash
test -f .env || cp .env.example .env
# Preencha LOCALSTACK_AUTH_TOKEN no arquivo .env.
docker compose --profile aws up --build
```

Abra `http://localhost:5174`. O frontend envia avaliações para `http://localhost:8080`. O perfil também inicia o LocalStack, que fornece os serviços AWS simulados. Não é necessária uma conta AWS.

Se alguma porta estiver ocupada, ajuste `FRONTEND_PORT`, `BACKEND_PORT`, `NOTIFIER_PORT` ou `REPORT_PORT` no `.env` e reinicie os serviços.

Para abrir o frontend sem conectar ao backend, configure `VITE_API_MODE=mock` no `.env` e rode:

```bash
docker compose up --build frontend
```

## API de avaliações

O endpoint `POST /avaliacao` recebe uma avaliação com descrição e nota. Exemplo:

```bash
curl -X POST 'http://localhost:8080/avaliacao' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 2f1e8e77-967d-4eeb-b7b7-5ab3be62bb39' \
  -d '{"descricao":"A explicação foi clara e os exemplos ajudaram.","nota":8}'
```

O cabeçalho `Idempotency-Key` identifica uma solicitação para evitar que uma repetição crie outra avaliação. O contrato e a especificação OpenAPI estão em [`docs/contratos`](docs/contratos/).

Na AWS, a rota pública `POST /avaliacao` usa limite global inicial de 2 requisições por segundo e rajada de 5, configurados pelos parâmetros `EvaluationThrottleRateLimit` e `EvaluationThrottleBurstLimit` no template SAM. Se a API responder `429`, o formulário informa que há muitos envios no momento, mantém a descrição e a nota e permite tentar novamente manualmente. O mesmo identificador de idempotência é preservado na tentativa.

Para acompanhar o volume e os erros, abra API Gateway na região do ambiente, selecione a HTTP API e consulte as métricas no CloudWatch (`Count` e `4xx`). `4xx` agrega diferentes erros e não isola `429`; por isso, as métricas servem para observar tendências, não para contar com precisão somente os bloqueios. O limite é global e compartilhado entre visitantes, aplicado por melhor esforço. A validação do `429` deve ser feita na AWS com carga pequena e controlada.

Se o navegador apresentar erro de CORS depois de uma alteração, recrie o backend:

```bash
docker compose --profile aws up -d --build --force-recreate backend
```

## LocalStack

O LocalStack simula DynamoDB, S3, SES, SQS, Lambda e EventBridge Scheduler. O token é obtido no [painel do LocalStack](https://app.localstack.cloud); as credenciais AWS usadas localmente são fictícias (`test` / `test`).

```bash
docker compose --profile aws up -d localstack
docker compose ps
```

O endpoint local é `http://localhost:4566`. Os dados ficam em volume Docker e são preservados por `docker compose down`. Para apagar os dados e recomeçar, use `docker compose down -v`.

O `template.yaml` é aplicado na AWS. No ambiente local, os pacotes das três funções são compilados e registrados automaticamente quando você executa `docker compose --profile aws up --build`. O Docker Compose compila os pacotes Java em um serviço temporário; ao iniciar, o LocalStack configura as Lambdas, a integração com DynamoDB Streams e o agendamento semanal.

```bash
docker compose --profile aws up --build
docker compose exec localstack awslocal lambda list-functions
```

Após alterações no código Java, rode novamente `docker compose --profile aws up --build` para recompilar e atualizar as funções. Para consultar a execução da configuração, use `docker compose logs localstack`.

O frontend local continua enviando avaliações ao contêiner `backend` do Compose. A licença LocalStack configurada nesta máquina não inclui API Gateway v2, então a integração HTTP API → Lambda do template SAM não fica ativa localmente. A Lambda de recebimento aparece no serviço Lambda e pode ser invocada diretamente; o endpoint HTTP local permanece no serviço Quarkus.

## Alertas de avaliação crítica

Avaliações com nota de 0 a 4 e urgência crítica enviam um e-mail simulado pelo SES. A integração entre DynamoDB Streams e Lambda é configurada automaticamente ao iniciar o perfil `aws`.

O remetente e o destinatário são definidos pelas variáveis `SES_FROM_EMAIL` e `ADMIN_EMAIL` no `.env`. Para consultar e-mails aceitos pelo LocalStack:

```bash
curl 'http://localhost:4566/_aws/ses?email=alertas@example.com'
```

O filtro `email` procura o remetente (`SES_FROM_EMAIL`), não o destinatário. Sem o parâmetro `email`, o endpoint lista todas as mensagens.

Se o processamento falhar, os detalhes ficam na fila SQS de falhas. O script abaixo tenta recuperar e processar novamente o registro original:

```bash
./scripts/reprocessar-falha-stream-local.sh
```

## Relatórios

O relatório semanal considera a última semana completa, de segunda a domingo, no fuso `America/Sao_Paulo`. Ele gera um CSV no bucket S3 local e envia um resumo pelo SES. Para um intervalo escolhido, use o relatório manual.

Para incluir a semana atual no relatório manual, execute os comandos nesta ordem. O primeiro inicia o LocalStack; o segundo carrega 4 avaliações por dia e 2 registros de borda; o terceiro gera o CSV e envia o resumo pelo SES:

```bash
docker compose --profile aws up -d localstack
./scripts/carregar-massa-avaliacoes-local.sh --semana-atual
docker compose --profile aws up -d reporter
./scripts/disparar-relatorio-local.sh --manual \
  --inicio 2026-10-26 --fim 2026-11-01
```

As datas `2026-10-26` e `2026-11-01` são o intervalo da semana atual neste exemplo; ajuste-as para a segunda e o domingo da semana que deseja consultar. A resposta do script mostra o status, a quantidade de avaliações e a chave do CSV criado. Para gerar dados da última semana completa, omitindo a opção `--semana-atual`, e produzir o relatório semanal automaticamente, use `./scripts/carregar-massa-avaliacoes-local.sh` seguido de `./scripts/disparar-relatorio-local.sh --semanal`.

Para gerar um relatório de datas específicas:

```bash
./scripts/disparar-relatorio-local.sh --manual \
  --inicio 2026-10-01 --fim 2026-10-07
```

Sem `--id`, o script cria um identificador novo a cada execução. Se você informar o mesmo `--id` de um relatório já concluído, a solicitação repetida não gera outro e-mail. Para testar novamente com um ID manual, use um valor diferente, por exemplo `--id local-manual-002`.

Depois que o relatório terminar, estes comandos mostram onde conferir os resultados:

```bash
docker compose exec localstack awslocal s3 ls s3://fiap-feedback-relatorios-local/ --recursive
curl 'http://localhost:4566/_aws/ses?email=alertas@example.com'
```

O primeiro comando lista os arquivos CSV guardados no S3 simulado pelo LocalStack. O segundo mostra os e-mails enviados pelo remetente `alertas@example.com` para `admin@example.com`. Nesse endpoint do LocalStack, o parâmetro `email` filtra pelo remetente (`SES_FROM_EMAIL`), não pelo destinatário (`ADMIN_EMAIL`). Se quiser listar todos os e-mails, remova `?email=alertas@example.com` da URL.

## Agendamento semanal

O EventBridge Scheduler local é configurado automaticamente para segunda-feira às 08h ao iniciar o perfil `aws`. Para demonstrar sem esperar o horário semanal, use uma agenda que dispara a cada minuto:

```bash
./scripts/configurar-agendamento-relatorio-local.sh --demo
docker compose exec localstack awslocal scheduler get-schedule --name gerar-relatorio-semanal-local
```

Depois do teste, restaure a agenda semanal rodando o script sem opções. Para remover a agenda local:

```bash
docker compose exec localstack awslocal scheduler delete-schedule --name gerar-relatorio-semanal-local
```

O Scheduler e a Lambda têm políticas de tentativas separadas. Falhas ao entregar a invocação e falhas durante o processamento são registradas em filas SQS diferentes.

## Publicação na AWS

O workflow [Deploy AWS and GitHub Pages](.github/workflows/deploy-aws.yml) compila as Lambdas e usa `sam deploy` para criar ou atualizar o backend definido em `template.yaml`. Depois compila o frontend Vite com a URL da API e publica no GitHub Pages por HTTPS. Ele roda em push para `main` ou pode ser iniciado manualmente em **Actions → Deploy AWS and GitHub Pages → Run workflow**. Em **Settings → Pages**, configure **Source: GitHub Actions**.

Como os recursos existentes foram criados manualmente, eles não serão adotados automaticamente pela stack. Recursos com nomes iguais aos do template podem causar conflito; outros podem ser criados em duplicidade. O passo a passo de OIDC, variáveis do GitHub e essa observação estão em [`docs/infra/github-actions-deploy.md`](docs/infra/github-actions-deploy.md). O workflow usa credenciais temporárias via OIDC; não precisa de access key nem secret key.

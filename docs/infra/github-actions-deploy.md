# Deploy com GitHub Actions

O workflow [deploy-aws.yml](../../.github/workflows/deploy-aws.yml) compila e publica o backend na AWS com SAM e publica o frontend Vite no GitHub Pages. Ele roda em push para `main` ou pode ser iniciado manualmente em **Actions → Deploy AWS and GitHub Pages → Run workflow**.

O template AWS não cria recursos de hospedagem do frontend nem distribuição CloudFront. O frontend é servido pelo GitHub Pages em HTTPS; a API e os serviços backend continuam na AWS.

## Configurar GitHub Pages

No repositório, abra **Settings → Pages** e selecione **GitHub Actions** como origem do build e deploy. Para repositórios públicos, o GitHub Pages está disponível no GitHub Free. A URL deste repositório será `https://machadofabio.github.io/fiap-tech-challenge-4/`.

O template define o parâmetro `FrontendOrigin` como `https://machadofabio.github.io`, que é a origem liberada no CORS da API. Se o proprietário ou endereço do Pages mudar, atualize o valor padrão desse parâmetro em [template.yaml](../../template.yaml).

## Configurar OIDC e a role AWS

1. Em IAM → Identity providers, crie ou reutilize o provedor OIDC com URL `https://token.actions.githubusercontent.com` e audiência `sts.amazonaws.com`.
2. Crie a role de deploy, por exemplo `GitHubActionsDeployRole`, usando [github-actions-deploy-trust-policy.json](github-actions-deploy-trust-policy.json) como política de confiança. A trust policy deve restringir o acesso ao repositório e ao ambiente GitHub `dev`.
3. Anexe [github-actions-deploy-policy.json](github-actions-deploy-policy.json) à role como política de permissões. Ela permite o deploy da stack backend e dos artefatos SAM. O frontend não é enviado para AWS.
4. Em **Settings → Environments**, configure `dev` e restrinja deploys à branch `main`.

## Variáveis do ambiente `dev`

Em **Settings → Environments → dev → Environment variables**, configure:

| Variável | Valor |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | ARN da role `GitHubActionsDeployRole` |
| `AWS_REGION` | Região AWS da stack, por exemplo `sa-east-1` |
| `AWS_STACK_NAME` | Nome da stack CloudFormation, por exemplo `fiap-tech-challenge-4-dev` |
| `ADMIN_EMAIL` | Destinatário de alertas e relatórios |
| `SES_FROM_EMAIL` | Remetente verificado no SES |
| `SAM_ARTIFACT_BUCKET` | Opcional: bucket S3 já existente, na mesma região, para armazenar os pacotes das Lambdas |

Esses valores não são chaves de acesso. Não crie `AWS_ACCESS_KEY_ID` nem `AWS_SECRET_ACCESS_KEY` no GitHub. O OIDC entrega credenciais temporárias ao job de backend.

## Disparar relatório manualmente

O workflow **Gerar relatório manual** usa o mesmo ambiente `dev` e a variável `AWS_DEPLOY_ROLE_ARN`. Por padrão, ele invoca `gerar-relatorio-semanal`; configure `REPORT_FUNCTION_NAME` somente se quiser sobrescrever esse nome. A role precisa confiar no OIDC do repositório e do ambiente `dev`, e permitir `lambda:InvokeFunction` nessa função. A região é lida de `AWS_REGION`.

Depois, abra **Actions → Gerar relatório manual → Run workflow** e informe pelo menos uma das datas (`dataInicio` ou `dataFim`) no formato `YYYY-MM-DD`.

## Etapas do workflow

1. Compila as três funções Quarkus com Gradle.
2. Reutiliza o bucket indicado em `SAM_ARTIFACT_BUCKET`, se configurado. Caso contrário, usa `fiap-tech-challenge-4-sam-artifacts-<ID-da-conta>-<região>`. O workflow cria esse bucket se estiver ausente e o passa explicitamente ao `sam deploy`; ele fica separado da stack e é reutilizado nos próximos deploys.
3. Lê o output `EvaluationsApiUrl` da stack.
4. Compila o frontend com a URL da API e a base `/fiap-tech-challenge-4/`, depois publica o build no GitHub Pages.

O job de frontend usa o ambiente GitHub `github-pages`, separado do ambiente `dev` usado para assumir a role AWS.

## Tabelas DynamoDB

`avaliacoes` continua como recurso nativo `AWS::DynamoDB::Table` da stack; esse tipo foi mantido para que o CloudFormation atualize a tabela existente sem tentar trocar o tipo do recurso. Ela é protegida contra exclusão ou substituição e usa Streams `NEW_IMAGE`. Para `notificacoes-avaliacoes` e `geracoes-relatorios`, um recurso customizado verifica se as tabelas existem na região da stack. Se faltarem, cria-as em modo sob demanda; se já existirem, reutiliza-as e valida a estrutura:

| Tabela | Chave de partição | Requisito adicional |
| --- | --- | --- |
| `avaliacoes` | `id` (String) | DynamoDB Streams habilitado com `NEW_IMAGE` |
| `notificacoes-avaliacoes` | `eventId` (String) | — |
| `geracoes-relatorios` | `generationId` (String) | — |

Nas duas tabelas verificadas pelo recurso customizado, uma chave incompatível faz o deploy falhar com uma mensagem indicando a configuração esperada. A estrutura de `avaliacoes` é declarada diretamente no recurso nativo do CloudFormation. As três tabelas são preservadas quando a stack é removida, para proteger os registros existentes. Se `avaliacoes` existir fora da stack e não tiver sido importada, o recurso nativo não consegue adotá-la automaticamente; é necessário importá-la na stack ou usar outro nome.

Nomes de tabelas DynamoDB são únicos por conta e região. Uma tabela com o mesmo nome em outra região não é detectada; o recurso customizado criará uma tabela na região da stack. `sam deploy` não importa automaticamente outros recursos criados manualmente. Recursos diferentes dessas três tabelas, com nomes fixos iguais aos já existentes, ainda podem causar conflito. Para reutilizá-los, é necessário importar recursos compatíveis para a stack CloudFormation ou alterar o template para apontar explicitamente para eles; não há uma opção geral de “ignorar tudo que já existe”.

Não apague tabelas ou buckets que contenham dados sem decidir como preservá-los.

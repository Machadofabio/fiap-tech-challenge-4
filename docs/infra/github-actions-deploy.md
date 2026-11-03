# Deploy pela GitHub Actions com SAM

O workflow [deploy-aws.yml](../../.github/workflows/deploy-aws.yml) compila as três funções Java, publica a stack do [template.yaml](../../template.yaml) com `sam deploy` e publica o frontend no bucket S3 da stack. Ele roda em push para `main` ou manualmente em **Actions → Deploy AWS → Run workflow**.

## Atenção: recursos criados manualmente

`sam deploy` não conecta automaticamente recursos criados manualmente a uma stack CloudFormation. O template tenta criar a infraestrutura descrita nele. Por exemplo, ele declara tabelas DynamoDB e filas SQS com nomes fixos e Lambdas com nomes definidos. Se já existirem recursos com esses nomes, o primeiro deploy pode falhar por conflito; outros recursos sem nome fixo podem ser criados duplicados.

Para gerenciar os recursos atuais com CloudFormation, será necessário importar os tipos compatíveis para uma stack ou adaptar o template para usar recursos existentes. Não apague tabelas ou buckets que contenham dados sem decidir antes como preservá-los. O workflow não faz essa migração; ele implanta o template como uma stack nova ou atualiza a stack já associada a esse template.

## Configurar OIDC e a role de deploy na AWS

1. Em IAM → Identity providers, crie um provedor OpenID Connect com URL `https://token.actions.githubusercontent.com` e audiência `sts.amazonaws.com`. Se já existir, reutilize-o.
2. Crie uma role IAM, por exemplo `GitHubActionsDeployRole`. Use [github-actions-deploy-trust-policy.json](github-actions-deploy-trust-policy.json) como política de confiança e substitua `ACCOUNT_ID` pelo ID da conta AWS.
3. Anexe [github-actions-deploy-policy.json](github-actions-deploy-policy.json) como política de permissões e substitua `ACCOUNT_ID`. Ela permite que SAM/CloudFormation crie e atualize os recursos do template e que o workflow publique o frontend. As ações de provisionamento têm escopo amplo (`*`); use uma conta de desenvolvimento dedicada e restrinja as permissões conforme a infraestrutura amadurecer.
4. Informe uma região para a stack e configure as identidades de remetente e destinatário do SES. O remetente precisa estar verificado; se o SES estiver no sandbox, o destinatário também precisa estar autorizado.

A trust policy restringe a role ao repositório `Machadofabio/fiap-tech-challenge-4` e ao ambiente GitHub `dev`. Em **Settings → Environments**, crie `dev` e restrinja deployments à branch `main`. Se o repositório ou ambiente tiver outro nome, atualize a condição `sub` na trust policy.

## Configurar variáveis no GitHub

Em **Settings → Environments → dev → Environment variables**, configure:

| Variável | Valor |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | ARN da role `GitHubActionsDeployRole` |
| `AWS_REGION` | Região da stack, por exemplo `us-east-1` |
| `AWS_STACK_NAME` | Nome da stack CloudFormation, por exemplo `fiap-tech-challenge-4-dev` |
| `ADMIN_EMAIL` | Destinatário de alertas e relatórios |
| `SES_FROM_EMAIL` | Remetente verificado no SES |

Esses valores não são chaves de acesso. Não crie `AWS_ACCESS_KEY_ID` nem `AWS_SECRET_ACCESS_KEY` no GitHub. O OIDC entrega credenciais temporárias ao workflow.

## Etapas do workflow

1. Compila as três funções Quarkus com Gradle.
2. Executa `sam deploy`, que cria ou atualiza a stack e usa um bucket para artefatos SAM.
3. Busca os outputs da stack, compila o frontend com a URL da API, sincroniza os arquivos no bucket S3 e invalida o cache CloudFront.

O primeiro deploy da distribuição CloudFront pode levar vários minutos. As URLs aparecem nos outputs `EvaluationsApiUrl` e `FrontendUrl` da stack.

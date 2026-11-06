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

Esses valores não são chaves de acesso. Não crie `AWS_ACCESS_KEY_ID` nem `AWS_SECRET_ACCESS_KEY` no GitHub. O OIDC entrega credenciais temporárias ao job de backend.

## Etapas do workflow

1. Compila as três funções Quarkus com Gradle.
2. Executa `sam deploy` para criar ou atualizar a stack backend e o bucket de artefatos SAM.
3. Lê o output `EvaluationsApiUrl` da stack.
4. Compila o frontend com a URL da API e a base `/fiap-tech-challenge-4/`, depois publica o build no GitHub Pages.

O job de frontend usa o ambiente GitHub `github-pages`, separado do ambiente `dev` usado para assumir a role AWS.

## Recursos AWS criados manualmente

`sam deploy` não conecta automaticamente recursos criados manualmente a uma stack CloudFormation. As tabelas `notificacoes-avaliacoes` e `geracoes-relatorios` são tratadas como recursos externos: o template usa os nomes informados em `NotificationStateTableName` e `ReportGenerationTableName`, mas não cria nem administra essas tabelas. Elas precisam ter, respectivamente, as chaves de partição `eventId` e `generationId` do tipo string. Outros recursos backend com nomes iguais aos já existentes podem causar conflito ou ser criados em duplicidade.

Para gerenciar os recursos atuais com CloudFormation, será necessário importar tipos compatíveis para uma stack ou adaptar o template para usar recursos existentes. Não apague tabelas ou buckets que contenham dados sem decidir como preservá-los.

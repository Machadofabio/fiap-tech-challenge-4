#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

env_value() {
  local key="$1" default="$2" value=""
  if [[ -f .env ]]; then
    value="$(awk -F= -v key="$key" '$1 == key { value=$2 } END { gsub(/\r$/, "", value); print value }' .env)"
  fi
  printf '%s' "${!key:-${value:-$default}}"
}

MODE="weekly"
case "${1:-}" in
  "") ;;
  --demo) MODE="demo" ;;
  -h|--help)
    cat <<'EOF'
Configura Lambda e EventBridge Scheduler do relatório no LocalStack.

Uso:
  scripts/configurar-agendamento-relatorio-local.sh [--demo]

Por padrão cria o agendamento semanal de segunda-feira às 08:00 em
America/Sao_Paulo. --demo cria um rate(1 minute) para demonstrar sem esperar.
EOF
    exit 0
    ;;
  *) echo "Opção desconhecida: $1" >&2; exit 2 ;;
esac

docker compose --profile aws up -d localstack
./gradlew --no-daemon :gerar-relatorio-semanal:quarkusBuild
docker compose cp build/gerar-relatorio-semanal/function.zip localstack:/tmp/inf-04-function.zip

docker compose exec -T \
  -e INF04_MODE="$MODE" \
  -e REPORTS_BUCKET="$(env_value REPORTS_BUCKET fiap-feedback-relatorios-local)" \
  -e REPORT_GENERATION_TABLE="$(env_value REPORT_GENERATION_TABLE geracoes-relatorios)" \
  -e AVALIACOES_TABLE="$(env_value AVALIACOES_TABLE avaliacoes)" \
  -e SES_FROM_EMAIL="$(env_value SES_FROM_EMAIL alertas@example.com)" \
  -e ADMIN_EMAIL="$(env_value ADMIN_EMAIL admin@example.com)" \
  -e FAILURE_QUEUE_RETENTION_SECONDS="$(env_value FAILURE_QUEUE_RETENTION_SECONDS 1209600)" \
  localstack bash -s <<'LOCAL'
set -euo pipefail
FUNCTION=gerar-relatorio-semanal
LAMBDA_ROLE=fiap-inf04-report-execution
SCHEDULER_ROLE=fiap-inf04-scheduler
SCHEDULE=gerar-relatorio-semanal-local
DELIVERY_QUEUE=gerar-relatorio-agendamento-falhas
EXECUTION_QUEUE=gerar-relatorio-execucao-falhas

for queue in "$DELIVERY_QUEUE" "$EXECUTION_QUEUE"; do
  if ! awslocal sqs get-queue-url --queue-name "$queue" >/dev/null 2>&1; then
    awslocal sqs create-queue --queue-name "$queue" \
      --attributes "MessageRetentionPeriod=${FAILURE_QUEUE_RETENTION_SECONDS}" >/dev/null
  fi
done
DELIVERY_URL="$(awslocal sqs get-queue-url --queue-name "$DELIVERY_QUEUE" --query QueueUrl --output text)"
DELIVERY_ARN="$(awslocal sqs get-queue-attributes --queue-url "$DELIVERY_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)"
EXECUTION_URL="$(awslocal sqs get-queue-url --queue-name "$EXECUTION_QUEUE" --query QueueUrl --output text)"
EXECUTION_ARN="$(awslocal sqs get-queue-attributes --queue-url "$EXECUTION_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)"

python3 - "$FUNCTION" "$LAMBDA_ROLE" "$SCHEDULER_ROLE" "$DELIVERY_ARN" "$EXECUTION_ARN" "$REPORTS_BUCKET" "$REPORT_GENERATION_TABLE" "$AVALIACOES_TABLE" "$SES_FROM_EMAIL" "$ADMIN_EMAIL" <<'PY'
import json, sys
function, lambda_role, scheduler_role, delivery_arn, execution_arn, bucket, report_table, evaluations_table, sender, recipient = sys.argv[1:]
account = "000000000000"
region = "us-east-1"
lambda_arn = f"arn:aws:lambda:{region}:{account}:function:{function}"
write = lambda path, data: open(path, "w", encoding="utf-8").write(json.dumps(data))
write("/tmp/inf-04-lambda-trust.json", {
  "Version":"2012-10-17", "Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]})
write("/tmp/inf-04-scheduler-trust.json", {
  "Version":"2012-10-17", "Statement":[{"Effect":"Allow","Principal":{"Service":"scheduler.amazonaws.com"},"Action":"sts:AssumeRole"}]})
write("/tmp/inf-04-lambda-policy.json", {
  "Version":"2012-10-17", "Statement":[
    {"Effect":"Allow","Action":["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents"],"Resource":"arn:aws:logs:*:*:*"},
    {"Effect":"Allow","Action":["dynamodb:Scan"],"Resource":f"arn:aws:dynamodb:{region}:{account}:table/{evaluations_table}"},
    {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem","dynamodb:UpdateItem"],"Resource":f"arn:aws:dynamodb:{region}:{account}:table/{report_table}"},
    {"Effect":"Allow","Action":["s3:PutObject"],"Resource":f"arn:aws:s3:::{bucket}/*"},
    {"Effect":"Allow","Action":["ses:SendEmail"],"Resource":"*"},
    {"Effect":"Allow","Action":["sqs:SendMessage"],"Resource":execution_arn}
  ]})
write("/tmp/inf-04-scheduler-policy.json", {
  "Version":"2012-10-17", "Statement":[
    {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":lambda_arn},
    {"Effect":"Allow","Action":"sqs:SendMessage","Resource":delivery_arn}
  ]})
PY

if ! awslocal iam get-role --role-name "$LAMBDA_ROLE" >/dev/null 2>&1; then
  awslocal iam create-role --role-name "$LAMBDA_ROLE" --assume-role-policy-document file:///tmp/inf-04-lambda-trust.json >/dev/null
fi
awslocal iam put-role-policy --role-name "$LAMBDA_ROLE" --policy-name inf-04-report-access --policy-document file:///tmp/inf-04-lambda-policy.json
LAMBDA_ROLE_ARN="$(awslocal iam get-role --role-name "$LAMBDA_ROLE" --query Role.Arn --output text)"
if ! awslocal iam get-role --role-name "$SCHEDULER_ROLE" >/dev/null 2>&1; then
  awslocal iam create-role --role-name "$SCHEDULER_ROLE" --assume-role-policy-document file:///tmp/inf-04-scheduler-trust.json >/dev/null
fi
awslocal iam put-role-policy --role-name "$SCHEDULER_ROLE" --policy-name inf-04-invoke-report --policy-document file:///tmp/inf-04-scheduler-policy.json
SCHEDULER_ROLE_ARN="$(awslocal iam get-role --role-name "$SCHEDULER_ROLE" --query Role.Arn --output text)"

ENVIRONMENT="Variables={AWS_REGION=us-east-1,AWS_ACCESS_KEY_ID=test,AWS_SECRET_ACCESS_KEY=test,DYNAMODB_ENDPOINT=http://localstack:4566,AVALIACOES_TABLE=$AVALIACOES_TABLE,REPORT_GENERATION_TABLE=$REPORT_GENERATION_TABLE,S3_ENDPOINT=http://localstack:4566,REPORTS_BUCKET=$REPORTS_BUCKET,SES_ENDPOINT=http://localstack:4566,SES_FROM_EMAIL=$SES_FROM_EMAIL,ADMIN_EMAIL=$ADMIN_EMAIL,REPORT_PROCESSING_LEASE_SECONDS=300,REPORT_TIME_ZONE=America/Sao_Paulo}"
if awslocal lambda get-function --function-name "$FUNCTION" >/dev/null 2>&1; then
  awslocal lambda update-function-code --function-name "$FUNCTION" --zip-file fileb:///tmp/inf-04-function.zip >/dev/null
  awslocal lambda wait function-updated --function-name "$FUNCTION"
  awslocal lambda update-function-configuration --function-name "$FUNCTION" --role "$LAMBDA_ROLE_ARN" --runtime java25 --handler 'io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest' --timeout 180 --memory-size 1024 --environment "$ENVIRONMENT" >/dev/null
else
  awslocal lambda create-function --function-name "$FUNCTION" --runtime java25 --handler 'io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest' --role "$LAMBDA_ROLE_ARN" --timeout 180 --memory-size 1024 --zip-file fileb:///tmp/inf-04-function.zip --environment "$ENVIRONMENT" >/dev/null
fi
awslocal lambda wait function-active --function-name "$FUNCTION"
awslocal lambda wait function-updated --function-name "$FUNCTION"
awslocal lambda put-function-event-invoke-config --function-name "$FUNCTION" --maximum-event-age-in-seconds 3600 --maximum-retry-attempts 2 --destination-config "OnFailure={Destination=$EXECUTION_ARN}" >/dev/null
FUNCTION_ARN="$(awslocal lambda get-function --function-name "$FUNCTION" --query Configuration.FunctionArn --output text)"

if [[ "$INF04_MODE" == "demo" ]]; then
  EXPRESSION='rate(1 minute)'
else
  EXPRESSION='cron(0 8 ? * MON *)'
fi
TARGET="$(python3 - "$FUNCTION_ARN" "$SCHEDULER_ROLE_ARN" "$DELIVERY_ARN" <<'PY'
import json, sys
function_arn, role_arn, dlq_arn = sys.argv[1:]
print(json.dumps({"Arn":function_arn,"RoleArn":role_arn,"Input":'{"tipo":"SEMANAL","dataReferencia":"<aws.scheduler.scheduled-time>"}',"RetryPolicy":{"MaximumEventAgeInSeconds":3600,"MaximumRetryAttempts":3},"DeadLetterConfig":{"Arn":dlq_arn}}))
PY
)"
if awslocal scheduler get-schedule --name "$SCHEDULE" >/dev/null 2>&1; then
  awslocal scheduler update-schedule --name "$SCHEDULE" --schedule-expression "$EXPRESSION" --schedule-expression-timezone America/Sao_Paulo --flexible-time-window Mode=OFF --state ENABLED --target "$TARGET" >/dev/null
else
  awslocal scheduler create-schedule --name "$SCHEDULE" --schedule-expression "$EXPRESSION" --schedule-expression-timezone America/Sao_Paulo --flexible-time-window Mode=OFF --state ENABLED --target "$TARGET" >/dev/null
fi
echo "Agendamento $SCHEDULE configurado: $EXPRESSION (America/Sao_Paulo)."
echo "Fila de falha de entrega: $DELIVERY_QUEUE; falha de execução Lambda: $EXECUTION_QUEUE."
echo "Confira com: awslocal scheduler get-schedule --name $SCHEDULE"
LOCAL

#!/bin/bash
set -euo pipefail

ARTIFACTS=/opt/code/lambda-artifacts
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
ACCOUNT=000000000000
EVALUATIONS_TABLE="${AVALIACOES_TABLE:-avaliacoes}"
NOTIFICATION_TABLE="${NOTIFICATION_TABLE:-notificacoes-avaliacoes}"
REPORT_TABLE="${REPORT_GENERATION_TABLE:-geracoes-relatorios}"
REPORT_BUCKET="${REPORTS_BUCKET:-fiap-feedback-relatorios-local}"
SENDER="${SES_FROM_EMAIL:-alertas@example.com}"
RECIPIENT="${ADMIN_EMAIL:-admin@example.com}"
FAILURE_QUEUE="${FAILURE_QUEUE_NAME:-notificar-avaliacao-critica-falhas}"
REPORT_DELIVERY_QUEUE="${REPORT_SCHEDULE_FAILURE_QUEUE_NAME:-gerar-relatorio-agendamento-falhas}"
REPORT_EXECUTION_QUEUE="${REPORT_EXECUTION_FAILURE_QUEUE_NAME:-gerar-relatorio-execucao-falhas}"

for artifact in receber-avaliacao notificar-avaliacao-critica gerar-relatorio-semanal; do
  if [[ ! -s "$ARTIFACTS/$artifact.zip" ]]; then
    echo "Artefato da Lambda ausente: $ARTIFACTS/$artifact.zip" >&2
    exit 1
  fi
done

make_role() {
  local role="$1" trust_file="$2" policy_file="$3" policy_name="$4"
  if ! awslocal iam get-role --role-name "$role" >/dev/null 2>&1; then
    awslocal iam create-role --role-name "$role" \
      --assume-role-policy-document "file://$trust_file" >/dev/null
  fi
  awslocal iam put-role-policy --role-name "$role" --policy-name "$policy_name" \
    --policy-document "file://$policy_file" >/dev/null
  awslocal iam get-role --role-name "$role" --query Role.Arn --output text
}

python3 - "$REGION" "$ACCOUNT" "$EVALUATIONS_TABLE" "$NOTIFICATION_TABLE" "$REPORT_TABLE" "$REPORT_BUCKET" "$FAILURE_QUEUE" "$REPORT_DELIVERY_QUEUE" "$REPORT_EXECUTION_QUEUE" <<'PY'
import json, sys
region, account, evaluations, notifications, reports, bucket, alert_queue, delivery_queue, execution_queue = sys.argv[1:]
def write(name, data):
    with open(f"/tmp/{name}.json", "w", encoding="utf-8") as f:
        json.dump(data, f)
lambda_trust = {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
scheduler_trust = {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"scheduler.amazonaws.com"},"Action":"sts:AssumeRole"}]}
logs = {"Effect":"Allow","Action":["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents"],"Resource":"arn:aws:logs:*:*:*"}
table = lambda name: f"arn:aws:dynamodb:{region}:{account}:table/{name}"
queue = lambda name: f"arn:aws:sqs:{region}:{account}:{name}"
write("lambda-trust", lambda_trust)
write("scheduler-trust", scheduler_trust)
write("receiving-policy", {"Version":"2012-10-17","Statement":[logs,{"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem"],"Resource":table(evaluations)}]})
write("notifier-policy", {"Version":"2012-10-17","Statement":[logs,
    {"Effect":"Allow","Action":["dynamodb:DescribeStream","dynamodb:GetRecords","dynamodb:GetShardIterator","dynamodb:ListStreams"],"Resource":f"{table(evaluations)}/stream/*"},
    {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem","dynamodb:UpdateItem","dynamodb:DeleteItem"],"Resource":table(notifications)},
    {"Effect":"Allow","Action":"ses:SendEmail","Resource":"*"},
    {"Effect":"Allow","Action":"sqs:SendMessage","Resource":queue(alert_queue)}]})
write("report-policy", {"Version":"2012-10-17","Statement":[logs,
    {"Effect":"Allow","Action":"dynamodb:Scan","Resource":table(evaluations)},
    {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem","dynamodb:UpdateItem"],"Resource":table(reports)},
    {"Effect":"Allow","Action":"s3:PutObject","Resource":f"arn:aws:s3:::{bucket}/*"},
    {"Effect":"Allow","Action":"ses:SendEmail","Resource":"*"},
    {"Effect":"Allow","Action":"sqs:SendMessage","Resource":queue(execution_queue)}]})
write("scheduler-policy", {"Version":"2012-10-17","Statement":[
    {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":f"arn:aws:lambda:{region}:{account}:function:gerar-relatorio-semanal"},
    {"Effect":"Allow","Action":"sqs:SendMessage","Resource":queue(delivery_queue)}]})
PY

RECEIVING_ROLE_ARN="$(make_role fiap-receber-avaliacao /tmp/lambda-trust.json /tmp/receiving-policy.json local-receiving-access)"
NOTIFIER_ROLE_ARN="$(make_role fiap-be03-notifier /tmp/lambda-trust.json /tmp/notifier-policy.json local-notifier-access)"
REPORT_ROLE_ARN="$(make_role fiap-inf04-report-execution /tmp/lambda-trust.json /tmp/report-policy.json local-report-access)"
SCHEDULER_ROLE_ARN="$(make_role fiap-inf04-scheduler /tmp/scheduler-trust.json /tmp/scheduler-policy.json local-scheduler-access)"

configure_lambda() {
  local name="$1" role_arn="$2" timeout="$3" memory="$4" environment="$5"
  local zip="$ARTIFACTS/$name.zip"
  if awslocal lambda get-function --function-name "$name" >/dev/null 2>&1; then
    awslocal lambda update-function-code --function-name "$name" --zip-file "fileb://$zip" >/dev/null
    awslocal lambda wait function-updated --function-name "$name"
    awslocal lambda update-function-configuration --function-name "$name" --role "$role_arn" \
      --runtime java25 --handler 'io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest' \
      --timeout "$timeout" --memory-size "$memory" --environment "$environment" >/dev/null
  else
    awslocal lambda create-function --function-name "$name" --runtime java25 \
      --handler 'io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest' \
      --role "$role_arn" --timeout "$timeout" --memory-size "$memory" \
      --zip-file "fileb://$zip" --environment "$environment" >/dev/null
  fi
  awslocal lambda wait function-active --function-name "$name"
  awslocal lambda wait function-updated --function-name "$name"
}

configure_lambda receber-avaliacao "$RECEIVING_ROLE_ARN" 30 512 \
  "Variables={AWS_REGION=$REGION,AWS_ACCESS_KEY_ID=test,AWS_SECRET_ACCESS_KEY=test,DYNAMODB_ENDPOINT=http://localstack:4566,AVALIACOES_TABLE=$EVALUATIONS_TABLE,CORS_ORIGINS=http://localhost:5174}"
configure_lambda notificar-avaliacao-critica "$NOTIFIER_ROLE_ARN" 30 512 \
  "Variables={AWS_REGION=$REGION,AWS_ACCESS_KEY_ID=test,AWS_SECRET_ACCESS_KEY=test,DYNAMODB_ENDPOINT=http://localstack:4566,SES_ENDPOINT=http://localstack:4566,NOTIFICATION_TABLE=$NOTIFICATION_TABLE,SES_FROM_EMAIL=$SENDER,ADMIN_EMAIL=$RECIPIENT,ALERT_PROCESSING_LEASE_SECONDS=300}"
configure_lambda gerar-relatorio-semanal "$REPORT_ROLE_ARN" 180 1024 \
  "Variables={AWS_REGION=$REGION,AWS_ACCESS_KEY_ID=test,AWS_SECRET_ACCESS_KEY=test,DYNAMODB_ENDPOINT=http://localstack:4566,AVALIACOES_TABLE=$EVALUATIONS_TABLE,REPORT_GENERATION_TABLE=$REPORT_TABLE,S3_ENDPOINT=http://localstack:4566,REPORTS_BUCKET=$REPORT_BUCKET,SES_ENDPOINT=http://localstack:4566,SES_FROM_EMAIL=$SENDER,ADMIN_EMAIL=$RECIPIENT,REPORT_PROCESSING_LEASE_SECONDS=300,REPORT_TIME_ZONE=America/Sao_Paulo}"

STREAM_ARN="$(awslocal dynamodb describe-table --table-name "$EVALUATIONS_TABLE" --query Table.LatestStreamArn --output text)"
ALERT_QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "$FAILURE_QUEUE" --query QueueUrl --output text)"
ALERT_QUEUE_ARN="$(awslocal sqs get-queue-attributes --queue-url "$ALERT_QUEUE_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)"
MAPPING_ID="$(awslocal lambda list-event-source-mappings --function-name notificar-avaliacao-critica \
  --event-source-arn "$STREAM_ARN" --query 'EventSourceMappings[0].UUID' --output text)"
FILTER='{"Filters":[{"Pattern":"{\"eventName\":[\"INSERT\"]}"}]}'
if [[ -z "$MAPPING_ID" || "$MAPPING_ID" == "None" ]]; then
  awslocal lambda create-event-source-mapping --function-name notificar-avaliacao-critica \
    --event-source-arn "$STREAM_ARN" --starting-position LATEST --batch-size 1 \
    --maximum-batching-window-in-seconds 0 --maximum-retry-attempts 3 \
    --maximum-record-age-in-seconds 86400 --parallelization-factor 1 \
    --filter-criteria "$FILTER" --destination-config "OnFailure={Destination=$ALERT_QUEUE_ARN}" >/dev/null
else
  awslocal lambda update-event-source-mapping --uuid "$MAPPING_ID" --enabled \
    --maximum-retry-attempts 3 --maximum-record-age-in-seconds 86400 \
    --filter-criteria "$FILTER" --destination-config "OnFailure={Destination=$ALERT_QUEUE_ARN}" >/dev/null
fi

REPORT_DELIVERY_URL="$(awslocal sqs get-queue-url --queue-name "$REPORT_DELIVERY_QUEUE" --query QueueUrl --output text)"
REPORT_DELIVERY_ARN="$(awslocal sqs get-queue-attributes --queue-url "$REPORT_DELIVERY_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)"
REPORT_EXECUTION_URL="$(awslocal sqs get-queue-url --queue-name "$REPORT_EXECUTION_QUEUE" --query QueueUrl --output text)"
REPORT_EXECUTION_ARN="$(awslocal sqs get-queue-attributes --queue-url "$REPORT_EXECUTION_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)"
awslocal lambda put-function-event-invoke-config --function-name gerar-relatorio-semanal \
  --maximum-event-age-in-seconds 3600 --maximum-retry-attempts 2 \
  --destination-config "OnFailure={Destination=$REPORT_EXECUTION_ARN}" >/dev/null
REPORT_FUNCTION_ARN="$(awslocal lambda get-function --function-name gerar-relatorio-semanal --query Configuration.FunctionArn --output text)"
TARGET="$(python3 - "$REPORT_FUNCTION_ARN" "$SCHEDULER_ROLE_ARN" "$REPORT_DELIVERY_ARN" <<'PY'
import json, sys
arn, role, queue = sys.argv[1:]
print(json.dumps({"Arn":arn,"RoleArn":role,"Input":'{"tipo":"SEMANAL","dataReferencia":"<aws.scheduler.scheduled-time>"}',"RetryPolicy":{"MaximumEventAgeInSeconds":3600,"MaximumRetryAttempts":3},"DeadLetterConfig":{"Arn":queue}}))
PY
)"
SCHEDULE=gerar-relatorio-semanal-local
if awslocal scheduler get-schedule --name "$SCHEDULE" >/dev/null 2>&1; then
  awslocal scheduler update-schedule --name "$SCHEDULE" --schedule-expression 'cron(0 8 ? * MON *)' \
    --schedule-expression-timezone America/Sao_Paulo --flexible-time-window Mode=OFF \
    --state ENABLED --target "$TARGET" >/dev/null
else
  awslocal scheduler create-schedule --name "$SCHEDULE" --schedule-expression 'cron(0 8 ? * MON *)' \
    --schedule-expression-timezone America/Sao_Paulo --flexible-time-window Mode=OFF \
    --state ENABLED --target "$TARGET" >/dev/null
fi

echo "Lambdas, DynamoDB Stream e agenda semanal configurados no LocalStack."

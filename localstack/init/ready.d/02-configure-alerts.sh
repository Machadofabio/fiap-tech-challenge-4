#!/bin/bash
set -euo pipefail

TABLE_NAME="${NOTIFICATION_TABLE:-notificacoes-avaliacoes}"
SENDER="${SES_FROM_EMAIL:-alertas@example.com}"
RECIPIENT="${ADMIN_EMAIL:-admin@example.com}"
FAILURE_QUEUE="${FAILURE_QUEUE_NAME:-notificar-avaliacao-critica-falhas}"
REPORT_SCHEDULE_FAILURE_QUEUE="${REPORT_SCHEDULE_FAILURE_QUEUE_NAME:-gerar-relatorio-agendamento-falhas}"
REPORT_EXECUTION_FAILURE_QUEUE="${REPORT_EXECUTION_FAILURE_QUEUE_NAME:-gerar-relatorio-execucao-falhas}"
REPORT_TABLE="${REPORT_GENERATION_TABLE:-geracoes-relatorios}"
REPORT_BUCKET="${REPORTS_BUCKET:-fiap-feedback-relatorios-local}"

if ! awslocal dynamodb describe-table --table-name "$TABLE_NAME" >/dev/null 2>&1; then
  awslocal dynamodb create-table \
    --table-name "$TABLE_NAME" \
    --attribute-definitions AttributeName=eventId,AttributeType=S \
    --key-schema AttributeName=eventId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
  awslocal dynamodb wait table-exists --table-name "$TABLE_NAME"
  echo "DynamoDB table $TABLE_NAME created for alert deduplication."
fi

if ! awslocal dynamodb describe-table --table-name "$REPORT_TABLE" >/dev/null 2>&1; then
  awslocal dynamodb create-table \
    --table-name "$REPORT_TABLE" \
    --attribute-definitions AttributeName=generationId,AttributeType=S \
    --key-schema AttributeName=generationId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
  awslocal dynamodb wait table-exists --table-name "$REPORT_TABLE"
  echo "DynamoDB table $REPORT_TABLE created for report retries."
fi

awslocal ses verify-email-identity --email-address "$SENDER"
awslocal ses verify-email-identity --email-address "$RECIPIENT"
if ! awslocal sqs get-queue-url --queue-name "$FAILURE_QUEUE" >/dev/null 2>&1; then
  awslocal sqs create-queue \
    --queue-name "$FAILURE_QUEUE" \
    --attributes "MessageRetentionPeriod=${FAILURE_QUEUE_RETENTION_SECONDS:-1209600}"
fi
QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "$FAILURE_QUEUE" --query QueueUrl --output text)"
awslocal sqs set-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attributes "MessageRetentionPeriod=${FAILURE_QUEUE_RETENTION_SECONDS:-1209600}"
for queue in "$REPORT_SCHEDULE_FAILURE_QUEUE" "$REPORT_EXECUTION_FAILURE_QUEUE"; do
  if ! awslocal sqs get-queue-url --queue-name "$queue" >/dev/null 2>&1; then
    awslocal sqs create-queue \
      --queue-name "$queue" \
      --attributes "MessageRetentionPeriod=${FAILURE_QUEUE_RETENTION_SECONDS:-1209600}" >/dev/null
  fi
  queue_url="$(awslocal sqs get-queue-url --queue-name "$queue" --query QueueUrl --output text)"
  awslocal sqs set-queue-attributes \
    --queue-url "$queue_url" \
    --attributes "MessageRetentionPeriod=${FAILURE_QUEUE_RETENTION_SECONDS:-1209600}"
done
if ! awslocal s3api head-bucket --bucket "$REPORT_BUCKET" >/dev/null 2>&1; then
  awslocal s3 mb "s3://$REPORT_BUCKET" >/dev/null
fi
echo "Local SES identities, failure queues, and report resources configured."

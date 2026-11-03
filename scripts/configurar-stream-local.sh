#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

docker compose --profile aws up -d localstack
./gradlew --no-daemon :notificar-avaliacao-critica:quarkusBuild

docker compose cp build/notificar-avaliacao-critica/function.zip localstack:/tmp/be-03-function.zip
docker compose cp localstack/be-03-assume-role.json localstack:/tmp/be-03-assume-role.json
docker compose cp localstack/be-03-role-policy.json localstack:/tmp/be-03-role-policy.json

docker compose exec -T localstack bash -c '
  set -euo pipefail
  ROLE=fiap-be03-notifier
  FUNCTION=notificar-avaliacao-critica
  TABLE="${AVALIACOES_TABLE:-avaliacoes}"
  STATE_TABLE="${NOTIFICATION_TABLE:-notificacoes-avaliacoes}"
  QUEUE="${FAILURE_QUEUE_NAME:-notificar-avaliacao-critica-falhas}"
  SENDER="${SES_FROM_EMAIL:-alertas@example.com}"
  RECIPIENT="${ADMIN_EMAIL:-admin@example.com}"

  if ! awslocal iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
    awslocal iam create-role --role-name "$ROLE" \
      --assume-role-policy-document file:///tmp/be-03-assume-role.json >/dev/null
  fi
  awslocal iam put-role-policy --role-name "$ROLE" \
    --policy-name be-03-notifier-access \
    --policy-document file:///tmp/be-03-role-policy.json
  ROLE_ARN="$(awslocal iam get-role --role-name "$ROLE" --query Role.Arn --output text)"
  ENVIRONMENT="Variables={AWS_REGION=us-east-1,AWS_ACCESS_KEY_ID=test,AWS_SECRET_ACCESS_KEY=test,DYNAMODB_ENDPOINT=http://localstack:4566,SES_ENDPOINT=http://localstack:4566,NOTIFICATION_TABLE=$STATE_TABLE,SES_FROM_EMAIL=$SENDER,ADMIN_EMAIL=$RECIPIENT,ALERT_PROCESSING_LEASE_SECONDS=300}"

  if awslocal lambda get-function --function-name "$FUNCTION" >/dev/null 2>&1; then
    awslocal lambda update-function-code --function-name "$FUNCTION" \
      --zip-file fileb:///tmp/be-03-function.zip >/dev/null
    awslocal lambda wait function-updated --function-name "$FUNCTION"
    awslocal lambda update-function-configuration --function-name "$FUNCTION" \
      --role "$ROLE_ARN" --timeout 30 --memory-size 512 --environment "$ENVIRONMENT" >/dev/null
  else
    awslocal lambda create-function --function-name "$FUNCTION" \
      --runtime java25 \
      --handler "io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest" \
      --role "$ROLE_ARN" --timeout 30 --memory-size 512 \
      --zip-file fileb:///tmp/be-03-function.zip \
      --environment "$ENVIRONMENT" >/dev/null
  fi

  STREAM_ARN="$(awslocal dynamodb describe-table --table-name "$TABLE" --query Table.LatestStreamArn --output text)"
  QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "$QUEUE" --query QueueUrl --output text)"
  QUEUE_ARN="$(awslocal sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)"
  EXISTING_MAPPING="$(awslocal lambda list-event-source-mappings --function-name "$FUNCTION" --event-source-arn "$STREAM_ARN" --query 'EventSourceMappings[0].UUID' --output text)"
  DESTINATION="{\"OnFailure\":{\"Destination\":\"$QUEUE_ARN\"}}"
  FILTER="{\"Filters\":[{\"Pattern\":\"{\\\"eventName\\\":[\\\"INSERT\\\"]}\"}]}"
  MAPPING_OPTIONS=(--function-name "$FUNCTION" --event-source-arn "$STREAM_ARN"
    --starting-position LATEST --batch-size 1 --maximum-batching-window-in-seconds 0
    --maximum-retry-attempts 3 --maximum-record-age-in-seconds 86400
    --parallelization-factor 1 --filter-criteria "$FILTER" --destination-config "$DESTINATION")

  if [[ -z "$EXISTING_MAPPING" || "$EXISTING_MAPPING" == "None" ]]; then
    awslocal lambda create-event-source-mapping "${MAPPING_OPTIONS[@]}"
  else
    awslocal lambda update-event-source-mapping --uuid "$EXISTING_MAPPING" "${MAPPING_OPTIONS[@]}"
  fi
'

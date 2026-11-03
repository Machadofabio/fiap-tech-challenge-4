#!/bin/bash
set -euo pipefail

TABLE_NAME="${AVALIACOES_TABLE:-avaliacoes}"

if awslocal dynamodb describe-table --table-name "$TABLE_NAME" >/dev/null 2>&1; then
  echo "DynamoDB table $TABLE_NAME already exists."
else
  awslocal dynamodb create-table \
    --table-name "$TABLE_NAME" \
    --attribute-definitions AttributeName=id,AttributeType=S \
    --key-schema AttributeName=id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --stream-specification StreamEnabled=true,StreamViewType=NEW_AND_OLD_IMAGES
  awslocal dynamodb wait table-exists --table-name "$TABLE_NAME"
  echo "DynamoDB table $TABLE_NAME created with Streams enabled."
fi

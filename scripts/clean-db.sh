#!/bin/bash

# Check for .env file
if [ ! -f .env ]; then
  echo ".env file not found!"
  exit 1
fi

# Load variables from .env
export $(grep -v '^#' .env | xargs)

# Check required variables
if [ -z "$DB_URL" ] || [ -z "$DB_USERNAME" ] || [ -z "$DB_PASSWORD" ]; then
  echo "DB_URL, DB_USERNAME or DB_PASSWORD is missing in .env!"
  exit 1
fi

# Run Flyway clean using the environment variables
./mvnw flyway:clean \
  -Dflyway.cleanDisabled=false \
  -Dflyway.url="$DB_URL" \
  -Dflyway.user="$DB_USERNAME" \
  -Dflyway.password="$DB_PASSWORD"

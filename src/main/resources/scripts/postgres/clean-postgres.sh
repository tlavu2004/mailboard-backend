#!/bin/bash

# Get the directory where script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Go up 5 levels from src/main/resources/scripts/postgres to reach project root
SERVICE_DIR="$(dirname "$(dirname "$(dirname "$(dirname "$(dirname "$SCRIPT_DIR")")")")")"

ENV_FILENAME=${1:-".env"}
ENV_FILE="$SERVICE_DIR/$ENV_FILENAME"

# Check for .env file in service directory
if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: Environment file not found at $ENV_FILE" >&2
  exit 1
fi

# Load variables from service .env (robustly without requiring quotes)
echo "Loading environment from $ENV_FILE..."
while IFS='=' read -r key value || [ -n "$key" ]; do
  # Skip comments and empty lines
  if [[ $key =~ ^#.* ]] || [[ -z $key ]]; then
    continue
  fi
  # Clean potential carriage returns and spaces
  key=$(echo "$key" | tr -d '\r' | xargs)
  value=$(echo "$value" | tr -d '\r' | xargs)
  
  if [ -n "$key" ]; then
    export "$key"="$value"
  fi
done < "$ENV_FILE"

# Validate required variables (aligned with LinkForge .env)
required_vars=(
  DB_URL
  DB_USERNAME
  DB_PASSWORD
)

for var in "${required_vars[@]}"; do
  if [ -z "${!var}" ]; then
    echo "ERROR: Required variable '$var' is missing in $ENV_FILENAME" >&2
    exit 1
  fi
done

# Confirm destructive action
echo "--------------------------------------------------------"
echo "WARNING: This will DROP all tables and data in the DB!"
echo "Target URL: $DB_URL"
echo "Env File:   $ENV_FILENAME"
echo "--------------------------------------------------------"
read -r -p "Are you sure? Type 'yes' to continue: " confirm
if [ "$confirm" != "yes" ]; then
  echo "Aborted."
  exit 0
fi

# Change to service directory
cd "$SERVICE_DIR" || {
  echo "ERROR: Cannot cd to $SERVICE_DIR" >&2
  exit 1
}

# Run Flyway clean with explicit parameters from .env
# Automatically replace 'postgresql' host with 'localhost' for local Maven execution
LOCAL_DB_URL=$(echo "$DB_URL" | sed 's/\/\/postgresql:/\/\/localhost:/g')

mvn flyway:clean -Dflyway.cleanDisabled=false -Dflyway.url="$LOCAL_DB_URL" -Dflyway.user="$DB_USERNAME" -Dflyway.password="$DB_PASSWORD"

echo "PostgreSQL database cleaned successfully!"
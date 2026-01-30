#!/bin/bash

# Get the directory where script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"

ENV_FILE="$SERVICE_DIR/.env"

# Check for .env file in service directory
if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: .env file not found at $ENV_FILE" >&2
  exit 1
fi

# Load variables from service .env (robust CRLF handling)
set -a
if [ -f "$SERVICE_DIR/.env" ]; then
  while IFS='=' read -r key value; do
    # Trim content just in case
    key=$(echo "$key" | tr -d '\r')
    value=$(echo "$value" | tr -d '\r')

    # Skip comments and empty keys
    [[ "$key" =~ ^#.*$ ]] && continue
    [[ -z "$key" ]] && continue
    
    # Export the variable
    export "$key=$value"
  done < "$SERVICE_DIR/.env"
fi
set +a

# Validate required variables
required_vars=(
  DB_URL
  DB_USERNAME
  DB_PASSWORD
)

for var in "${required_vars[@]}"; do
  if [ -z "${!var}" ]; then
    echo "ERROR: Required variable '$var' is missing in .env" >&2
    exit 1
  fi
done

# Confirm destructive action (Extract simplistic DB name from URL for display, or show URL)
read -r -p "Are you sure you want to run Flyway Clean on '$DB_URL'? Type 'yes' to continue: " confirm
if [ "$confirm" != "yes" ]; then
  echo "Aborted."
  exit 0
fi

# Change to service directory
cd "$SERVICE_DIR" || {
  echo "ERROR: Cannot cd to $SERVICE_DIR" >&2
  exit 1
}

# Set environment variables for Flyway (secure, cross-platform)
export FLYWAY_URL="$DB_URL"
export FLYWAY_USER="$DB_USERNAME"
export FLYWAY_PASSWORD="$DB_PASSWORD"

# Run Flyway clean
mvn flyway:clean -Dflyway.cleanDisabled=false

# Unset sensitive env vars after use
unset FLYWAY_URL
unset FLYWAY_USER
unset FLYWAY_PASSWORD

echo "Database cleaned successfully!"
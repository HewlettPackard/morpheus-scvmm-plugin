#!/bin/bash

# Check if required environment variables are set
if [ -z "$JFROG_USERNAME" ] || [ -z "$JFROG_PASSWORD" ]; then
  echo "Error: JFROG_USERNAME or JFROG_PASSWORD environment variable not set."
  exit 1
fi

# Create pip.conf with the credentials
cat <<EOF > ./pip.conf
[global]
index-url = https://${JFROG_USERNAME}:${JFROG_PASSWORD}@aruba.jfrog.io/aruba/api/pypi/pypi-local/simple/
extra-index-url = https://pypi.org/simple
EOF

echo "pip.conf created successfully."

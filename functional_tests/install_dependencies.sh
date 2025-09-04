#!/bin/bash

# Call the create_pip_conf script
./create_pip_conf.sh

# Check if pip.conf was created
if [ ! -f "./pip.conf" ]; then
  echo "Error: pip.conf was not created successfully."
  exit 1
fi

export export PIP_CONFIG_FILE=pip.conf

# Install dependencies from requirements.txt
pip3 install --no-cache-dir -r requirements.txt

# We need to these steps for playwright to install browsers and other dependencies
playwright install-deps
playwright install

# Clean up the pip.conf file after installation
rm ./pip.conf
echo "pip.conf cleaned up successfully."

#!/bin/bash
# Test HERE credentials from credentials.properties

echo "Testing HERE credentials..."
echo "=========================="

# Read credentials
ACCESS_KEY_ID=$(grep "accessKeyId=" credentials.properties | cut -d'=' -f2)
ACCESS_KEY_SECRET=$(grep "accessKeySecret=" credentials.properties | cut -d'=' -f2)

echo "Access Key ID length: ${#ACCESS_KEY_ID}"
echo "Access Key Secret length: ${#ACCESS_KEY_SECRET}"
echo "First 8 chars of ID: ${ACCESS_KEY_ID:0:8}"
echo "First 8 chars of Secret: ${ACCESS_KEY_SECRET:0:8}"

# Check for common issues
if [[ "$ACCESS_KEY_ID" == *" "* ]]; then
    echo "WARNING: Access Key ID contains spaces!"
fi

if [[ "$ACCESS_KEY_SECRET" == *" "* ]]; then
    echo "WARNING: Access Key Secret contains spaces!"
fi

# Check for trailing whitespace
if [[ "$ACCESS_KEY_ID" != "${ACCESS_KEY_ID%% }" ]]; then
    echo "WARNING: Access Key ID has trailing spaces!"
fi

if [[ "$ACCESS_KEY_SECRET" != "${ACCESS_KEY_SECRET%% }" ]]; then
    echo "WARNING: Access Key Secret has trailing spaces!"
fi

echo "=========================="
echo "If lengths are 22 and 68 respectively, credentials format is correct."
import os
import pytest
import json
from dotenv import load_dotenv

# Load variables from .env
load_dotenv()

# List of ALL required environment variables
REQUIRED_ENV_VARS = [
    "BASE_URL",
    "USERNAME",
    "PASSWORD",
    "HOST",
    "HOST_USERNAME",
    "HOST_PASSWORD",
    "HOST_NAME",
    "NETWORK_NAME",
    "SCVMM_TEMPLATE_NAME",
    "SERVER_TYPE_NAME",
    "CLUSTER_TYPE",
    "SCVMM_INSTANCE_VOLUME_SIZE",
    "CLUSTER_LAYOUT_NAME",
    "SCVMM_INSTANCE_TYPE",
    "UBUNTU_INSTANCE_TYPE",
    "DEBIAN_INSTANCE_TYPE",
    "ALMALINUX_INSTANCE_TYPE",
    "CENTOS_INSTANCE_TYPE",
    "OPENSUSE_INSTANCE_TYPE",
    "ROCKYLINUX_INSTANCE_TYPE",
    "SCVMM_LAYOUT_CODE",
    "SCVMM_UBUNTU_LAYOUT_CODE",
    "SCVMM_DEBIAN_LAYOUT_CODE",
    "SCVMM_ALMALINUX_LAYOUT_CODE",
    "SCVMM_CENTOS_LAYOUT_CODE",
    "SCVMM_OPENSUSE_LAYOUT_CODE",
    "SCVMM_ROCKYLINUX_LAYOUT_CODE"
]

@pytest.fixture(scope="session", autouse=True)
def validate_required_env():
    """Fail the entire test run if any required env var is missing/empty/invalid."""
    missing = []

    for var in REQUIRED_ENV_VARS:
        value = os.getenv(var)
        if not value or value.strip() == "":
            missing.append(var)

    # Extra: validate VOLUMES is valid JSON
    volumes = os.getenv("VOLUMES")
    if volumes:
        try:
            json.loads(volumes)
        except json.JSONDecodeError:
            pytest.fail(" VOLUMES must be valid JSON")

    if missing:
        pytest.fail(f" Missing required environment variables: {', '.join(missing)}")

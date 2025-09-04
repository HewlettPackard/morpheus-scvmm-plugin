# conftest.py

import pytest
import logging
import os
from hpe_morpheus_automation_libs.api.external_api.morpheus_session import MorpheusExternalAPISession

log = logging.getLogger(__name__)

@pytest.fixture
def morpheus_session():
    host = os.getenv("BASE_URL")
    username = os.getenv("USERNAME")
    password = os.getenv("PASSWORD")
    return MorpheusExternalAPISession(host=host, username=username, password=password, version="v8_0_6")
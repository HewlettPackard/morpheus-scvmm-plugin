# conftest.py

import pytest
import logging

from hpe_morpheus_automation_libs.api.external_api.morpheus_session import MorpheusExternalAPISession

from functional_tests.common.configs.config_reader import ConfigHandler

log = logging.getLogger(__name__)
config_data = ConfigHandler().read_testcase_variable_config()


@pytest.fixture
def morpheus_session():
    host = config_data["host"]
    username = list(config_data["role"]["admin"].keys())[0]
    password = list(config_data["role"]["admin"].values())[0]
    return MorpheusExternalAPISession(host=host, username=username, password=password)
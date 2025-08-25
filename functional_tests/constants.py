"""
   User Defined Constants classes
"""
import os
import time

from hpe_glcp_automation_lib.libs.commons.common_testbed_data.settings import Settings
from hpe_glcp_automation_lib.libs.commons.utils.random_gens import RandomGenUtils

rand = RandomGenUtils.random_string_of_chars(7)

AUTOMATION_DIRECTORY = os.path.abspath(os.path.dirname(__file__))
settings = Settings()
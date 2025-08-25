"""
Utility method to read config files required for testcase automation
"""
import json
import logging
import os
from collections import OrderedDict


from functional_tests.conftest import ExistingUserAcctDevices, settings

login_data = ExistingUserAcctDevices.test_data


class ConfigHandler:
    config = OrderedDict()

    def __init__(self):
        self.data = self.read_testcase_variable_config()

    def load_json_from_file(self, file_path: str):
        """
        This method loads json file

        :param file_path: File path to pick the json file
        :returns json object
        """
        with open(file_path, "r") as openfile:
            json_object = json.load(openfile)
        return json_object

    def read_testcase_variable_config(self):
        """
        Method to read testcase variable config file

        :returns data present in testcase_variable_data file
        :rtype dict
        """
        if ExistingUserAcctDevices.current_env == 'mvm':
            test_variable_file = "/../resources/testcase_variable_data.json"
            logging.info(f"MVM environment:{test_variable_file}")
        else:
            test_variable_file = "/../resources/testcase_variable_data.json"
        working_dir_path = os.path.dirname(__file__)
        test_variable_data = self.load_json_from_file(
            working_dir_path + test_variable_file
        )
        return test_variable_data

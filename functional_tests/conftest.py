"""Common Conftest file for automation"""
import logging
import os
import sys
import time

import pytest


from hpe_glcp_automation_lib.libs.commons.common_testbed_data.settings import (
    Settings,
    all_envs,
)
from functional_tests.constants import AUTOMATION_DIRECTORY
from functional_tests.common.configs.morpheus_settings import morpheus_test_data
from functional_tests.common.secrets.update_secrets import write_json_file, read_json_file


if os.getenv("POD_NAMESPACE") is None:
    from functional_tests.common.configs.morpheus_settings import morpheus_test_data

settings = Settings()

os.environ["AUTOMATION_DIRECTORY"] = AUTOMATION_DIRECTORY
RECORD_DIR = os.path.join("tmp", "results")
ROOT = os.path.dirname(os.path.realpath(__file__))
default_env = "mvm"

log = logging.getLogger(__name__)


class CreateLogFile(object):
    """
    Usage: Create log and result file location if not exists
    From this location Job will upload all logs and results
    """

    @staticmethod
    def log_file_location():
        """hard coded log file location"""
        return "/tmp/results/"

    @staticmethod
    def create_log_file():
        """To Create log file"""
        try:
            if not os.path.exists("/tmp/results/"):
                os.mkdir("/tmp/results/")
            if not os.path.exists("/tmp/results/log1.log"):
                with open("/tmp/results/log1.log", "w"):
                    pass
            return True
        except Exception:
            log.info("not able to create log file and directory")

# Placeholder for pytest not to raise exception because of new unknown argument.
# Argument processing need to be executed before all imports, so pytest build in will
def pytest_addoption(parser):
    """
    Pytest command line option to send clueter name as: polaris, mira, pavo,
    triton-lite, aquila
    """
    parser.addoption(
        "--env", action="store", choices=list(all_envs.keys()) + ["on-prem", "dino", "mvm"], default=default_env
    )
    parser.addoption(
        "--tags", action="store", help="enter valid security tags", default=None
    )
    parser.addoption(
        "--application", action="store", help="Name of the application is mandatory"
    )
    parser.addoption(
        "--vm_instance", action="store", help="Name of VM instance is mandatory for OnPrem", default=None
    )
    parser.addoption(
        "--server_family", action="store", help="Name of the server family", default=None
    )
    parser.addoption(
        "--email", action="store", help="Name of the email", default=None
    )
    parser.addoption(
        "--disable-testrail", action="store_true", help="To disable TestRail Integration", default=False
    )
    parser.addoption(
        "--ignore-https-error", action="store", help="To disable HTTPS check in playwright browser", default=False
    )
    parser.addoption(
        "--templates", action="store", help="Name of the template data for morpheus", default=None
    )
    parser.addoption(
        "--create-temp-mail", action="store_true", help="Create temporary email accounts"
    )


def pytest_generate_tests(metafunc):
    """Missing doc string"""
    if "application" in metafunc.fixturenames:
        metafunc.parametrize("application", [metafunc.config.getoption("application")])


os.environ["CURRENT_ENV"] = default_env

for index, arg in enumerate(sys.argv):
    if arg.startswith("--tags"):
        os.environ["TEST_RUN"] = arg.split("=")[1]
    if arg.startswith("--env"):
        if "=" in arg:
            if arg.split("=")[1] in all_envs.keys() or arg.split("=")[1] == "on-prem" or arg.split("=")[1] == "dino" \
                    or arg.split("=")[1] == "mvm":
                os.environ["CURRENT_ENV"] = arg.split("=")[1]
                break
            else:
                raise Exception(f"Unsupported environment: {arg.split('=')[1]}")
        elif sys.argv[index + 1] in all_envs.keys() or sys.argv[index + 1] == "on-prem" or \
                sys.argv[index + 1] == "dino" or sys.argv[index + 1] == "mvm":
            os.environ["CURRENT_ENV"] = sys.argv[index + 1]
            break
        else:
            raise ValueError(f"Unsupported environment: {sys.argv[index + 1]} {all_envs.keys()}")

all_envs.update({"on-prem": all_envs["default"]})
all_envs.update({"mvm": all_envs["default"]})


def push_user_creds_to_variable_file(user_creds_data):
    """
    Function to write user_cred.json data into testcase_variable_data.json
    :param user_creds_data: User cred to be replaced
    """
    variable_file_path, json_data = None, None
    current_env = settings.current_env().lower()
    base_path = os.path.dirname(__file__) + "/tests/resources/"

    if current_env == "mvm":
        variable_file_path = os.path.join(base_path, "testcase_variable_data.json")
        json_data = read_json_file(variable_file_path)

    # Write the updated data to the file to testcase_variable_data
    write_json_file(variable_file_path, json_data)


def update_user_api_data(json_data):
    """
    Function to update user_api data in testcase_variable_data.json
    :param json_data: testcase data in which user_api_automation data to be updated
    :return: updated json data
    """
    json_data.update({'user_api_automation': {'role': 'Test'}})
    return json_data


class ExistingUserAcctDevices:
    """
    Class for tests to reads all the test data, login creds, and device related
    information
    """

    current_env = settings.current_env()
    login_page_url = settings.login_page_url()
    get_app_api_hostname = settings.get_app_api_hostname()
    sso_device_url = settings.get_sso_host()
    create_log_file = CreateLogFile.create_log_file()
    log_files = CreateLogFile.log_file_location()
    if current_env == "mvm":
        test_data = morpheus_test_data(current_env)


def browser_type(playwright, browser_name: str):
    """
    Playwright browser type to be used by the test scripts
    """
    if browser_name == "chromium":
        browser = playwright.chromium
        if os.getenv("POD_NAMESPACE") is None:
            return browser.launch(headless=False, slow_mo=100)
        else:
            return browser.launch(headless=True, slow_mo=100)
    if browser_name == "firefox":
        browser = playwright.firefox
        if os.getenv("POD_NAMESPACE") is None:
            return browser.launch(headless=False, slow_mo=100)
        else:
            return browser.launch(headless=True, slow_mo=100)
    if browser_name == "webkit":
        browser = playwright.webkit
        if os.getenv("POD_NAMESPACE") is None:
            return browser.launch(headless=False, slow_mo=100)
        else:
            return browser.launch(headless=True, slow_mo=100)


@pytest.fixture(scope="session")
def browser_instance(playwright):
    """
    Tests create browser instance with this function to create chromium browser
    """
    browser = browser_type(playwright, "chromium")
    yield browser
    browser.close()


active_devices = ExistingUserAcctDevices()
test_data = active_devices.test_data


@pytest.fixture
def skip_if_hoku():
    """
    To skip pavo env execution
    """
    if "hoku" in ExistingUserAcctDevices.login_page_url:
        log.info("skipping env {}".format(ExistingUserAcctDevices.login_page_url))
        pytest.skip("hoku env is SKIPPED")


@pytest.fixture
def plbrowser(browser_type, pytestconfig):
    """
    To launch browser
    """
    log.info("Browser type %s", browser_type)
    browser_headless = test_data["BROWSER_HEADLESS"]
    if pytestconfig.getoption("--browser-channel") == 'msedge':
        browser = browser_type.launch(headless=browser_headless, slow_mo=800, channel="msedge")
    else:
        browser = browser_type.launch(headless=browser_headless, slow_mo=800)
    yield browser
    browser.close()


pytest_failed = False


@pytest.fixture
def plcontext(plbrowser, request):
    """
    hook for playwright
    """
    global pytest_failed
    test_name = request.node.originalname
    timestamp = time.strftime("%Y%m%d%H%M%S")
    ROOT_DIRECTORY = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
    os.makedirs(os.path.join(ROOT_DIRECTORY, "logs"), exist_ok=True)
    ignore_https_error = request.config.getoption("--ignore-https-error")
    test_data["IGNORE_HTTPS_ERROR"] = ignore_https_error
    if test_data['IGNORE_HTTPS_ERROR']:
        log.info("Launching PL browser with bypass HTTPS")
        context = plbrowser.new_context(ignore_https_errors=True)
    elif test_data["TRACE_HAR_ON"]:
        os.makedirs(os.path.join(ROOT_DIRECTORY, "logs/har"), exist_ok=True)
        context = plbrowser.new_context(
            record_har_path=os.path.join(
                ROOT_DIRECTORY, f"logs/har/har-{test_name}_{timestamp}.zip"
            )
        )
    else:
        context = plbrowser.new_context()
    browser_context = context
    api_context = context.request
    if test_data["TRACE_ON"]:
        os.makedirs(os.path.join(ROOT_DIRECTORY, "logs/playwright-trace"), exist_ok=True)
        context.tracing.start(
            screenshots=test_data["SCREENSHOTS"],
            snapshots=test_data["SNAPSHOTS"],
            sources=test_data["SOURCES"],
        )

    yield browser_context, api_context
    if test_data["TRACE_ON"] and pytest_failed:
        trace_file = (
                os.path.join(ROOT_DIRECTORY, "logs/playwright-trace/")
                + test_name
                + "_"
                + timestamp
                + ".zip"
        )
        log.debug("Trace file: %s", trace_file)
        context.tracing.stop(path=trace_file)
    pytest_failed = False
    context.close()



@pytest.hookimpl(tryfirst=True)
def pytest_runtest_makereport(item, call):
    """Pytest hook method to collect report based on test status"""
    global pytest_failed
    if call.when == "call" and call.excinfo:
        pytest_failed = True

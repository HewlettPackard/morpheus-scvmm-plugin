"""
Morpheus Settings
"""


def morpheus_test_data(url):
    """
    Usage: Test bed data will be initialized in conftest.py
    define username, acct_name, app_name, device serials, subscriptions in this file
    Password and api_secret need to be in user_creds.json file
    """
    tb_data = {}
    tb_data["test_or_workflow_name1"] = {}
    tb_data["test_or_workflow_name2"] = {}
    if url.startswith("mvm"):
        tb_data["url"] = url
        tb_data["test_or_workflow_name1"]["username"] = "username"
        tb_data["test_or_workflow_name1"]["account_name1"] = "account_name1"
        tb_data["test_or_workflow_name1"]["app_api_user"] = "_api"

        tb_data["PLAYWRIGHT_TRACE_FILE_PATH"] = "/tmp/trace_files"
        tb_data["BROWSER_HEADLESS"] = False
        tb_data["BROWSER_NAME"] = "chromium"
        tb_data["SCREENSHOTS"] = True
        tb_data["SNAPSHOTS"] = True
        tb_data["SOURCES"] = True
        tb_data["TRACE_ON"] = True
        tb_data["TRACE_HAR_ON"] = True
        tb_data["update_testrail"] = False
        tb_data["update_slack"] = False
        return tb_data
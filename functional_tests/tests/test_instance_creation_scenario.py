"""
SCVMM Plugin related test cases.
"""

import glob
import json
import logging
import os

import pytest
from dotenv import load_dotenv
from hpe_glcp_automation_lib.libs.commons.utils.random_gens import RandomGenUtils
from hpe_morpheus_automation_libs.api.external_api.plugins.plugin_api import PluginAPI
from functional_tests.common.cloud_helper import ResourcePoller
from functional_tests.common.scvmm_utils import SCVMMUtils

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

load_dotenv()

host = os.getenv("BASE_URL")
admin_username = os.getenv("USERNAME")
admin_password = os.getenv("PASSWORD")

class TestSCVMMPlugin:
    """Test class for SCVMM plugin related functionalities."""

    group_id = None
    cloud_id = None
    instance_id = None

    def test_validate_windows_instance_creation_and_agent_installation(self, morpheus_session):
        """Validate Windows instance creation, agent installation, and basic operations."""
        instance_id = None  # Initialize instance_id to None for cleanup purposes
        try:
            # Upload SCVMM plugin
            plugin_api = PluginAPI(host=host, username=admin_username, password=admin_password)
            SCVMMUtils.upload_scvmm_plugin(plugin_api)

            # Create a SCVMM group
            group_name = "test-scvmm-group-" + RandomGenUtils.random_string_of_chars(4)
            group_response = morpheus_session.groups.add_groups({"group": {"name": group_name}})
            assert group_response.status_code == 200, "Group creation failed!"
            TestSCVMMPlugin.group_id = group_response.json()["group"]["id"]

            # Create cloud & cluster
            TestSCVMMPlugin.cloud_id = SCVMMUtils.create_scvmm_cloud(morpheus_session, TestSCVMMPlugin.group_id)
            SCVMMUtils.create_scvmm_cluster(morpheus_session, TestSCVMMPlugin.cloud_id, TestSCVMMPlugin.group_id)

            # Create instance
            instance_id, _ = SCVMMUtils.create_instance(
                morpheus_session,
                group_id=TestSCVMMPlugin.group_id,
                cloud_id=TestSCVMMPlugin.cloud_id
            )
            assert instance_id, "Instance creation failed!"

            # Validate agent installation
            details = SCVMMUtils.get_instance_details(morpheus_session, instance_id)
            server = details["instance"]["containerDetails"][0]["server"]
            assert server.get("agentInstalled"), "Agent installation failed!"
            log.info(f"Agent version: {server.get('agentVersion')} on instance {instance_id}")

            # Validate basic lifecycle operations
            for action, state in [("start", "running"), ("restart", "running"),
                                  ("stop", "stopped"), ("start", "running")]:
                SCVMMUtils.perform_instance_operation(morpheus_session, instance_id, action, state)

        except Exception as e:
            pytest.fail(f"Windows instance creation & agent validation failed: {e}")
        finally:
            SCVMMUtils.cleanup_resource("instance", morpheus_session, instance_id)

    def test_windows_instance_creation_with_selected_storage_and_host(self, morpheus_session):
        """Validate Windows instance creation with selected storage and host."""

        try:
            # Fetch host
            hosts_response = morpheus_session.hosts.list_hosts(name="hyperv-node-44")
            assert hosts_response.status_code == 200, "Failed to retrieve hosts!"
            servers = hosts_response.json().get("servers", [])
            assert servers, "No hosts found in the response."
            host_id = servers[0]["id"]

           # Create Instance
            instance_id, created_instance_name = SCVMMUtils.create_instance(
                morpheus_session=morpheus_session,
                instance_name= "test-instance-" + RandomGenUtils.random_string_of_chars(5),
                group_id= TestSCVMMPlugin.group_id,
                cloud_id= TestSCVMMPlugin.cloud_id,
                host_id=host_id,
            )
            TestSCVMMPlugin.instance_id = instance_id

            # Validate host assignment
            details = SCVMMUtils.get_instance_details(morpheus_session,instance_id)
            assert details["instance"]["config"]["hostId"] == host_id, "Host ID mismatch!"

        except Exception as e:
            pytest.fail(f"Instance creation test failed: {e}")

    def test_validate_reconfigure_operation_on_deployed_windows_instance(
            self, morpheus_session
    ):
        """Test case to validate the reconfigure operation on a deployed windows instance."""
        instance_id = TestSCVMMPlugin.instance_id
        try:
            # Fetch current instance details
            details = SCVMMUtils.get_instance_details(morpheus_session, instance_id)

            # Build payloads
            update_payload = SCVMMUtils.create_update_payload(labels=["Test1"])
            reconfig_payload = SCVMMUtils.create_reconfigure_payload(details)

            # Update labels
            resp = morpheus_session.instances.update_instance(id=instance_id, update_instance_request=update_payload)
            assert resp.status_code == 200, f"Update failed: {resp.text}"
            assert ResourcePoller.poll_instance_status(instance_id, "running", morpheus_session) == "running"

            # Reconfigure / resize
            resize_response = morpheus_session.instances.resize_instance(id=instance_id, resize_instance_request=reconfig_payload)
            assert resize_response.status_code == 200, f"Instance resize operation failed: {resize_response.text}"
            assert ResourcePoller.poll_instance_status(instance_id, "running", morpheus_session) == "running"

            # Validate applied changes
            final = SCVMMUtils.get_instance_details(morpheus_session, instance_id)
            SCVMMUtils.validate_labels(final, update_payload["instance"]["labels"])
            SCVMMUtils.validate_volume_size(final, reconfig_payload["volumes"][0]["size"])
            SCVMMUtils.validate_plan_id(final, reconfig_payload["instance"]["plan"]["id"])

        except Exception as e:
            pytest.fail(f"Reconfigure test failed: {e}")

    def test_validate_clone_instance_operation_on_windows_instance_with_agent_install_not_skipped(
            self, morpheus_session
    ):
        """Test case to validate clone windows instance with agent install not skipped operation."""
        instance_id = TestSCVMMPlugin.instance_id
        cloned_instance_id= None
        try:
            clone_name = f"clone-{instance_id}" + RandomGenUtils.random_string_of_chars(2)
            log.info(f"Cloning instance with id '{instance_id}'...")
            clone_payload = SCVMMUtils.create_clone_payload(clone_instance_name=clone_name,)
            clone_response = morpheus_session.instances.clone_instance(id=instance_id, clone_instance_request=clone_payload)
            assert clone_response.status_code == 200, "Instance clone operation failed!"

            # Verify original instance is still running
            assert ResourcePoller.poll_instance_status(instance_id, "running", morpheus_session) == "running"

            # Fetch cloned instance ID
            clone_ins_response = morpheus_session.instances.list_instances(name=clone_name)
            cloned_instance_id = clone_ins_response.json()["instances"][0]["id"]

            # Fetch cloned instance ID & verify it's running
            clone_id = morpheus_session.instances.list_instances(name=clone_name).json()["instances"][0]["id"]
            assert ResourcePoller.poll_instance_status(clone_id, "running", morpheus_session) == "running"

        except Exception as e:
            pytest.fail(f"Clone instance test failed: {e}")
        finally:
            if 'cloned_instance_id':
                SCVMMUtils.cleanup_resource("instance", morpheus_session, cloned_instance_id)

    def test_validate_backup_and_restore_operation_on_windows_instance(self, morpheus_session):
        """Test case to validate the backup and restore operation on a windows instance."""

        instance_id = TestSCVMMPlugin.instance_id
        backup_name = f"backup-instance-{instance_id}" + RandomGenUtils.random_string_of_chars(2)
        backup_job_name = f"backup-job-{instance_id}" + RandomGenUtils.random_string_of_chars(2)
        backup_id = None
        try:
            # Create backup
            instance_details = SCVMMUtils.get_instance_details(morpheus_session, instance_id)
            container_id = instance_details["instance"]["containers"][0]
            backup_payload = SCVMMUtils.create_backup_payload(instance_id, container_id, backup_name, backup_job_name)

            backup_response = morpheus_session.backups.add_backups(add_backups_request=backup_payload)
            assert backup_response.status_code == 200, "Instance backup operation failed!"
            backup_id = backup_response.json()["backup"]["id"]

            # Execute backup job
            backup_job_response = morpheus_session.session.post(
                f"{morpheus_session.base_url}/api/backups/jobs/{backup_id}/execute",
                headers=morpheus_session.session.headers,
                json={}
            )
            assert backup_job_response.status_code == 200, "Failed to execute backup job!"
            assert ResourcePoller.poll_backup_status(backup_id, "SUCCEEDED", morpheus_session) == "SUCCEEDED"

            # Restore backup
            last_result_id = morpheus_session.backups.get_backups(id=backup_id).json()["backup"]["lastResult"]["id"]
            restore_payload = SCVMMUtils.create_restore_payload(instance_id, last_result_id)

            restore_response = morpheus_session.backups.execute_backup_restore(execute_backup_restore_request=restore_payload)
            assert restore_response.status_code == 200, "Instance restore operation failed!"
            restore_id = restore_response.json()["restore"]["id"]

            assert ResourcePoller.poll_backup_restore_status(restore_id, "SUCCEEDED", morpheus_session) == "SUCCEEDED"

        except Exception as e:
            pytest.fail(f"Test failed with exception: {e}")

        finally:
            if backup_id:
                SCVMMUtils.cleanup_resource("backup", morpheus_session, backup_id)

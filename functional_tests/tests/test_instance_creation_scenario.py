"""
SCVMM Plugin related test cases.
"""

import logging
import os
import json

import pytest
from dotenv import load_dotenv
from hpe_glcp_automation_lib.libs.commons.utils.random_gens import RandomGenUtils
from functional_tests.common.cloud_helper import ResourcePoller
from functional_tests.common.scvmm_utils import SCVMMUtils

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

load_dotenv()

class TestSCVMMPlugin:
    """Test class for SCVMM plugin related functionalities."""

    group_id = None
    cloud_id = None
    instance_id = None

    def test_validate_windows_instance_creation_and_agent_installation(self, morpheus_session):
        """Validate Windows instance creation, agent installation, and basic operations."""
        try:
            # Upload SCVMM plugin
            SCVMMUtils.upload_scvmm_plugin()

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
                group_id= TestSCVMMPlugin.group_id,
                cloud_id= TestSCVMMPlugin.cloud_id
            )
            assert instance_id, "Instance creation failed!"
            TestSCVMMPlugin.instance_id= instance_id

            # Fetch instance details
            details = SCVMMUtils.get_instance_details(morpheus_session, instance_id)
            instance = details["instance"]
            server = details["instance"]["containerDetails"][0]["server"]

            #  Verify config
            assert instance["plan"]["id"] == int(os.getenv("PLAN_ID")), "Plan ID mismatch"
            assert instance["layout"]["id"] == int(os.getenv("SCVMM_LAYOUT_ID")), "Layout ID mismatch"

            # Ensure critical fields are not null
            for field in ["maxCores", "maxMemory", "maxStorage", "platform"]:
                assert server.get(field) is not None, f"{field} is missing in server details"

            #  Verify volumes
            expected_vols = json.loads(os.getenv("VOLUMES") or "[]")
            for ev in expected_vols:
                av = next((v for v in instance.get("volumes", []) if v["name"] == ev["name"]), None)
                assert av and av["size"] == ev["size"], f"Volume {ev['name']} size mismatch"

            # Verify agent
            log.info("Checking whether agent is installed..")
            assert server.get("agentInstalled"), "Agent installation failed!"
            log.info(f"Agent version: {server.get('agentVersion')} on instance {instance_id}")

            # Validate basic lifecycle operations
            for action, state in [("start", "running"), ("restart", "running"),
                                  ("stop", "stopped"), ("start", "running")]:
                SCVMMUtils.perform_instance_operation(morpheus_session, TestSCVMMPlugin.instance_id, action, state)

        except Exception as e:
            pytest.fail(f"Windows instance creation & agent validation failed: {e}")

    def test_windows_instance_creation_with_selected_storage_and_host(self, morpheus_session):
        """Validate Windows instance creation with selected storage and host."""

        instance_id= None
        try:
            # Fetch host
            hosts_response = morpheus_session.hosts.list_hosts(name=os.getenv("HOST_NAME"))
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

            # Validate host assignment
            details = SCVMMUtils.get_instance_details(morpheus_session,instance_id)
            instance = details["instance"]
            assert details["instance"]["config"]["hostId"] == host_id, "Host ID mismatch!"

            #  Verify volumes
            expected_vols = json.loads(os.getenv("VOLUMES") or "[]")
            for ev in expected_vols:
                av = next((v for v in instance.get("volumes", []) if v["name"] == ev["name"]), None)
                assert av and av["size"] == ev["size"], f"Volume {ev['name']} size mismatch"

        except Exception as e:
            pytest.fail(f"Instance creation test failed: {e}")
        finally:
            SCVMMUtils.cleanup_resource("instance", morpheus_session, instance_id)

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
            clone_payload = SCVMMUtils.create_clone_payload(clone_instance_name=clone_name)
            clone_response = morpheus_session.instances.clone_instance(id=instance_id, clone_instance_request=clone_payload)
            assert clone_response.status_code == 200, "Instance clone operation failed!"

            # Verify original instance is still running
            assert ResourcePoller.poll_instance_status(instance_id, "running", morpheus_session) == "running"

            # Fetch cloned instance ID & verify it's running
            cloned_instance_id = morpheus_session.instances.list_instances(name=clone_name).json()["instances"][0]["id"]
            assert ResourcePoller.poll_instance_status(cloned_instance_id, "running", morpheus_session) == "running"

            # Fetch cloned instance details
            details = SCVMMUtils.get_instance_details(morpheus_session, cloned_instance_id)
            instance = details["instance"]
            server = instance["containerDetails"][0]["server"]

            # Verify agent installation on cloned instance
            log.info("Checking whether agent is installed on cloned instance..")
            assert server.get("agentInstalled"), f"Agent not installed on cloned instance {cloned_instance_id}"
            log.info(f"Agent version: {server.get('agentVersion')} on cloned instance {cloned_instance_id}")
            # Verify storage size
            expected_size = clone_payload["volumes"][0]["size"]
            cloned_volumes = instance.get("volumes", [])
            assert cloned_volumes, "No volumes found in cloned instance"

            actual_size = cloned_volumes[0]["size"]
            assert actual_size == expected_size, \
                f"Volume size mismatch! Expected {expected_size}, got {actual_size}"
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

            # Create schedule (after 5 min cron)
            schedule_id = SCVMMUtils.create_execute_schedule(morpheus_session)
            backup_payload = SCVMMUtils.create_backup_payload(instance_id, container_id, backup_name, backup_job_name, schedule_id)

            backup_response = morpheus_session.backups.add_backups(add_backups_request=backup_payload)
            assert backup_response.status_code == 200, "Instance backup operation failed!"
            backup_id = backup_response.json()["backup"]["id"]

            #  Poll for backup job execution (runs after ~5 min)
            job_result_id, status = SCVMMUtils.poll_backup_job_execution(
                morpheus_session, backup_id, timeout=600, interval=30
            )
            assert status == "SUCCEEDED", f"Backup job failed with status {status}"
            #  Restore backup
            restore_payload = SCVMMUtils.create_restore_payload(instance_id, job_result_id)
            restore_response = morpheus_session.backups.execute_backup_restore(
                execute_backup_restore_request=restore_payload
            )
            assert restore_response.status_code == 200, "Restore operation failed!"
            restore_id = restore_response.json()["restore"]["id"]

            # Wait for restore completion
            assert ResourcePoller.poll_backup_restore_status(
                restore_id, "SUCCEEDED", morpheus_session
            ) == "SUCCEEDED", "Restore did not succeed!"

            log.info(f"Backup {backup_id} restored successfully for instance {instance_id}")

        except Exception as e:
            pytest.fail(f"Backup & restore test failed: {e}")

        finally:
            if backup_id:
                SCVMMUtils.cleanup_resource("backup", morpheus_session, backup_id)

    def test_validate_infrastructure_delete(self, morpheus_session):
        """Test case to validate the cleanup of created resources."""
        try:
            SCVMMUtils.verify_delete_resource(morpheus_session,
                "instance",
                morpheus_session.instances.list_instances,
                TestSCVMMPlugin.instance_id,
                "instances"
            )
            SCVMMUtils.verify_delete_resource(morpheus_session,
                "cloud",
                morpheus_session.clouds.list_clouds,
                TestSCVMMPlugin.cloud_id,
                "clouds"
            )
            SCVMMUtils.verify_delete_resource(morpheus_session,
                "group",
                morpheus_session.groups.list_groups,
                TestSCVMMPlugin.group_id,
                "groups"
            )
            # delete plugin
            SCVMMUtils.delete_scvmm_plugin(morpheus_session)

            log.info("Cleanup of created resources and plugin deletion completed successfully.")
        except Exception as e:
            pytest.fail(f"Cleanup test failed: {e}")

"""
SCVMM Plugin related test cases.
"""

import logging
import os
import json

import pytest
from dotenv import load_dotenv
from functional_tests.common.cloud_helper import ResourcePoller
from functional_tests.common.create_payloads import SCVMMpayloads
from functional_tests.common.scvmm_utils import SCVMMUtils
from functional_tests.common.common_utils import CommonUtils
from functional_tests.common.common_utils import DateTimeGenUtils

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

        plan_name = "2 Core, 8GB Memory"
        cluster_id= None
        try:
            # Upload SCVMM plugin
            SCVMMUtils.upload_scvmm_plugin()

            # Create a SCVMM group
            group_name = DateTimeGenUtils.name_with_datetime("scvmm-group", "%Y%m%d-%H%M%S")
            group_response = morpheus_session.groups.add_groups({"group": {"name": group_name}})
            assert group_response.status_code == 200, "Group creation failed!"
            TestSCVMMPlugin.group_id = group_response.json()["group"]["id"]

            # Create cloud
            TestSCVMMPlugin.cloud_id = SCVMMUtils.create_scvmm_cloud(morpheus_session, TestSCVMMPlugin.group_id)

            # Create cluster
            cluster_id = SCVMMUtils.create_scvmm_cluster(morpheus_session, TestSCVMMPlugin.cloud_id, TestSCVMMPlugin.group_id, plan_name)
            assert cluster_id, "Cluster creation failed! No cluster ID returned."

            template_name= os.getenv("SCVMM_TEMPLATE_NAME")
            template_id = CommonUtils.get_template_id(morpheus_session, template_name)

            # Create instance
            instance_id, _ = SCVMMUtils.create_instance(
                morpheus_session= morpheus_session,
                template_id=template_id,
                plan_name= plan_name,
                instance_name=DateTimeGenUtils.name_with_datetime("scvmm-inst", "%Y%m%d-%H%M%S"),
                group_id= TestSCVMMPlugin.group_id,
                cloud_id= TestSCVMMPlugin.cloud_id,

            )
            assert instance_id, "Instance creation failed!"
            TestSCVMMPlugin.instance_id= instance_id

            # Fetch instance details
            details = CommonUtils.get_instance_details(morpheus_session, instance_id)
            instance = details["instance"]
            server = details["instance"]["containerDetails"][0]["server"]

            # Fetch dynamic expected values
            expected_plan_id = CommonUtils.get_plan_id(
                morpheus_session,
                plan_name=plan_name,
                zone_id=TestSCVMMPlugin.cloud_id,
                group_id=TestSCVMMPlugin.group_id
            )
            expected_layout_id = CommonUtils.get_scvmm_instance_layout_id(morpheus_session)

            # Verify plan and layout dynamically
            assert instance["plan"][
                       "id"] == expected_plan_id, f"Plan ID mismatch! Expected {expected_plan_id}, got {instance['plan']['id']}"
            assert instance["layout"][
                       "id"] == expected_layout_id, f"Layout ID mismatch! Expected {expected_layout_id}, got {instance['layout']['id']}"

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
        finally:
            SCVMMUtils.cleanup_resource("cluster", morpheus_session, cluster_id)

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

            template_name = os.getenv("SCVMM_TEMPLATE_NAME")
            template_id = CommonUtils.get_template_id(morpheus_session, template_name)

            plan_name= "2 Core, 8GB Memory"

           # Create Instance
            instance_id, created_instance_name = SCVMMUtils.create_instance(
                morpheus_session=morpheus_session,
                template_id=template_id,
                plan_name=plan_name,
                instance_name= DateTimeGenUtils.name_with_datetime("scvmm-inst", "%Y%m%d-%H%M%S"),
                group_id= TestSCVMMPlugin.group_id,
                cloud_id= TestSCVMMPlugin.cloud_id,
                host_id=host_id
            )

            # Validate host assignment
            details = CommonUtils.get_instance_details(morpheus_session,instance_id)
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
            details = CommonUtils.get_instance_details(morpheus_session, instance_id)
            plan_name= "1 Core, 4GB Memory"

            # Build payloads
            update_payload = SCVMMpayloads.create_update_payload(labels=["Test1"])
            reconfig_payload = SCVMMpayloads.create_reconfigure_payload(
                morpheus_session,
                details,
                plan_name,
                TestSCVMMPlugin.cloud_id,
                TestSCVMMPlugin.group_id,
                new_volume_size = 85
            )
            log.info(f"Reconfigure Payload: {reconfig_payload}")
            # Update labels
            resp = morpheus_session.instances.update_instance(id=instance_id, update_instance_request=update_payload)
            assert resp.status_code == 200, f"Update failed: {resp.text}"
            assert ResourcePoller.poll_instance_status(instance_id, "running", morpheus_session) == "running"

            # Reconfigure / resize
            resize_response = morpheus_session.instances.resize_instance(id=instance_id, resize_instance_request=reconfig_payload)
            assert resize_response.status_code == 200, f"Instance resize operation failed: {resize_response.text}"
            assert ResourcePoller.poll_instance_status(instance_id, "running", morpheus_session) == "running"

            # Validate applied changes
            details = CommonUtils.get_instance_details(morpheus_session, instance_id)
            SCVMMUtils.validate_labels(details, update_payload["instance"]["labels"])
            SCVMMUtils.validate_volume_size(details, reconfig_payload["volumes"][0]["size"])
            SCVMMUtils.validate_plan_id(details, reconfig_payload["instance"]["plan"]["id"])

        except Exception as e:
            pytest.fail(f"Reconfigure test failed: {e}")

    def test_validate_clone_instance_operation_on_windows_instance_with_agent_install_not_skipped(
            self, morpheus_session
    ):
        """Test case to validate clone windows instance with agent install not skipped operation."""
        instance_id = TestSCVMMPlugin.instance_id
        cloned_instance_id= None
        try:
            clone_name = DateTimeGenUtils.name_with_datetime("clone-inst", "%Y%m%d-%H%M%S")
            log.info(f"Cloning instance with id '{instance_id}'...")
            details = CommonUtils.get_instance_details(morpheus_session, instance_id)
            clone_payload = SCVMMpayloads.create_clone_payload(clone_instance_name=clone_name, instance_details= details)
            clone_response = morpheus_session.instances.clone_instance(id=instance_id, clone_instance_request=clone_payload)
            assert clone_response.status_code == 200, "Instance clone operation failed!"

            # Verify original instance is still running
            assert ResourcePoller.poll_instance_status(instance_id, "running", morpheus_session) == "running"

            # Fetch cloned instance ID & verify it's running
            cloned_instance_id = morpheus_session.instances.list_instances(name=clone_name).json()["instances"][0]["id"]
            assert ResourcePoller.poll_instance_status(cloned_instance_id, "running", morpheus_session) == "running"

            # Fetch cloned instance details
            details = CommonUtils.get_instance_details(morpheus_session, cloned_instance_id)
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
        backup_name = DateTimeGenUtils.name_with_datetime("backup-inst", "%Y%m%d-%H%M%S")
        backup_job_name = DateTimeGenUtils.name_with_datetime("backup-job", "%Y%m%d-%H%M%S")
        backup_id = None
        schedule_id = None
        try:
            # Create backup
            container_id = CommonUtils.get_container_id(morpheus_session, instance_id)

            # Create schedule (after 5 min cron)
            schedule_id = SCVMMUtils.create_execute_schedule(morpheus_session)
            TestSCVMMPlugin.schedule_id= schedule_id
            backup_payload = SCVMMpayloads.create_backup_payload(instance_id, backup_name, container_id, backup_job_name, schedule_id)
            log.info(f"Backup Payload: {backup_payload}")

            backup_response = morpheus_session.backups.add_backups(add_backups_request=backup_payload)
            assert backup_response.status_code == 200, "Instance backup operation failed!"
            backup_id = backup_response.json()["backup"]["id"]

            #  Poll for backup job execution (runs after ~5 min)
            job_result_id, status = ResourcePoller.poll_backup_job_execution(
                morpheus_session, backup_id, timeout=600, interval=30
            )
            assert status == "SUCCEEDED", f"Backup job failed with status {status}"
            #  Restore backup
            restore_payload = SCVMMpayloads.create_restore_payload(instance_id, job_result_id)
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

            if schedule_id:
                SCVMMUtils.cleanup_resource("schedule", morpheus_session, schedule_id)

    def test_validate_cluster_creation_and_deletion(self, morpheus_session):
        """ Test case to validate the cluster creation workflow"""
        cloud_id = TestSCVMMPlugin.cloud_id
        group_id = TestSCVMMPlugin.group_id
        plan_name = "1 Core, 4GB Memory"

        cluster_id = SCVMMUtils.create_scvmm_cluster(morpheus_session, cloud_id, group_id, plan_name)
        assert cluster_id, "Cluster creation failed! No cluster ID returned."

        cluster_details = morpheus_session.clusters.get_cluster(cluster_id=cluster_id).json().get("cluster", {})
        assert cluster_details.get("id") == cluster_id, f"No cluster found with id: {cluster_id}"
        assert cluster_details.get(
            "status") == "ok", f"Cluster creation failed, current status: {cluster_details.get('status')}"
        assert cluster_details.get("layout", {}).get("id"), "Layout ID missing in cluster details"
        log.info(f"Cluster {cluster_id} verified successfully with status ok")

        # Delete cluster
        SCVMMUtils.cleanup_resource(resource_type="cluster", morpheus_session=morpheus_session, resource_id=cluster_id)

        # Verify deletion
        get_resp = morpheus_session.clusters.get_cluster(cluster_id=cluster_id)
        assert get_resp.status_code == 404, f"Cluster '{cluster_id}' still exists after deletion!"
        log.info(f"Cluster {cluster_id} deleted successfully and verified.")

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

            log.info("Cleanup of created resources and plugin uninstall completed successfully.")
        except Exception as e:
            pytest.fail(f"Cleanup test failed: {e}")

import json
import os
import glob
import subprocess

import pytest
import logging
import time

from hpe_glcp_automation_lib.libs.commons.utils.random_gens import RandomGenUtils
from hpe_morpheus_automation_libs.api.external_api.cloud.clouds_api import CloudAPI
from dotenv import load_dotenv
from hpe_morpheus_automation_libs.api.external_api.cloud.clouds_payload import DeleteCloud
from hpe_morpheus_automation_libs.api.external_api.plugins.plugin_api import PluginAPI

from functional_tests.common.cloud_helper import ResourcePoller

log = logging.getLogger(__name__)

load_dotenv()

host = os.getenv("BASE_URL")
admin_username = os.getenv("USERNAME")
admin_password = os.getenv("PASSWORD")

class SCVMMUtils:
    """Helper methods for SCVMM operations."""

    @staticmethod
    def get_create_instance_payload(instance_name, template, group_id, cloud_id, host_id=None):
        """Helper function to create the payload for instance creation."""
        return {
            "instance": {
                "name": instance_name,
                "site": {"id": group_id},
                "instanceType": {"code": "scvmm"},
                "layout": {
                    "id": int(os.getenv("SCVMM_LAYOUT_ID"))

                },
                "plan": {
                    "id": int(os.getenv("PLAN_ID"))
                },
            },
            "zoneId": cloud_id,
            "networkInterfaces": [
                {
                    "network": {
                        "id": str(os.getenv("NETWORK_ID"))
                    }
                }
            ],
            "config": {
                "noAgent": False,
                "hostId": host_id,
                "template": int(template),
                "scvmmCapabilityProfile": "Hyper-V",
                "createUser": False,
                "backup": {  "providerBackupType": int(os.getenv("BACKUP_TYPE_ID"))
                }
            },
            "labels": ["TEST"],
            "volumes": json.loads(os.getenv("VOLUMES")) if os.getenv("VOLUMES") else [],
        }

    @staticmethod
    def get_create_cloud_payload(cloud_name, group_id, zone_type_id):
        """Helper function to create the payload for cloud creation."""
        return {
            "zone": {
                "name": cloud_name,
                "credential": {"type": "local"},
                "zoneType": {"id": zone_type_id},
                "groups": {"id": group_id},
                "groupId": group_id,
                "config": {
                    "host": os.getenv("HOST"),
                    "username": os.getenv("HOST_USERNAME"),
                    "password": os.getenv("HOST_PASSWORD"),
                    "sharedController": os.getenv("SHARED_CONTROLLER"),
                },
            }
        }

    @staticmethod
    def upload_scvmm_plugin():
        """
        Builds and uploads the SCVMM plugin JAR file using the provided PluginAPI instance.
        Automatically detects the generated JAR file without requiring version input.
        """
        plugin_api = PluginAPI(host=host, username=admin_username, password=admin_password)
        current_dir = os.getcwd()
        jar_dir = os.path.join(current_dir, "build", "libs")

        # --- Step 1: Build the JAR ---
        try:
            log.info("Running './gradlew shadowJar' to build plugin JAR...")
            subprocess.run(["./gradlew", "shadowJar"], cwd=current_dir, check=True)
            log.info("Build completed successfully.")
        except subprocess.CalledProcessError as e:
            log.error(f"Gradle build failed: {e}")
            pytest.fail(f"Gradle build failed: {e}")

        # --- Step 2: Search for JAR ---
        log.info(f"Searching for plugin JAR in {jar_dir}")
        pattern = os.path.join(jar_dir, "morpheus-scvmm-plugin-*.jar")
        matching_files = glob.glob(pattern)

        if not matching_files:
            raise FileNotFoundError("No plugin JAR file found in build/libs")
        elif len(matching_files) > 1:
            log.warning(f"Multiple JARs found: {matching_files}, using latest.")

        # Pick the newest JAR by modified time
        jar_file_path = max(matching_files, key=os.path.getmtime)
        log.info(f"Found plugin JAR: {jar_file_path}")
        log.info("Uploading plugin...")

        # --- Step 3: Upload ---
        try:
            plugin_response = plugin_api.upload_plugin(jar_file_path=jar_file_path)
            log.info(f"Response Status Code: {plugin_response.status_code}")
            assert plugin_response.status_code == 200, f"Plugin upload failed: {plugin_response.text}"
            log.info("Plugin uploaded successfully.")
            return plugin_response
        except Exception as e:
            log.error(f"Plugin upload failed: {e}")
            pytest.fail(f"Plugin upload failed: {e}")

    @staticmethod
    def create_scvmm_cloud(morpheus_session, group_id):
        """
        function to create scvmm cloud and wait until it's active
        """
        cloud_name = "test-scvmm-cloud-" + RandomGenUtils.random_string_of_chars(5)

        # 1. Get zone types
        zone_type_response = morpheus_session.clouds.list_cloud_types()
        assert zone_type_response.status_code == 200, "Failed to retrieve cloud types!"

        zone_types = zone_type_response.json().get("zoneTypes", [])
        zone_type_id = None
        for zone in zone_types:
            if zone.get("name") == "SCVMM":
                zone_type_id = zone.get("id")
                break

        assert zone_type_id is not None, "SCVMM zone type not found!"

        # 2. Build payload
        cloud_payload = SCVMMUtils.get_create_cloud_payload(
            cloud_name=cloud_name, group_id=group_id, zone_type_id=zone_type_id
        )

        # 3. Create cloud
        cloud_response = morpheus_session.clouds.add_clouds(cloud_payload)
        assert cloud_response.status_code == 200, "Cloud creation failed!"
        cloud_id = cloud_response.json()["zone"]["id"]
        log.info(f"cloud_id: {cloud_id}")

        # 4. Poll until active
        final_status = ResourcePoller.poll_cloud_status(
            cloud_id=cloud_id,
            morpheus_session=morpheus_session,
        )
        assert final_status == "ok", f"Cloud creation failed with status: {final_status}"

        log.info(f"Cloud '{cloud_name}' created successfully with ID: {cloud_id}")
        return cloud_id

    @staticmethod
    def create_scvmm_cluster(morpheus_session, cloud_id, group_id):
        cluster_name = "test-scvmm-cluster-" + RandomGenUtils.random_string_of_chars(5)

        # Fetching cluster-type ID for SCVMM
        cluster_type_response = morpheus_session.clusters.list_cluster_types()
        assert (cluster_type_response.status_code == 200), "Failed to retrieve cluster types!"
        cluster_types = cluster_type_response.json().get("clusterTypes", [])
        cluster_type_id = None
        for cluster in cluster_types:
            if cluster.get("code") == "docker-cluster":
                cluster_type_id = cluster.get("id")
                break
        assert cluster_type_id is not None, "SCVMM cluster type not found!"
        log.info(f"Cluster type ID: {cluster_type_id}")

        # Fetching layout ID for SCVMM
        layout_response = morpheus_session.cluster_layouts.list_cluster_layouts()
        assert (layout_response.status_code == 200), "Failed to retrieve cluster layouts!"
        layouts = layout_response.json().get("layouts", [])
        layout_id = None
        for layout in layouts:
            if layout.get("name") == "SCVMM Docker Host":
                layout_id = layout.get("id")
                log.info(f"Layout ID: {layout_id}")
                break
        assert layout_id is not None, "SCVMM cluster layout not found!"

        cluster_payload = {
            "cluster": {
                "name": cluster_name,
                "cloud": {"id": cloud_id},
                "type": {"id": cluster_type_id},
                "layout": {"id": layout_id},
                "server": {
                    "id": int(os.getenv("SERVER_ID")),
                    "name": cluster_name,
                    "plan": {
                        "id": int(os.getenv("PLAN_ID"))
                    },
                    "config": {},
                },
                "group": {"id": group_id},
            }
        }

        cluster_response = morpheus_session.clusters.add_cluster(cluster_payload)

        if cluster_response.status_code == 200:
            cluster_id = cluster_response.json()["cluster"]["id"]
            log.info(f"cluster_id: {cluster_id}")

            final_status = ResourcePoller.poll_cluster_status(cluster_id=cluster_id,morpheus_session=morpheus_session)
            if final_status == "ok":
                log.info(f"Cluster '{cluster_name}' registered successfully with ID: {cluster_id}")
            else:
                log.warning(f"Cluster registration failed with status: {final_status}")
        else:
            log.warning("Cluster registration failed! Could not create cluster.")

    @staticmethod
    def create_instance(morpheus_session, instance_name=None, group_id=None, cloud_id=None, host_id=None):
        """
        Generic method to create an instance and wait until it's running.

        :param morpheus_session: Active Morpheus session
        :param instance_name: Optional name; if None, random name will be generated
        :param template: Template ID for instance (optional)
        :param group_id: Group ID for instance
        :param cloud_id: Cloud ID for instance
        :param host_id: Host ID for instance (optional)
        :return: (instance_id, instance_name)
        """
        log.info("Creating instance...")
        if not instance_name:
            instance_name = "test-scvmm-instance-" + RandomGenUtils.random_string_of_chars(3)

        # Fetching template
        template_name = os.getenv("SCVMM_TEMPLATE_NAME")
        filter_type = "Synced"
        template_response = morpheus_session.library.list_virtual_images(name=template_name, filter_type=filter_type)
        assert template_response.status_code == 200, "Failed to retrieve templates!"
        template_data = template_response.json()
        template_id = template_data["virtualImages"][0]["id"]

        # Generate payload
        log.info("Generating instance payload...")
        create_instance_payload = SCVMMUtils.get_create_instance_payload(
            instance_name=instance_name, template= template_id, group_id=group_id, cloud_id=cloud_id, host_id=host_id
        )
        log.info("Payload generated successfully")

        # Send request
        instance_response = morpheus_session.instances.add_instance(add_instance_request=create_instance_payload)

        if instance_response.status_code == 200:
            instance_id = instance_response.json()["instance"]["id"]

            final_status = ResourcePoller.poll_instance_status(
                instance_id=instance_id,
                target_state="running",
                morpheus_session=morpheus_session,
            )

            if final_status == "running":
                instance_name = instance_response.json()["instance"]["name"]
                log.info(f"Instance '{instance_name}' created successfully with ID: {instance_id}")
                return instance_id, instance_name
            else:
                log.warning(f"Instance creation failed with status: {final_status}")
                return None, None
        else:
            log.warning("Instance creation failed! Could not create instance.")
            return None, None

    @staticmethod
    def perform_instance_operation(
        morpheus_session,
        instance_id,
        operation: str,
        expected_status: str
    ):
        """
        Perform an operation (start, restart, stop) on a given instance
        and validate the final status.
        """
        log.info(f"Initiating '{operation}' operation on instance '{instance_id}'...")

        # Call correct API based on operation
        if operation == "start":
            response = morpheus_session.instances.start_instance(instance_id)
        elif operation == "restart":
            response = morpheus_session.instances.restart_instance(instance_id)
        elif operation == "stop":
            response = morpheus_session.instances.stop_instance(instance_id)
        else:
            raise ValueError(f"Unsupported operation: {operation}")

        assert response.status_code == 200, f"Instance {operation} operation failed!"

        final_status = ResourcePoller.poll_instance_status(instance_id, expected_status, morpheus_session)
        assert final_status == expected_status, f"Instance {operation} failed, current status: {final_status}"

        log.info(f"Instance '{instance_id}' {operation}ed successfully.")
        return final_status

    @staticmethod
    def get_instance_details(morpheus_session, instance_id, assert_message=None):
        """
        Fetch instance details and assert the API call was successful.

        Args:
            morpheus_session: Active Morpheus session
            instance_id (int): ID of the instance to fetch
            assert_message (str, optional): Custom error message if request fails

        Returns:
            dict: JSON response of the instance details
        """
        response = morpheus_session.instances.get_instance(id=instance_id)
        message = assert_message or f"Failed to retrieve instance details for {instance_id}!"
        assert response.status_code == 200, message
        return response.json()

    @staticmethod
    def create_update_payload(labels):
        """Create payload for updating instance labels."""
        return {
            "instance": {
                "labels": labels
            }
        }

    @staticmethod
    def create_reconfigure_payload(instance_details):
        """Create payload for reconfiguring an instance."""
        volume = instance_details["instance"]["volumes"][0]

        return {
            "instance": {
                "plan": {
                    "id": 163
                }
            },
            "volumes": [
                {
                    "size": 85,
                    "id": volume["id"],
                    "name": volume["name"],
                    "rootVolume": volume.get("rootVolume", True),
                    "storageType": 1,
                }
            ],
            "networkInterfaces": [
                {
                    "network": {"id": os.getenv("NETWORK_ID")},
                }
            ]
        }

    @staticmethod
    def validate_labels(final_details: dict, expected_labels: list[str]) -> None:
        """Validate that all expected labels exist in the instance details."""
        final_labels = final_details["instance"].get("labels", [])
        for label in expected_labels:
            assert label in final_labels, f"Expected label '{label}' not found in {final_labels}"
        log.info(f"Labels validated successfully: {final_labels}")

    @staticmethod
    def validate_plan_id(final_details: dict, expected_plan_id: int) -> None:
        """Validate that the plan ID matches the expected value."""
        final_plan_id = final_details["instance"]["plan"]["id"]
        assert final_plan_id == expected_plan_id, (
            f"Expected plan ID {expected_plan_id}, but got {final_plan_id}"
        )
        log.info(f"Plan ID validated successfully: {final_plan_id}")

    @staticmethod
    def validate_volume_size(final_details: dict, expected_volume_size: int) -> None:
        """Validate that the volume size matches the expected value."""
        final_volume_size = final_details["instance"]["volumes"][0]["size"]
        assert final_volume_size == expected_volume_size, (
            f"Expected volume size {expected_volume_size}, but got {final_volume_size}"
        )
        log.info(f"Volume size validated successfully: {final_volume_size}")

    @staticmethod
    def create_clone_payload(clone_instance_name: str):
        """
        Create payload for cloning an instance.

        :param clone_instance_name: Name for the cloned instance
        :return: Clone payload dictionary
        """
        return {
            "name": clone_instance_name,
            "volumes": [
                {
                    "datastoreId": "auto",
                    "size": 90,
                }
            ]
        }

    @staticmethod
    def create_backup_payload(instance_id, container_id, backup_name, backup_job_name, schedule_id):
        """
        Create payload for backup creation.
        """
        return {
            "backup": {
                "locationType": "instance",
                "backupType": "scvmmSnapshot",
                "jobAction": "new",
                "jobSchedule": schedule_id,
                "name": backup_name,
                "instanceId": instance_id,
                "retentionCount": 2,
                "jobName": backup_job_name,
                "containerId": container_id
            }
        }

    @staticmethod
    def create_restore_payload(instance_id, last_backup_result_id):
        """Generate payload for restoring a backup."""
        return {
            "restore": {
                "restoreInstance": "existing",
                "backupResultId": last_backup_result_id,
                "instanceId": instance_id,
            }
        }

    @staticmethod
    def cleanup_resource(resource_type: str, morpheus_session, resource_id: int):
        """
        Generic cleanup function to delete a resource (instance/backup/etc.).

        Args:
            resource_type (str): Type of resource ('instance', 'backup', etc.)
            morpheus_session: Active Morpheus session object
            resource_id (int): ID of the resource to delete
        """
        if not resource_id:
            log.info(f"No {resource_type} was created, so nothing to clean up.")
            return

        log.info(f"Cleaning up {resource_type} '{resource_id}'...")
        try:
            if resource_type == "instance":
                delete_response = morpheus_session.instances.delete_instance(id=resource_id)
            elif resource_type == "backup":
                delete_response = morpheus_session.backups.remove_backups(id=resource_id)
            elif resource_type == "clone":
                delete_response = morpheus_session.instances.delete_instance(id=resource_id)
            elif resource_type == "cluster":
                delete_response = morpheus_session.clusters.delete_cluster(cluster_id=resource_id)
            elif resource_type == "group":
                delete_response = morpheus_session.groups.remove_groups(id=resource_id)
            elif resource_type == "cloud":
                cloud_api= CloudAPI(host=host, username=admin_username, password=admin_password)
                delete_response= cloud_api.delete_cloud(cloud_id= str(resource_id), qparams=DeleteCloud(force="true"))
            else:
                log.warning(f"Cleanup for resource type '{resource_type}' is not supported.")
                return

            if delete_response.status_code == 200:
                log.info(f"{resource_type.capitalize()} '{resource_id}' deleted successfully.")
            else:
                log.warning(
                    f"Failed to delete {resource_type} '{resource_id}': {delete_response.text}"
                )
        except Exception as e:
            log.error(f"Cleanup of {resource_type} failed with exception: {e}")

    @staticmethod
    def create_execute_schedule(morpheus_session):
        """
        Create a schedule to run every minute.
        """
        schedule_payload = {
            "schedule": {
                "name": "test-schedule-" + RandomGenUtils.random_string_of_chars(3),
                "enabled": True,
                "cron": "*/5  * * * *",
            }
        }
        response = morpheus_session.automation.add_execute_schedules(add_execute_schedules_request=schedule_payload)
        assert response.status_code == 200, "Failed to create schedule!"
        schedule_id = response.json()["schedule"]["id"]
        log.info(f"Schedule created successfully with ID: {schedule_id}")
        return schedule_id

    @staticmethod
    def wait_for_agent_installation(morpheus_session, instance_id, retries=30, interval=10):
        """
        Poll until agent is installed on the instance.
        :param morpheus_session: API session
        :param instance_id: ID of the instance
        :param retries: Number of retries
        :param interval: Sleep interval (seconds) between retries
        :return: server details dict if agent is installed
        """
        for attempt in range(retries):
            details = SCVMMUtils.get_instance_details(morpheus_session, instance_id)
            container_details = details["instance"].get("containerDetails", [])
            if container_details and container_details[0]["server"].get("agentInstalled"):
                log.info(
                    f"Agent installed on instance {instance_id} "
                    f"(attempt {attempt + 1}/{retries})"
                )
                return container_details[0]["server"]
            time.sleep(interval)

        pytest.fail(f"Agent installation did not complete within {retries * interval} seconds")

    @staticmethod
    def wait_for_backup_job_completion(morpheus_session, backup_job_id, timeout=7 * 60, interval=30):
        """
        Wait for a scheduled backup job to complete.
        """
        end_time = time.time() + timeout
        last_result = None

        while time.time() < end_time:
            job_details = SCVMMUtils.get_backup_job_details(morpheus_session, backup_job_id)
            last_result = job_details["job"].get("lastResult")

            if last_result:
                job_result_id = last_result["id"]
                status = last_result["status"]
                log.info(f"Backup job result {job_result_id} status: {status}")

                if status in ["SUCCEEDED", "FAILED"]:
                    break
            time.sleep(interval)

        assert last_result, "No backup job result found after schedule!"
        assert last_result["status"] == "SUCCEEDED", f"Backup job failed with status {last_result['status']}"

        return last_result

    @staticmethod
    def get_backup_job_details(morpheus_session, backup_id):
        """Fetch backup job details by backup ID."""
        response = morpheus_session.session.get(
            f"{morpheus_session.base_url}/api/backups/{backup_id}",
            headers=morpheus_session.session.headers
        )
        response.raise_for_status()
        return response.json()

    @staticmethod
    def poll_backup_job_execution(morpheus_session, backup_id, timeout=600, interval=30):
        """
        Poll until backup job executes and produces a result.

        Returns:
            (last_result_id, status)
        """
        start = time.time()
        while time.time() - start < timeout:
            job_details = SCVMMUtils.get_backup_job_details(morpheus_session, backup_id)
            last_result = job_details["backup"].get("lastResult")

            if last_result:
                job_result_id = last_result["id"]
                status = last_result["status"]
                log.info(f"Backup job result {job_result_id} status: {status}")
                if status in ["SUCCEEDED", "FAILED"]:
                    return job_result_id, status
            time.sleep(interval)

        pytest.fail("Backup job did not execute within expected time")

    @staticmethod
    def verify_delete_resource(morpheus_session, resource_type, list_func, resource_id, key, retries=5, delay=3):
        """
        Verify that a resource is deleted by polling the list API.
        """
        if not resource_id:
            log.info(f"No {resource_type} was created, skipping cleanup.")
            return

        delete_response = SCVMMUtils.cleanup_resource(resource_type, morpheus_session, resource_id)

        # Poll to check if resource is really gone
        for attempt in range(1, retries + 1):
            resp = list_func()
            assert resp.status_code == 200, f"Failed to fetch {resource_type} list!"
            resources = resp.json().get(key, []) or []
            ids = [r["id"] for r in resources]

            if resource_id not in ids:
                log.info(f"{resource_type.capitalize()} {resource_id} already absent or deleted successfully.")
                return

            log.warning(f"{resource_type.capitalize()} {resource_id} still present, retry {attempt}/{retries}...")
            time.sleep(delay)

        pytest.fail(f"{resource_type.capitalize()} {resource_id} still exists after {retries * delay}s!")

    @staticmethod
    def delete_scvmm_plugin(self):
        """Deletes the SCVMM plugin."""
        plugin_api = PluginAPI(host=host, username=admin_username, password=admin_password)
        response = plugin_api.get_all_plugins()
        assert response.status_code == 200, "Failed to retrieve plugins!"

        plugins = response.json().get("plugins", [])
        scvmm_plugin = next((p for p in plugins if p.get("code") == "morpheus-scvmm-plugin"), None)

        if not scvmm_plugin:
            log.info("SCVMM plugin not found, nothing to delete.")
            return

        plugin_id = scvmm_plugin["id"]
        log.info(f"Deleting SCVMM plugin with ID {plugin_id}...")
        delete_response = plugin_api.delete_plugin(plugin_id=plugin_id)
        assert delete_response.status_code == 200, "Failed to delete SCVMM plugin!"
        log.info(f"SCVMM plugin with ID {plugin_id} deleted successfully.")
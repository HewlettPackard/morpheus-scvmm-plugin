import json
import os
import glob
import subprocess

import pytest
import logging
import time

from hpe_glcp_automation_lib.libs.commons.utils.common_utils.common_utils import CommonUtils
from hpe_morpheus_automation_libs.api.external_api.cloud.clouds_api import CloudAPI
from dotenv import load_dotenv
from hpe_morpheus_automation_libs.api.external_api.cloud.clouds_payload import DeleteCloud
from hpe_morpheus_automation_libs.api.external_api.plugins.plugin_api import PluginAPI

from functional_tests.common.cloud_helper import ResourcePoller
from functional_tests.common.create_payloads import SCVMMpayloads
from functional_tests.common.common_utils import CommonUtils, DateTimeGenUtils

log = logging.getLogger(__name__)

load_dotenv()

host = os.getenv("BASE_URL")
admin_username = os.getenv("USERNAME")
admin_password = os.getenv("PASSWORD")

class SCVMMUtils:
    """Helper methods for SCVMM operations."""

    @staticmethod
    def upload_scvmm_plugin():
        """
        Builds and uploads the SCVMM plugin JAR file using the PluginAPI instance.
        Automatically builds the plugin, locates the latest JAR, and uploads it to Morpheus.
        """
        try:
            # Step 1: Initialize Plugin API with environment variables
            plugin_api = PluginAPI(
                host= host,
                username= admin_username,
                password= admin_password,
            )

            current_dir = os.getcwd()
            jar_dir = os.path.join(current_dir, "build", "libs")

            # Step 2: Build the plugin using Gradle
            log.info("Running './gradlew shadowJar' to build SCVMM plugin JAR...")
            cmd = (
                'curl -s "https://get.sdkman.io" | bash && '
                'source "$HOME/.sdkman/bin/sdkman-init.sh" && '
                "SDKMAN_NON_INTERACTIVE=true sdk install java 17.0.11-jbr && "
                "sdk use java 17.0.11-jbr && "
                "./gradlew shadowJar"
            )
            subprocess.run(["bash", "-lc", cmd], cwd=current_dir, check=True)
            log.info("Build completed successfully.")

            # Step 3: Locate the built JAR file
            log.info(f"Searching for plugin JAR in {jar_dir}")
            pattern = os.path.join(jar_dir, "morpheus-scvmm-plugin-*.jar")
            matching_files = glob.glob(pattern)

            if not matching_files:
                raise FileNotFoundError("No plugin JAR file found in build/libs")
            elif len(matching_files) > 1:
                matching_files.sort(key=os.path.getmtime, reverse=True)
                log.warning(
                    f"Multiple JARs found: {matching_files}. Using the latest one: {matching_files[0]}"
                )

            jar_file_path = matching_files[0]
            log.info(f"Found plugin JAR: {jar_file_path}")

            # Step 4: Upload plugin
            log.info("Uploading plugin to Morpheus...")
            plugin_response = plugin_api.upload_plugin(jar_file_path=jar_file_path)
            log.info(f"Response Status Code: {plugin_response.status_code}")

            assert plugin_response.status_code == 200, f"Plugin upload failed: {plugin_response.text}"
            log.info("Plugin uploaded successfully.")
            return plugin_response

        except subprocess.CalledProcessError as e:
            log.error(f"Plugin build failed: {e}")
            pytest.fail(f"Plugin build failed: {e}")

        except Exception as e:
            log.error(f"Plugin upload failed: {e}")
            pytest.fail(f"Plugin upload failed with error: {e}")

    @staticmethod
    def create_scvmm_cloud(morpheus_session, group_id):
        """
        function to create scvmm cloud and wait until it's active
        """
        cloud_name = DateTimeGenUtils.name_with_datetime("scvmm-cloud", "%Y%m%d-%H%M%S")

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

        api_proxy_id= CommonUtils.get_network_proxy(morpheus_session)
        provisioning_proxy= api_proxy_id

        # 2. Build payload
        cloud_payload = SCVMMpayloads.get_create_cloud_payload(
            cloud_name=cloud_name, group_id=group_id, zone_type_id=zone_type_id, api_proxy_id= api_proxy_id, provisioning_proxy= provisioning_proxy
        )
        log.info(f"Cloud Payload: {json.dumps(cloud_payload, indent=2)}")

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
    def create_scvmm_cluster(morpheus_session, cloud_id, group_id, plan_name, cluster_name):
        """ function to create scvmm cluster and wait until it's active"""

        # Fetching cluster-type ID for SCVMM
        log.info("Fetching cluster types...")
        cluster_type_response = morpheus_session.clusters.list_cluster_types()
        assert (cluster_type_response.status_code == 200), "Failed to retrieve cluster types!"
        cluster_types = cluster_type_response.json().get("clusterTypes", [])

        log.info("Searching for SCVMM cluster type...")
        cluster_type_id = None
        for cluster in cluster_types:
            if cluster.get("code") == os.getenv("CLUSTER_TYPE"):
                cluster_type_id = cluster.get("id")
                break
        assert cluster_type_id is not None, "SCVMM cluster type not found!"
        log.info(f"Cluster type ID: {cluster_type_id}")

        # Fetching layout ID for SCVMM
        log.info("Fetching cluster layouts...")
        layout_response = morpheus_session.cluster_layouts.list_cluster_layouts(phrase= os.getenv("CLUSTER_LAYOUT_NAME"))
        assert (layout_response.status_code == 200), "Failed to retrieve cluster layouts!"
        layouts = layout_response.json().get("layouts", [])
        layout_id = None
        if layouts:
            layout_id = layouts[0].get("id")
            log.info(f"Layout ID: {layout_id}")

        assert layout_id is not None, "SCVMM cluster layout not found!"

        #fetch plan_id
        log.info("Fetching plan ID...")
        plan_id= CommonUtils.get_plan_id(morpheus_session, plan_name= plan_name, zone_id=cloud_id, group_id=group_id)

        cluster_payload= SCVMMpayloads.get_create_cluster_payload(morpheus_session,cluster_name, group_id, cloud_id, layout_id, cluster_type_id, plan_id)
        log.info(f"Cluster Payload: {json.dumps(cluster_payload, indent=2)}")

        cluster_response = morpheus_session.session.post(
            f"{morpheus_session.base_url}/api/clusters",
            headers=morpheus_session.session.headers,
            json=cluster_payload,
            verify=False
        )
        assert cluster_response.status_code == 200, "Cluster creation failed!"

        cluster_id = cluster_response.json()["cluster"]["id"]
        log.info(f"Cluster ID: {cluster_id}")

        final_status = ResourcePoller.poll_cluster_status(cluster_id=cluster_id, morpheus_session=morpheus_session)
        if final_status == "ok":
            log.info(f"Cluster '{cluster_name}' registered successfully with ID: {cluster_id}")
            return cluster_id
        else:
            log.warning(f"Cluster registration failed with status: {final_status}")
            return None

    @staticmethod
    def create_instance(morpheus_session, layout_code, template_id=None, plan_name=None, instance_name=None, group_id=None, cloud_id=None, host_id=None, instance_type_code= None):
        """
        Generic method to create an instance and wait until it's running.

        :param morpheus_session: Active Morpheus session
        :param layout_code: Layout code for instance
        :param instance_name: Optional name; if None, random name will be generated
        :param template_id: Template ID for instance
        :param plan_name: Plan name for instance
        :param group_id: Group ID for instance
        :param cloud_id: Cloud ID for instance
        :param host_id: Host ID for instance (optional)
        :param instance_type_code: Instance type code
        :return: (instance_id, instance_name)
        """
        log.info("Creating instance...")
        if not instance_name:
            instance_name = DateTimeGenUtils.name_with_datetime("scvmm-inst", "%Y%m%d-%H%M%S")

        # Generate payload
        log.info("Generating instance payload...")
        plan_id= CommonUtils.get_plan_id(morpheus_session, plan_name=plan_name, zone_id=cloud_id, group_id=group_id)
        create_instance_payload = SCVMMpayloads.get_create_instance_payload(
            morpheus_session,
            instance_name=instance_name,
            template=template_id if template_id else None,
            group_id=group_id,
            cloud_id=cloud_id,
            plan_id=plan_id,
            instance_type_code=instance_type_code,
            layout_code=layout_code,
            host_id=host_id
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
                # Trigger backup creation
                backup_name= f"test-backup-{instance_id}"
                SCVMMUtils.create_backup(morpheus_session, instance_id, backup_name)
                return instance_id, instance_name
            else:
                log.warning(f"Instance creation failed with status: {final_status}")
                return None, None
        else:
            log.warning("Instance creation failed! Could not create instance.")
            return None, None

    @staticmethod
    def create_backup(morpheus_session, instance_id, backup_name):
        """ Create backup"""
        container_id= CommonUtils.get_container_id(morpheus_session,instance_id)
        backup_payload= SCVMMpayloads.create_backup_payload(instance_id= instance_id,backup_name= backup_name, container_id= container_id)
        backup_response = morpheus_session.backups.add_backups(add_backups_request=backup_payload)
        assert backup_response.status_code == 200, "Instance backup operation failed!"
        backup_id = backup_response.json()["backup"]["id"]
        log.info(f"Backup created with ID: {backup_id}")
        return backup_id

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
    def validate_labels(final_details: dict, expected_labels: list[str]) -> None:
        """Validate that all expected labels exist in the instance details."""
        final_labels = final_details["instance"].get("labels", [])
        for label in expected_labels:
            assert label in final_labels, f"Expected label '{label}' not found in {final_labels}"
        log.info(f"Labels validated successfully: {final_labels}")

    @staticmethod
    def validate_plan_id(instance_details: dict, expected_plan_id: int) -> None:
        """Validate that the plan ID matches the expected value."""
        final_plan_id = instance_details["instance"]["plan"]["id"]
        assert final_plan_id == expected_plan_id, (
            f"Expected plan ID {expected_plan_id}, but got {final_plan_id}"
        )
        log.info(f"Plan ID validated successfully: {final_plan_id}")

    @staticmethod
    def validate_volume_size(instance_details: dict, expected_volume_size: int) -> None:
        """Validate that the volume size matches the expected value."""
        final_volume_size = instance_details["instance"]["volumes"][0]["size"]
        assert final_volume_size == expected_volume_size, (
            f"Expected volume size {expected_volume_size}, but got {final_volume_size}"
        )
        log.info(f"Volume size validated successfully: {final_volume_size}")

    @staticmethod
    def cleanup_resource(resource_type: str, morpheus_session, resource_id: int, wait_time: int = 10):
        """
        Generic cleanup function to delete a resource (instance/backup/etc.).

        Args:
            resource_type (str): Type of resource ('instance', 'backup', etc.)
            morpheus_session: Active Morpheus session object
            resource_id (int): ID of the resource to delete
            wait_time (int): Seconds to wait after deletion (default=10)
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
            elif resource_type == "schedule":
                delete_response = morpheus_session.automation.remove_execute_schedules(id=resource_id)
            elif resource_type == "cloud":
                cloud_api= CloudAPI(host=host, username=admin_username, password=admin_password)
                delete_response= cloud_api.delete_cloud(cloud_id= str(resource_id), qparams=DeleteCloud(force="true"))
            else:
                log.warning(f"Cleanup for resource type '{resource_type}' is not supported.")
                return

            if delete_response.status_code == 200:
                log.info(f"{resource_type.capitalize()} '{resource_id}' deleted successfully.")
                if wait_time > 0:
                    log.info(f"Waiting {wait_time}s for {resource_type} deletion to finalize...")
                    time.sleep(wait_time)
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
                "name": DateTimeGenUtils.name_with_datetime("schedule", "%Y%m%d-%H%M%S"),
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
            details = CommonUtils.get_instance_details(morpheus_session, instance_id)
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
    def enable_backup_settings(morpheus_session):
        """
        Enable backup settings if disabled.
        """
        backup_setting_request={
                "backupSettings": {
                        "backupsEnabled": True,
                        "createBackups": True
                                    }
                            }
        response = morpheus_session.backup_settings.update_backup_settings(update_backup_settings_request= backup_setting_request)
        assert response.status_code == 200, "Failed to enable backup settings!"
        log.info("Backup settings enabled successfully.")


    @staticmethod
    def wait_for_backup_job_completion(morpheus_session, backup_job_id, timeout=7 * 60, interval=30):
        """
        Wait for a scheduled backup job to complete.
        """
        end_time = time.time() + timeout
        last_result = None

        while time.time() < end_time:
            job_details = CommonUtils.get_backup_job_details(morpheus_session, backup_job_id)
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
    def verify_delete_resource(morpheus_session, resource_type, list_func, resource_id, key, retries=20, delay=5):
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
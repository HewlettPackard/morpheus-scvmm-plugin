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
from functional_tests.common.configs.config_reader import ConfigHandler

config_data = ConfigHandler().read_testcase_variable_config()


logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

load_dotenv()

host = config_data["host"]
admin_username = list(config_data["role"]["admin"].keys())[0]
admin_password = list(config_data["role"]["admin"].values())[0]


class TestSCVMMPlugin:
    """Test class for SCVMM plugin related functionalities."""

    group_id = None
    cloud_id = None
    instance_id = None

    def get_create_instance_payload(self, instance_name, group_id, cloud_id):
        """Helper function to create the payload for instance creation."""
        return {
            "instance": {
                "name": instance_name,
                "site": {"id": group_id},
                "instanceType": {"code": "scvmm"},
                "layout": {
                    "id": config_data["templates"]["vm_creation_window_image"][
                        "layout_id"
                    ]
                },
                "plan": {
                    "id": config_data["templates"]["vm_creation_window_image"][
                        "plan_id"
                    ]
                },
            },
            "zoneId": cloud_id,
            "networkInterfaces": [
                {
                    "network": {
                        "id": config_data["templates"]["vm_creation_window_image"][
                            "network_id"
                        ],
                    }
                }
            ],
            "config": {
                "template": config_data["templates"]["vm_creation_window_image"][
                    "template_id"
                ],
                "scvmmCapabilityProfile": "Hyper-V",
                "createUser": False,
                "noAgent": False,
                "hostId": 96,
            },
            "labels": ["TEST"],
            "volumes": config_data["templates"]["vm_creation_window_image"]["volumes"],
        }

    def get_create_cloud_payload(self, cloud_name, group_id, zone_type_id):
        """Helper function to create the payload for cloud creation."""
        return {
            "zone": {
                "name": cloud_name,
                "credential": {"type": "local"},
                "zoneType": {"id": zone_type_id},
                "groups": {"id": group_id},
                "groupId": TestSCVMMPlugin.group_id,
                "config": {
                    "host": config_data["cloud_config"]["host"],
                    "username": config_data["cloud_config"]["username"],
                    "password": config_data["cloud_config"]["password"],
                    "sharedController": config_data["cloud_config"]["shared_controller"],
                },
            }
        }

    def test_validate_windows_instance_creation_and_agent_installation_behaviour(
        self, morpheus_session
    ):
        """1. Test case to validate the creation of windows instance and agent installation behavior."""
        # This test case assumes that the SCVMM plugin is already registered and the necessary configurations are in place.

        instance_id = None  # Initialize instance_id to None for cleanup purposes
        try:

            plugin_api = PluginAPI(
                host=host, username=admin_username, password=admin_password
            )
            current_dir = os.getcwd()
            jar_dir = os.path.join(current_dir, "build", "libs")
            log.info(f"Searching for plugin JAR in {jar_dir}")

            pattern = os.path.join(jar_dir, "morpheus-scvmm-plugin-0.1.0-*.jar")
            matching_files = glob.glob(pattern)

            if len(matching_files) != 1:
                raise FileNotFoundError(
                    f"Expected one JAR file, found {len(matching_files)}"
                )

            jar_file_path = matching_files[0]
            log.info(f"Found plugin JAR: {jar_file_path}")
            log.info("Uploading plugin...")
            try:
                plugin_response = plugin_api.upload_plugin(jar_file_path=jar_file_path)
                log.info(f"Response Status Code: {plugin_response.status_code}")
                assert (
                    plugin_response.status_code == 200
                ), f"Plugin upload failed: {plugin_response.text}"
                log.info("Plugin uploaded successfully.")
            except Exception as e:
                log.error(f"Plugin upload failed: {e}")
                pytest.fail(f"Plugin upload failed: {e}")

            # 2. create a scvmm group
            group_name = "test-scvmm-group-" + RandomGenUtils.random_string_of_chars(5)
            group_payload = {
                "group": {
                    "name": group_name,
                }
            }

            group_response = morpheus_session.groups.add_groups(group_payload)
            assert group_response.status_code == 200, "Group creation failed!"
            TestSCVMMPlugin.group_id = group_response.json()["group"]["id"]
            log.info(f"group_id: {TestSCVMMPlugin.group_id}")

            # 3. create a scvmm cloud under the created group
            cloud_name = "test-scvmm-cloud-" + RandomGenUtils.random_string_of_chars(5)
            zone_type_response = morpheus_session.clouds.list_cloud_types()
            assert (
                zone_type_response.status_code == 200
            ), "Failed to retrieve cloud types!"
            zone_types = zone_type_response.json().get("zoneTypes", [])
            zone_type_id = None

            for zone in zone_types:
                if zone.get("name") == "SCVMM":
                    zone_type_id = zone.get("id")
                    break

            assert zone_type_id is not None, "SCVMM zone type not found!"
            cloud_payload = self.get_create_cloud_payload(
                cloud_name, group_id=TestSCVMMPlugin.group_id, zone_type_id=zone_type_id
            )
            cloud_response = morpheus_session.clouds.add_clouds(cloud_payload)
            assert cloud_response.status_code == 200, "Cloud creation failed!"
            TestSCVMMPlugin.cloud_id = cloud_response.json()["zone"]["id"]
            log.info(f"cloud_id: {TestSCVMMPlugin.cloud_id}")
            final_status = ResourcePoller.poll_cloud_status(
                cloud_id=TestSCVMMPlugin.cloud_id,
                morpheus_session=morpheus_session,
            )
            assert (
                final_status == "ok"
            ), f"Cloud creation failed with status: {final_status}"
            log.info(
                f"Cloud '{cloud_name}' created successfully with ID: {TestSCVMMPlugin.cloud_id}"
            )
            # 4. register a scvmm cluster under the created cloud
            cluster_name = (
                "test-scvmm-cluster-" + RandomGenUtils.random_string_of_chars(5)
            )

            # Fetching cluster-type ID for SCVMM
            cluster_type_response = morpheus_session.clusters.list_cluster_types()
            assert (
                cluster_type_response.status_code == 200
            ), "Failed to retrieve cluster types!"
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
            assert (
                layout_response.status_code == 200
            ), "Failed to retrieve cluster layouts!"
            layouts = layout_response.json().get("layouts", [])
            layout_id = None
            for layout in layouts:
                if layout.get("name") == "SCVMM Docker Host Ubuntu 22.04":
                    layout_id = layout.get("id")
                    log.info(f"Layout ID: {layout_id}")
                    break
            assert layout_id is not None, "SCVMM cluster layout not found!"

            cluster_payload = {
                "cluster": {
                    "name": cluster_name,
                    "cloud": {"id": TestSCVMMPlugin.cloud_id},
                    "type": {"id": cluster_type_id},
                    "layout": {"id": layout_id},
                    "server": {
                        "id": config_data["templates"]["cluster_creation"]["server"][ "id"],
                        "name": cluster_name,
                        "plan": {
                            "id": config_data["templates"]["cluster_creation"]["server"]["plan"]["id"]
                        },
                        "config": {},
                    },
                    "group": {"id": TestSCVMMPlugin.group_id},
                }
            }
            cluster_response = morpheus_session.clusters.add_cluster(cluster_payload)

            if cluster_response.status_code == 200:
                TestSCVMMPlugin.cluster_id = cluster_response.json()["cluster"]["id"]
                log.info(f"cluster_id: {TestSCVMMPlugin.cluster_id}")

                final_status = ResourcePoller.poll_cluster_status(
                    cluster_id=TestSCVMMPlugin.cluster_id,
                    morpheus_session=morpheus_session,
                )

                if final_status == "ok":
                    log.info(
                        f"Cluster '{cluster_name}' registered successfully with ID: {TestSCVMMPlugin.cluster_id}"
                    )
                else:
                    log.warning(
                        f"Cluster registration failed with status: {final_status}"
                    )
            else:
                log.warning("Cluster registration failed! Could not create cluster.")

            # 5. create instance using windows2019 template
            log.info("creating instance")
            instance_name = (
                "test-scvmm-instance-" + RandomGenUtils.random_string_of_chars(3)
            )
            group_id = TestSCVMMPlugin.group_id
            cloud_id = TestSCVMMPlugin.cloud_id

            log.info("payload Generating")
            create_instance_payload = self.get_create_instance_payload(
                instance_name, group_id=group_id, cloud_id=cloud_id
            )
            log.info("payload Generated")
            log.info(f"Creating instance with payload: {create_instance_payload}")
            log.info(json.dumps(create_instance_payload, indent=2))

            instance_response = morpheus_session.instances.add_instance(
                add_instance_request=create_instance_payload
            )
            assert instance_response.status_code == 200, "Instance creation failed!"
            instance_id = instance_response.json()["instance"]["id"]
            log.info(type(instance_id))
            log.info(f"Instance {instance_id} created. Waiting for status...")

            final_status = ResourcePoller.poll_instance_status(
                instance_id=instance_id,
                target_state="running",
                morpheus_session=morpheus_session,
            )
            assert (
                final_status == "running"
            ), f"Instance creation failed with status: {final_status}"
            instance_name = instance_response.json()["instance"]["name"]
            log.info(f"Instance '{instance_name}' created successfully.")

            # 6. Verify the VM instance has been successfully deployed along with the agent
            agent_response = morpheus_session.instances.get_instance(id=instance_id)
            log.info(f"Response Status Code: {agent_response.status_code}")
            assert (
                agent_response.status_code == 200
            ), "Failed to retrieve instance details!"
            details = agent_response.json()
            agent_installed = details["instance"]["containerDetails"][0]["server"][
                "agentInstalled"
            ]
            log.info(f"agent installed: {agent_installed}")
            assert agent_installed, "Agent installation failed on the instance."
            agent_version = agent_response.json()["instance"]["containerDetails"][0][
                "server"
            ]["agentVersion"]
            log.info(
                f"Agent installed successfully on instance '{instance_id}' with version: {agent_version}"
            )

            # 7. Initiate instance Start operation
            log.info(f"Starting instance with id'{instance_id}'...")
            start_response = morpheus_session.instances.start_instance(id=instance_id)
            assert start_response.status_code == 200, "Instance start operation failed!"
            start_status = ResourcePoller.poll_instance_status(
                instance_id,
                "running",
                morpheus_session,
            )
            assert (
                start_status == "running"
            ), f"Instance failed to start, current status: {start_status}"
            log.info(f"Instance with '{instance_id}' started successfully.")

            # 8. Initiate instance restart operation
            log.info(f"Resetting instance '{instance_id}'...")
            restart_response = morpheus_session.instances.restart_instance(instance_id)
            assert (
                restart_response.status_code == 200
            ), "Instance reset operation failed!"
            restart_status = ResourcePoller.poll_instance_status(
                instance_id, "running", morpheus_session
            )
            assert (
                restart_status == "running"
            ), f"Instance failed to reset, current status: {restart_status}"
            log.info(f"Instance '{instance_id}' reset successfully.")

            # 9. Initiate instance stop operation
            log.info(f"Stopping instance '{instance_id}'...")
            stop_response = morpheus_session.instances.stop_instance(instance_id)
            assert stop_response.status_code == 200, "Instance stop operation failed!"
            stop_status = ResourcePoller.poll_instance_status(
                instance_id, "stopped", morpheus_session
            )
            assert (
                stop_status == "stopped"
            ), f"Instance failed to stop, current status: {stop_status}"
            log.info(f"Instance '{instance_id}' stopped successfully.")

            # 10. Initiate instance start operation again
            log.info(f"Starting instance '{instance_id}' again...")
            start_again_response = morpheus_session.instances.start_instance(
                instance_id
            )
            assert (
                start_again_response.status_code == 200
            ), "Instance start operation failed!"
            start_again_status = ResourcePoller.poll_instance_status(
                instance_id, "running", morpheus_session
            )
            assert (
                start_again_status == "running"
            ), f"Instance failed to start again, current status: {start_again_status}"
            log.info(f"Instance '{instance_id}' started successfully again.")
        except Exception as e:
            log.error(f"Test failed with exception: {e}")
            pytest.fail(f"Test failed with exception: {e}")

        finally:
            # Cleanup: Delete the instance if it was created
            if instance_id:
                log.info(f"Cleaning up instance '{instance_id}'...")
                try:
                    delete_response = morpheus_session.instances.delete_instance(
                        id=instance_id
                    )
                    if delete_response.status_code == 200:
                        log.info(f"Instance '{instance_id}' deleted successfully.")
                    else:
                        log.warning(
                            f"Failed to delete instance '{instance_id}': {delete_response.text}"
                        )
                except Exception as e:
                    log.error(f"Cleanup failed with exception: {e}")
            else:
                log.info("No instance was created, so nothing to clean up.")

    def test_validate_windows_instance_creation_with_selected_storage_and_host(
        self, morpheus_session
    ):
        """2. Test case to validate the creation of windows instance with selected storage and host."""
        # This test case assumes that the SCVMM plugin is already registered and the necessary configurations are in place.

        try:
            log.info("Creating instance with selected storage and host")
            instance_name = (
                "test-scvmm-instance-" + RandomGenUtils.random_string_of_chars(3)
            )
            group_id = TestSCVMMPlugin.group_id
            cloud_id = TestSCVMMPlugin.cloud_id

            log.info(f"getting the host id for the instance creation")
            # Get the list of hosts
            hosts_response = morpheus_session.hosts.list_hosts(name="hyperv-node-44")
            assert hosts_response.status_code == 200, "Failed to retrieve hosts!"
            hosts_data = hosts_response.json()
            # Extract the host ID from the response
            if not hosts_data["servers"]:
                raise ValueError("No hosts found in the response.")
            host_id = hosts_data["servers"][0]["id"]
            log.info(f"Selected host ID: {host_id}")
            create_instance_payload = self.get_create_instance_payload(
                instance_name, group_id=group_id, cloud_id=cloud_id
            )
            create_instance_payload["config"]["hostId"] = host_id  # Set specific host ID

            instance_response = morpheus_session.instances.add_instance(
                add_instance_request=create_instance_payload
            )
            assert instance_response.status_code == 200, "Instance creation failed!"
            instance_id = instance_response.json()["instance"]["id"]
            log.info(f"Instance {instance_id} created. Waiting for status...")

            final_status = ResourcePoller.poll_instance_status(
                instance_id, "running", morpheus_session
            )
            assert (
                final_status == "running"
            ), f"Instance creation failed with status: {final_status}"
            instance_name = instance_response.json()["instance"]["name"]
            TestSCVMMPlugin.instance_id = instance_response.json()["instance"]["id"]
            log.info(f"Instance '{instance_name}' created successfully.")
            get_instance_response = morpheus_session.instances.get_instance(
                id=instance_id
            )
            assert (
                get_instance_response.status_code == 200
            ), "Failed to retrieve instance details!"
            details = get_instance_response.json()
            # Verify the host ID in the instance details
            actual_host_id = details["instance"]["config"]["hostId"]
            log.info(f"Actual host ID in instance details: {actual_host_id}")
            assert (
                actual_host_id == host_id
            ), f"Expected host ID {host_id}, but got {actual_host_id}."

        except Exception as e:
            log.error(f"Test failed with exception: {e}")
            pytest.fail(f"Test failed with exception: {e}")

    def test_validate_reconfigure_operation_on_deployed_windows_instance(
            self, morpheus_session
    ):
        """3. Test case to validate the reconfigure operation on a deployed windows instance."""
        instance_id = TestSCVMMPlugin.instance_id
        try:

            log.info(f"Reconfiguring instance with id '{instance_id}'...")
            update_payload = {
                "instance": {
                    "labels": ["Test1"]
                }
            }
            reconfigure_payload = {
                "instance": {
                    "volumes": [
                        {
                            "size": 90,
                            "rootVolume": True,
                            "volumeCategory": "disk",
                            "datastoreId": "2",
                            "storageType": 1
                        },
                    ],
                    "interfaces": [{"network": {"id": 1}}],
                },
            }
            update_response = morpheus_session.instances.update_instance(
                id=instance_id, update_instance_request=update_payload
            )
            assert (
                update_response.status_code == 200
            ), f"Instance update operation failed: {update_response.text}"
            #  Wait for instance to be stable (e.g., 'running') before resizing
            log.info("Waiting for instance to reach 'running' state after label update...")
            intermediate_status = ResourcePoller.poll_instance_status(
                instance_id, "running", morpheus_session
            )
            assert intermediate_status == "running", (
                f"Instance didn't reach 'running' after label update. Current status: {intermediate_status}"
            )
            # Step 2: Resize root volume
            existing_instance = morpheus_session.instances.get_instance(id=instance_id).json()

            log.info(f"Existing volumes: {existing_instance['instance']['volumes']}")
            name = existing_instance["instance"]["volumes"][0]["name"]
            reconfigure_payload["instance"]["volumes"][0]["name"] = name
            vol_id = existing_instance["instance"]["volumes"][0]["id"]
            reconfigure_payload["instance"]["volumes"][0]["id"] = vol_id

            resize_response = morpheus_session.instances.resize_instance(
                id=instance_id, resize_instance_request=reconfigure_payload
            )
            assert (
                    resize_response.status_code == 200
            ), f"Instance resize operation failed: {resize_response.text}"
            log.info(
                f"Instance '{instance_id}' reconfigured successfully. Waiting for status..."
            )
            final_status = ResourcePoller.poll_instance_status(
                instance_id, "running", morpheus_session,
            )
            assert (
                    final_status == "running"
            ), f"Instance reconfigure failed with status: {final_status}"
            # Validating label updation
            final_instance_details = morpheus_session.instances.get_instance(id=instance_id)
            assert (
                    final_instance_details.status_code == 200
            ), "Failed to retrieve instance details after reconfigure!"
            final_details = final_instance_details.json()
            final_labels = final_details["instance"]["labels"]
            assert "Test1" in final_labels, "Instance label update failed during reconfigure."

            log.info(
                f"Instance '{instance_id}' reconfigured successfully with new volume size and labels."
            )
        except Exception as e:
            log.error(f"Test failed with exception: {e}")
            pytest.fail(f"Test failed with exception: {e}")

        finally:
            # Cleanup: Delete the instance if it was created
            log.info(f"Cleaning up instance '{instance_id}'...")
            try:
                delete_response = morpheus_session.instances.delete_instance(id=instance_id)
                assert delete_response.status_code == 200, f"Failed to delete instance '{instance_id}': {delete_response.text}"
                log.info(f"Instance '{instance_id}' deleted successfully.")
            except Exception as e:
                log.error(f"Cleanup failed with exception: {e}")
                pytest.fail(f"Cleanup failed with exception: {e}")

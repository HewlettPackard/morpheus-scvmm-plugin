import json
import os
import logging

from functional_tests.common.common_utils import CommonUtils
from functional_tests.tests.conftest import morpheus_session


log = logging.getLogger(__name__)

class SCVMMpayloads:
    """ Helper class to create payloads for SCVMM related operations """

    @staticmethod
    def get_create_instance_payload(morpheus_session, instance_name, template, group_id, cloud_id, plan_id, instance_type_code, layout_code, host_id=None):
        """Helper function to create the payload for instance creation."""

        instance_layout_id = CommonUtils.get_scvmm_instance_layout_id(morpheus_session, layout_code= layout_code)
        network_id = CommonUtils.get_network_id(morpheus_session)
        payload= {
            "instance": {
                "name": instance_name,
                "site": {"id": group_id},
                "instanceType": {"code": instance_type_code},
                "layout": {
                    "id": instance_layout_id

                },
                "plan": {
                    "id": plan_id
                },
            },
            "zoneId": cloud_id,
            "networkInterfaces": [
                {
                    "network": {
                        "id": str(network_id)
                    }
                }
            ],
            "config": {
                "noAgent": False,
                "hostId": host_id,
                "scvmmCapabilityProfile": "Hyper-V",
                "createUser": False,
            },
            "labels": ["TEST"],
            "volumes": [
                        {
                            "rootVolume": True,
                            "name": "root",
                            "size": int(os.getenv("SCVMM_INSTANCE_VOLUME_SIZE")),
                            "datastoreId":{"id":"auto"},
                        }
                        ],
        }
        if template:
            payload["config"]["template"] = int(template)

        return payload

    @staticmethod
    def get_create_cloud_payload(cloud_name, group_id, zone_type_id, api_proxy_id, provisioning_proxy):
        """Helper function to create the payload for cloud creation."""
        return {
            "zone": {
                "name": cloud_name,
                "credential": {"type": "local"},
                "zoneType": {"id": zone_type_id},
                "groups": {"id": group_id},
                "groupId": group_id,
                "guidanceMode": "manual",
                "costingMode": "costing",
                "autoRecoverPowerState": True,
                "config": {
                    "host": os.getenv("HOST"),
                    "username": os.getenv("HOST_USERNAME"),
                    "password": os.getenv("HOST_PASSWORD"),
                    "importExisting": "on",
                    "enableHypervisorConsole": "on",
                },
                "apiProxy": {"id": api_proxy_id},
                "provisioningProxy": {
                    "id": provisioning_proxy
                },
            }
        }

    @staticmethod
    def get_create_cluster_payload(morpheus_session, cluster_name, group_id, cloud_id, layout_id, cluster_type_id, plan_id):
        """ Helper function to create the payload for cluster creation"""

        network_id = CommonUtils.get_network_id(morpheus_session)
        return {
                "cluster": {
                    "name": cluster_name,
                    "group": {
                        "id": group_id
                    },
                    "cloud": {
                        "id": cloud_id
                    },
                    "layout": {
                        "id": layout_id
                    },
                    "type": {
                        "id": cluster_type_id
                    },
                    "server": {
                        "name": cluster_name,
                        "plan": {
                            "id": plan_id
                        },
                        "volumes": [
                            {
                                "rootVolume": True,
                                "name": "root",
                                "size": 40,
                                "datastoreId": "auto"
                            }
                        ],
                        "networkInterfaces": [
                            {
                                "network": {
                                    "id": f"network-{network_id}"
                                }
                            }
                        ]
                    },
                    "config": {
                        "templateParameter": {
                                "notHighAvailability": True,
                                "provisionType": "scvmm"
                                              }
                               }
                }
            }

    @staticmethod
    def create_update_payload(labels):
        """Create payload for updating instance labels."""
        return {
            "instance": {
                "labels": labels
            }
        }

    @staticmethod
    def create_reconfigure_payload(morpheus_session,instance_details, plan_name, cloud_id, group_id, new_volume_size):
        """Create payload for reconfiguring an instance."""
        volume = instance_details["instance"]["volumes"][0]
        plan_id= CommonUtils.get_plan_id(morpheus_session=morpheus_session, plan_name= plan_name, zone_id=cloud_id, group_id=group_id)
        log.info(f"Plan ID: {plan_id}")
        network_id = CommonUtils.get_network_id(morpheus_session)

        return {
            "instance": {
                "plan": {
                    "id": plan_id
                }
            },
            "volumes": [
                {
                    "size": new_volume_size,
                    "id": volume["id"],
                    "name": volume["name"],
                    "rootVolume": volume.get("rootVolume", True),
                }
            ],
            "networkInterfaces": [
                {
                    "network": {"id":str(network_id)}
                }
            ]
        }

    @staticmethod
    def create_clone_payload(clone_instance_name: str, instance_details):
        """
        Create payload for cloning an instance.
        :param instance_details: Details of the original instance
        :param clone_instance_name: Name for the cloned instance
        :return: Clone payload dictionary
        """
        volume = instance_details["instance"]["volumes"][0]

        return {
            "name": clone_instance_name,
            "volumes": [
                {
                    "rootVolume":  volume.get("rootVolume", True),
                    "id": volume["id"],
                    "name": volume["name"],
                    "size": 85,
                    "datastoreId": "auto"
                }
            ]
        }

    @staticmethod
    def create_backup_payload(instance_id, backup_name, container_id, backup_job_name= None, schedule_id= None):
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

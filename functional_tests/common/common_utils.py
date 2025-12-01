import os
import logging
from datetime import datetime

log = logging.getLogger(__name__)


class CommonUtils:
    """ Common utility functions for functional tests. """

    @staticmethod
    def get_scvmm_instance_layout_id(morpheus_session, layout_code: str):
        """Fetch the first SCVMM layout ID using the SCVMM layout code from env."""
        response = morpheus_session.library.list_layouts(code= layout_code)
        assert response.status_code == 200, "Failed to retrieve cluster layouts!"

        layouts = response.json().get("instanceTypeLayouts", [])
        if not layouts:
            raise ValueError("No layouts found for the given SCVMM layout code!")
        layout_id = layouts[0].get("id")
        log.info(f"Layout ID: {layout_id}")
        return layout_id

    @staticmethod
    def get_network_id(morpheus_session):
        """Fetch the network ID for a given network name."""
        response = morpheus_session.networks.list_networks(name=os.getenv("NETWORK_NAME"))
        assert response.status_code == 200, "Failed to retrieve networks!"

        networks = response.json().get("networks", [])
        if networks:
            return networks[0].get("id")

        raise ValueError(f"Network not found!")

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
    def get_template_id(morpheus_session, template_name):
        """Fetch the template ID for a given template name."""
        response = morpheus_session.library.list_virtual_images(name=template_name, filter_type="Synced")
        assert response.status_code == 200, "Failed to retrieve templates!"
        templates = response.json().get("virtualImages", [])
        assert templates, f"No template found for {template_name}"
        return templates[0]["id"]

    @staticmethod
    def get_server_type(morpheus_session):
        """ Fetch server type"""
        response= morpheus_session.hosts.list_host_types(name= os.getenv("SERVER_TYPE_NAME"))
        assert response.status_code == 200, "Failed to retrieve server type!"
        server_type_id= response.json().get("serverTypes", [])[0].get("id")
        return server_type_id

    @staticmethod
    def get_plan_id(morpheus_session, plan_name, zone_id, group_id):
        """ Fetch plan id"""
        server_type_id= CommonUtils.get_server_type(morpheus_session)
        response= morpheus_session.hosts.list_server_service_plans(zone_id=zone_id, server_type_id= server_type_id, site_id=group_id)
        assert response.status_code == 200, "Failed to retrieve plan!"
        plans= response.json().get("plans", [])
        for plan in plans:
            if plan.get("name") == plan_name:
                return plan.get("id")
        raise ValueError(f"Plan not found for {plan_name}")

    @staticmethod
    def get_container_id(morpheus_session, instance_id):
        """Fetch container details"""
        instance_details = CommonUtils.get_instance_details(morpheus_session, instance_id)
        container_id = instance_details["instance"]["containers"][0]
        return container_id

    @staticmethod
    def get_host_id(morpheus_session, host_name):
        """Fetch host id"""
        response= morpheus_session.hosts.list_hosts(name= host_name)
        assert response.status_code == 200, "Failed to retrieve host!"
        servers= response.json().get("servers", [])
        if servers:
            return servers[0].get("id")
        raise ValueError(f"Host not found for {host_name}")

    @staticmethod
    def get_network_proxy(morpheus_session):
        """ Fetch network proxy"""
        response= morpheus_session.networks.get_network_proxies()
        assert response.status_code == 200, "Failed to retrieve network proxies!"
        proxies= response.json().get("networkProxies", [])
        if proxies:
            proxy_id= proxies[0].get("id")
            log.info(f"Network Proxy ID: {proxy_id}")
            return proxy_id
        raise ValueError("No network proxy found!")

class DateTimeGenUtils:
    """ Utility class for generating names with datetime suffixes."""

    @staticmethod
    def name_with_datetime(prefix: str, fmt: str = "%Y%m%d-%H%M%S") -> str:
        """
        Generate a name by appending the current datetime to a given prefix.

        Args:
            prefix (str): Base prefix for the name.
            fmt (str): Datetime format (default: YYYYMMDD-HHMMSS).

        Returns:
            str: Generated name with datetime suffix.
        """
        timestamp = datetime.now().strftime(fmt)
        return f"{prefix}-{timestamp}"

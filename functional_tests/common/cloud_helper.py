import time
from time import sleep
import logging

import pytest

from functional_tests.common.common_utils import CommonUtils
from functional_tests.tests.conftest import morpheus_session

log = logging.getLogger(__name__)


class ResourcePoller:
    """
    Helper methods for Morpheus cloud operations.
    """

    @staticmethod
    def poll_cloud_status(morpheus_session, cloud_id):
        """
        Polls until the cloud status becomes 'ok' or returns any other response.

        :param morpheus_session: fixture providing Morpheus API clients
        :param cloud_id: ID of the cloud to be validated
        :return: Response object if status is 'ok', else None
        """
        max_attempts = 50
        sleep_interval = 10
        for attempt in range(max_attempts):
            response = morpheus_session.clouds.get_clouds(cloud_id)
            if response.status_code == 200:
                cloud_status = response.json().get("zone", {}).get("status")
                log.info(f"Polling attempt {attempt + 1}: Cloud status = {cloud_status}")
                if cloud_status == "ok":
                    return cloud_status
            else:
                return cloud_status
            sleep(sleep_interval)

        return None


    @staticmethod
    def poll_instance_status(instance_id: int, target_state: str, morpheus_session, timeout: int = 350, sleep_time: int = 10):
        """
        Polls the status of a Morpheus instance until it matches the target state or the timeout is reached.

        Args:
            morpheus_session (dict): Dictionary containing Morpheus API clients, including 'instance_api'.
            instance_id (int): ID of the instance whose status is to be polled.
            target_state (str): Desired state to wait for (e.g., 'running', 'stopped').
            timeout (int, optional): Maximum number of polling attempts before giving up. Defaults to 30.
            sleep_time (int, optional): Time (in seconds) to wait between polling attempts. Defaults to 10.

        Returns:
            str: The final instance status observed (either the target state or the last known status after timeout).
        """
        # instance_api = morpheus_clients.get("instance_api")

        log.info(f"Polling for instance ID '{instance_id}' to reach target state: '{target_state}'")

        for attempt in range(1, timeout + 1):
            response = morpheus_session.instances.get_instance(id=instance_id)
            instance_status = response.json()["instance"]["status"]
            log.info(f"Polling attempt {attempt}: Current status = '{instance_status}'")

            if instance_status.lower() == "failed":
                if target_state.lower() == "failed":
                    log.info("Instance has reached the expected 'failed' state.")
                    return instance_status
                log.error(f"Instance reached 'failed' state during polling.")
                assert False, f"Instance ID {instance_id} entered 'failed' state during polling."

            if instance_status.lower() == target_state.lower():
                log.info(f"Instance has reached the desired state '{instance_status}'.")
                return instance_status

            sleep(sleep_time)

        # Final status after timeout
        response = morpheus_session.instances.get_instance(instance_id=instance_id)
        final_status = response.json()["instance"]["status"]
        log.warning(f"Timeout reached after {timeout} attempts. Final observed status: '{final_status}'")
        return final_status


    @staticmethod
    def poll_cluster_status(morpheus_session, cluster_id):
        """
        Poll the status of a Morpheus cluster until it is 'ok', 'failed', or timeout occurs.

        This method continuously checks the status of a Morpheus cluster at regular intervals.
        It stops polling when the cluster status becomes either 'ok' or 'failed',
        or after the maximum number of attempts is reached.

        Args:
            morpheus_session: Fixture providing Morpheus API clients.
            cluster_id (int): ID of the cluster to be validated.

        Returns:
            str:
                - 'ok' if the cluster reaches the OK state.
                - 'failed' if the cluster creation fails.
                - Final cluster status string if timeout occurs before 'ok' or 'failed'.
        """
        timeout = 200  # number of attempts
        sleep_time = 10  # seconds between each poll

        for i in range(timeout):
            response = morpheus_session.clusters.get_cluster(cluster_id=cluster_id)
            cluster_status = response.json()["cluster"]["status"]
            log.info(f"Polling attempt {i + 1}: Status = {cluster_status}")

            if cluster_status == "failed":
                log.error("Cluster creation failed.")
                log.error(f"Error response: {response.json()}")
                return "failed"

            if cluster_status == "ok":
                log.info("Cluster is now in ok state.")
                return "ok"

            sleep(sleep_time)

        final_status = morpheus_session.clusters.get_cluster(cluster_id=cluster_id).json()["cluster"]["status"]
        log.info(f"Final status after polling: {final_status}")
        return final_status

    @staticmethod
    def poll_backup_status(backup_id: int, target_state: str, morpheus_session, timeout: int = 30,
                           sleep_time: int = 10):
        """Polls the status of a backup until it reaches the target state or times out."""
        for attempt in range(timeout):
            response = morpheus_session.backups.get_backups(id=backup_id)
            assert response.status_code == 200, "Failed to retrieve backup status!"
            backup_status = response.json()["backup"]["lastResult"]["status"]
            log.info(f"Polling attempt {attempt + 1}: Backup Status = {backup_status}")

            if backup_status == target_state:
                log.info(f"Backup reached target state: {target_state}")
                return backup_status
            elif backup_status == "failed":
                log.error("Backup operation failed.")
                return backup_status

            sleep(sleep_time)

        final_status = morpheus_session.backups.get_backups(id=backup_id).json()["backup"]["lastResult"]["status"]
        log.info(f"Final backup status after polling: {final_status}")
        return final_status

    def poll_backup_restore_status(restore_id: int, target_state: str, morpheus_session, timeout: int = 30,):
        """Polls the status of a backup restore until it reaches the target state or times out."""
        sleep_time = 10
        for attempt in range(timeout):
            response = morpheus_session.backups.get_backup_restores(id=restore_id)
            assert response.status_code == 200, "Failed to retrieve backup restore status!"
            restore_status = response.json()["restore"]["status"]
            log.info(f"Polling attempt {attempt + 1}: Backup Restore Status = {restore_status}")

            if restore_status == target_state:
                log.info(f"Backup restore reached target state: {target_state}")
                return restore_status
            elif restore_status == "failed":
                log.error("Backup restore operation failed.")
                return restore_status

            sleep(sleep_time)

    def poll_until_condition(
            poll_function,
            condition_fn,
            timeout=200,
            poll_interval=10,
            description="",
    ):
        """
        Generic polling utility that waits until a condition becomes True.

        Args:
            poll_function (callable): Function that fetches the current state/data.
            condition_fn (callable): Function that returns True when desired condition is met.
            timeout (int): Maximum time to wait in seconds.
            poll_interval (int): Time between checks in seconds.
            description (str): Optional description for logging.

        Raises:
            AssertionError: If condition is not met within timeout.
        """
        start_time = time.time()
        while True:
            try:
                result = poll_function()
                if condition_fn(result):
                    log.info(f"Condition met for {description}")
                    break
            except Exception as e:
                log.warning(f"Polling exception ignored: {e}")

            if time.time() - start_time > timeout:
                raise AssertionError(f"Condition not met for {description} after {timeout} seconds.")

            log.info(f"{description} not met, retrying in {poll_interval} seconds...")
            time.sleep(poll_interval)

    @staticmethod
    def poll_backup_job_execution(morpheus_session, backup_id, timeout=600, interval=30):
        """
        Poll until backup job executes and produces a result.

        Returns:
            (last_result_id, status)
        """
        start = time.time()
        while time.time() - start < timeout:
            job_details = CommonUtils.get_backup_job_details(morpheus_session, backup_id)
            last_result = job_details["backup"].get("lastResult")

            if last_result:
                job_result_id = last_result["id"]
                status = last_result["status"]
                log.info(f"Backup job result {job_result_id} status: {status}")
                if status in ["SUCCEEDED", "FAILED"]:
                    return job_result_id, status
            time.sleep(interval)

        pytest.fail("Backup job did not execute within expected time")

    def poll_instance_event(
            morpheus_session,
            instance_id,
            process_type_code,
            description,
            expected_status="complete",
            timeout=300,
            interval=10,
    ):
        """
        Poll instance history until a specific event reaches the expected status.

        :param morpheus_session: Morpheus API session
        :param instance_id: Instance ID
        :param process_type_code: processType.code (e.g. executeTask, instanceWorkflow)
        :param description: Event or process description/name
        :param expected_status: Expected final status (default: complete)
        :param timeout: Max wait time in seconds
        :param interval: Poll interval in seconds
        :return: Matching event/process dict
        """
        start_time = time.time()

        while time.time() - start_time < timeout:
            history_res = morpheus_session.instances.get_instance_history(id=instance_id)
            assert history_res.status_code == 200, "Failed to retrieve instance history"

            processes = history_res.json().get("processes", [])

            for process in processes:
                # Case 1: workflow (process-level)
                if (
                        process.get("processType", {}).get("code") == process_type_code
                        and process.get("description") == description
                ):
                    if process.get("status") == expected_status:
                        return process

                # Case 2: task (event-level)
                for event in process.get("events", []):
                    if (
                            event.get("processType", {}).get("code") == process_type_code
                            and event.get("description") == description
                    ):
                        if event.get("status") == expected_status:
                            return event

            time.sleep(interval)

        raise TimeoutError(
            f"Timed out waiting for '{description}' "
            f"({process_type_code}) to reach '{expected_status}' state"
        )

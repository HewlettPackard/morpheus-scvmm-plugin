""" Script to update secret."""
import json
import base64
import logging
import os
import argparse

logging.basicConfig(level=logging.INFO)
BASE_DIR = os.path.dirname(os.path.realpath(__file__))


def read_json_file(file_path):
    """
    Reads a JSON file and returns its content as a dictionary.

    Parameters:
    file_path (str): The path to the JSON file.

    Returns:
    dict or None: A dictionary containing the content of the JSON file if successful,
                  or None if there was an error.

    Raises:
    FileNotFoundError: If the specified file_path does not exist.
    json.JSONDecodeError: If there is an error decoding the JSON content.
    """
    try:
        with open(file_path, 'r') as file:
            data = json.load(file)
        return data
    except FileNotFoundError:
        logging.error(f"File not found: {file_path}")
    except json.JSONDecodeError:
        logging.error(f"Error decoding JSON in file: {file_path}")
    return None


def write_json_file(file_path, data):
    """
    Writes a dictionary to a JSON file.

    Parameters:
    file_path (str): The path to the JSON file.
    data (dict): The dictionary to be written to the JSON file.

    Returns:
    None

    Raises:
    Exception: If there is an error while writing to the JSON file.
    """
    try:
        with open(file_path, 'w') as file:
            json.dump(data, file, indent=2)
        logging.info(f"Data written to {file_path} successfully.")
    except Exception as e:
        logging.error(f"Error writing to {file_path}: {e}")
    finally:
        file.close()


def decode(base64_string, datatype):
    """
    Converts a base64-encoded string to a specified data type.

    Parameters:
    base64_string (str): The base64-encoded string to be decoded.
    datatype (str): The desired data type for the decoded result. Possible values: "DICT", "INT", "FLOAT", or default (string).

    Returns:
    dict, int, float, or str or None: The decoded result based on the specified data type.
                                      Returns None if there is an error during decoding.

    Raises:
    ValueError: If there is a conversion error.
    base64.binascii.Error: If the base64 string is invalid.
    json.JSONDecodeError: If there is an error decoding JSON from the base64 string.
    """
    try:
        decoded_bytes = base64.b64decode(base64_string)
        decoded_str = decoded_bytes.decode('utf-8')

        if datatype == "DICT":
            data_dict = json.loads(decoded_str)
            return data_dict
        elif datatype == "INT":
            return int(decoded_str)
        elif datatype == "FLOAT":
            return float(decoded_str)
        else:
            return decoded_str

    except ValueError as ve:
        logging.error(f"Conversion error: {ve}")
        return None
    except base64.binascii.Error as be:
        logging.error(f"Invalid base64 string: {be}")
        return None
    except json.JSONDecodeError as je:
        logging.error(f"Error decoding JSON from base64 string: {je}")
        return None


def find_nested_key(json_data, target_value, current_path=None):
    """
    Recursively searches for a target value in a nested JSON structure and returns the path to the first occurrence.

    Parameters:
    json_data (dict or list): The JSON data to be searched.
    target_value: The value to search for in the JSON data.
    current_path (list, optional): The current path in the JSON hierarchy. Used internally during recursion.

    Returns:
    list or None: A list representing the path to the first occurrence of the target value.
                 Returns None if the target value is not found.

    Example:
    >>> data = {'key1': {'key2': {'key3': 'value'}}}
    >>> find_nested_key(data, 'value')
    ['key1', 'key2', 'key3']
    """
    if current_path is None:
        current_path = []

    if isinstance(json_data, dict):
        for key, value in json_data.items():
            new_path = current_path + [key]
            if value == target_value:
                return new_path
            elif isinstance(value, (dict, list)):
                result = find_nested_key(value, target_value, new_path)
                if result:
                    return result
    elif isinstance(json_data, list):
        for index, item in enumerate(json_data):
            new_path = current_path + [index]
            result = find_nested_key(item, target_value, new_path)
            if result:
                return result
    return None

def update_nested_key(json_data, keys, new_value):
    """
    Updates a nested key within a JSON-like data structure with a new value.

    Parameters:
    json_data (dict or list): The JSON-like data structure to be updated.
    keys (list): A list of keys representing the path to the nested key.
    new_value: The new value to be set for the specified nested key.

    Returns:
    dict or list: The updated JSON-like data structure.

    Raises:
    ValueError: If an invalid key is encountered or if the key path is not valid for the given data structure.

    Example:
    >>> data = {'key1': {'key2': {'key3': 'value'}}}
    >>> update_nested_key(data, ['key1', 'key2', 'key3'], 'new_value')
    {'key1': {'key2': {'key3': 'new_value'}}}
    """
    if not keys:
        return new_value

    key = keys[0]

    if isinstance(json_data, dict):
        if key in json_data:
            if len(keys) == 1:
                # If it's the last key, update the value
                json_data[key] = new_value
            else:
                # Recur for the next level
                json_data[key] = update_nested_key(json_data[key], keys[1:], new_value)
        else:
            # If the key is not present, create a new nested structure
            json_data[key] = update_nested_key({}, keys[1:], new_value)

    elif isinstance(json_data, list):
        try:
            index = int(key)
            if len(keys) == 1:
                # If it's the last key, update the value at the list index
                json_data[index] = new_value
            else:
                # Recur for the next level
                json_data[index] = update_nested_key(json_data[index], keys[1:], new_value)
        except ValueError:
            raise ValueError(f"Invalid key '{key}' for list; must be an integer.")

    return json_data

def replace_string_in_keys(d, old_value, new_value):
    """
    Recursively replaces occurrences of `old_value` with `new_value` in all keys of the dictionary.

    Args:
        d (dict): The dictionary to process.
        old_value (str): The value to be replaced in the keys.
        new_value (str): The value to replace with in the keys.

    Returns:
        dict: A new dictionary with the replaced keys.
    """
    if not isinstance(d, dict):
        logging.error("The provided input is not a dictionary.")
        raise ValueError("The provided input must be a dictionary.")

    def recursive_replace(data):
        """
        Inner function to recursively replace keys.

        Args:
            data (dict): The dictionary or nested dictionary to process.

        Returns:
            dict: A dictionary with replaced keys.
        """
        if isinstance(data, dict):
            new_dict = {}
            for k, v in data.items():
                new_key = k.replace(old_value, new_value) if isinstance(k, str) else k
                new_dict[new_key] = recursive_replace(v) if isinstance(v, dict) else v
            return new_dict
        else:
            return data

    logging.info("Starting the replacement process.")
    result = recursive_replace(d)
    logging.info("Replacement process completed.")
    return result


def main(args):
    """
    Reads a JSON file, searches for specified secrets, decodes and updates them, and writes the updated data to a new JSON file.

    Parameters:
    args (argparse.Namespace): Command-line arguments passed to the script.

    Returns:
    None

    Example:
    $ python script.py --json input.json --secrets SECRET_KEY API_KEY
    """
    json_file = args.json
    secrets = args.secrets

    # Reading a JSON file
    logging.info(f"Reading from {json_file}:")
    json_data = read_json_file(json_file)
    if json_data is None:
        logging.warn(f"No data found in file: {json_file}")
        exit(-1)

    for secret in secrets:
        keys = find_nested_key(json_data, secret)
        if keys is None:
            logging.info(f"No keys found for secret: {secret}")
        else:
            if os.environ.get(secret, None):
                secret_value = os.environ.get(secret)
                name, datatype = secret.split("_")[0], secret.split("_")[1]
                decode_data = decode(secret_value, datatype)
                json_data = update_nested_key(json_data, keys, decode_data)
    logging.info(f"\nWriting to {json_file}:")
    logging.info(f"\nData {json_data}:")
    write_json_file(json_file, json_data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Replacing Secrets in Json file.")
    parser.add_argument("--json", required=True, help="Input JSON file path")
    parser.add_argument("--secrets", nargs="+", required=True, help="List of secret names.")
    args = parser.parse_args()
    main(args)
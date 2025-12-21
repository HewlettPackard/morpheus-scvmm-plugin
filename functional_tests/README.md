# Installing Dependencies

## Pre-requisite  
JFROG credentials are required to install the library and can be set in the following way:

### Mac / Linux
```bash
export JFROG_USERNAME="username"
export JFROG_PASSWORD="password"
```

### Windows (PowerShell)
```powershell
$env:JFROG_USERNAME="username"
$env:JFROG_PASSWORD="password"
```

## Create Virtual Environment
It's recommended to use a virtual environment to manage dependencies. You can create one using `venv`:

### Mac / Linux
```bash
python3 -m venv venv
source venv/bin/activate
```

### Windows (PowerShell)
```powershell
python -m venv venv
venv\Scripts\activate.bat
```

## Install Dependencies
Install the required dependencies:

### Mac / Linux
```bash
./install_dependencies.sh
```
### Windows (PowerShell)
```powershell
bash ./install_dependencies.sh
```

### Running Tests
Before running tests, update the .env file with the correct values.
To run the tests, use the following command:

```bash
pytest -v --log-cli-level=INFO
```

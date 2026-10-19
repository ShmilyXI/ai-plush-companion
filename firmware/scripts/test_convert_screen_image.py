from pathlib import Path
import subprocess
import sys


def test_converter_script_has_cli_contract():
    script = Path(__file__).with_name('convert_screen_image.py')
    result = subprocess.run([sys.executable, str(script), '--help'], capture_output=True, text=True, check=True)
    assert '--width' in result.stdout
    assert '--manifest' in result.stdout

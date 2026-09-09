# SolarNode "Howler Monkey" meter support

"Howler Monkey" is a Raspberry Pi-based device with an integrated ATM90E36 energy meter chip.

# `meter-tool` script

The [meter-tool.py](./scripts/meter-tool.py) Python program is a CLI tool for reading and
calibrating the ATM90E36 chip.

## OS setup

SPI communication must be enabled in `/boot/firmware/config.txt` by adding a line with 
`dtparam=spi=on`.

Then to allow SolarNode to access the SPI devices, add `solar` to the `spi` group:

```sh
# add solar to spi group
usermod -a -G spi solar
```

Reboot the device after making these changes:

```sh
sudo reboot
```

## Software setup

The software requires the `spidev` Python package, which requires a working compiler on the device.
Install the necessary compiler support and set up a Python virtual environment for the packages:

```sh
# install python + pip + venv support + spidev compile support
sudo apt install python3-pip python3-venv python3-dev

# install build support (for spidev)
sudo apt install build-essential libtool

# setup venv
python3 -m venv ~/python/venv/hm
source ~/python/venv/hm/bin/activate
pip3 install spidev
```

Copy the `meter-tool.py` script to the device. Make it executable:

```sh
chmod 755 meter-tool.py
```

## Run tool

Run the tool without any options to print out CSV data on a continuous loop (hit <kbd>Ctrl-C</kbd>
to stop):

```sh
# run tool (prints CSV to STDOUT)
./meter-tool.py

Timestamp,Voltage_A,Voltage_B,Voltage_C,Current_A,Current_B,Current_C,Power_A,Power_B,Power_C,Power_Total,PF_Total,Frequency
2026-09-09 03:42:50,654.27,0.00,0.00,65.427,0.000,0.000,-2264.96,0.00,0.00,0.00,0.000,0.00
2026-09-09 03:42:55,654.27,0.00,0.00,65.427,0.000,0.000,-2264.96,0.00,0.00,0.00,0.000,0.00
```

### Calibration mode

Run the tool with `--mode calibrate` to configure the meter settings:

```sh
./meter-tool.py --mode calibrate

============================================================
ATM90E36 Calibration Mode
============================================================

Device initializing...

============================================================
CALIBRATION MENU
============================================================
1. Show current measurements
2. Voltage calibration
3. Current calibration (Phase A)
4. Show calibration values
0. Exit

Your choice (0-4):
```
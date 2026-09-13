# SolarNode "Howler Monkey" meter support

"Howler Monkey" is a Raspberry Pi-based device with an integrated ATM90E36 energy meter chip.

# OS setup

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


# SolarNode plugins

The `net.solarnetwork.node.hw.atm90e36` and `net.solarnetwork.node.datum.atm90e36` directories
contains SolarNode plugin projects that enable capturing data from the ATM90E36 chip.


# `meter-tool` script

The [meter-tool.py](./scripts/meter-tool.py) Python program is a CLI tool for reading and
calibrating the ATM90E36 chip.

## Software setup

The software requires the `spidev2` Python package. Set up a Python virtual environment for the
packages:

```sh
# install python + pip + venv support + spidev2
sudo apt install python3-pip python3-venv python3-dev

# setup venv
python3 -m venv ~/python/venv/hm
source ~/python/venv/hm/bin/activate
pip3 install spidev2
```

Copy the `meter-tool.py` script to the device. Make it executable:

```sh
chmod 755 meter-tool.py
```

## Run tool

Run the tool without any options to print out CSV data on a continuous loop (hit <kbd>Ctrl-C</kbd>
to stop). This only reads from the chip; the chip keeps its configuration until it loses power,
so after a power cycle run [calibration mode](#calibration-mode) first (a warning is printed if
the chip is not configured):

```sh
# run tool (prints CSV to STDOUT)
./meter-tool.py

Timestamp,Voltage_A,Voltage_B,Voltage_C,Current_A,Current_B,Current_C,Power_A,Power_B,Power_C,Power_Total,PF_Total,Frequency
2026-09-13T01:42:50.123456Z,222.23,0.00,0.00,0.381,0.000,0.000,-51.68,0.00,0.00,-51.69,-0.611,50.00
```

### Read registers

Run the tool with `--read` to print raw register values and exit, without configuring the chip.
With no registers it prints those useful for diagnosing power readings; otherwise give register
names (as in the datasheet, ignoring case), hex or decimal addresses:

```sh
./meter-tool.py --read
0x0033 (MMode0)   : 0x0087
0x0035 (PStartTh) : 0x1D4C
0x0038 (PPhaseTh) : 0x02EE
0x00D9 (UrmsA)    : 0x56CF
0x00DD (IrmsA)    : 0x017D
0x00B0 (PmeanT)   : 0xFFF3
0x00C0 (PmeanTLSB): 0x1400
0x00B1 (PmeanA)   : 0xFFCC
0x00C1 (PmeanALSB): 0x5100
0x00B5 (QmeanA)   : 0x0038
0x00BD (PFmeanA)  : 0xFD9D
0x0095 (EnStatus0): 0x0005

./meter-tool.py --read ConfigStart 0xF8
```

### Calibration mode

Run the tool with `--mode calibrate` to configure the meter settings. It applies a 3-phase
4-wire, 50 Hz configuration with all three phases counted into the totals (`MMode0` `0087H`),
then presents the calibration menu:

```sh
./meter-tool.py --mode calibrate

============================================================
ATM90E36 Calibration Mode
============================================================

Device initializing...
Configured: MMode0=0x0087 (expected 0x0087)

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
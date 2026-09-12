#!/usr/bin/env python3
import os
import spidev2
import time
import sys
import csv
import argparse
from datetime import datetime, timezone

# ============================================================================
# REGISTER DEFINITIONS
# ============================================================================

# SPI Communication Modes
WRITE = 0
READ = 1

# STATUS REGISTERS
SoftReset = 0x00
SysStatus0 = 0x01
SysStatus1 = 0x02
FuncEn0 = 0x03
FuncEn1 = 0x04
SagTh = 0x08

# CONFIGURATION REGISTERS
ConfigStart = 0x30
PLconstH = 0x31
PLconstL = 0x32
MMode0 = 0x33
MMode1 = 0x34
PStartTh = 0x35
QStartTh = 0x36
SStartTh = 0x37
PPhaseTh = 0x38
QPhaseTh = 0x39
SPhaseTh = 0x3A
CSZero = 0x3B

# CALIBRATION REGISTERS
CalStart = 0x40
GainA = 0x47
PhiA = 0x48
GainB = 0x49
PhiB = 0x4A
GainC = 0x4B
PhiC = 0x4C
PoffsetA = 0x41
QoffsetA = 0x42
PoffsetB = 0x43
QoffsetB = 0x44
PoffsetC = 0x45
QoffsetC = 0x46
CSOne = 0x4D

# HARMONIC REGISTERS
HarmStart = 0x50
POffsetAF = 0x51
POffsetBF = 0x52
POffsetCF = 0x53
PGainAF = 0x54
PGainBF = 0x55
PGainCF = 0x56
CSTwo = 0x57

# MEASUREMENT CALIBRATION REGISTERS
AdjStart = 0x60
UgainA = 0x61
IgainA = 0x62
UoffsetA = 0x63
IoffsetA = 0x64
UgainB = 0x65
IgainB = 0x66
UoffsetB = 0x67
IoffsetB = 0x68
UgainC = 0x69
IgainC = 0x6A
UoffsetC = 0x6B
IoffsetC = 0x6C
IgainN = 0x6D
IoffsetN = 0x6E
CSThree = 0x6F

# ENERGY REGISTERS
APenergyT = 0x80
APenergyA = 0x81
APenergyB = 0x82
APenergyC = 0x83
ANenergyT = 0x84
ANenergyA = 0x85
ANenergyB = 0x86
ANenergyC = 0x87

EnStatus0 = 0x95
EnStatus1 = 0x96

# POWER & V/I RMS REGISTERS
PmeanT = 0xB0
PmeanA = 0xB1
PmeanB = 0xB2
PmeanC = 0xB3
QmeanT = 0xB4
QmeanA = 0xB5
QmeanB = 0xB6
QmeanC = 0xB7
SmeanT = 0xB8
SmeanA = 0xB9
SmeanB = 0xBA
SmeanC = 0xBB
PFmeanT = 0xBC
PFmeanA = 0xBD
PFmeanB = 0xBE
PFmeanC = 0xBF

PmeanTLSB = 0xC0
PmeanALSB = 0xC1
PmeanBLSB = 0xC2
PmeanCLSB = 0xC3
QmeanTLSB = 0xC4
QmeanALSB = 0xC5
QmeanBLSB = 0xC6
QmeanCLSB = 0xC7
SAmeanTLSB = 0xC8
SmeanALSB = 0xC9
SmeanBLSB = 0xCA
SmeanCLSB = 0xCB

UrmsA = 0xD9
UrmsB = 0xDA
UrmsC = 0xDB
IrmsN0 = 0xDC
IrmsA = 0xDD
IrmsB = 0xDE
IrmsC = 0xDF

Freq = 0xF8
PAngleA = 0xF9
PAngleB = 0xFA
PAngleC = 0xFB
Temp = 0xFC

# Register addresses by lower-case name, and names by address, for the --read option
_REGISTERS = [
    (name, value) for name, value in list(globals().items())
    if isinstance(value, int) and name not in ('READ', 'WRITE')
]
REGISTERS_BY_NAME = {name.lower(): value for name, value in _REGISTERS}
REGISTER_NAMES = {
    value: '/'.join(n for n, v in _REGISTERS if v == value) for _, value in _REGISTERS
}

# The --read default: registers for diagnosing power readings. MMode0 bits
# EnPA/EnPB/EnPC select the phases counted into PmeanT; a phase whose |P|+|Q|
# is below PPhaseTh is counted as 0; UrmsA x IrmsA x PFmeanA cross-checks
# PmeanA; EnStatus0 b14 (TPNoload) flags total power below PStartTh.
DIAGNOSTIC_REGISTERS = (
    'MMode0', 'PStartTh', 'PPhaseTh', 'UrmsA', 'IrmsA', 'PmeanT', 'PmeanTLSB',
    'PmeanA', 'PmeanALSB', 'QmeanA', 'PFmeanA', 'EnStatus0',
)

# MMode0 (33H) metering method bits, datasheet 6.4.2. Defined after the register
# maps above so they are not mistaken for register addresses.
MMODE0_FREQ_60HZ = 1 << 12  # 0: 50 Hz
MMODE0_3P3W = 1 << 8        # 0: 3-phase 4-wire
MMODE0_CF2_VARH = 1 << 7    # CF2 pulses reactive energy
MMODE0_EN_PA = 1 << 2       # EnPA/EnPB/EnPC: phase counted into the all-phase totals
MMODE0_EN_PB = 1 << 1
MMODE0_EN_PC = 1 << 0

# Default metering method: 3-phase 4-wire at 50 Hz (as in New Zealand), CT
# current sensors, all three phases counted into the totals. This is 0087H, the
# app note's 3P4W 50 Hz value and also the chip's power-on value.
DEFAULT_METERING_MODE = MMODE0_CF2_VARH | MMODE0_EN_PA | MMODE0_EN_PB | MMODE0_EN_PC


# ============================================================================
# ATM90E36 CLASS
# ============================================================================

class ATM90E36:
    """
    ATM90E36 Energy Monitor IC Driver for Raspberry Pi
    Uses SPI0 interface (CE0 or CE1)

    This variant talks to the SPI bus through the pure-python ``spidev2``
    package, which needs no native compilation, instead of ``spidev``.
    """

    def __init__(self, spi_bus=0, spi_device=0, metering_mode=DEFAULT_METERING_MODE, pga_gain=21,
                 voltage_gain=50000, current_gain_a=32498,
                 current_gain_b=32498, current_gain_c=32498):
        """Initialize ATM90E36 energy monitor"""
        self.spi = None
        self.spi_bus = spi_bus
        self.spi_device = spi_device

        self.metering_mode = metering_mode
        self.pga_gain = pga_gain
        self.voltage_gain = voltage_gain
        self.current_gain_a = current_gain_a
        self.current_gain_b = current_gain_b
        self.current_gain_c = current_gain_c

    def open(self):
        """Open the SPI bus, without writing any register"""
        self.spi = spidev2.SPIBus(
            f'/dev/spidev{self.spi_bus}.{self.spi_device}',
            'w+b',
            bits_per_word=8,
            speed_hz=200000,
            spi_mode=spidev2.SPIMode32.SPI_MODE_3,
        )

    def begin(self):
        """Initialize SPI communication and configure ATM90E36"""
        self.open()

        # Determine voltage sag threshold from the line frequency: 90V nominal
        # for 60 Hz mains, 190V for 50 Hz
        if self.metering_mode & MMODE0_FREQ_60HZ:
            sag_v = 90
        else:
            sag_v = 190

        import math
        v_sag_th = int((sag_v * 100 * math.sqrt(2)) / (2 * self.voltage_gain / 32768))

        # Soft reset
        self.comm_energy_ic(WRITE, SoftReset, 0x789A)
        time.sleep(0.1)

        # Configure
        self.comm_energy_ic(WRITE, FuncEn0, 0x0000)
        self.comm_energy_ic(WRITE, FuncEn1, 0x0000)
        self.comm_energy_ic(WRITE, SagTh, v_sag_th)

        # CONFIG
        checksum = 0
        checksum = self.write_and_get_checksum(WRITE, ConfigStart, 0x5678, checksum)
        checksum = self.write_and_get_checksum(WRITE, PLconstH, 0x0861, checksum)
        checksum = self.write_and_get_checksum(WRITE, PLconstL, 0xC468, checksum)
        checksum = self.write_and_get_checksum(WRITE, MMode0, self.metering_mode, checksum)
        checksum = self.write_and_get_checksum(WRITE, MMode1, self.pga_gain, checksum)
        checksum = self.write_and_get_checksum(WRITE, PStartTh, 0x1D4C, checksum)
        checksum = self.write_and_get_checksum(WRITE, QStartTh, 0x1D4C, checksum)
        checksum = self.write_and_get_checksum(WRITE, SStartTh, 0x1D4C, checksum)
        checksum = self.write_and_get_checksum(WRITE, PPhaseTh, 0x02EE, checksum)
        checksum = self.write_and_get_checksum(WRITE, QPhaseTh, 0x02EE, checksum)
        checksum = self.write_and_get_checksum(WRITE, SPhaseTh, 0x02EE, checksum)
        self.write_and_get_checksum(WRITE, CSZero, checksum, checksum)

        # CALIBRATION
        self.comm_energy_ic(WRITE, CalStart, 0x5678)
        self.comm_energy_ic(WRITE, GainA, 0x0000)
        self.comm_energy_ic(WRITE, PhiA, 0x0000)
        self.comm_energy_ic(WRITE, GainB, 0x0000)
        self.comm_energy_ic(WRITE, PhiB, 0x0000)
        self.comm_energy_ic(WRITE, GainC, 0x0000)
        self.comm_energy_ic(WRITE, PhiC, 0x0000)
        self.comm_energy_ic(WRITE, PoffsetA, 0x0000)
        self.comm_energy_ic(WRITE, QoffsetA, 0x0000)
        self.comm_energy_ic(WRITE, PoffsetB, 0x0000)
        self.comm_energy_ic(WRITE, QoffsetB, 0x0000)
        self.comm_energy_ic(WRITE, PoffsetC, 0x0000)
        self.comm_energy_ic(WRITE, QoffsetC, 0x0000)
        self.comm_energy_ic(WRITE, CSOne, 0x0000)

        # HARMONIC
        checksum = 0
        checksum = self.write_and_get_checksum(WRITE, HarmStart, 0x5678, checksum)
        checksum = self.write_and_get_checksum(WRITE, POffsetAF, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, POffsetBF, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, POffsetCF, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, PGainAF, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, PGainBF, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, PGainCF, 0x0000, checksum)
        self.write_and_get_checksum(WRITE, CSTwo, checksum, checksum)

        # ADJUST
        checksum = 0
        checksum = self.write_and_get_checksum(WRITE, AdjStart, 0x5678, checksum)
        checksum = self.write_and_get_checksum(WRITE, UgainA, self.voltage_gain, checksum)
        checksum = self.write_and_get_checksum(WRITE, IgainA, self.current_gain_a, checksum)
        checksum = self.write_and_get_checksum(WRITE, UoffsetA, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, IoffsetA, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, UgainB, self.voltage_gain, checksum)
        checksum = self.write_and_get_checksum(WRITE, IgainB, self.current_gain_b, checksum)
        checksum = self.write_and_get_checksum(WRITE, UoffsetB, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, IoffsetB, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, UgainC, self.voltage_gain, checksum)
        checksum = self.write_and_get_checksum(WRITE, IgainC, self.current_gain_c, checksum)
        checksum = self.write_and_get_checksum(WRITE, UoffsetC, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, IoffsetC, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, IgainN, 0x0000, checksum)
        checksum = self.write_and_get_checksum(WRITE, IoffsetN, 0x0000, checksum)
        self.write_and_get_checksum(WRITE, CSThree, checksum, checksum)

    def _xfer(self, data):
        """Full-duplex SPI transfer of a list of bytes, returning a list of bytes.

        Equivalent to ``spidev``'s ``xfer2``: a single chip-select-asserted
        transfer where the number of bytes clocked out equals the number
        clocked in.
        """
        rx_buf = bytearray(len(data))
        self.spi.transfer(tx_buf=bytes(data), rx_buf=rx_buf)
        return list(rx_buf)

    def comm_energy_ic(self, rw, address, val):
        """SPI communication with ATM90E36.

        Per datasheet 4.2.1 each transaction is 32 SCLK cycles, MSB first: the
        access type bit (1 = read, 0 = write), a 15-bit register address (only
        the lower 10 bits are decoded), then 16 bits of data.
        """
        command = (rw << 15) | (address & 0x7FFF)
        addr_msb = (command >> 8) & 0xFF
        addr_lsb = command & 0xFF
        val_msb = (val >> 8) & 0xFF
        val_lsb = val & 0xFF

        time.sleep(0.00001)

        if rw == READ:
            response = self._xfer([addr_msb, addr_lsb, 0x00, 0x00])
            time.sleep(0.000004)
            result = (response[2] << 8) | response[3]
        else:
            self._xfer([addr_msb, addr_lsb, val_msb, val_lsb])
            time.sleep(0.000004)
            result = 0

        time.sleep(0.00001)
        return result

    def write_and_get_checksum(self, rw, address, val, checksum):
        """Write to register and update checksum"""
        self.comm_energy_ic(rw, address, val)
        if address != CSZero and address != CSOne and address != CSTwo and address != CSThree:
            checksum ^= val
        return checksum & 0xFFFF

    def read_register(self, address):
        """Read a raw 16-bit register value"""
        return self.comm_energy_ic(READ, address, 0xFFFF)

    def is_configured(self):
        """Check if the chip has been configured since it last reset.

        ConfigStart reads 6886H at power-on, and 5678H or 8765H once configured.
        """
        return self.read_register(ConfigStart) in (0x5678, 0x8765)

    # Watts (var, VA) per count of a phase / total power register, datasheet Table-11
    PHASE_POWER_WEIGHT = 1.0
    TOTAL_POWER_WEIGHT = 4.0

    def _read_power(self, msb_reg, lsb_reg, weight):
        """Read a signed MSB + LSB power register pair, scaled to W / var / VA.

        The MSB register is two's complement, with 1 LSB worth ``weight``. Only
        the upper 8 bits of the LSB register are valid, each worth
        ``weight / 256``, so together the pair is a 32-bit fixed-point value with
        16 fractional bits.
        """
        val = self.read_register(msb_reg)
        val_lsb = self.read_register(lsb_reg)
        if val & 0x8000:
            val = -((~val & 0xFFFF) + 1)
        return (val * 65536 + val_lsb) / 65536 * weight

    # VOLTAGE
    def get_line_voltage_a(self):
        return self.comm_energy_ic(READ, UrmsA, 0xFFFF) / 100.0

    def get_line_voltage_b(self):
        return self.comm_energy_ic(READ, UrmsB, 0xFFFF) / 100.0

    def get_line_voltage_c(self):
        return self.comm_energy_ic(READ, UrmsC, 0xFFFF) / 100.0

    # CURRENT
    def get_line_current_a(self):
        return self.comm_energy_ic(READ, IrmsA, 0xFFFF) / 1000.0

    def get_line_current_b(self):
        return self.comm_energy_ic(READ, IrmsB, 0xFFFF) / 1000.0

    def get_line_current_c(self):
        return self.comm_energy_ic(READ, IrmsC, 0xFFFF) / 1000.0

    def get_line_current_n(self):
        return self.comm_energy_ic(READ, IrmsN0, 0xFFFF) / 1000.0

    # ACTIVE POWER
    def get_active_power_a(self):
        return self._read_power(PmeanA, PmeanALSB, self.PHASE_POWER_WEIGHT)

    def get_active_power_b(self):
        return self._read_power(PmeanB, PmeanBLSB, self.PHASE_POWER_WEIGHT)

    def get_active_power_c(self):
        return self._read_power(PmeanC, PmeanCLSB, self.PHASE_POWER_WEIGHT)

    def get_total_active_power(self):
        return self._read_power(PmeanT, PmeanTLSB, self.TOTAL_POWER_WEIGHT)

    # REACTIVE POWER
    def get_reactive_power_a(self):
        return self._read_power(QmeanA, QmeanALSB, self.PHASE_POWER_WEIGHT)

    def get_reactive_power_b(self):
        return self._read_power(QmeanB, QmeanBLSB, self.PHASE_POWER_WEIGHT)

    def get_reactive_power_c(self):
        return self._read_power(QmeanC, QmeanCLSB, self.PHASE_POWER_WEIGHT)

    def get_total_reactive_power(self):
        return self._read_power(QmeanT, QmeanTLSB, self.TOTAL_POWER_WEIGHT)

    # APPARENT POWER (the MSB is always 0, so signed decoding is harmless)
    def get_apparent_power_a(self):
        return self._read_power(SmeanA, SmeanALSB, self.PHASE_POWER_WEIGHT)

    def get_apparent_power_b(self):
        return self._read_power(SmeanB, SmeanBLSB, self.PHASE_POWER_WEIGHT)

    def get_apparent_power_c(self):
        return self._read_power(SmeanC, SmeanCLSB, self.PHASE_POWER_WEIGHT)

    def get_total_apparent_power(self):
        return self._read_power(SmeanT, SAmeanTLSB, self.TOTAL_POWER_WEIGHT)

    # FREQUENCY
    def get_frequency(self):
        return self.comm_energy_ic(READ, Freq, 0xFFFF) / 100.0

    # POWER FACTOR
    def get_power_factor_a(self):
        pf = self.comm_energy_ic(READ, PFmeanA, 0xFFFF)
        if pf & 0x8000:
            pf = -((~pf & 0xFFFF) + 1)
        return pf / 1000.0

    def get_power_factor_b(self):
        pf = self.comm_energy_ic(READ, PFmeanB, 0xFFFF)
        if pf & 0x8000:
            pf = -((~pf & 0xFFFF) + 1)
        return pf / 1000.0

    def get_power_factor_c(self):
        pf = self.comm_energy_ic(READ, PFmeanC, 0xFFFF)
        if pf & 0x8000:
            pf = -((~pf & 0xFFFF) + 1)
        return pf / 1000.0

    def get_total_power_factor(self):
        pf = self.comm_energy_ic(READ, PFmeanT, 0xFFFF)
        if pf & 0x8000:
            pf = -((~pf & 0xFFFF) + 1)
        return pf / 1000.0

    # PHASE ANGLE
    def get_phase_a(self):
        angle = self.comm_energy_ic(READ, PAngleA, 0xFFFF)
        if angle & 0x8000:
            angle = -((~angle & 0xFFFF) + 1)
        return angle / 10.0

    def get_phase_b(self):
        angle = self.comm_energy_ic(READ, PAngleB, 0xFFFF)
        if angle & 0x8000:
            angle = -((~angle & 0xFFFF) + 1)
        return angle / 10.0

    def get_phase_c(self):
        angle = self.comm_energy_ic(READ, PAngleC, 0xFFFF)
        if angle & 0x8000:
            angle = -((~angle & 0xFFFF) + 1)
        return angle / 10.0

    # TEMPERATURE
    def get_temperature(self):
        return self.comm_energy_ic(READ, Temp, 0xFFFF)

    # ENERGY
    def get_import_energy(self):
        energy_t = self.comm_energy_ic(READ, APenergyT, 0xFFFF)
        energy_a = self.comm_energy_ic(READ, APenergyA, 0xFFFF)
        energy_b = self.comm_energy_ic(READ, APenergyB, 0xFFFF)
        energy_c = self.comm_energy_ic(READ, APenergyC, 0xFFFF)
        total_energy = (energy_t * 65536 + energy_a + energy_b + energy_c) * 0.0001
        return total_energy / 3600.0

    def get_export_energy(self):
        energy_t = self.comm_energy_ic(READ, ANenergyT, 0xFFFF)
        energy_a = self.comm_energy_ic(READ, ANenergyA, 0xFFFF)
        energy_b = self.comm_energy_ic(READ, ANenergyB, 0xFFFF)
        energy_c = self.comm_energy_ic(READ, ANenergyC, 0xFFFF)
        total_energy = (energy_t * 65536 + energy_a + energy_b + energy_c) * 0.0001
        return total_energy / 3600.0

    # SYSTEM STATUS
    def get_sys_status0(self):
        return self.comm_energy_ic(READ, SysStatus0, 0xFFFF)

    def get_sys_status1(self):
        return self.comm_energy_ic(READ, SysStatus1, 0xFFFF)

    def get_meter_status0(self):
        return self.comm_energy_ic(READ, EnStatus0, 0xFFFF)

    def get_meter_status1(self):
        return self.comm_energy_ic(READ, EnStatus1, 0xFFFF)

    def calibration_error(self):
        """Check for calibration errors"""
        sys0 = self.get_sys_status0()
        sys1 = self.get_sys_status1()
        if sys0 & 0x8000 or sys1 & 0x8000:
            return True
        return False

    def close(self):
        """Close SPI connection"""
        if self.spi is not None:
            self.spi.close()


# ============================================================================
# MODE 2: CALIBRATION
# ============================================================================

def run_calibration_mode():
    """Interactive calibration mode"""
    print("=" * 60)
    print("ATM90E36 Calibration Mode")
    print("=" * 60)
    print()

    PGA_GAIN = 21
    VOLTAGE_GAIN = 50000
    CURRENT_GAIN = 32498

    eic = ATM90E36(
        spi_bus=0,
        spi_device=0,
        pga_gain=PGA_GAIN,
        voltage_gain=VOLTAGE_GAIN,
        current_gain_a=CURRENT_GAIN,
        current_gain_b=CURRENT_GAIN,
        current_gain_c=CURRENT_GAIN
    )

    try:
        print("Device initializing...")
        time.sleep(2)
        eic.begin()
        time.sleep(1)
        print(f"Configured: MMode0=0x{eic.read_register(MMode0):04X} "
              f"(expected 0x{eic.metering_mode:04X})")

        while True:
            print("\n" + "=" * 60)
            print("CALIBRATION MENU")
            print("=" * 60)
            print("1. Show current measurements")
            print("2. Voltage calibration")
            print("3. Current calibration (Phase A)")
            print("4. Show calibration values")
            print("0. Exit")
            print()

            choice = input("Your choice (0-4): ").strip()

            if choice == '0':
                break
            elif choice == '1':
                # Current measurements
                print("\n" + "=" * 60)
                print("CURRENT MEASUREMENTS")
                print("=" * 60)

                va = eic.get_line_voltage_a()
                vb = eic.get_line_voltage_b()
                vc = eic.get_line_voltage_c()
                print(f"\n⚡ Voltage: A={va:.2f}V  B={vb:.2f}V  C={vc:.2f}V")

                ia = eic.get_line_current_a()
                ib = eic.get_line_current_b()
                ic = eic.get_line_current_c()
                print(f"🔌 Current: A={ia:.3f}A  B={ib:.3f}A  C={ic:.3f}A")

                pa = eic.get_active_power_a()
                pt = eic.get_total_active_power()
                print(f"💡 Power: A={pa:.2f}W  Total={pt:.2f}W")

                freq = eic.get_frequency()
                print(f"🌊 Frequency: {freq:.2f} Hz")

            elif choice == '2':
                # Voltage calibration
                print("\n" + "=" * 60)
                print("VOLTAGE CALIBRATION")
                print("=" * 60)

                measured_va = eic.get_line_voltage_a()
                print(f"\nATM90E36 reading: {measured_va:.2f} V")

                try:
                    actual_va = float(input("Voltage measured with multimeter (V): "))
                    current_gain = eic.voltage_gain
                    new_gain = int((actual_va / measured_va) * current_gain)

                    print(f"\n✅ Recommended voltage gain: {new_gain}")
                    print(f"Use in code: voltage_gain={new_gain}")

                except ValueError:
                    print("❌ Invalid input!")

            elif choice == '3':
                # Current calibration
                print("\n" + "=" * 60)
                print("CURRENT CALIBRATION - PHASE A")
                print("=" * 60)

                measured_ia = eic.get_line_current_a()
                print(f"\nATM90E36 reading: {measured_ia:.3f} A")

                if measured_ia < 0.1:
                    print("⚠️  Current is too low! Connect a higher load.")
                else:
                    try:
                        actual_ia = float(input("Current measured by ammeter (A): "))
                        current_gain = eic.current_gain_a
                        new_gain = int((actual_ia / measured_ia) * current_gain)

                        print(f"\n✅ Recommended current gain: {new_gain}")
                        print(f"Use in code: current_gain_a={new_gain}")

                    except ValueError:
                        print("❌ Invalid input!")

            elif choice == '4':
                # Kalibrasyon değerleri
                print("\n" + "=" * 60)
                print("CURRENT CALIBRATION VALUES")
                print("=" * 60)
                print(f"Voltage Gain: {eic.voltage_gain}")
                print(f"Current Gain A: {eic.current_gain_a}")
                print(f"Current Gain B: {eic.current_gain_b}")
                print(f"Current Gain C: {eic.current_gain_c}")
                print(f"Metering Mode (MMode0): 0x{eic.metering_mode:04X}")
                print(f"PGA Gain: {eic.pga_gain}")
            else:
                print("❌ Invalid selection!")

            input("\nPress Enter to continue...")

    except KeyboardInterrupt:
        print("\n\nCancelled.")
    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        eic.close()
        print("Connection closed.")



# ============================================================================
# MODE 4: CSV LOGGER
# ============================================================================

def run_csv_mode():
    """CSV data logger, which only reads from the chip"""
    eic = ATM90E36(spi_bus=0, spi_device=0)

    try:
        # the chip keeps its configuration until it loses power, so use
        # --mode calibrate to configure it
        eic.open()
        if not eic.is_configured():
            print("Warning: ATM90E36 not configured since power-on; "
                  "run --mode calibrate to configure it", file=sys.stderr)
        fieldnames = [
            'Timestamp', 'Voltage_A', 'Voltage_B', 'Voltage_C',
            'Current_A', 'Current_B', 'Current_C',
            'Power_A', 'Power_B', 'Power_C', 'Power_Total',
            'PF_Total', 'Frequency'
        ]

        writer = csv.DictWriter(sys.stdout, fieldnames=fieldnames)
        writer.writeheader()
        sys.stdout.flush()

        sample_count = 0

        while True:
            data = {
                'Timestamp': datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                'Voltage_A': f"{eic.get_line_voltage_a():.2f}",
                'Voltage_B': f"{eic.get_line_voltage_b():.2f}",
                'Voltage_C': f"{eic.get_line_voltage_c():.2f}",
                'Current_A': f"{eic.get_line_current_a():.3f}",
                'Current_B': f"{eic.get_line_current_b():.3f}",
                'Current_C': f"{eic.get_line_current_c():.3f}",
                'Power_A': f"{eic.get_active_power_a():.2f}",
                'Power_B': f"{eic.get_active_power_b():.2f}",
                'Power_C': f"{eic.get_active_power_c():.2f}",
                'Power_Total': f"{eic.get_total_active_power():.2f}",
                'PF_Total': f"{eic.get_total_power_factor():.3f}",
                'Frequency': f"{eic.get_frequency():.2f}"
            }

            writer.writerow(data)
            sys.stdout.flush()

            sample_count += 1

            time.sleep(5)

    except BrokenPipeError:
        devnull = os.open(os.devnull, os.O_WRONLY)
        os.dup2(devnull, sys.stdout.fileno())
        sys.exit(0)
    except KeyboardInterrupt:
        pass
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
    finally:
        eic.close()


# ============================================================================
# REGISTER READ
# ============================================================================

def parse_register(value):
    """Parse a register address given as a name (PmeanT), hex (0xB0) or decimal (176)"""
    address = REGISTERS_BY_NAME.get(value.lower())
    if address is None:
        try:
            address = int(value, 0)
        except ValueError:
            raise argparse.ArgumentTypeError(
                f"not a register name or number: {value}") from None
    if not 0 <= address <= 0x7FFF:
        raise argparse.ArgumentTypeError(f"register address out of range: {value}")
    return address


def register_label(address):
    """Format a register address with its name, if known, like 0x00B0 (PmeanT)"""
    name = REGISTER_NAMES.get(address)
    return f"0x{address:04X} ({name})" if name else f"0x{address:04X}"


def run_read_mode(registers):
    """Print raw register values, without configuring the chip"""
    labels = [register_label(address) for address in registers]
    width = max(len(label) for label in labels)
    eic = ATM90E36(spi_bus=0, spi_device=0)
    try:
        eic.open()
        for address, label in zip(registers, labels):
            print(f"{label:<{width}}: 0x{eic.read_register(address):04X}")
    finally:
        eic.close()


# ============================================================================
# MAIN
# ============================================================================

def main():
    """The program"""
    parser = argparse.ArgumentParser(
        description='ATM90E36 Energy Monitor',
        formatter_class=argparse.RawDescriptionHelpFormatter
    )

    parser.add_argument(
        '--mode',
        choices=['calibrate', 'csv'],
        default='csv',
        help='Working mode (default: csv)'
    )

    parser.add_argument(
        '--read',
        nargs='*',
        type=parse_register,
        metavar='REG',
        help='Print raw register values as "0xADDR (Name): 0xVALUE" lines and exit, '
             'without configuring the chip. Each REG is a register name '
             '(e.g. PmeanT), hex (0xB0) or decimal (176). With no REG, reads '
             'the power diagnostic registers: ' + ' '.join(DIAGNOSTIC_REGISTERS)
    )

    args = parser.parse_args()

    if args.read is not None:
        run_read_mode(args.read or [REGISTERS_BY_NAME[n.lower()] for n in DIAGNOSTIC_REGISTERS])
    elif args.mode == 'calibrate':
        run_calibration_mode()
    elif args.mode == 'csv':
        run_csv_mode()


if __name__ == "__main__":
    main()

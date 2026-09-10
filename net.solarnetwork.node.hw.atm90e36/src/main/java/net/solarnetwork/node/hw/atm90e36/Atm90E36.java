/* ==================================================================
 * Atm90E36.java - 10/09/2026
 *
 * Copyright 2026 SolarNetwork.net Dev Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307 USA
 * ==================================================================
 */

package net.solarnetwork.node.hw.atm90e36;

import java.util.concurrent.locks.LockSupport;

/**
 * Driver for the Atmel/Microchip ATM90E36 poly-phase energy metering IC,
 * communicating over SPI.
 *
 * <p>
 * This is a direct port of the {@code ATM90E36} class from the
 * {@code meter-tool-2.py} program. Register definitions, the SPI framing in
 * {@link #readRegister(int)} / {@link #writeRegister(int, int)}, the
 * configuration sequence in {@link #begin()} and every measurement scaling factor
 * are preserved as-is. The only substituted layer is the SPI transport, provided
 * here by a {@link SpiDevice}.
 * </p>
 *
 * <p>
 * Instances are not safe for concurrent use; drive one from a single thread (as
 * {@code meter-tool} does). {@link #close()} is the exception — it is safe to
 * call from another thread, such as a shutdown hook, and is idempotent, so an
 * {@code Atm90E36} works as a try-with-resources resource.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class Atm90E36 implements AutoCloseable {

	// ========================================================================
	// REGISTER DEFINITIONS
	// ========================================================================

	/** Address bit 15 set marks a register read (clear marks a write). */
	private static final int READ_FLAG = 0x8000;

	/**
	 * Microseconds to hold between register accesses in a batched read. The
	 * ATM90E36 only requires a sub-microsecond chip-select gap (datasheet
	 * {@code tCSH}); this matches the more conservative spacing the per-register
	 * Python path used.
	 */
	private static final int BATCH_SETTLE_MICROS = 10;

	// STATUS REGISTERS
	private static final int SoftReset = 0x00;
	private static final int SysStatus0 = 0x01;
	private static final int SysStatus1 = 0x02;
	private static final int FuncEn0 = 0x03;
	private static final int FuncEn1 = 0x04;
	private static final int SagTh = 0x08;

	// CONFIGURATION REGISTERS
	private static final int ConfigStart = 0x30;
	private static final int PLconstH = 0x31;
	private static final int PLconstL = 0x32;
	private static final int MMode0 = 0x33;
	private static final int MMode1 = 0x34;
	private static final int PStartTh = 0x35;
	private static final int QStartTh = 0x36;
	private static final int SStartTh = 0x37;
	private static final int PPhaseTh = 0x38;
	private static final int QPhaseTh = 0x39;
	private static final int SPhaseTh = 0x3A;
	private static final int CSZero = 0x3B;

	// CALIBRATION REGISTERS
	private static final int CalStart = 0x40;
	private static final int GainA = 0x47;
	private static final int PhiA = 0x48;
	private static final int GainB = 0x49;
	private static final int PhiB = 0x4A;
	private static final int GainC = 0x4B;
	private static final int PhiC = 0x4C;
	private static final int PoffsetA = 0x41;
	private static final int QoffsetA = 0x42;
	private static final int PoffsetB = 0x43;
	private static final int QoffsetB = 0x44;
	private static final int PoffsetC = 0x45;
	private static final int QoffsetC = 0x46;
	private static final int CSOne = 0x4D;

	// HARMONIC REGISTERS
	private static final int HarmStart = 0x50;
	private static final int POffsetAF = 0x51;
	private static final int POffsetBF = 0x52;
	private static final int POffsetCF = 0x53;
	private static final int PGainAF = 0x54;
	private static final int PGainBF = 0x55;
	private static final int PGainCF = 0x56;
	private static final int CSTwo = 0x57;

	// MEASUREMENT CALIBRATION REGISTERS
	private static final int AdjStart = 0x60;
	private static final int UgainA = 0x61;
	private static final int IgainA = 0x62;
	private static final int UoffsetA = 0x63;
	private static final int IoffsetA = 0x64;
	private static final int UgainB = 0x65;
	private static final int IgainB = 0x66;
	private static final int UoffsetB = 0x67;
	private static final int IoffsetB = 0x68;
	private static final int UgainC = 0x69;
	private static final int IgainC = 0x6A;
	private static final int UoffsetC = 0x6B;
	private static final int IoffsetC = 0x6C;
	private static final int IgainN = 0x6D;
	private static final int IoffsetN = 0x6E;
	private static final int CSThree = 0x6F;

	// ENERGY REGISTERS
	private static final int APenergyT = 0x80;
	private static final int APenergyA = 0x81;
	private static final int APenergyB = 0x82;
	private static final int APenergyC = 0x83;
	private static final int ANenergyT = 0x84;
	private static final int ANenergyA = 0x85;
	private static final int ANenergyB = 0x86;
	private static final int ANenergyC = 0x87;

	private static final int EnStatus0 = 0x95;
	private static final int EnStatus1 = 0x96;

	// POWER & V/I RMS REGISTERS
	private static final int PmeanT = 0xB0;
	private static final int PmeanA = 0xB1;
	private static final int PmeanB = 0xB2;
	private static final int PmeanC = 0xB3;
	private static final int QmeanT = 0xB4;
	private static final int QmeanA = 0xB5;
	private static final int QmeanB = 0xB6;
	private static final int QmeanC = 0xB7;
	private static final int SmeanT = 0xB8;
	private static final int SmeanA = 0xB9;
	private static final int SmeanB = 0xBA;
	private static final int SmeanC = 0xBB;
	private static final int PFmeanT = 0xBC;
	private static final int PFmeanA = 0xBD;
	private static final int PFmeanB = 0xBE;
	private static final int PFmeanC = 0xBF;

	private static final int PmeanTLSB = 0xC0;
	private static final int PmeanALSB = 0xC1;
	private static final int PmeanBLSB = 0xC2;
	private static final int PmeanCLSB = 0xC3;
	private static final int QmeanTLSB = 0xC4;
	private static final int QmeanALSB = 0xC5;
	private static final int QmeanBLSB = 0xC6;
	private static final int QmeanCLSB = 0xC7;
	private static final int SAmeanTLSB = 0xC8;
	private static final int SmeanALSB = 0xC9;
	private static final int SmeanBLSB = 0xCA;
	private static final int SmeanCLSB = 0xCB;

	private static final int UrmsA = 0xD9;
	private static final int UrmsB = 0xDA;
	private static final int UrmsC = 0xDB;
	private static final int IrmsN0 = 0xDC;
	private static final int IrmsA = 0xDD;
	private static final int IrmsB = 0xDE;
	private static final int IrmsC = 0xDF;

	private static final int Freq = 0xF8;
	private static final int PAngleA = 0xF9;
	private static final int PAngleB = 0xFA;
	private static final int PAngleC = 0xFB;
	private static final int Temp = 0xFC;

	// ========================================================================

	private final SpiDevice spi;
	private final int lineFreq;
	private final int pgaGain;
	private final int voltageGain;
	private final int currentGainA;
	private final int currentGainB;
	private final int currentGainC;

	/**
	 * Construct with defaults matching {@code meter-tool-2.py}: line frequency
	 * mode {@code 500}, PGA gain {@code 21}, voltage gain {@code 50000} and
	 * current gain {@code 32498} on all three phases.
	 *
	 * @param spi
	 *        the SPI device to communicate over
	 */
	public Atm90E36(SpiDevice spi) {
		this(spi, 500, 21, 50000, 32498, 32498, 32498);
	}

	/**
	 * Construct with explicit calibration settings.
	 *
	 * @param spi
	 *        the SPI device to communicate over
	 * @param lineFreq
	 *        the {@code MMode0} line-frequency configuration value
	 * @param pgaGain
	 *        the {@code MMode1} PGA gain configuration value
	 * @param voltageGain
	 *        the voltage gain calibration value
	 * @param currentGainA
	 *        the phase A current gain calibration value
	 * @param currentGainB
	 *        the phase B current gain calibration value
	 * @param currentGainC
	 *        the phase C current gain calibration value
	 */
	public Atm90E36(SpiDevice spi, int lineFreq, int pgaGain, int voltageGain, int currentGainA,
			int currentGainB, int currentGainC) {
		super();
		this.spi = spi;
		this.lineFreq = lineFreq;
		this.pgaGain = pgaGain;
		this.voltageGain = voltageGain;
		this.currentGainA = currentGainA;
		this.currentGainB = currentGainB;
		this.currentGainC = currentGainC;
	}

	/**
	 * Open the SPI device and push the full configuration, calibration, harmonic
	 * and measurement-adjustment register sets to the chip.
	 */
	public void begin() {
		spi.open(3, 200000, 8);

		// Determine voltage sag threshold
		int sagV;
		if ( lineFreq == 4485 || lineFreq == 5231 ) {
			sagV = 90;
		} else {
			sagV = 190;
		}

		int vSagTh = (int) ((sagV * 100 * Math.sqrt(2)) / (2.0 * voltageGain / 32768.0));

		// Soft reset
		writeRegister(SoftReset, 0x789A);
		sleepMillis(100);

		// Configure
		writeRegister(FuncEn0, 0x0000);
		writeRegister(FuncEn1, 0x0000);
		writeRegister(SagTh, vSagTh);

		// CONFIG
		int checksum = 0;
		checksum = writeAndGetChecksum(ConfigStart, 0x5678, checksum);
		checksum = writeAndGetChecksum(PLconstH, 0x0861, checksum);
		checksum = writeAndGetChecksum(PLconstL, 0xC468, checksum);
		checksum = writeAndGetChecksum(MMode0, lineFreq, checksum);
		checksum = writeAndGetChecksum(MMode1, pgaGain, checksum);
		checksum = writeAndGetChecksum(PStartTh, 0x1D4C, checksum);
		checksum = writeAndGetChecksum(QStartTh, 0x1D4C, checksum);
		checksum = writeAndGetChecksum(SStartTh, 0x1D4C, checksum);
		checksum = writeAndGetChecksum(PPhaseTh, 0x02EE, checksum);
		checksum = writeAndGetChecksum(QPhaseTh, 0x02EE, checksum);
		checksum = writeAndGetChecksum(SPhaseTh, 0x02EE, checksum);
		writeAndGetChecksum(CSZero, checksum, checksum);

		// CALIBRATION
		writeRegister(CalStart, 0x5678);
		writeRegister(GainA, 0x0000);
		writeRegister(PhiA, 0x0000);
		writeRegister(GainB, 0x0000);
		writeRegister(PhiB, 0x0000);
		writeRegister(GainC, 0x0000);
		writeRegister(PhiC, 0x0000);
		writeRegister(PoffsetA, 0x0000);
		writeRegister(QoffsetA, 0x0000);
		writeRegister(PoffsetB, 0x0000);
		writeRegister(QoffsetB, 0x0000);
		writeRegister(PoffsetC, 0x0000);
		writeRegister(QoffsetC, 0x0000);
		writeRegister(CSOne, 0x0000);

		// HARMONIC
		checksum = 0;
		checksum = writeAndGetChecksum(HarmStart, 0x5678, checksum);
		checksum = writeAndGetChecksum(POffsetAF, 0x0000, checksum);
		checksum = writeAndGetChecksum(POffsetBF, 0x0000, checksum);
		checksum = writeAndGetChecksum(POffsetCF, 0x0000, checksum);
		checksum = writeAndGetChecksum(PGainAF, 0x0000, checksum);
		checksum = writeAndGetChecksum(PGainBF, 0x0000, checksum);
		checksum = writeAndGetChecksum(PGainCF, 0x0000, checksum);
		writeAndGetChecksum(CSTwo, checksum, checksum);

		// ADJUST
		checksum = 0;
		checksum = writeAndGetChecksum(AdjStart, 0x5678, checksum);
		checksum = writeAndGetChecksum(UgainA, voltageGain, checksum);
		checksum = writeAndGetChecksum(IgainA, currentGainA, checksum);
		checksum = writeAndGetChecksum(UoffsetA, 0x0000, checksum);
		checksum = writeAndGetChecksum(IoffsetA, 0x0000, checksum);
		checksum = writeAndGetChecksum(UgainB, voltageGain, checksum);
		checksum = writeAndGetChecksum(IgainB, currentGainB, checksum);
		checksum = writeAndGetChecksum(UoffsetB, 0x0000, checksum);
		checksum = writeAndGetChecksum(IoffsetB, 0x0000, checksum);
		checksum = writeAndGetChecksum(UgainC, voltageGain, checksum);
		checksum = writeAndGetChecksum(IgainC, currentGainC, checksum);
		checksum = writeAndGetChecksum(UoffsetC, 0x0000, checksum);
		checksum = writeAndGetChecksum(IoffsetC, 0x0000, checksum);
		checksum = writeAndGetChecksum(IgainN, 0x0000, checksum);
		checksum = writeAndGetChecksum(IoffsetN, 0x0000, checksum);
		writeAndGetChecksum(CSThree, checksum, checksum);
	}

	/**
	 * Read a 16-bit register value.
	 *
	 * <p>
	 * Byte-for-byte equivalent to a read through the Python {@code comm_energy_ic}
	 * method: the address (with the read flag set) and the result are both
	 * byte-swapped, and the same inter-transfer delays are applied.
	 * </p>
	 *
	 * @param address
	 *        the register address, {@code 0x00}-{@code 0xFF}
	 * @return the register value, {@code 0}-{@code 65535}
	 */
	public int readRegister(int address) {
		int addr = swap16((address | READ_FLAG) & 0xFFFF);
		int addrMsb = (addr >> 8) & 0xFF;
		int addrLsb = addr & 0xFF;

		sleepMicros(10);
		byte[] response = spi.transfer(new byte[] { (byte) addrMsb, (byte) addrLsb, 0x00, 0x00 });
		sleepMicros(4);
		int result = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
		sleepMicros(10);

		return swap16(result) & 0xFFFF;
	}

	/**
	 * Write a 16-bit register value.
	 *
	 * <p>
	 * Byte-for-byte equivalent to a write through the Python
	 * {@code comm_energy_ic} method: the address and the value are both
	 * byte-swapped, and the same inter-transfer delays are applied.
	 * </p>
	 *
	 * @param address
	 *        the register address, {@code 0x00}-{@code 0xFF}
	 * @param value
	 *        the value to write, {@code 0}-{@code 65535}
	 */
	public void writeRegister(int address, int value) {
		int addr = swap16(address & 0xFFFF);
		int val = swap16(value & 0xFFFF);
		int addrMsb = (addr >> 8) & 0xFF;
		int addrLsb = addr & 0xFF;
		int valMsb = (val >> 8) & 0xFF;
		int valLsb = val & 0xFF;

		sleepMicros(10);
		spi.transfer(new byte[] { (byte) addrMsb, (byte) addrLsb, (byte) valMsb, (byte) valLsb });
		sleepMicros(4);
		sleepMicros(10);
	}

	/** Swap the upper and lower bytes of a 16-bit value. */
	private static int swap16(int v) {
		return ((v >> 8) & 0xFF) | ((v << 8) & 0xFF00);
	}

	/** Build the 4-byte read frame for each register address. */
	private static byte[][] readFrames(int[] addresses) {
		byte[][] frames = new byte[addresses.length][];
		for ( int i = 0; i < addresses.length; i++ ) {
			int addr = swap16((addresses[i] | READ_FLAG) & 0xFFFF);
			frames[i] = new byte[] { (byte) ((addr >> 8) & 0xFF), (byte) (addr & 0xFF), 0x00, 0x00 };
		}
		return frames;
	}

	/** Decode the received frames from a batched read into register values. */
	private static int[] decodeReadResponses(byte[][] responses) {
		int[] values = new int[responses.length];
		for ( int i = 0; i < responses.length; i++ ) {
			byte[] r = responses[i];
			int raw = ((r[2] & 0xFF) << 8) | (r[3] & 0xFF);
			values[i] = swap16(raw) & 0xFFFF;
		}
		return values;
	}

	/**
	 * Read several registers in a single batched SPI operation (one
	 * {@code ioctl}), each still framed by its own chip-select cycle as the
	 * ATM90E36 protocol requires.
	 *
	 * @param addresses
	 *        the register addresses to read, in order
	 * @return the register values, one per address, each {@code 0}-{@code 65535}
	 */
	private int[] readRegisters(int... addresses) {
		try ( SpiDevice.Batch batch = spi.batch(readFrames(addresses), BATCH_SETTLE_MICROS) ) {
			return decodeReadResponses(batch.transfer());
		}
	}

	private int writeAndGetChecksum(int address, int value, int checksum) {
		writeRegister(address, value);
		if ( address != CSZero && address != CSOne && address != CSTwo && address != CSThree ) {
			checksum ^= value;
		}
		return checksum & 0xFFFF;
	}

	private static int signed16(int v) {
		if ( (v & 0x8000) != 0 ) {
			return -(((~v) & 0xFFFF) + 1);
		}
		return v;
	}

	// ========================================================================
	// BATCHED MEASUREMENTS
	// ========================================================================

	/**
	 * A snapshot of the measurements written to each CSV row: per-phase and total
	 * RMS voltage, RMS current, active power, plus total power factor and line
	 * frequency.
	 *
	 * @param voltageA
	 *        phase A voltage, V
	 * @param voltageB
	 *        phase B voltage, V
	 * @param voltageC
	 *        phase C voltage, V
	 * @param currentA
	 *        phase A current, A
	 * @param currentB
	 *        phase B current, A
	 * @param currentC
	 *        phase C current, A
	 * @param powerA
	 *        phase A active power, W
	 * @param powerB
	 *        phase B active power, W
	 * @param powerC
	 *        phase C active power, W
	 * @param powerTotal
	 *        total active power, W
	 * @param powerFactorTotal
	 *        total power factor
	 * @param frequency
	 *        line frequency, Hz
	 */
	public record Measurements(double voltageA, double voltageB, double voltageC, double currentA,
			double currentB, double currentC, double powerA, double powerB, double powerC,
			double powerTotal, double powerFactorTotal, double frequency) {
	}

	/** The registers read for one {@link #readMeasurements()} call, in order. */
	private static final int[] MEASUREMENT_REGISTERS = { UrmsA, UrmsB, UrmsC, IrmsA, IrmsB, IrmsC,
			PmeanA, PmeanALSB, PmeanB, PmeanBLSB, PmeanC, PmeanCLSB, PmeanT, PmeanTLSB, PFmeanT, Freq };

	private SpiDevice.Batch measurementBatch;

	/**
	 * Read every register needed for a CSV row in a single batched SPI operation.
	 *
	 * <p>
	 * This issues one {@code ioctl} covering all 16 register reads instead of one
	 * per register, which both cuts system-call overhead and removes the
	 * per-access thread-sleep jitter of the individual accessor methods. The
	 * batch is created on first use and its native buffers are reused on every
	 * subsequent call, so a caller polling at a short interval allocates nothing
	 * here beyond the returned {@link Measurements}; it is released by
	 * {@link #close()}.
	 * </p>
	 *
	 * @return the measurement snapshot
	 */
	public Measurements readMeasurements() {
		int[] r = decodeReadResponses(measurementBatch().transfer());
		return new Measurements(r[0] / 100.0, r[1] / 100.0, r[2] / 100.0, r[3] / 1000.0, r[4] / 1000.0,
				r[5] / 1000.0, power(r[6], r[7]), power(r[8], r[9]), power(r[10], r[11]),
				power(r[12], r[13]), signed16(r[14]) / 1000.0, r[15] / 100.0);
	}

	private synchronized SpiDevice.Batch measurementBatch() {
		if ( measurementBatch == null ) {
			measurementBatch = spi.batch(readFrames(MEASUREMENT_REGISTERS), BATCH_SETTLE_MICROS);
		}
		return measurementBatch;
	}

	// VOLTAGE

	/** @return phase A line voltage, in volts */
	public double getLineVoltageA() {
		return readRegister(UrmsA) / 100.0;
	}

	/** @return phase B line voltage, in volts */
	public double getLineVoltageB() {
		return readRegister(UrmsB) / 100.0;
	}

	/** @return phase C line voltage, in volts */
	public double getLineVoltageC() {
		return readRegister(UrmsC) / 100.0;
	}

	// CURRENT

	/** @return phase A line current, in amperes */
	public double getLineCurrentA() {
		return readRegister(IrmsA) / 1000.0;
	}

	/** @return phase B line current, in amperes */
	public double getLineCurrentB() {
		return readRegister(IrmsB) / 1000.0;
	}

	/** @return phase C line current, in amperes */
	public double getLineCurrentC() {
		return readRegister(IrmsC) / 1000.0;
	}

	/** @return neutral line current, in amperes */
	public double getLineCurrentN() {
		return readRegister(IrmsN0) / 1000.0;
	}

	// ACTIVE POWER

	/** Scale a signed MSB + unsigned LSB power register pair to watts / var. */
	private static double power(int msbRaw, int lsbRaw) {
		return (signed16(msbRaw) * 65536.0 + lsbRaw) * 0.00032;
	}

	private double activePower(int msbReg, int lsbReg) {
		return power(readRegister(msbReg), readRegister(lsbReg));
	}

	/** @return phase A active power, in watts */
	public double getActivePowerA() {
		return activePower(PmeanA, PmeanALSB);
	}

	/** @return phase B active power, in watts */
	public double getActivePowerB() {
		return activePower(PmeanB, PmeanBLSB);
	}

	/** @return phase C active power, in watts */
	public double getActivePowerC() {
		return activePower(PmeanC, PmeanCLSB);
	}

	/** @return total active power, in watts */
	public double getTotalActivePower() {
		return activePower(PmeanT, PmeanTLSB);
	}

	// REACTIVE POWER

	private double reactivePower(int msbReg, int lsbReg) {
		return power(readRegister(msbReg), readRegister(lsbReg));
	}

	/** @return phase A reactive power, in var */
	public double getReactivePowerA() {
		return reactivePower(QmeanA, QmeanALSB);
	}

	/** @return phase B reactive power, in var */
	public double getReactivePowerB() {
		return reactivePower(QmeanB, QmeanBLSB);
	}

	/** @return phase C reactive power, in var */
	public double getReactivePowerC() {
		return reactivePower(QmeanC, QmeanCLSB);
	}

	/** @return total reactive power, in var */
	public double getTotalReactivePower() {
		return reactivePower(QmeanT, QmeanTLSB);
	}

	// APPARENT POWER

	private double apparentPower(int msbReg, int lsbReg) {
		int val = readRegister(msbReg);
		int valLsb = readRegister(lsbReg);
		return ((double) val * 65536.0 + valLsb) * 0.00032;
	}

	/** @return phase A apparent power, in VA */
	public double getApparentPowerA() {
		return apparentPower(SmeanA, SmeanALSB);
	}

	/** @return phase B apparent power, in VA */
	public double getApparentPowerB() {
		return apparentPower(SmeanB, SmeanBLSB);
	}

	/** @return phase C apparent power, in VA */
	public double getApparentPowerC() {
		return apparentPower(SmeanC, SmeanCLSB);
	}

	/** @return total apparent power, in VA */
	public double getTotalApparentPower() {
		return apparentPower(SmeanT, SAmeanTLSB);
	}

	// FREQUENCY

	/** @return the line frequency, in Hz */
	public double getFrequency() {
		return readRegister(Freq) / 100.0;
	}

	// POWER FACTOR

	private double powerFactor(int reg) {
		return signed16(readRegister(reg)) / 1000.0;
	}

	/** @return phase A power factor */
	public double getPowerFactorA() {
		return powerFactor(PFmeanA);
	}

	/** @return phase B power factor */
	public double getPowerFactorB() {
		return powerFactor(PFmeanB);
	}

	/** @return phase C power factor */
	public double getPowerFactorC() {
		return powerFactor(PFmeanC);
	}

	/** @return total power factor */
	public double getTotalPowerFactor() {
		return powerFactor(PFmeanT);
	}

	// PHASE ANGLE

	private double phaseAngle(int reg) {
		return signed16(readRegister(reg)) / 10.0;
	}

	/** @return phase A voltage/current angle, in degrees */
	public double getPhaseA() {
		return phaseAngle(PAngleA);
	}

	/** @return phase B voltage/current angle, in degrees */
	public double getPhaseB() {
		return phaseAngle(PAngleB);
	}

	/** @return phase C voltage/current angle, in degrees */
	public double getPhaseC() {
		return phaseAngle(PAngleC);
	}

	// TEMPERATURE

	/** @return the raw on-chip temperature register value */
	public int getTemperature() {
		return readRegister(Temp);
	}

	// ENERGY

	/** @return cumulative imported (positive) active energy, in kWh */
	public double getImportEnergy() {
		int energyT = readRegister(APenergyT);
		int energyA = readRegister(APenergyA);
		int energyB = readRegister(APenergyB);
		int energyC = readRegister(APenergyC);
		double totalEnergy = ((double) energyT * 65536.0 + energyA + energyB + energyC) * 0.0001;
		return totalEnergy / 3600.0;
	}

	/** @return cumulative exported (negative) active energy, in kWh */
	public double getExportEnergy() {
		int energyT = readRegister(ANenergyT);
		int energyA = readRegister(ANenergyA);
		int energyB = readRegister(ANenergyB);
		int energyC = readRegister(ANenergyC);
		double totalEnergy = ((double) energyT * 65536.0 + energyA + energyB + energyC) * 0.0001;
		return totalEnergy / 3600.0;
	}

	// SYSTEM STATUS

	/** @return the {@code SysStatus0} register */
	public int getSysStatus0() {
		return readRegister(SysStatus0);
	}

	/** @return the {@code SysStatus1} register */
	public int getSysStatus1() {
		return readRegister(SysStatus1);
	}

	/** @return the {@code EnStatus0} metering status register */
	public int getMeterStatus0() {
		return readRegister(EnStatus0);
	}

	/** @return the {@code EnStatus1} metering status register */
	public int getMeterStatus1() {
		return readRegister(EnStatus1);
	}

	/**
	 * Check the system status registers for a calibration error.
	 *
	 * @return {@code true} if either {@code SysStatus0} or {@code SysStatus1}
	 *         reports a checksum error
	 */
	public boolean calibrationError() {
		int sys0 = getSysStatus0();
		int sys1 = getSysStatus1();
		return (sys0 & 0x8000) != 0 || (sys1 & 0x8000) != 0;
	}

	/**
	 * Release the batched-measurement buffers and close the underlying SPI
	 * device. Idempotent, and safe to call from a thread other than the one
	 * using this instance.
	 */
	@Override
	public synchronized void close() {
		SpiDevice.Batch batch = measurementBatch;
		if ( batch != null ) {
			measurementBatch = null;
			batch.close();
		}
		spi.close();
	}

	/** @return the configured voltage gain calibration value */
	public int getVoltageGain() {
		return voltageGain;
	}

	/** @return the configured phase A current gain calibration value */
	public int getCurrentGainA() {
		return currentGainA;
	}

	/** @return the configured phase B current gain calibration value */
	public int getCurrentGainB() {
		return currentGainB;
	}

	/** @return the configured phase C current gain calibration value */
	public int getCurrentGainC() {
		return currentGainC;
	}

	/** @return the configured {@code MMode0} line-frequency value */
	public int getLineFreq() {
		return lineFreq;
	}

	/** @return the configured {@code MMode1} PGA gain value */
	public int getPgaGain() {
		return pgaGain;
	}

	// ========================================================================
	// timing helpers
	// ========================================================================

	private static void sleepMicros(long micros) {
		LockSupport.parkNanos(micros * 1000L);
	}

	private static void sleepMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

}

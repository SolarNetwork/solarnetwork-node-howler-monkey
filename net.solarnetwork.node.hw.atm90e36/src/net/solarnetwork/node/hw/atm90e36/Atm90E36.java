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

import static net.solarnetwork.util.ObjectUtils.requireNonNullArgument;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Phase;
import net.solarnetwork.node.hw.linux.spi.SpiDevice;

/**
 * Driver for the Atmel/Microchip ATM90E36 poly-phase energy metering IC,
 * communicating over SPI.
 *
 * <p>
 * Instances are not safe for concurrent use; drive one from a single thread.
 * {@link #close()} is the exception — it is safe to call from another thread,
 * such as a shutdown hook, and is idempotent, so an {@code Atm90E36} works as a
 * try-with-resources resource.
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
	 * {@code tCSH}); this matches the more conservative spacing the
	 * per-register Python path used.
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
	private static final int RPenergyT = 0x88;
	private static final int RPenergyA = 0x89;
	private static final int RPenergyB = 0x8A;
	private static final int RPenergyC = 0x8B;
	private static final int RNenergyT = 0x8C;
	private static final int RNenergyA = 0x8D;
	private static final int RNenergyB = 0x8E;
	private static final int RNenergyC = 0x8F;
	private static final int SAenergyT = 0x90;
	private static final int SenergyA = 0x91;
	private static final int SenergyB = 0x92;
	private static final int SenergyC = 0x93;
	private static final int SVenergyT = 0x94;

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
	// REGISTER BIT MASKS
	// ========================================================================

	/*-
	 * The power-on value of the {@code xxxStart} registers, meaning the
	 * associated register block has not been configured since the chip last
	 * reset.
	 */
	//private static final int Start_PowerOn = 0x6886;

	/**
	 * The {@code xxxStart} value that resets the associated block and starts
	 * metering without a checksum check.
	 */
	private static final int Start_Calibration = 0x5678;

	/**
	 * The {@code xxxStart} value that starts metering with checksum checking
	 * enabled.
	 */
	private static final int Start_Operation = 0x8765;

	/**
	 * {@code MMode0} bit 12 ({@code Freq60Hz}): the grid line frequency, clear
	 * for 50Hz and set for 60Hz.
	 */
	private static final int MMode0_Freq60Hz = 1 << 12;

	/**
	 * The {@code SysStatus0} checksum error bits: {@code CS0Err} (b14),
	 * {@code CS1Err} (b12), {@code CS2Err} (b10) and {@code CS3Err} (b8).
	 */
	private static final int SysStatus0_CsErrMask = (1 << 14) | (1 << 12) | (1 << 10) | (1 << 8);

	// ========================================================================

	private final SpiDevice spi;
	private final InstantSource clock;
	private int meteringMode;
	private int pgaGainMode;
	private int voltageGain;
	private int currentGainA;
	private int currentGainB;
	private int currentGainC;
	private long plConstant;
	private double energyResolution;

	private SpiDevice.@Nullable Batch measurementBatch;
	private SpiDevice.@Nullable Batch energyBatch;
	private Instant energyReadTime;

	/**
	 * Construct with a default {@link Atm90E36Config}.
	 *
	 * @param spi
	 *        the SPI device to communicate over
	 */
	public Atm90E36(SpiDevice spi) {
		this(spi, new Atm90E36Config());
	}

	/**
	 * Construct with an explicit configuration.
	 *
	 * <p>
	 * The configuration values are copied here, so later changes to
	 * {@code config} do not affect this instance.
	 * </p>
	 *
	 * @param spi
	 *        the SPI device to communicate over
	 * @param config
	 *        the configuration to apply in {@link #begin()}
	 */
	public Atm90E36(SpiDevice spi, Atm90E36Config config) {
		this(spi, Clock.systemUTC(), config);
	}

	/**
	 * Construct with an explicit configuration.
	 *
	 * <p>
	 * The configuration values are copied here, so later changes to
	 * {@code config} do not affect this instance.
	 * </p>
	 *
	 * @param spi
	 *        the SPI device to communicate over
	 * @param clock
	 *        the instant source to use
	 * @param config
	 *        the configuration to apply in {@link #begin()}
	 */
	public Atm90E36(SpiDevice spi, InstantSource clock, Atm90E36Config config) {
		super();
		this.spi = requireNonNullArgument(spi, "spi");
		this.clock = requireNonNullArgument(clock, "clock");
		adopt(requireNonNullArgument(config, "config"));
		this.energyReadTime = clock.instant();
	}

	/** Copy the values this instance works from out of {@code config}. */
	private void adopt(Atm90E36Config config) {
		this.meteringMode = config.meteringMode();
		this.pgaGainMode = config.pgaGainMode();
		this.voltageGain = config.getVoltageGain();
		this.currentGainA = config.getCurrentGainA();
		this.currentGainB = config.getCurrentGainB();
		this.currentGainC = config.getCurrentGainC();
		this.plConstant = config.plConstant();
		this.energyResolution = config.energyResolutionWattHours();
	}

	/**
	 * Open the SPI device, without touching the chip's configuration.
	 *
	 * <p>
	 * This is safe to call against a chip that is already configured and
	 * accumulating energy, so it is what a process that has just restarted
	 * should call. Use {@link #isConfigured()} to decide whether the chip also
	 * needs {@link #configure(Atm90E36Config)}.
	 * </p>
	 */
	public void open() {
		spi.open(3, 200000, 8);
	}

	/**
	 * Check whether the chip already holds a configuration.
	 *
	 * <p>
	 * The {@code ConfigStart} register reads back {@code 6886H} until it is
	 * written, so this distinguishes a chip that has been configured since it
	 * last powered up from one that has not. The chip keeps its configuration
	 * in volatile registers with no non-volatile backing, so a configuration
	 * survives exactly as long as the chip stays powered: it outlives a restart
	 * of this process, but not a power cycle of the chip.
	 * </p>
	 *
	 * @return {@code true} if {@code ConfigStart} holds a configured value
	 */
	public boolean isConfigured() {
		int start = readRegister(ConfigStart);
		return (start == Start_Calibration || start == Start_Operation);
	}

	/**
	 * Push the full configuration, calibration, harmonic and
	 * measurement-adjustment register sets to the chip, and adopt
	 * {@code config} for this instance.
	 *
	 * <p>
	 * <b>This discards any energy the chip has accumulated.</b> It issues a
	 * software reset, whose reset domain the datasheet describes as the same as
	 * the {@code RESET} pin, so the read-to-clear energy registers start again
	 * from zero and the {@link #readEnergy()} interval restarts here. Only call
	 * it when the chip actually needs configuring — see
	 * {@link #configureIfNeeded(Atm90E36Config)}.
	 * </p>
	 *
	 * @param config
	 *        the configuration to apply
	 */
	public void configure(Atm90E36Config config) {
		adopt(requireNonNullArgument(config, "config"));

		// Determine voltage sag threshold from the configured grid frequency
		// (MMode0 bit 12): 90V nominal for 60Hz mains, 190V for 50Hz.
		int sagV = ((meteringMode & MMode0_Freq60Hz) != 0 ? 90 : 190);

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
		checksum = writeAndGetChecksum(ConfigStart, Start_Calibration, checksum);
		checksum = writeAndGetChecksum(PLconstH, (int) ((plConstant >> 16) & 0xFFFF), checksum);
		checksum = writeAndGetChecksum(PLconstL, (int) (plConstant & 0xFFFF), checksum);
		checksum = writeAndGetChecksum(MMode0, meteringMode, checksum);
		checksum = writeAndGetChecksum(MMode1, pgaGainMode, checksum);
		checksum = writeAndGetChecksum(PStartTh, 0x1D4C, checksum);
		checksum = writeAndGetChecksum(QStartTh, 0x1D4C, checksum);
		checksum = writeAndGetChecksum(SStartTh, 0x1D4C, checksum);
		checksum = writeAndGetChecksum(PPhaseTh, 0x02EE, checksum);
		checksum = writeAndGetChecksum(QPhaseTh, 0x02EE, checksum);
		checksum = writeAndGetChecksum(SPhaseTh, 0x02EE, checksum);
		writeAndGetChecksum(CSZero, checksum, checksum);

		// CALIBRATION
		writeRegister(CalStart, Start_Calibration);
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
		checksum = writeAndGetChecksum(HarmStart, Start_Calibration, checksum);
		checksum = writeAndGetChecksum(POffsetAF, 0x0000, checksum);
		checksum = writeAndGetChecksum(POffsetBF, 0x0000, checksum);
		checksum = writeAndGetChecksum(POffsetCF, 0x0000, checksum);
		checksum = writeAndGetChecksum(PGainAF, 0x0000, checksum);
		checksum = writeAndGetChecksum(PGainBF, 0x0000, checksum);
		checksum = writeAndGetChecksum(PGainCF, 0x0000, checksum);
		writeAndGetChecksum(CSTwo, checksum, checksum);

		// ADJUST
		checksum = 0;
		checksum = writeAndGetChecksum(AdjStart, Start_Calibration, checksum);
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

		// the soft reset above cleared the energy registers, so the next
		// readEnergy() interval starts here
		energyReadTime = clock.instant();
	}

	/**
	 * Configure the chip unless it already holds this configuration.
	 *
	 * <p>
	 * This is the call a process that may be restarting against a running chip
	 * wants. It leaves the chip alone — preserving the energy it has
	 * accumulated — only when the chip both {@link #isConfigured()} and
	 * {@link #matchesConfiguration(Atm90E36Config)}. It therefore still
	 * reconfigures a chip that survived the restart but holds different
	 * settings, such as one an earlier version of the calling code set up.
	 * </p>
	 *
	 * @param config
	 *        the configuration to apply if needed
	 * @return {@code true} if the configuration was applied
	 */
	public boolean configureIfNeeded(Atm90E36Config config) {
		requireNonNullArgument(config, "config");
		if ( isConfigured() && matchesConfiguration(config) ) {
			return false;
		}
		configure(config);
		return true;
	}

	/**
	 * The registers compared by {@link #matchesConfiguration(Atm90E36Config)},
	 * in order: every register {@link #configure(Atm90E36Config)} derives from
	 * an {@link Atm90E36Config}.
	 */
	private static final int[] CONFIGURATION_REGISTERS = { MMode0, MMode1, PLconstH, PLconstL, UgainA,
			IgainA, UgainB, IgainB, UgainC, IgainC };

	/**
	 * Check whether the chip's registers already hold the values {@code config}
	 * would write.
	 *
	 * <p>
	 * This compares register values rather than {@link Atm90E36Config}
	 * instances, so it is exact: it is unaffected by the reserved
	 * {@code MMode0} bits a configuration object cannot represent. A chip
	 * configured by an earlier process with different settings therefore
	 * reports {@code false}, even though {@link #isConfigured()} reports
	 * {@code true}.
	 * </p>
	 *
	 * <p>
	 * Only the registers derived from an {@link Atm90E36Config} are compared:
	 * the fixed values {@link #configure(Atm90E36Config)} writes, such as the
	 * startup thresholds and the zeroed offset registers, are not.
	 * </p>
	 *
	 * @param config
	 *        the configuration to compare against
	 * @return {@code true} if every compared register matches
	 */
	public boolean matchesConfiguration(Atm90E36Config config) {
		requireNonNullArgument(config, "config");
		final int[] r = readRegisters(CONFIGURATION_REGISTERS);
		final long pl = config.plConstant();
		final int uGain = config.getVoltageGain() & 0xFFFF;
		return (r[0] == config.meteringMode() && r[1] == config.pgaGainMode()
				&& r[2] == (int) ((pl >> 16) & 0xFFFF) && r[3] == (int) (pl & 0xFFFF) && r[4] == uGain
				&& r[5] == (config.getCurrentGainA() & 0xFFFF) && r[6] == uGain
				&& r[7] == (config.getCurrentGainB() & 0xFFFF) && r[8] == uGain
				&& r[9] == (config.getCurrentGainC() & 0xFFFF));
	}

	/**
	 * Read the configuration back off the chip and adopt it for this instance.
	 *
	 * <p>
	 * Use this after attaching to a chip another process configured, so that
	 * the energy scaling this instance applies matches what the chip is
	 * actually metering with, rather than whatever configuration this instance
	 * was constructed with.
	 * </p>
	 *
	 * @return the configuration read from the chip
	 */
	public Atm90E36Config refreshConfig() {
		int[] r = readRegisters(CONFIGURATION_REGISTERS);
		Atm90E36Config config = new Atm90E36Config();
		config.setMeteringMode(r[0]);
		config.setPgaGainMode(r[1]);
		int mc = Atm90E36Config.meterConstantForPlConstant((((long) r[2]) << 16) | r[3]);
		if ( mc > 0 ) {
			config.setMeterConstant(mc);
		}
		config.setVoltageGain(r[4]);
		config.setCurrentGainA(r[5]);
		config.setCurrentGainB(r[7]);
		config.setCurrentGainC(r[9]);
		adopt(config);
		return config;
	}

	/**
	 * Read a 16-bit register value.
	 *
	 * <p>
	 * Byte-for-byte equivalent to a read through the Python
	 * {@code comm_energy_ic} method: the address (with the read flag set) and
	 * the result are both byte-swapped, and the same inter-transfer delays are
	 * applied.
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

	/**
	 * Read several registers in a single batched SPI operation, without
	 * retaining the batch.
	 *
	 * @param addresses
	 *        the register addresses to read, in order
	 * @return the register values, one per address
	 */
	private int[] readRegisters(int... addresses) {
		try (SpiDevice.Batch batch = spi.batch(readFrames(addresses), BATCH_SETTLE_MICROS)) {
			return decodeReadResponses(batch.transfer());
		}
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
	 * A snapshot of the measurements written to each CSV row: per-phase and
	 * total RMS voltage, RMS current, active power, plus total power factor and
	 * line frequency.
	 *
	 * @param timestamp
	 *        the timestamp
	 * @param voltageA
	 *        phase A voltage, V: to neutral when wired 3P4W, or the
	 *        line-to-line A to B voltage when wired 3P3W
	 * @param voltageB
	 *        phase B voltage, V; meaningless when wired 3P3W, where phase B is
	 *        the voltage reference
	 * @param voltageC
	 *        phase C voltage, V: to neutral when wired 3P4W, or the
	 *        line-to-line C to B voltage when wired 3P3W
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
	public record Measurements(Instant timestamp, double voltageA, double voltageB, double voltageC,
			double currentA, double currentB, double currentC, double powerA, double powerB,
			double powerC, double powerTotal, double powerFactorTotal, double frequency) {

		/**
		 * Get a phase current.
		 *
		 * @param phase
		 *        the desired phase
		 * @return the phase current value
		 */
		public double current(Phase phase) {
			return switch (phase) {
				case A -> currentA;
				case B -> currentB;
				case C -> currentC;
			};
		}

		/**
		 * Get a phase voltage.
		 *
		 * @param phase
		 *        the desired phase
		 * @return the phase voltage value
		 */
		public double voltage(Phase phase) {
			return switch (phase) {
				case A -> voltageA;
				case B -> voltageB;
				case C -> voltageC;
			};
		}

		/**
		 * Get a phase power.
		 *
		 * @param phase
		 *        the desired phase
		 * @return the phase power value
		 */
		public double power(Phase phase) {
			return switch (phase) {
				case A -> powerA;
				case B -> powerB;
				case C -> powerC;
			};
		}

	}

	/**
	 * The registers read for one {@link #readMeasurements()} call, in order.
	 */
	private static final int[] MEASUREMENT_REGISTERS = { UrmsA, UrmsB, UrmsC, IrmsA, IrmsB, IrmsC,
			PmeanA, PmeanALSB, PmeanB, PmeanBLSB, PmeanC, PmeanCLSB, PmeanT, PmeanTLSB, PFmeanT, Freq };

	/**
	 * Read every register needed for a CSV row in a single batched SPI
	 * operation.
	 *
	 * <p>
	 * This issues one {@code ioctl} covering all 16 register reads instead of
	 * one per register, which both cuts system-call overhead and removes the
	 * per-access thread-sleep jitter of the individual accessor methods. The
	 * batch is created on first use and its native buffers are reused on every
	 * subsequent call, so a caller polling at a short interval allocates
	 * nothing here beyond the returned {@link Measurements}; it is released by
	 * {@link #close()}.
	 * </p>
	 *
	 * @return the measurement snapshot
	 */
	public Measurements readMeasurements() {
		int[] r = decodeReadResponses(measurementBatch().transfer());
		return new Measurements(clock.instant(), r[0] / 100.0, r[1] / 100.0, r[2] / 100.0, r[3] / 1000.0,
				r[4] / 1000.0, r[5] / 1000.0, power(r[6], r[7]), power(r[8], r[9]), power(r[10], r[11]),
				power(r[12], r[13]), signed16(r[14]) / 1000.0, r[15] / 100.0);
	}

	private synchronized SpiDevice.Batch measurementBatch() {
		if ( measurementBatch == null ) {
			measurementBatch = spi.batch(readFrames(MEASUREMENT_REGISTERS), BATCH_SETTLE_MICROS);
		}
		return measurementBatch;
	}

	// VOLTAGE
	//
	// What the voltage channels measure depends on the configured wiring
	// (MMode0 bit 3P3W), so each accessor documents both cases:
	//
	//   3P4W: Ua, Ub and Uc, each measured against neutral.
	//   3P3W: phase B is the reference, so Ua is the line-to-line Uab and Uc
	//         is the line-to-line Ucb. There is no Ub -- the application note
	//         has the phase B voltage and current pins tied to ground.

	/**
	 * Get the phase A voltage.
	 *
	 * @return the phase A to neutral voltage when wired
	 *         {@link Atm90E36Config.Wiring#THREE_PHASE_FOUR_WIRE}, or the
	 *         line-to-line A to B voltage when wired
	 *         {@link Atm90E36Config.Wiring#THREE_PHASE_THREE_WIRE}, in volts
	 */
	public double getLineVoltageA() {
		return readRegister(UrmsA) / 100.0;
	}

	/**
	 * Get the phase B voltage.
	 *
	 * @return the phase B to neutral voltage when wired
	 *         {@link Atm90E36Config.Wiring#THREE_PHASE_FOUR_WIRE}, in volts;
	 *         meaningless when wired
	 *         {@link Atm90E36Config.Wiring#THREE_PHASE_THREE_WIRE}, where phase
	 *         B is the voltage reference and its input is grounded
	 */
	public double getLineVoltageB() {
		return readRegister(UrmsB) / 100.0;
	}

	/**
	 * Get the phase C voltage.
	 *
	 * @return the phase C to neutral voltage when wired
	 *         {@link Atm90E36Config.Wiring#THREE_PHASE_FOUR_WIRE}, or the
	 *         line-to-line C to B voltage when wired
	 *         {@link Atm90E36Config.Wiring#THREE_PHASE_THREE_WIRE}, in volts
	 */
	public double getLineVoltageC() {
		return readRegister(UrmsC) / 100.0;
	}

	// CURRENT

	/**
	 * Get the phase A line current.
	 *
	 * @return phase A line current, in amperes
	 */
	public double getLineCurrentA() {
		return readRegister(IrmsA) / 1000.0;
	}

	/**
	 * Get the phase B line current.
	 *
	 * @return the phase B line current, in amperes; meaningless when wired
	 *         {@link Atm90E36Config.Wiring#THREE_PHASE_THREE_WIRE}, which does
	 *         not sample phase B current
	 */
	public double getLineCurrentB() {
		return readRegister(IrmsB) / 1000.0;
	}

	/**
	 * Get the phase C line current.
	 *
	 * @return phase C line current, in amperes
	 */
	public double getLineCurrentC() {
		return readRegister(IrmsC) / 1000.0;
	}

	/**
	 * Get the neutral line current.
	 *
	 * @return the neutral line current, in amperes; meaningless when wired
	 *         {@link Atm90E36Config.Wiring#THREE_PHASE_THREE_WIRE}, which has
	 *         no neutral to sample
	 */
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

	/**
	 * Get the phase A active power.
	 *
	 * @return phase A active power, in watts
	 */
	public double getActivePowerA() {
		return activePower(PmeanA, PmeanALSB);
	}

	/**
	 * Get the phase B active power.
	 *
	 * @return phase B active power, in watts
	 */
	public double getActivePowerB() {
		return activePower(PmeanB, PmeanBLSB);
	}

	/**
	 * Get the phase C active power.
	 *
	 * @return phase C active power, in watts
	 */
	public double getActivePowerC() {
		return activePower(PmeanC, PmeanCLSB);
	}

	/**
	 * Get the total active power.
	 *
	 * @return total active power, in watts
	 */
	public double getTotalActivePower() {
		return activePower(PmeanT, PmeanTLSB);
	}

	// REACTIVE POWER

	private double reactivePower(int msbReg, int lsbReg) {
		return power(readRegister(msbReg), readRegister(lsbReg));
	}

	/**
	 * Get the phase A reactive power.
	 *
	 * @return phase A reactive power, in var
	 */
	public double getReactivePowerA() {
		return reactivePower(QmeanA, QmeanALSB);
	}

	/**
	 * Get the phase B reactive power.
	 *
	 * @return phase B reactive power, in var
	 */
	public double getReactivePowerB() {
		return reactivePower(QmeanB, QmeanBLSB);
	}

	/**
	 * Get the phase C reactive power.
	 *
	 * @return phase C reactive power, in var
	 */
	public double getReactivePowerC() {
		return reactivePower(QmeanC, QmeanCLSB);
	}

	/**
	 * Get the total reactive power.
	 *
	 * @return total reactive power, in var
	 */
	public double getTotalReactivePower() {
		return reactivePower(QmeanT, QmeanTLSB);
	}

	// APPARENT POWER

	private double apparentPower(int msbReg, int lsbReg) {
		int val = readRegister(msbReg);
		int valLsb = readRegister(lsbReg);
		return (val * 65536.0 + valLsb) * 0.00032;
	}

	/**
	 * Get the phase A apparent power.
	 *
	 * @return phase A apparent power, in VA
	 */
	public double getApparentPowerA() {
		return apparentPower(SmeanA, SmeanALSB);
	}

	/**
	 * Get the phase B apparent power.
	 *
	 * @return phase B apparent power, in VA
	 */
	public double getApparentPowerB() {
		return apparentPower(SmeanB, SmeanBLSB);
	}

	/**
	 * Get the phase C apparent power.
	 *
	 * @return phase C apparent power, in VA
	 */
	public double getApparentPowerC() {
		return apparentPower(SmeanC, SmeanCLSB);
	}

	/**
	 * Get the total apparent power.
	 *
	 * @return total apparent power, in VA
	 */
	public double getTotalApparentPower() {
		return apparentPower(SmeanT, SAmeanTLSB);
	}

	// FREQUENCY

	/**
	 * Get the line frequency.
	 *
	 * @return the line frequency, in Hz
	 */
	public double getFrequency() {
		return readRegister(Freq) / 100.0;
	}

	// POWER FACTOR

	private double powerFactor(int reg) {
		return signed16(readRegister(reg)) / 1000.0;
	}

	/**
	 * Get the phase A power factor.
	 *
	 * @return phase A power factor
	 */
	public double getPowerFactorA() {
		return powerFactor(PFmeanA);
	}

	/**
	 * Get the phase B power factor.
	 *
	 * @return phase B power factor
	 */
	public double getPowerFactorB() {
		return powerFactor(PFmeanB);
	}

	/**
	 * Get the phase C power factor.
	 *
	 * @return phase C power factor
	 */
	public double getPowerFactorC() {
		return powerFactor(PFmeanC);
	}

	/**
	 * Get the total power factor.
	 *
	 * @return total power factor
	 */
	public double getTotalPowerFactor() {
		return powerFactor(PFmeanT);
	}

	// PHASE ANGLE

	private double phaseAngle(int reg) {
		return signed16(readRegister(reg)) / 10.0;
	}

	/**
	 * Get the phase A voltage/current angle.
	 *
	 * @return phase A voltage/current angle, in degrees
	 */
	public double getPhaseA() {
		return phaseAngle(PAngleA);
	}

	/**
	 * Get the phase B voltage/current angle.
	 *
	 * @return phase B voltage/current angle, in degrees
	 */
	public double getPhaseB() {
		return phaseAngle(PAngleB);
	}

	/**
	 * Get the phase C voltage/current angle.
	 *
	 * @return phase C voltage/current angle, in degrees
	 */
	public double getPhaseC() {
		return phaseAngle(PAngleC);
	}

	// TEMPERATURE

	/**
	 * Get the raw on-chip temperature register value.
	 *
	 * @return the raw on-chip temperature register value
	 */
	public int getTemperature() {
		return readRegister(Temp);
	}

	// ========================================================================
	// ENERGY
	// ========================================================================

	/**
	 * An energy quantity, as the chip's all-phase total and each individual
	 * phase.
	 *
	 * <p>
	 * {@code total} is the chip's own total register, not the sum of the three
	 * phases: which phases it counts is set by
	 * {@link Atm90E36Config#setSummedPhases(java.util.Set)}.
	 * </p>
	 *
	 * @param total
	 *        the all-phase total
	 * @param phaseA
	 *        the phase A value
	 * @param phaseB
	 *        the phase B value
	 * @param phaseC
	 *        the phase C value
	 */
	public record PhaseEnergy(double total, double phaseA, double phaseB, double phaseC) {

		/**
		 * Get a phase value.
		 *
		 * @param phase
		 *        the desired phase
		 * @return the phase value
		 */
		public double value(Phase phase) {
			return switch (phase) {
				case A -> phaseA;
				case B -> phaseB;
				case C -> phaseC;
			};
		}
	}

	/**
	 * The energy accumulated over one read interval.
	 *
	 * <p>
	 * The energy registers are read-to-clear, so these are the amounts
	 * accumulated between {@code start} and {@code end}, not running totals:
	 * the caller must accumulate them itself to maintain an odometer.
	 * </p>
	 *
	 * @param start
	 *        when the registers were last cleared, by the previous read or by
	 *        {@link Atm90E36#begin()}
	 * @param end
	 *        when this read cleared them again
	 * @param activeImport
	 *        forward active energy, in Wh
	 * @param activeExport
	 *        reverse active energy, in Wh
	 * @param reactiveImport
	 *        forward reactive energy, in varh
	 * @param reactiveExport
	 *        reverse reactive energy, in varh
	 * @param apparent
	 *        arithmetic-sum apparent energy, in VAh; the chip accumulates
	 *        apparent energy without a direction split
	 * @param apparentVectorTotal
	 *        the vector-sum apparent energy total, in VAh
	 */
	public record EnergyReading(Instant start, Instant end, PhaseEnergy activeImport,
			PhaseEnergy activeExport, PhaseEnergy reactiveImport, PhaseEnergy reactiveExport,
			PhaseEnergy apparent, double apparentVectorTotal) {

		/**
		 * Get the interval this reading covers.
		 *
		 * @return the interval this reading covers
		 */
		public Duration duration() {
			return Duration.between(start, end);
		}
	}

	/**
	 * The registers read for one {@link #readEnergy()} call, in order.
	 */
	private static final int[] ENERGY_REGISTERS = { APenergyT, APenergyA, APenergyB, APenergyC,
			ANenergyT, ANenergyA, ANenergyB, ANenergyC, RPenergyT, RPenergyA, RPenergyB, RPenergyC,
			RNenergyT, RNenergyA, RNenergyB, RNenergyC, SAenergyT, SenergyA, SenergyB, SenergyC,
			SVenergyT };

	/**
	 * Read every energy register in a single batched SPI operation.
	 *
	 * <p>
	 * The energy registers are <em>read-to-clear</em>: the chip resets each one
	 * to zero as it is read, so every call returns only what accumulated since
	 * the previous call, over the interval the returned
	 * {@link EnergyReading#start()} and {@link EnergyReading#end()} describe.
	 * That is why all 21 registers are read together here rather than exposed
	 * as individual accessors — reading one would clear it, leaving the rest
	 * covering a different interval.
	 * </p>
	 *
	 * <p>
	 * Each register is 16 bits, so an interval must be short enough that no
	 * value exceeds 65535 LSB. At the default meter constant and 0.1CF
	 * resolution that is about 2kWh per phase per read; selecting 0.01CF
	 * resolution with {@link Atm90E36Config#setHighResolutionEnergy(boolean)}
	 * divides that headroom by ten.
	 * </p>
	 *
	 * @return the energy accumulated since the previous call
	 */
	public EnergyReading readEnergy() {
		Instant start = energyReadTime;
		int[] r = decodeReadResponses(energyBatch().transfer());
		Instant end = clock.instant();
		energyReadTime = end;
		return new EnergyReading(start, end, phaseEnergy(r, 0), phaseEnergy(r, 4), phaseEnergy(r, 8),
				phaseEnergy(r, 12), phaseEnergy(r, 16), r[20] * energyResolution);
	}

	/**
	 * Scale four consecutive total/A/B/C registers into a {@link PhaseEnergy}.
	 */
	private PhaseEnergy phaseEnergy(int[] values, int offset) {
		return new PhaseEnergy(values[offset] * energyResolution, values[offset + 1] * energyResolution,
				values[offset + 2] * energyResolution, values[offset + 3] * energyResolution);
	}

	private synchronized SpiDevice.Batch energyBatch() {
		if ( energyBatch == null ) {
			energyBatch = spi.batch(readFrames(ENERGY_REGISTERS), BATCH_SETTLE_MICROS);
		}
		return energyBatch;
	}

	/**
	 * Get the instant the energy registers were last cleared, which the next
	 * {@link #readEnergy()} reports as its interval start.
	 *
	 * @return the interval start
	 */
	public Instant getEnergyIntervalStart() {
		return energyReadTime;
	}

	/**
	 * Set the instant the energy registers were last cleared.
	 *
	 * <p>
	 * A new instance assumes the registers were cleared when it was
	 * constructed, which is wrong when attaching to a chip that has been
	 * accumulating since before this process started. A caller that knows when
	 * it last read the chip — because it persisted that across its own restart
	 * — should set it here, so the first {@link #readEnergy()} reports the
	 * interval its value actually covers.
	 * </p>
	 *
	 * @param start
	 *        the instant the registers were last cleared
	 */
	public void setEnergyIntervalStart(Instant start) {
		this.energyReadTime = requireNonNullArgument(start, "start");
	}

	/**
	 * Get the energy one energy register LSB represents.
	 *
	 * @return the energy one energy register LSB represents, in Wh
	 */
	public double getEnergyResolution() {
		return energyResolution;
	}

	/**
	 * Measurement and energy data combined.
	 *
	 * @param measurements
	 *        the measurements
	 * @param energy
	 *        the energy reading
	 */
	public record MeasurementsAndEnergy(Measurements measurements, EnergyReading energy) {

		/**
		 * Get the overall timestamp for the data set.
		 *
		 * <p>
		 * This returns {@code energy.end()} to represent the overall timestamp
		 * for the data.
		 * </p>
		 *
		 * @return the timestamp
		 */
		public Instant timestamp() {
			// return the energy end as the overall timestamp
			return energy.end();
		}

	}

	/**
	 * Read both measurements and energy data.
	 *
	 * @return the measurement and energy data
	 * @see #readMeasurements()
	 * @see #readEnergy()
	 */
	public MeasurementsAndEnergy readMeasurementsAndEnergy() {
		return new MeasurementsAndEnergy(readMeasurements(), readEnergy());
	}

	// SYSTEM STATUS

	/**
	 * Get the {@code SysStatus0} register.
	 *
	 * @return the {@code SysStatus0} register
	 */
	public int getSysStatus0() {
		return readRegister(SysStatus0);
	}

	/**
	 * Get the {@code SysStatus1} register.
	 *
	 * @return the {@code SysStatus1} register
	 */
	public int getSysStatus1() {
		return readRegister(SysStatus1);
	}

	/**
	 * Get the {@code EnStatus0} metering status register.
	 *
	 * @return the {@code EnStatus0} metering status register
	 */
	public int getMeterStatus0() {
		return readRegister(EnStatus0);
	}

	/**
	 * Get the {@code EnStatus1} metering status register.
	 *
	 * @return the {@code EnStatus1} metering status register
	 */
	public int getMeterStatus1() {
		return readRegister(EnStatus1);
	}

	/**
	 * Check the system status register for a configuration or calibration
	 * checksum error.
	 *
	 * <p>
	 * The chip continuously recomputes the {@code CS0}-{@code CS3} checksums
	 * over the configuration, calibration, harmonic and measurement-adjustment
	 * register blocks, and reports a mismatch against the written values in
	 * {@code SysStatus0}. Note that it only compares them once the associated
	 * start register holds {@code 8765H}: {@link #begin()} writes
	 * {@code 5678H}, which starts the chip without a checksum check, so this
	 * always reports no error after a {@code begin()}.
	 * </p>
	 *
	 * @return {@code true} if {@code SysStatus0} reports any of the
	 *         {@code CS0Err}, {@code CS1Err}, {@code CS2Err} or {@code CS3Err}
	 *         checksum errors
	 */
	public boolean calibrationError() {
		return (getSysStatus0() & SysStatus0_CsErrMask) != 0;
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
		batch = energyBatch;
		if ( batch != null ) {
			energyBatch = null;
			batch.close();
		}
		spi.close();
	}

	/**
	 * Get the configured voltage gain calibration value.
	 *
	 * @return the configured voltage gain calibration value
	 */
	public int getVoltageGain() {
		return voltageGain;
	}

	/**
	 * Get the configured phase A current gain calibration value.
	 *
	 * @return the configured phase A current gain calibration value
	 */
	public int getCurrentGainA() {
		return currentGainA;
	}

	/**
	 * Get the configured phase B current gain calibration value.
	 *
	 * @return the configured phase B current gain calibration value
	 */
	public int getCurrentGainB() {
		return currentGainB;
	}

	/**
	 * Get the configured phase C current gain calibration value.
	 *
	 * @return the configured phase C current gain calibration value
	 */
	public int getCurrentGainC() {
		return currentGainC;
	}

	/**
	 * Get the configuration this instance was constructed with.
	 *
	 * <p>
	 * The result is decoded from the values copied at construction, so it is a
	 * new and independent object on every call: mutating it does not affect
	 * this instance. Note that {@link Atm90E36Config#setMeteringMode(int)}
	 * cannot represent the reserved {@code MMode0} bits, so a configuration
	 * built from a register value that set one of those does not come back
	 * identical.
	 * </p>
	 *
	 * @return a new configuration holding this instance's settings
	 */
	public Atm90E36Config config() {
		Atm90E36Config config = new Atm90E36Config();
		config.setMeteringMode(meteringMode);
		config.setPgaGainMode(pgaGainMode);
		config.setVoltageGain(voltageGain);
		config.setCurrentGainA(currentGainA);
		config.setCurrentGainB(currentGainB);
		config.setCurrentGainC(currentGainC);
		return config;
	}

	/**
	 * Get the configured {@code MMode0} metering method register value.
	 *
	 * @return the configured {@code MMode0} metering method register value
	 */
	public int getMeteringMode() {
		return meteringMode;
	}

	/**
	 * Get the configured {@code MMode1} PGA gain register value.
	 *
	 * @return the configured {@code MMode1} PGA gain register value
	 */
	public int getPgaGainMode() {
		return pgaGainMode;
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

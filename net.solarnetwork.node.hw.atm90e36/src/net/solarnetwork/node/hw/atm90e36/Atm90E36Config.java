/* ==================================================================
 * Atm90E36Config.java - 11/09/2026
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
import java.util.EnumSet;
import java.util.Set;
import net.solarnetwork.domain.AcPhase;

/**
 * Mutable configuration for an {@link Atm90E36}.
 *
 * <p>
 * The ATM90E36 is configured through two packed registers, {@code MMode0}
 * (metering method) and {@code MMode1} (PGA gain), each of which holds several
 * independent fields. This class models those fields individually and composes
 * them back into the register words with {@link #meteringMode()} and
 * {@link #pgaGainMode()}, so callers never have to assemble bit patterns by
 * hand. It also carries the voltage and current gain calibration values.
 * </p>
 *
 * <p>
 * The defaults are the chip's own power-on values: {@code MMode0} =
 * {@code 0087H} (3P4W, 50Hz, all three phases counted into the all-phase
 * totals) and {@code MMode1} = {@code 0000H} (1X gain on every channel), with
 * the voltage and current gains carried over from {@code meter-tool-2.py}.
 * </p>
 *
 * <p>
 * Instances are not thread-safe. Configure one, hand it to
 * {@link Atm90E36#Atm90E36(net.solarnetwork.node.hw.linux.spi.SpiDevice, Atm90E36Config)},
 * and note that the driver copies the values it needs at construction: mutating
 * the configuration afterwards has no effect on that driver.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class Atm90E36Config {

	/** The default meter constant, in imp/kWh. */
	public static final int DEFAULT_METER_CONSTANT = 3200;

	/**
	 * The numerator of the {@code PL_Constant} formula, from the application
	 * note: {@code PL_Constant = 450,000,000,000 / MC}.
	 */
	private static final long PL_CONSTANT_NUMERATOR = 450_000_000_000L;

	/** The grid line frequency, {@code MMode0} bit 12 ({@code Freq60Hz}). */
	public enum LineFrequency {

		/** 50Hz mains. */
		HZ_50(0),

		/** 60Hz mains. */
		HZ_60(1);

		private final int bit;

		private LineFrequency(int bit) {
			this.bit = bit;
		}

		/**
		 * Get the {@code Freq60Hz} bit value.
		 *
		 * @return the {@code Freq60Hz} bit value
		 */
		public int bitValue() {
			return bit;
		}

		/**
		 * Get an enum instance for a {@code Freq60Hz} field value.
		 *
		 * @param value
		 *        the field value
		 * @return the enum instance
		 * @throws IllegalArgumentException
		 *         if {@code value} is not a supported field value
		 */
		public static LineFrequency forBitValue(int value) {
			for ( LineFrequency e : values() ) {
				if ( e.bit == value ) {
					return e;
				}
			}
			throw new IllegalArgumentException("Unsupported Freq60Hz field value: " + value);
		}
	}

	/** The meter connection type, {@code MMode0} bit 8 ({@code 3P3W}). */
	public enum Wiring {

		/** Three phase, four wire. */
		THREE_PHASE_FOUR_WIRE(0),

		/** Three phase, three wire: Ua is Uab, Uc is Ucb, Ub is unused. */
		THREE_PHASE_THREE_WIRE(1);

		private final int bit;

		private Wiring(int bit) {
			this.bit = bit;
		}

		/**
		 * Get the {@code 3P3W} bit value.
		 *
		 * @return the {@code 3P3W} bit value
		 */
		public int bitValue() {
			return bit;
		}

		/**
		 * Get an enum instance for a {@code 3P3W} field value.
		 *
		 * @param value
		 *        the field value
		 * @return the enum instance
		 * @throws IllegalArgumentException
		 *         if {@code value} is not a supported field value
		 */
		public static Wiring forBitValue(int value) {
			for ( Wiring e : values() ) {
				if ( e.bit == value ) {
					return e;
				}
			}
			throw new IllegalArgumentException("Unsupported 3P3W field value: " + value);
		}
	}

	/**
	 * The current sampling method, {@code MMode0} bit 10 ({@code didtEn}).
	 */
	public enum CurrentSensor {

		/** Current transformer sampling. */
		CURRENT_TRANSFORMER(0),

		/** Rogowski coil sampling, enabling the di/dt integrator. */
		ROGOWSKI_COIL(1);

		private final int bit;

		private CurrentSensor(int bit) {
			this.bit = bit;
		}

		/**
		 * Get the {@code didtEn} bit value.
		 *
		 * @return the {@code didtEn} bit value
		 */
		public int bitValue() {
			return bit;
		}

		/**
		 * Get an enum instance for a {@code didtEn} field value.
		 *
		 * @param value
		 *        the field value
		 * @return the enum instance
		 * @throws IllegalArgumentException
		 *         if {@code value} is not a supported field value
		 */
		public static CurrentSensor forBitValue(int value) {
			for ( CurrentSensor e : values() ) {
				if ( e.bit == value ) {
					return e;
				}
			}
			throw new IllegalArgumentException("Unsupported didtEn field value: " + value);
		}
	}

	/**
	 * A metered phase, for the {@code MMode0} {@code EnPA}/{@code EnPB}/
	 * {@code EnPC} bits.
	 */
	public enum Phase {

		/** Phase A, {@code EnPA} (bit 2). */
		A(2),

		/** Phase B, {@code EnPB} (bit 1). */
		B(1),

		/** Phase C, {@code EnPC} (bit 0). */
		C(0);

		private final int bit;

		private Phase(int bit) {
			this.bit = bit;
		}

		/**
		 * Get the {@code MMode0} bit number for this phase.
		 *
		 * @return the {@code MMode0} bit number for this phase
		 */
		public int bitNumber() {
			return bit;
		}

		/**
		 * Get the {@code AcPhase} equivalent of this phase.
		 *
		 * @return the {@code AcPhase} equivalent of this phase
		 */
		public AcPhase toAcPhase() {
			return switch (this) {
				case A -> AcPhase.PhaseA;
				case B -> AcPhase.PhaseB;
				case C -> AcPhase.PhaseC;
			};
		}
	}

	/**
	 * The all-phase sum method for total active or reactive energy and power,
	 * {@code MMode0} bits 3 ({@code ABSEnP}) and 4 ({@code ABSEnQ}).
	 */
	public enum SumMethod {

		/** Signed sum, so reverse energy subtracts from the total. */
		ARITHMETIC(0),

		/** Sum of absolute values, so reverse energy adds to the total. */
		ABSOLUTE(1);

		private final int bit;

		private SumMethod(int bit) {
			this.bit = bit;
		}

		/**
		 * Get the {@code ABSEnP}/{@code ABSEnQ} bit value.
		 *
		 * @return the {@code ABSEnP}/{@code ABSEnQ} bit value
		 */
		public int bitValue() {
			return bit;
		}

		/**
		 * Get an enum instance for a {@code ABSEnP/ABSEnQ} field value.
		 *
		 * @param value
		 *        the field value
		 * @return the enum instance
		 * @throws IllegalArgumentException
		 *         if {@code value} is not a supported field value
		 */
		public static SumMethod forBitValue(int value) {
			for ( SumMethod e : values() ) {
				if ( e.bit == value ) {
					return e;
				}
			}
			throw new IllegalArgumentException("Unsupported ABSEnP/ABSEnQ field value: " + value);
		}
	}

	/**
	 * The analog PGA (programmable gain amplifier) gain for one ADC channel, a
	 * 2-bit {@code MMode1} field.
	 *
	 * <p>
	 * Size this so the channel's analog input stays within the 0-720mVrms
	 * dynamic range, choosing the largest gain that still fits. The fourth
	 * encoding, {@code 11}, is documented as N/A and so has no constant here.
	 * </p>
	 */
	public enum PgaGain {

		/** 1X gain. */
		X1(0),

		/** 2X gain. */
		X2(1),

		/** 4X gain. */
		X4(2);

		private final int bits;

		private PgaGain(int bits) {
			this.bits = bits;
		}

		/**
		 * Get the 2-bit field value.
		 *
		 * @return the 2-bit field value
		 */
		public int bitValue() {
			return bits;
		}

		/**
		 * Get an enum instance for a {@code PGA_GAIN} field value.
		 *
		 * @param value
		 *        the field value
		 * @return the enum instance
		 * @throws IllegalArgumentException
		 *         if {@code value} is not a supported field value
		 */
		public static PgaGain forBitValue(int value) {
			for ( PgaGain e : values() ) {
				if ( e.bits == value ) {
					return e;
				}
			}
			throw new IllegalArgumentException("Unsupported PGA_GAIN field value: " + value);
		}
	}

	/**
	 * The digital PGA gain applied to all four current channels at the end of
	 * the decimation filter, {@code MMode1} bits 15-14 ({@code DPGA_GAIN}).
	 */
	public enum DigitalPgaGain {

		/** 1X gain. */
		X1(0),

		/** 2X gain. */
		X2(1),

		/** 4X gain. */
		X4(2),

		/** 8X gain. */
		X8(3);

		private final int bits;

		private DigitalPgaGain(int bits) {
			this.bits = bits;
		}

		/**
		 * Get the 2-bit field value.
		 *
		 * @return the 2-bit field value
		 */
		public int bitValue() {
			return bits;
		}

		/**
		 * Get an enum instance for a {@code DPGA_GAIN} field value.
		 *
		 * @param value
		 *        the field value
		 * @return the enum instance
		 * @throws IllegalArgumentException
		 *         if {@code value} is not a supported field value
		 */
		public static DigitalPgaGain forBitValue(int value) {
			for ( DigitalPgaGain e : values() ) {
				if ( e.bits == value ) {
					return e;
				}
			}
			throw new IllegalArgumentException("Unsupported DPGA_GAIN field value: " + value);
		}
	}

	// MMode0 fields
	private LineFrequency lineFrequency = LineFrequency.HZ_50;
	private Wiring wiring = Wiring.THREE_PHASE_FOUR_WIRE;
	private CurrentSensor currentSensor = CurrentSensor.CURRENT_TRANSFORMER;
	private SumMethod activePowerSum = SumMethod.ARITHMETIC;
	private SumMethod reactivePowerSum = SumMethod.ARITHMETIC;
	private final EnumSet<Phase> summedPhases = EnumSet.allOf(Phase.class);
	private boolean currentChannelsSwapped = false;
	private boolean highPassFilterEnabled = true;
	private boolean highResolutionEnergy = false;
	private boolean cf2ReactiveEnergy = true;
	private boolean apparentEnergyVectorSum = false;

	// MMode1 fields
	private DigitalPgaGain digitalPgaGain = DigitalPgaGain.X1;
	private PgaGain currentGainI1 = PgaGain.X1;
	private PgaGain currentGainI2 = PgaGain.X1;
	private PgaGain currentGainI3 = PgaGain.X1;
	private PgaGain currentGainI4 = PgaGain.X1;
	private PgaGain voltageGainV1 = PgaGain.X1;
	private PgaGain voltageGainV2 = PgaGain.X1;
	private PgaGain voltageGainV3 = PgaGain.X1;

	// calibration values
	private int meterConstant = DEFAULT_METER_CONSTANT;
	private int voltageGain = 50000;
	private int currentGainA = 32498;
	private int currentGainB = 32498;
	private int currentGainC = 32498;

	/**
	 * Constructor, with every value set to the chip's power-on default.
	 */
	public Atm90E36Config() {
		super();
	}

	/**
	 * Compose the {@code MMode0} (metering method) register value.
	 *
	 * @return the register value, {@code 0}-{@code 65535}
	 */
	public int meteringMode() {
		int v = 0;
		v |= (currentChannelsSwapped ? 1 : 0) << 13;
		v |= lineFrequency.bitValue() << 12;
		v |= (highPassFilterEnabled ? 0 : 1) << 11;
		v |= currentSensor.bitValue() << 10;
		v |= (highResolutionEnergy ? 1 : 0) << 9;
		v |= wiring.bitValue() << 8;
		v |= (cf2ReactiveEnergy ? 1 : 0) << 7;
		v |= (apparentEnergyVectorSum ? 1 : 0) << 6;
		v |= reactivePowerSum.bitValue() << 4;
		v |= activePowerSum.bitValue() << 3;
		for ( Phase p : summedPhases ) {
			v |= 1 << p.bitNumber();
		}
		return v;
	}

	/**
	 * Compose the {@code MMode1} (PGA gain) register value.
	 *
	 * @return the register value, {@code 0}-{@code 65535}
	 */
	public int pgaGainMode() {
		int v = 0;
		v |= digitalPgaGain.bitValue() << 14;
		v |= voltageGainV3.bitValue() << 12;
		v |= voltageGainV2.bitValue() << 10;
		v |= voltageGainV1.bitValue() << 8;
		v |= currentGainI4.bitValue() << 6;
		v |= currentGainI3.bitValue() << 4;
		v |= currentGainI2.bitValue() << 2;
		v |= currentGainI1.bitValue();
		return v;
	}

	/**
	 * Decode a {@code MMode0} (metering method) register value into the
	 * individual properties.
	 *
	 * <p>
	 * This is the inverse of {@link #meteringMode()}, for turning a register
	 * value read back from the chip into named settings. Note that the bits the
	 * datasheet reserves (15, 14 and 5) have no property to hold them, so they
	 * are dropped: a value with any of them set does not survive a round trip.
	 * </p>
	 *
	 * @param value
	 *        the register value to decode
	 * @throws IllegalArgumentException
	 *         if any field holds an unsupported value
	 */
	public void setMeteringMode(int value) {
		this.currentChannelsSwapped = ((value >> 13) & 0x01) != 0;
		this.lineFrequency = LineFrequency.forBitValue((value >> 12) & 0x01);
		this.highPassFilterEnabled = ((value >> 11) & 0x01) == 0;
		this.currentSensor = CurrentSensor.forBitValue((value >> 10) & 0x01);
		this.highResolutionEnergy = ((value >> 9) & 0x01) != 0;
		this.wiring = Wiring.forBitValue((value >> 8) & 0x01);
		this.cf2ReactiveEnergy = ((value >> 7) & 0x01) != 0;
		this.apparentEnergyVectorSum = ((value >> 6) & 0x01) != 0;
		this.reactivePowerSum = SumMethod.forBitValue((value >> 4) & 0x01);
		this.activePowerSum = SumMethod.forBitValue((value >> 3) & 0x01);
		this.summedPhases.clear();
		for ( Phase p : Phase.values() ) {
			if ( ((value >> p.bitNumber()) & 0x01) != 0 ) {
				this.summedPhases.add(p);
			}
		}
	}

	/**
	 * Decode a {@code MMode1} (PGA gain) register value into the individual
	 * properties.
	 *
	 * <p>
	 * This is the inverse of {@link #pgaGainMode()}, for turning a register
	 * value read back from the chip into named settings.
	 * </p>
	 *
	 * @param value
	 *        the register value to decode
	 * @throws IllegalArgumentException
	 *         if any channel holds the {@code 11} encoding, which the datasheet
	 *         documents as N/A
	 */
	public void setPgaGainMode(int value) {
		this.digitalPgaGain = DigitalPgaGain.forBitValue((value >> 14) & 0x03);
		this.voltageGainV3 = PgaGain.forBitValue((value >> 12) & 0x03);
		this.voltageGainV2 = PgaGain.forBitValue((value >> 10) & 0x03);
		this.voltageGainV1 = PgaGain.forBitValue((value >> 8) & 0x03);
		this.currentGainI4 = PgaGain.forBitValue((value >> 6) & 0x03);
		this.currentGainI3 = PgaGain.forBitValue((value >> 4) & 0x03);
		this.currentGainI2 = PgaGain.forBitValue((value >> 2) & 0x03);
		this.currentGainI1 = PgaGain.forBitValue(value & 0x03);
	}

	/**
	 * Set the same analog PGA gain on the three phase current channels
	 * {@code I1}, {@code I2} and {@code I3}, leaving the neutral channel
	 * {@code I4} alone.
	 *
	 * @param gain
	 *        the gain to set
	 */
	public void setPhaseCurrentPgaGain(PgaGain gain) {
		requireNonNullArgument(gain, "gain");
		this.currentGainI1 = gain;
		this.currentGainI2 = gain;
		this.currentGainI3 = gain;
	}

	/**
	 * Set the same current gain calibration value on all three phases.
	 *
	 * @param gain
	 *        the calibration value to set
	 */
	public void setCurrentGain(int gain) {
		this.currentGainA = gain;
		this.currentGainB = gain;
		this.currentGainC = gain;
	}

	@Override
	public String toString() {
		return String.format(
				"Atm90E36Config{MMode0=0x%04X, MMode1=0x%04X, %s, %s, %s, summedPhases=%s, "
						+ "voltageGain=%d, currentGain=[%d, %d, %d]}",
				meteringMode(), pgaGainMode(), lineFrequency, wiring, currentSensor, summedPhases,
				voltageGain, currentGainA, currentGainB, currentGainC);
	}

	/**
	 * Get the grid line frequency.
	 *
	 * @return the grid line frequency; defaults to {@code HZ_50}
	 */
	public LineFrequency getLineFrequency() {
		return lineFrequency;
	}

	/**
	 * Set the grid line frequency.
	 *
	 * @param lineFrequency
	 *        the grid line frequency to set
	 */
	public void setLineFrequency(LineFrequency lineFrequency) {
		this.lineFrequency = requireNonNullArgument(lineFrequency, "lineFrequency");
	}

	/**
	 * Get the connection type.
	 *
	 * @return the connection type; defaults to {@code THREE_PHASE_FOUR_WIRE}
	 */
	public Wiring getWiring() {
		return wiring;
	}

	/**
	 * Set the connection type.
	 *
	 * @param wiring
	 *        the connection type to set
	 */
	public void setWiring(Wiring wiring) {
		this.wiring = requireNonNullArgument(wiring, "wiring");
	}

	/**
	 * Get the current sampling method.
	 *
	 * @return the current sampling method; defaults to
	 *         {@code CURRENT_TRANSFORMER}
	 */
	public CurrentSensor getCurrentSensor() {
		return currentSensor;
	}

	/**
	 * Set the current sampling method.
	 *
	 * @param currentSensor
	 *        the current sampling method to set
	 */
	public void setCurrentSensor(CurrentSensor currentSensor) {
		this.currentSensor = requireNonNullArgument(currentSensor, "currentSensor");
	}

	/**
	 * Get the total active power sum method.
	 *
	 * @return the total active power sum method; defaults to {@code ARITHMETIC}
	 */
	public SumMethod getActivePowerSum() {
		return activePowerSum;
	}

	/**
	 * Set the total active power sum method.
	 *
	 * @param activePowerSum
	 *        the total active power sum method to set
	 */
	public void setActivePowerSum(SumMethod activePowerSum) {
		this.activePowerSum = requireNonNullArgument(activePowerSum, "activePowerSum");
	}

	/**
	 * Get the total reactive power sum method.
	 *
	 * @return the total reactive power sum method; defaults to
	 *         {@code ARITHMETIC}
	 */
	public SumMethod getReactivePowerSum() {
		return reactivePowerSum;
	}

	/**
	 * Set the total reactive power sum method.
	 *
	 * @param reactivePowerSum
	 *        the total reactive power sum method to set
	 */
	public void setReactivePowerSum(SumMethod reactivePowerSum) {
		this.reactivePowerSum = requireNonNullArgument(reactivePowerSum, "reactivePowerSum");
	}

	/**
	 * Get the phases counted into the all-phase sum energy and power values,
	 * which by the contract of {@link #setSummedPhases(Set)} are also the
	 * phases the chip is connected to measure.
	 *
	 * @return the live set of counted phases; defaults to all three phases
	 */
	public Set<Phase> getSummedPhases() {
		return summedPhases;
	}

	/**
	 * Set the phases counted into the all-phase sum energy and power values.
	 *
	 * <p>
	 * <b>Set this to the phases that are actually connected.</b> The chip only
	 * defines this as a totalling control — it computes its totals as
	 * {@code PT = PA*EnPA + PB*EnPB + PC*EnPC}, so a phase left out here is
	 * simply missing from every total register — but this driver and its
	 * callers additionally treat it as the authoritative statement of which
	 * phases the chip is wired to measure. The per-phase measurement and energy
	 * registers are populated whatever this is set to, so nothing else
	 * distinguishes a real reading from whatever an unconnected input picks up.
	 * </p>
	 *
	 * <p>
	 * {@link #setWiring(Wiring)} cannot answer that question: it names the
	 * topology, not which phases are populated, so a
	 * {@link Wiring#THREE_PHASE_FOUR_WIRE} meter with only one or two phases
	 * connected — an ordinary single-phase or split-phase service on a
	 * three-phase-capable chip — is expressible only here.
	 * </p>
	 *
	 * <p>
	 * A {@link Wiring#THREE_PHASE_THREE_WIRE} meter wants {@code A} and
	 * {@code C} — the two elements of the two-wattmeter method, which together
	 * measure the whole three-wire system — and not {@code B}, which such a
	 * meter does not sample. That is the combination the application note's
	 * recommended 3P3W value, {@code 0185H}, selects, and it clears
	 * {@code EnPB} for exactly this reason: phase B voltage and current are not
	 * connected in that wiring.
	 * </p>
	 *
	 * @param phases
	 *        the phases that are connected, and so are counted into the
	 *        all-phase sum energy and power values
	 */
	public void setSummedPhases(Set<Phase> phases) {
		requireNonNullArgument(phases, "phases");
		this.summedPhases.clear();
		this.summedPhases.addAll(phases);
	}

	/**
	 * Get whether the {@code I1} and {@code I3} current channels are swapped.
	 *
	 * @return {@code true} if {@code I1} maps to phase C and {@code I3} to
	 *         phase A; defaults to {@code false}
	 */
	public boolean isCurrentChannelsSwapped() {
		return currentChannelsSwapped;
	}

	/**
	 * Swap the phase mapping of the {@code I1} and {@code I3} current channels,
	 * to compensate for the chip being mounted on the opposite PCB layer.
	 *
	 * @param currentChannelsSwapped
	 *        {@code true} to map {@code I1} to phase C and {@code I3} to phase
	 *        A
	 */
	public void setCurrentChannelsSwapped(boolean currentChannelsSwapped) {
		this.currentChannelsSwapped = currentChannelsSwapped;
	}

	/**
	 * Get whether the high pass filter is enabled.
	 *
	 * @return {@code true} if the high pass filter is enabled; defaults to
	 *         {@code true}
	 */
	public boolean isHighPassFilterEnabled() {
		return highPassFilterEnabled;
	}

	/**
	 * Enable the high pass filter. Disable it to measure DC.
	 *
	 * @param highPassFilterEnabled
	 *        {@code true} to enable the high pass filter
	 */
	public void setHighPassFilterEnabled(boolean highPassFilterEnabled) {
		this.highPassFilterEnabled = highPassFilterEnabled;
	}

	/**
	 * Get the energy register resolution.
	 *
	 * @return {@code true} for 0.01CF energy register resolution, {@code false}
	 *         for 0.1CF; defaults to {@code false}
	 */
	public boolean isHighResolutionEnergy() {
		return highResolutionEnergy;
	}

	/**
	 * Set the energy register resolution.
	 *
	 * @param highResolutionEnergy
	 *        {@code true} for 0.01CF energy register resolution, {@code false}
	 *        for 0.1CF
	 */
	public void setHighResolutionEnergy(boolean highResolutionEnergy) {
		this.highResolutionEnergy = highResolutionEnergy;
	}

	/**
	 * Get the energy type the {@code CF2} pin pulses for.
	 *
	 * @return {@code true} if the {@code CF2} pin outputs reactive energy,
	 *         {@code false} for apparent energy; defaults to {@code true}
	 */
	public boolean isCf2ReactiveEnergy() {
		return cf2ReactiveEnergy;
	}

	/**
	 * Set the energy type the {@code CF2} pin pulses for.
	 *
	 * @param cf2ReactiveEnergy
	 *        {@code true} to output reactive energy on the {@code CF2} pin,
	 *        {@code false} for apparent energy
	 */
	public void setCf2ReactiveEnergy(boolean cf2ReactiveEnergy) {
		this.cf2ReactiveEnergy = cf2ReactiveEnergy;
	}

	/**
	 * Get the all-phase apparent energy computation method.
	 *
	 * @return {@code true} if all-phase apparent energy is a vector sum,
	 *         {@code false} for an arithmetic sum; defaults to {@code false}
	 */
	public boolean isApparentEnergyVectorSum() {
		return apparentEnergyVectorSum;
	}

	/**
	 * Set the all-phase apparent energy computation used for the {@code CF2}
	 * output and for power factor calibration.
	 *
	 * @param apparentEnergyVectorSum
	 *        {@code true} for a vector sum, {@code false} for an arithmetic sum
	 */
	public void setApparentEnergyVectorSum(boolean apparentEnergyVectorSum) {
		this.apparentEnergyVectorSum = apparentEnergyVectorSum;
	}

	/**
	 * Get the digital PGA gain.
	 *
	 * @return the digital PGA gain; defaults to {@code X1}
	 */
	public DigitalPgaGain getDigitalPgaGain() {
		return digitalPgaGain;
	}

	/**
	 * Set the digital PGA gain.
	 *
	 * @param digitalPgaGain
	 *        the digital PGA gain to set
	 */
	public void setDigitalPgaGain(DigitalPgaGain digitalPgaGain) {
		this.digitalPgaGain = requireNonNullArgument(digitalPgaGain, "digitalPgaGain");
	}

	/**
	 * Get the {@code I1} channel analog PGA gain.
	 *
	 * @return the {@code I1} channel analog PGA gain; defaults to {@code X1}
	 */
	public PgaGain getCurrentGainI1() {
		return currentGainI1;
	}

	/**
	 * Set the {@code I1} channel analog PGA gain.
	 *
	 * @param currentGainI1
	 *        the {@code I1} channel analog PGA gain to set
	 */
	public void setCurrentGainI1(PgaGain currentGainI1) {
		this.currentGainI1 = requireNonNullArgument(currentGainI1, "currentGainI1");
	}

	/**
	 * Get the {@code I2} channel analog PGA gain.
	 *
	 * @return the {@code I2} channel analog PGA gain; defaults to {@code X1}
	 */
	public PgaGain getCurrentGainI2() {
		return currentGainI2;
	}

	/**
	 * Set the {@code I2} channel analog PGA gain.
	 *
	 * @param currentGainI2
	 *        the {@code I2} channel analog PGA gain to set
	 */
	public void setCurrentGainI2(PgaGain currentGainI2) {
		this.currentGainI2 = requireNonNullArgument(currentGainI2, "currentGainI2");
	}

	/**
	 * Get the {@code I3} channel analog PGA gain.
	 *
	 * @return the {@code I3} channel analog PGA gain; defaults to {@code X1}
	 */
	public PgaGain getCurrentGainI3() {
		return currentGainI3;
	}

	/**
	 * Set the {@code I3} channel analog PGA gain.
	 *
	 * @param currentGainI3
	 *        the {@code I3} channel analog PGA gain to set
	 */
	public void setCurrentGainI3(PgaGain currentGainI3) {
		this.currentGainI3 = requireNonNullArgument(currentGainI3, "currentGainI3");
	}

	/**
	 * Get the {@code I4} (neutral) channel analog PGA gain.
	 *
	 * @return the {@code I4} (neutral) channel analog PGA gain; defaults to
	 *         {@code X1}
	 */
	public PgaGain getCurrentGainI4() {
		return currentGainI4;
	}

	/**
	 * Set the {@code I4} (neutral) channel analog PGA gain.
	 *
	 * @param currentGainI4
	 *        the {@code I4} (neutral) channel analog PGA gain to set
	 */
	public void setCurrentGainI4(PgaGain currentGainI4) {
		this.currentGainI4 = requireNonNullArgument(currentGainI4, "currentGainI4");
	}

	/**
	 * Get the {@code V1} channel analog PGA gain.
	 *
	 * @return the {@code V1} channel analog PGA gain; defaults to {@code X1}
	 */
	public PgaGain getVoltageGainV1() {
		return voltageGainV1;
	}

	/**
	 * Set the {@code V1} channel analog PGA gain.
	 *
	 * @param voltageGainV1
	 *        the {@code V1} channel analog PGA gain to set
	 */
	public void setVoltageGainV1(PgaGain voltageGainV1) {
		this.voltageGainV1 = requireNonNullArgument(voltageGainV1, "voltageGainV1");
	}

	/**
	 * Get the {@code V2} channel analog PGA gain.
	 *
	 * @return the {@code V2} channel analog PGA gain; defaults to {@code X1}
	 */
	public PgaGain getVoltageGainV2() {
		return voltageGainV2;
	}

	/**
	 * Set the {@code V2} channel analog PGA gain.
	 *
	 * @param voltageGainV2
	 *        the {@code V2} channel analog PGA gain to set
	 */
	public void setVoltageGainV2(PgaGain voltageGainV2) {
		this.voltageGainV2 = requireNonNullArgument(voltageGainV2, "voltageGainV2");
	}

	/**
	 * Get the {@code V3} channel analog PGA gain.
	 *
	 * @return the {@code V3} channel analog PGA gain; defaults to {@code X1}
	 */
	public PgaGain getVoltageGainV3() {
		return voltageGainV3;
	}

	/**
	 * Set the {@code V3} channel analog PGA gain.
	 *
	 * @param voltageGainV3
	 *        the {@code V3} channel analog PGA gain to set
	 */
	public void setVoltageGainV3(PgaGain voltageGainV3) {
		this.voltageGainV3 = requireNonNullArgument(voltageGainV3, "voltageGainV3");
	}

	/**
	 * Get the meter constant, in imp/kWh.
	 *
	 * @return the meter constant; defaults to {@link #DEFAULT_METER_CONSTANT}
	 */
	public int getMeterConstant() {
		return meterConstant;
	}

	/**
	 * Set the meter constant.
	 *
	 * <p>
	 * This determines the {@code PL_Constant} written to the chip, and with it
	 * the energy each register LSB represents.
	 * </p>
	 *
	 * @param meterConstant
	 *        the meter constant to set, in imp/kWh
	 * @throws IllegalArgumentException
	 *         if {@code meterConstant} is not greater than 0
	 */
	public void setMeterConstant(int meterConstant) {
		if ( meterConstant < 1 ) {
			throw new IllegalArgumentException("The meter constant must be greater than 0.");
		}
		this.meterConstant = meterConstant;
	}

	/**
	 * Derive the meter constant a {@code PL_Constant} value represents, the
	 * inverse of {@link #plConstant()}.
	 *
	 * <p>
	 * The division truncates, so a {@code PL_Constant} that was not derived
	 * from a whole meter constant does not round-trip exactly.
	 * </p>
	 *
	 * @param plConstant
	 *        the {@code PL_Constant} value
	 * @return the meter constant in imp/kWh, or 0 if {@code plConstant} is not
	 *         greater than 0
	 */
	public static int meterConstantForPlConstant(long plConstant) {
		if ( plConstant < 1 ) {
			return 0;
		}
		return (int) (PL_CONSTANT_NUMERATOR / plConstant);
	}

	/**
	 * Compose the {@code PL_Constant} value, whose high and low words are the
	 * {@code PLconstH} and {@code PLconstL} registers.
	 *
	 * @return {@code 450,000,000,000 / meterConstant}
	 */
	public long plConstant() {
		return PL_CONSTANT_NUMERATOR / meterConstant;
	}

	/**
	 * Get the energy one energy-register LSB represents.
	 *
	 * <p>
	 * An LSB is 0.1CF, or 0.01CF when {@link #isHighResolutionEnergy()} is
	 * {@code true}, and one CF is {@code 1kWh / meterConstant}. The same scale
	 * applies to the reactive (varh) and apparent (VAh) registers, since the
	 * chip derives every meter constant from the one {@code PL_Constant}.
	 * </p>
	 *
	 * @return the energy per LSB, in Wh
	 */
	public double energyResolutionWattHours() {
		return (1000.0 / meterConstant) * (highResolutionEnergy ? 0.01 : 0.1);
	}

	/**
	 * Get the voltage gain calibration value.
	 *
	 * @return the voltage gain calibration value; defaults to {@code 50000}
	 */
	public int getVoltageGain() {
		return voltageGain;
	}

	/**
	 * Set the voltage gain calibration value.
	 *
	 * @param voltageGain
	 *        the voltage gain calibration value to set
	 */
	public void setVoltageGain(int voltageGain) {
		this.voltageGain = voltageGain;
	}

	/**
	 * Get the phase A current gain calibration value.
	 *
	 * @return the phase A current gain calibration value; defaults to
	 *         {@code 32498}
	 */
	public int getCurrentGainA() {
		return currentGainA;
	}

	/**
	 * Set the phase A current gain calibration value.
	 *
	 * @param currentGainA
	 *        the phase A current gain calibration value to set
	 */
	public void setCurrentGainA(int currentGainA) {
		this.currentGainA = currentGainA;
	}

	/**
	 * Get the phase B current gain calibration value.
	 *
	 * @return the phase B current gain calibration value; defaults to
	 *         {@code 32498}
	 */
	public int getCurrentGainB() {
		return currentGainB;
	}

	/**
	 * Set the phase B current gain calibration value.
	 *
	 * @param currentGainB
	 *        the phase B current gain calibration value to set
	 */
	public void setCurrentGainB(int currentGainB) {
		this.currentGainB = currentGainB;
	}

	/**
	 * Get the phase C current gain calibration value.
	 *
	 * @return the phase C current gain calibration value; defaults to
	 *         {@code 32498}
	 */
	public int getCurrentGainC() {
		return currentGainC;
	}

	/**
	 * Set the phase C current gain calibration value.
	 *
	 * @param currentGainC
	 *        the phase C current gain calibration value to set
	 */
	public void setCurrentGainC(int currentGainC) {
		this.currentGainC = currentGainC;
	}

}

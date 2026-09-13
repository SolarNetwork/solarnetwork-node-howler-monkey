/* ==================================================================
 * Atm90e36DatumDataSource.java - 11 Sept 2026 10:05:40 am
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

package net.solarnetwork.node.datum.atm90e36;

import static net.solarnetwork.domain.datum.DatumSamplesType.Instantaneous;
import static net.solarnetwork.domain.datum.EnergyDatum.WATTS_KEY;
import static net.solarnetwork.node.hw.linux.spi.SpiDeviceFactory.spiDeviceFor;
import static net.solarnetwork.settings.support.BasicMultiValueSettingSpecifier.enumSpec;
import static net.solarnetwork.util.ObjectUtils.nonnull;
import static net.solarnetwork.util.ObjectUtils.requireNonNullArgument;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import net.solarnetwork.domain.AcPhase;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.MutableDatumSamplesOperations;
import net.solarnetwork.node.domain.datum.AcEnergyDatum;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.domain.datum.SimpleAcEnergyDatum;
import net.solarnetwork.node.hw.atm90e36.Atm90E36;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.CurrentSensor;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.DigitalPgaGain;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.LineFrequency;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.PgaGain;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Phase;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.SumMethod;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Wiring;
import net.solarnetwork.node.service.DatumDataSource;
import net.solarnetwork.node.service.support.DatumDataSourceSupport;
import net.solarnetwork.service.ServiceLifecycleObserver;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SettingSpecifierProvider;
import net.solarnetwork.settings.SettingsChangeObserver;
import net.solarnetwork.settings.support.BasicTextFieldSettingSpecifier;
import net.solarnetwork.settings.support.BasicTitleSettingSpecifier;
import net.solarnetwork.settings.support.BasicToggleSettingSpecifier;

/**
 * Generate datum from an ATM90E36 energy meter chip.
 *
 * <p>
 * Note that all energy values are published as <b>instantaneous</b> datum
 * property values because the ATM90E36 does not track lifetime energy values.
 * It is expected that a Virtual Meter Datum Filter be used in conjunction with
 * this datum source, using an expression style that simply adds the
 * instantaneous values to their previous reading value (for example
 * {@code prevReading + currInput}.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class Atm90e36DatumDataSource extends DatumDataSourceSupport implements DatumDataSource,
		SettingSpecifierProvider, SettingsChangeObserver, ServiceLifecycleObserver {

	/** The setting UID */
	public static final String SETTING_UID = "net.solarnetwork.node.datum.atm90e36";

	/** The {@code spiBus} property default value. */
	public static final int DEFAULT_SPI_BUS = 0;

	/** The {@code spiChip} property default value. */
	public static final int DEFAULT_SPI_CHIP = 0;

	private final Atm90E36Config config = new Atm90E36Config();
	private final Clock clock;
	private final AtomicReference<@Nullable NodeDatum> lastDatum = new AtomicReference<>();

	private int spiBus = DEFAULT_SPI_BUS;
	private int spiChip = DEFAULT_SPI_CHIP;
	private @Nullable String sourceId;

	private @Nullable Atm90E36 device;

	// keep track of in-use config, as `config` can change at any time; this instance
	// is never mutated and only created after configuring the device
	private @Nullable Atm90E36Config deviceConfig;

	/**
	 * Constructor.
	 *
	 * <p>
	 * Uses the system UTC clock.
	 * </p>
	 */
	public Atm90e36DatumDataSource() {
		this(Clock.systemUTC());
	}

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the clock to use
	 */
	public Atm90e36DatumDataSource(Clock clock) {
		super();
		this.clock = requireNonNullArgument(clock, "clock");
	}

	@Override
	public void serviceDidStartup() {
		// TODO Auto-generated method stub

	}

	@Override
	public synchronized void serviceDidShutdown() {
		if ( device != null ) {
			device.close();
			device = null;
		}
	}

	@Override
	public synchronized void configurationChanged(@Nullable Map<String, Object> properties) {
		if ( device != null ) {
			if ( device.configureIfNeeded(config) ) {
				wasConfigured();
				deviceConfig = device.config();
			}
		}
	}

	@Override
	public String getSettingUid() {
		return SETTING_UID;
	}

	@Override
	public List<SettingSpecifier> getSettingSpecifiers() {
		final List<SettingSpecifier> result = new ArrayList<>(32);

		result.add(new BasicTitleSettingSpecifier("datumStatus",
				datumPropertiesHtmlMessage(lastDatum.get(), Locale.getDefault()), true, true));

		result.add(new BasicTextFieldSettingSpecifier("sourceId", null));

		final Atm90E36Config defaults = new Atm90E36Config();
		final MessageSource msg = messageSource();
		final Locale locale = Locale.getDefault();

		result.add(new BasicTextFieldSettingSpecifier("spiBus", String.valueOf(DEFAULT_SPI_BUS)));
		result.add(new BasicTextFieldSettingSpecifier("spiChip", String.valueOf(DEFAULT_SPI_CHIP)));

		// service wiring
		result.add(enumSpec(LineFrequency.class, "config.lineFrequency", defaults.getLineFrequency(),
				msg, locale));
		result.add(enumSpec(Wiring.class, "config.wiring", defaults.getWiring(), msg, locale));
		result.add(new BasicTextFieldSettingSpecifier("summedPhasesValue",
				phasesValue(defaults.getSummedPhases())));
		result.add(enumSpec(CurrentSensor.class, "config.currentSensor", defaults.getCurrentSensor(),
				msg, locale));
		result.add(new BasicToggleSettingSpecifier("config.currentChannelsSwapped",
				defaults.isCurrentChannelsSwapped()));

		// metering
		result.add(new BasicTextFieldSettingSpecifier("config.meterConstant",
				String.valueOf(defaults.getMeterConstant())));
		result.add(new BasicToggleSettingSpecifier("config.highResolutionEnergy",
				defaults.isHighResolutionEnergy()));
		result.add(new BasicToggleSettingSpecifier("config.highPassFilterEnabled",
				defaults.isHighPassFilterEnabled()));
		result.add(enumSpec(SumMethod.class, "config.activePowerSum", defaults.getActivePowerSum(), msg,
				locale));
		result.add(enumSpec(SumMethod.class, "config.reactivePowerSum", defaults.getReactivePowerSum(),
				msg, locale));
		result.add(new BasicToggleSettingSpecifier("config.apparentEnergyVectorSum",
				defaults.isApparentEnergyVectorSum()));
		result.add(new BasicToggleSettingSpecifier("config.cf2ReactiveEnergy",
				defaults.isCf2ReactiveEnergy()));

		// RMS calibration
		result.add(new BasicTextFieldSettingSpecifier("config.voltageGain",
				String.valueOf(defaults.getVoltageGain())));
		result.add(new BasicTextFieldSettingSpecifier("config.currentGainA",
				String.valueOf(defaults.getCurrentGainA())));
		result.add(new BasicTextFieldSettingSpecifier("config.currentGainB",
				String.valueOf(defaults.getCurrentGainB())));
		result.add(new BasicTextFieldSettingSpecifier("config.currentGainC",
				String.valueOf(defaults.getCurrentGainC())));

		// ADC gain
		result.add(enumSpec(DigitalPgaGain.class, "config.digitalPgaGain", defaults.getDigitalPgaGain(),
				msg, locale));
		result.add(enumSpec(PgaGain.class, "config.currentGainI1", defaults.getCurrentGainI1(), msg,
				locale));
		result.add(enumSpec(PgaGain.class, "config.currentGainI2", defaults.getCurrentGainI2(), msg,
				locale));
		result.add(enumSpec(PgaGain.class, "config.currentGainI3", defaults.getCurrentGainI3(), msg,
				locale));
		result.add(enumSpec(PgaGain.class, "config.currentGainI4", defaults.getCurrentGainI4(), msg,
				locale));
		result.add(enumSpec(PgaGain.class, "config.voltageGainV1", defaults.getVoltageGainV1(), msg,
				locale));
		result.add(enumSpec(PgaGain.class, "config.voltageGainV2", defaults.getVoltageGainV2(), msg,
				locale));
		result.add(enumSpec(PgaGain.class, "config.voltageGainV3", defaults.getVoltageGainV3(), msg,
				locale));

		return result;
	}

	@Override
	public Class<? extends NodeDatum> getDatumType() {
		return AcEnergyDatum.class;
	}

	private @Nullable String sourceId() {
		final String sourceId = resolvePlaceholders(getSourceId());
		return (sourceId != null && !sourceId.isEmpty() ? sourceId : null);
	}

	@Override
	public Collection<String> publishedSourceIds() {
		final String sourceId = sourceId();
		return (sourceId != null ? List.of(sourceId) : List.of());
	}

	@Override
	public @Nullable NodeDatum readCurrentDatum() {
		final String sourceId = sourceId();
		if ( sourceId == null ) {
			return null;
		}
		final DataAndConfig reading = readFromDevice();
		final var datum = new SimpleAcEnergyDatum(sourceId, reading.data().timestamp(),
				new DatumSamples());
		populateMeasurements(reading, datum);
		lastDatum.set(datum);
		return datum;
	}

	private record DataAndConfig(Atm90E36.MeasurementsAndEnergy data, Atm90E36Config config) {

	}

	private void populateMeasurements(DataAndConfig dataAndConfig, SimpleAcEnergyDatum datum) {
		final MutableDatumSamplesOperations ops = datum.asMutableSampleOperations();
		final Atm90E36Config config = dataAndConfig.config();
		final Atm90E36.Measurements meas = dataAndConfig.data().measurements();

		// assume the summed phases configuration is correct for the type of wiring
		final Set<Phase> phases = config.getSummedPhases();

		datum.setRealPower((int) Math.round(meas.powerTotal()));
		datum.setWatts(datum.getRealPower()); // just copy of realPower
		for ( Phase phase : phases ) {
			ops.putSampleValue(Instantaneous, phase.toAcPhase().withKey(WATTS_KEY),
					(int) Math.round(meas.power(phase)));
		}
		datum.setPowerFactor((float) meas.powerFactorTotal());
		datum.setFrequency((float) meas.frequency());

		// current
		for ( Phase phase : phases ) {
			datum.setCurrent(phase.toAcPhase(), (float) meas.current(phase));
		}

		// voltage/current measurements depend on wiring
		if ( config.getWiring() == Wiring.THREE_PHASE_FOUR_WIRE ) {
			// a/b/c to neutral
			for ( Phase phase : phases ) {
				datum.setVoltage(phase.toAcPhase(), (float) meas.voltage(phase));
			}
		} else {
			for ( Phase phase : phases ) {
				AcPhase linePhase = lineVoltagePhase(phase);
				if ( linePhase != null ) {
					datum.setLineVoltage(linePhase, (float) meas.voltage(phase));
				}
			}
		}

		// energy; the per-phase registers are always populated, so the same
		// summed-phases contract decides which of them are meaningful
		final Atm90E36.EnergyReading energy = dataAndConfig.data().energy();
		populateEnergy(ops, "activeEnergyDeltaImport", energy.activeImport(), phases);
		populateEnergy(ops, "activeEnergyDeltaExport", energy.activeExport(), phases);
		populateEnergy(ops, "reactiveEnergyDeltaImport", energy.reactiveImport(), phases);
		populateEnergy(ops, "reactiveEnergyDeltaExport", energy.reactiveExport(), phases);
		populateEnergy(ops, "apparentEnergyDelta", energy.apparent(), phases);
	}

	/**
	 * Populate a total and its per-phase energy values.
	 *
	 * @param ops
	 *        the samples to populate
	 * @param name
	 *        the property name to use for the total; each phase value is added
	 *        with that phase's key suffix
	 * @param energy
	 *        the energy values
	 * @param phases
	 *        the phases to populate
	 */
	private static void populateEnergy(MutableDatumSamplesOperations ops, String name,
			Atm90E36.PhaseEnergy energy, Set<Phase> phases) {
		ops.putSampleValue(Instantaneous, name, energy.total());
		for ( Phase phase : phases ) {
			ops.putSampleValue(Instantaneous, phase.toAcPhase().withKey(name), energy.value(phase));
		}
	}

	/**
	 * Get the phase whose line voltage key names what a 3P3W channel measures.
	 *
	 * <p>
	 * With phase B as the voltage reference, the A channel measures
	 * {@code Uab}, which is phase A's own line voltage key
	 * ({@code voltage_ab}), and the C channel measures {@code Ucb}. RMS is
	 * unsigned, so {@code |Ucb| == |Ubc|}, which is phase B's line voltage key
	 * ({@code voltage_bc}). Phase C's own key would be {@code voltage_ca}, a
	 * different voltage that this wiring does not measure.
	 * </p>
	 *
	 * @param phase
	 *        the measured phase
	 * @return the phase to derive the line voltage key from, or {@code null} if
	 *         this wiring does not measure a line voltage for {@code phase}
	 */
	private static @Nullable AcPhase lineVoltagePhase(Phase phase) {
		return switch (phase) {
			case A -> AcPhase.PhaseA;
			case C -> AcPhase.PhaseB;
			case B -> null; // phase B is the reference, so has no line voltage here
		};
	}

	// read device and return data + active config so it can be interpreted correctly
	private synchronized DataAndConfig readFromDevice() {
		if ( device == null ) {
			device = new Atm90E36(spiDeviceFor(spiBus, spiChip), clock, config);
			device.open();
			if ( device.configureIfNeeded(config) ) {
				wasConfigured();
			}
			deviceConfig = device.config();
		}
		// deviceConfig should always be non-null here
		return new DataAndConfig(device.readMeasurementsAndEnergy(),
				nonnull(deviceConfig, "Device config"));
	}

	private void wasConfigured() {
		log.info("Configured ATM90E36 on SPI {}.{} with {}", spiBus, spiChip, config);
	}

	/**
	 * Get the SPI bus number.
	 *
	 * @return the SPI bus number
	 */
	public final int getSpiBus() {
		return spiBus;
	}

	/**
	 * Set the SPI bus number.
	 *
	 * @param spiBus
	 *        the SPI bus number to set
	 */
	public final void setSpiBus(int spiBus) {
		this.spiBus = spiBus;
	}

	/**
	 * Get the SPI chip number.
	 *
	 * @return the SPI chip number
	 */
	public final int getSpiChip() {
		return spiChip;
	}

	/**
	 * Set the SPI chip number.
	 *
	 * @param spiChip
	 *        the SPI chip number to set
	 */
	public final void setSpiChip(int spiChip) {
		this.spiChip = spiChip;
	}

	/**
	 * Get the connected phases, as a compact string like {@code a,c}.
	 *
	 * @return the phases counted into the chip's all-phase totals, which are
	 *         also the phases treated as connected
	 * @see Atm90E36Config#setSummedPhases(Set)
	 */
	public final String getSummedPhasesValue() {
		return phasesValue(config.getSummedPhases());
	}

	/**
	 * Set the connected phases from a compact string.
	 *
	 * <p>
	 * Accepts the phase letters in any order and case, with or without
	 * separators, so {@code ac}, {@code a,c} and {@code A C} are equivalent.
	 * Anything else is ignored, so a partially typed value cannot leave the
	 * configuration in a state the chip would reject.
	 * </p>
	 *
	 * @param value
	 *        the phases to set
	 */
	public final void setSummedPhasesValue(@Nullable String value) {
		final Set<Phase> phases = EnumSet.noneOf(Phase.class);
		if ( value != null ) {
			for ( char c : value.toLowerCase(Locale.ROOT).toCharArray() ) {
				switch (c) {
					case 'a' -> phases.add(Phase.A);
					case 'b' -> phases.add(Phase.B);
					case 'c' -> phases.add(Phase.C);
					default -> {
						// ignore anything else, including separators
					}
				}
			}
		}
		config.setSummedPhases(phases);
	}

	/** Render a phase set as a compact string like {@code a,c}. */
	private static String phasesValue(Set<Phase> phases) {
		StringBuilder buf = new StringBuilder(5);
		for ( Phase phase : phases ) {
			if ( !buf.isEmpty() ) {
				buf.append(',');
			}
			buf.append(Character.toLowerCase(phase.name().charAt(0)));
		}
		return buf.toString();
	}

	/**
	 * Get the configuration.
	 *
	 * @return the configuration
	 */
	public final Atm90E36Config getConfig() {
		return config;
	}

	/**
	 * Get the source ID.
	 *
	 * @return the source ID
	 */
	public final @Nullable String getSourceId() {
		return sourceId;
	}

	/**
	 * Set the source ID.
	 *
	 * @param sourceId
	 *        the source ID to set
	 */
	public final void setSourceId(@Nullable String sourceId) {
		this.sourceId = sourceId;
	}

}

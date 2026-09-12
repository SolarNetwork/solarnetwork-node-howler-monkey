/* ==================================================================
 * Atm90E36EnergyTests.java - 11/09/2026
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

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.within;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.Test;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.LineFrequency;
import net.solarnetwork.node.hw.atm90e36.test.FakeSpiDevice;

/**
 * Unit tests for the {@link Atm90E36} energy registers: their scaling to base
 * units, and the read-to-clear behaviour the datasheet documents for them.
 *
 * @author matt
 * @version 1.0
 */
@SuppressWarnings("resource")
public class Atm90E36EnergyTests {

	/** Comparison tolerance for scaled energy values. */
	private static final double TOLERANCE = 1e-9;

	// energy register addresses, from datasheet Table-9
	private static final int APenergyT = 0x80;
	private static final int APenergyA = 0x81;
	private static final int ANenergyT = 0x84;
	private static final int RPenergyT = 0x88;
	private static final int RNenergyT = 0x8C;
	private static final int SAenergyT = 0x90;
	private static final int SVenergyT = 0x94;

	private static final int ConfigStart = 0x30;
	private static final int MMode0 = 0x33;
	private static final int PLconstH = 0x31;
	private static final int PLconstL = 0x32;

	/** An {@link InstantSource} the test advances by hand. */
	private static final class TestClock implements InstantSource {

		private Instant now = Instant.parse("2026-09-11T00:00:00Z");

		@Override
		public Instant instant() {
			return now;
		}

		private void advance(Duration amount) {
			now = now.plus(amount);
		}
	}

	@Test
	public void energyRegistersScaleToWattHours() {
		// GIVEN the default meter constant of 3200 imp/kWh at 0.1CF, so one
		// LSB is 0.03125Wh and 32000 LSB is exactly 1000Wh
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(APenergyT, 32000);
		fake.registers.put(APenergyA, 16000);
		fake.registers.put(ANenergyT, 3200);
		fake.registers.put(RPenergyT, 320);
		fake.registers.put(RNenergyT, 32);
		fake.registers.put(SAenergyT, 64000);
		fake.registers.put(SVenergyT, 1);

		// WHEN
		Atm90E36.EnergyReading r = new Atm90E36(fake).readEnergy();

		// THEN
		then(r.activeImport().total()).as("active import total, Wh").isCloseTo(1000.0,
				within(TOLERANCE));
		then(r.activeImport().phaseA()).as("active import phase A, Wh").isCloseTo(500.0,
				within(TOLERANCE));
		then(r.activeImport().phaseB()).as("unset register").isCloseTo(0.0, within(TOLERANCE));
		then(r.activeExport().total()).as("active export total, Wh").isCloseTo(100.0, within(TOLERANCE));
		then(r.reactiveImport().total()).as("reactive import total, varh").isCloseTo(10.0,
				within(TOLERANCE));
		then(r.reactiveExport().total()).as("reactive export total, varh").isCloseTo(1.0,
				within(TOLERANCE));
		then(r.apparent().total()).as("apparent total, VAh").isCloseTo(2000.0, within(TOLERANCE));
		then(r.apparentVectorTotal()).as("one LSB, VAh").isCloseTo(0.03125, within(TOLERANCE));
	}

	@Test
	public void energyResolutionFollowsTheMeterConstant() {
		// GIVEN half the default meter constant, so each CF is worth twice as
		// much energy
		Atm90E36Config config = new Atm90E36Config();
		config.setMeterConstant(1600);
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(APenergyT, 16000);

		// WHEN
		Atm90E36 eic = new Atm90E36(fake, config);

		// THEN
		then(eic.getEnergyResolution()).as("Wh per LSB").isCloseTo(0.0625, within(TOLERANCE));
		then(eic.readEnergy().activeImport().total()).as("active import total, Wh").isCloseTo(1000.0,
				within(TOLERANCE));
	}

	@Test
	public void energyResolutionFollowsThe001LsbBit() {
		// GIVEN 0.01CF resolution, a tenth of the default
		Atm90E36Config config = new Atm90E36Config();
		config.setHighResolutionEnergy(true);
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(APenergyT, 32000);

		// WHEN
		Atm90E36 eic = new Atm90E36(fake, config);

		// THEN
		then(eic.getEnergyResolution()).as("Wh per LSB").isCloseTo(0.003125, within(TOLERANCE));
		then(eic.readEnergy().activeImport().total()).as("active import total, Wh").isCloseTo(100.0,
				within(TOLERANCE));
	}

	@Test
	public void readEnergyClearsTheRegisters() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(APenergyT, 32000);
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN
		double first = eic.readEnergy().activeImport().total();
		double second = eic.readEnergy().activeImport().total();

		// THEN
		then(first).as("the interval's accumulation").isCloseTo(1000.0, within(TOLERANCE));
		then(second).as("cleared by the first read, so nothing accumulated since").isCloseTo(0.0,
				within(TOLERANCE));
	}

	@Test
	public void readEnergyReportsTheIntervalItCovers() {
		// GIVEN
		TestClock clock = new TestClock();
		Instant constructed = clock.instant();
		Atm90E36 eic = new Atm90E36(new FakeSpiDevice(), clock, new Atm90E36Config());

		// WHEN
		clock.advance(Duration.ofMinutes(5));
		Atm90E36.EnergyReading first = eic.readEnergy();
		clock.advance(Duration.ofMinutes(15));
		Atm90E36.EnergyReading second = eic.readEnergy();

		// THEN
		then(first.start()).as("the first interval starts at construction").isEqualTo(constructed);
		then(first.duration()).as("first interval").isEqualTo(Duration.ofMinutes(5));
		then(second.start()).as("intervals are contiguous").isEqualTo(first.end());
		then(second.duration()).as("second interval").isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	public void configureRestartsTheEnergyInterval() {
		// GIVEN configure() soft-resets the chip, which clears the energy registers
		TestClock clock = new TestClock();
		Atm90E36 eic = new Atm90E36(new FakeSpiDevice(), clock, new Atm90E36Config());
		clock.advance(Duration.ofHours(2));

		// WHEN
		eic.configure(new Atm90E36Config());
		Instant begun = clock.instant();
		clock.advance(Duration.ofMinutes(1));

		// THEN
		then(eic.readEnergy().start()).as("the interval restarts at configure(), not construction")
				.isEqualTo(begun);
	}

	@Test
	public void unconfiguredChipIsDetectedByItsConfigStartRegister() {
		// GIVEN a chip in its power-on state
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(ConfigStart, 0x6886);

		// THEN
		then(new Atm90E36(fake).isConfigured()).as("6886H is the power-on value").isFalse();
	}

	@Test
	public void configuredChipIsDetectedByItsConfigStartRegister() {
		FakeSpiDevice fake = new FakeSpiDevice();

		fake.registers.put(ConfigStart, 0x5678);
		then(new Atm90E36(fake).isConfigured()).as("5678H, calibration").isTrue();

		fake.registers.put(ConfigStart, 0x8765);
		then(new Atm90E36(fake).isConfigured()).as("8765H, operation").isTrue();
	}

	@Test
	public void configureIfNeededPreservesEnergyWhenTheSettingsMatch() {
		// GIVEN a chip an earlier process configured with these very settings,
		// which has accumulated energy since
		FakeSpiDevice fake = new FakeSpiDevice();
		new Atm90E36(fake).configure(new Atm90E36Config());
		fake.registers.put(APenergyT, 32000);
		fake.writes.clear();
		fake.softResets = 0; // discount the set-up configure() above
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN
		boolean configured = eic.configureIfNeeded(new Atm90E36Config());

		// THEN
		then(configured).as("nothing to do").isFalse();
		then(fake.writes).as("no registers written").isEmpty();
		then(fake.softResets).as("no reset").isEqualTo(0);
		then(eic.readEnergy().activeImport().total()).as("the accumulated energy is still there")
				.isCloseTo(1000.0, within(TOLERANCE));
	}

	@Test
	public void configureIfNeededReconfiguresWhenTheSettingsDiffer() {
		// GIVEN a chip an earlier process configured for 60Hz
		Atm90E36Config old = new Atm90E36Config();
		old.setLineFrequency(LineFrequency.HZ_60);
		FakeSpiDevice fake = new FakeSpiDevice();
		new Atm90E36(fake).configure(old);
		fake.registers.put(APenergyT, 32000);
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN this process wants 50Hz
		boolean configured = eic.configureIfNeeded(new Atm90E36Config());

		// THEN it is not left on the old settings, even though the chip
		// reported itself configured
		then(eic.isConfigured()).as("the chip was configured all along").isTrue();
		then(configured).as("reconfigured for the mismatch").isTrue();
		then(fake.registers).as("the new metering mode reached the chip").containsEntry(MMode0, 0x0087);
	}

	@Test
	public void matchesConfigurationComparesEveryDerivedRegister() {
		// GIVEN
		Atm90E36Config config = new Atm90E36Config();
		config.setMeterConstant(1600);
		config.setVoltageGain(12345);
		config.setCurrentGainB(23456);
		FakeSpiDevice fake = new FakeSpiDevice();
		Atm90E36 eic = new Atm90E36(fake);
		eic.configure(config);

		// THEN
		then(eic.matchesConfiguration(config)).as("as written").isTrue();

		Atm90E36Config other = new Atm90E36Config();
		other.setMeterConstant(1600);
		other.setVoltageGain(12345);
		other.setCurrentGainB(23456);
		then(eic.matchesConfiguration(other)).as("an equivalent configuration").isTrue();

		other.setCurrentGainC(999);
		then(eic.matchesConfiguration(other)).as("one differing current gain").isFalse();

		other.setCurrentGainC(new Atm90E36Config().getCurrentGainC());
		other.setMeterConstant(3200);
		then(eic.matchesConfiguration(other)).as("a differing meter constant").isFalse();
	}

	@Test
	public void matchesConfigurationIsFalseOnAPoweredOnChip() {
		// GIVEN a chip in its power-on state, whose MMode0, MMode1 and
		// PL_Constant defaults happen to equal a default configuration's
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(ConfigStart, 0x6886);
		fake.registers.put(MMode0, 0x0087);
		fake.registers.put(PLconstH, 0x0861);
		fake.registers.put(PLconstL, 0xC468);
		Atm90E36 eic = new Atm90E36(fake);

		// THEN the gain registers still differ, and isConfigured() is the
		// authoritative signal either way
		then(eic.matchesConfiguration(new Atm90E36Config())).as("gains are not the power-on values")
				.isFalse();
		then(eic.isConfigured()).as("never configured").isFalse();
		then(eic.configureIfNeeded(new Atm90E36Config())).as("configured").isTrue();
	}

	@Test
	public void configureIfNeededConfiguresAPoweredOnChip() {
		// GIVEN a chip that has just powered up
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(ConfigStart, 0x6886);
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN
		boolean configured = eic.configureIfNeeded(new Atm90E36Config());

		// THEN
		then(configured).as("configuration applied").isTrue();
		then(fake.registers).as("the configuration reached the chip").containsEntry(MMode0, 0x0087);
	}

	@Test
	public void configureDiscardsAccumulatedEnergy() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(APenergyT, 32000);
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN the soft reset in configure() resets the chip
		eic.configure(new Atm90E36Config());

		// THEN
		then(eic.readEnergy().activeImport().total()).as("energy lost to the reset").isCloseTo(0.0,
				within(TOLERANCE));
	}

	@Test
	public void refreshConfigAdoptsTheChipsOwnScaling() {
		// GIVEN a chip configured by an earlier process for 0.01CF resolution
		// at 1600 imp/kWh, which this instance knows nothing about
		Atm90E36Config chipConfig = new Atm90E36Config();
		chipConfig.setMeterConstant(1600);
		chipConfig.setHighResolutionEnergy(true);
		FakeSpiDevice fake = new FakeSpiDevice();
		new Atm90E36(fake, chipConfig).configure(chipConfig);

		Atm90E36 restarted = new Atm90E36(fake); // default config
		then(restarted.getEnergyResolution()).as("before refresh, the default scaling")
				.isCloseTo(0.03125, within(TOLERANCE));

		// WHEN
		Atm90E36Config read = restarted.refreshConfig();

		// THEN
		then(read.getMeterConstant()).as("meter constant recovered from PL_Constant").isEqualTo(1600);
		then(read.isHighResolutionEnergy()).as("001LSB recovered from MMode0").isTrue();
		then(restarted.getEnergyResolution()).as("the instance adopted the chip's scaling")
				.isCloseTo(0.00625, within(TOLERANCE));
	}

	@Test
	public void energyIntervalStartCanBeRestoredAfterARestart() {
		// GIVEN a process that persisted when it last read the chip
		TestClock clock = new TestClock();
		Instant lastRead = clock.instant().minus(Duration.ofMinutes(30));
		Atm90E36 eic = new Atm90E36(new FakeSpiDevice(), clock, new Atm90E36Config());

		// WHEN
		eic.setEnergyIntervalStart(lastRead);

		// THEN
		then(eic.getEnergyIntervalStart()).isEqualTo(lastRead);
		then(eic.readEnergy().duration()).as("the interval covers the gap across the restart")
				.isEqualTo(Duration.ofMinutes(30));
	}

	@Test
	public void readEnergyUsesOneBatchedTransfer() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN
		eic.readEnergy();
		eic.readEnergy();

		// THEN
		then(fake.batchOpens).as("batch created once and reused").isEqualTo(1);
		then(fake.batchTransfers).as("one transfer per read").isEqualTo(2);
		then(fake.txFrames).as("21 energy registers per read").hasSize(42);

		eic.close();

		then(fake.batchCloses).as("released by close()").isEqualTo(1);
	}

	@Test
	public void configureWritesPlConstantForTheMeterConstant() {
		// GIVEN the default 3200 imp/kWh, so PL_Constant is 450e9 / 3200 =
		// 140,625,000 = 0x0861C468 -- the value the Python tool hard-coded
		FakeSpiDevice fake = new FakeSpiDevice();

		// WHEN
		new Atm90E36(fake).configure(new Atm90E36Config());

		// THEN
		then(fake.registers).as("PL_Constant high and low words").containsEntry(PLconstH, 0x0861)
				.containsEntry(PLconstL, 0xC468);
	}

	@Test
	public void configureWritesPlConstantForACustomMeterConstant() {
		// GIVEN 1000 imp/kWh -> 450,000,000 = 0x1AD27480
		Atm90E36Config config = new Atm90E36Config();
		config.setMeterConstant(1000);
		FakeSpiDevice fake = new FakeSpiDevice();

		// WHEN
		new Atm90E36(fake, config).configure(config);

		// THEN
		then(config.plConstant()).as("PL_Constant").isEqualTo(450_000_000L);
		then(fake.registers).as("PL_Constant high and low words").containsEntry(PLconstH, 0x1AD2)
				.containsEntry(PLconstL, 0x7480);
	}

}

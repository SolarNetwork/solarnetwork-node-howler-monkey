/* ==================================================================
 * Atm90E36Tests.java - 10/09/2026
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
import java.util.EnumSet;
import org.junit.Test;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.LineFrequency;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.PgaGain;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Phase;
import net.solarnetwork.node.hw.atm90e36.Atm90E36Config.Wiring;
import net.solarnetwork.node.hw.atm90e36.test.FakeSpiDevice;

/**
 * Unit tests for {@link Atm90E36} measurement scaling and SPI framing, using a
 * {@link FakeSpiDevice} so no hardware is required.
 *
 * @author matt
 * @version 1.0
 */
@SuppressWarnings("resource")
public class Atm90E36Tests {

	/** Comparison tolerance for scaled measurement values. */
	private static final double TOLERANCE = 1e-9;

	// register addresses (from the ATM90E36 datasheet )
	private static final int UrmsA = 0xD9;
	private static final int IrmsA = 0xDD;
	private static final int PmeanA = 0xB1;
	private static final int PmeanALSB = 0xC1;
	private static final int PFmeanT = 0xBC;
	private static final int Freq = 0xF8;
	private static final int MMode0 = 0x33;
	private static final int MMode1 = 0x34;
	private static final int UgainA = 0x61;
	private static final int IgainA = 0x62;
	private static final int CSZero = 0x3B;
	private static final int SysStatus0 = 0x01;
	private static final int SagTh = 0x08;

	// SagTh = (sagV * 100 * sqrt(2)) / (2 * UgainA / 32768), at the default
	// voltage gain of 50000
	private static final int SAG_TH_50HZ = 8804; // 190V nominal
	private static final int SAG_TH_60HZ = 4170; //  90V nominal

	@Test
	public void readFramingMatchesSpidevWireFormat() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(Freq, 5000);
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN
		double f = eic.getFrequency();

		// THEN
		then(f).as("Freq register scaled to Hz").isCloseTo(50.0, within(TOLERANCE));
		then(fake.txFrames.get(0))
				.as("address 0xF8 | read-bit 0x8000 -> 0x80F8, byte-swapped to 0xF880");
		then(fake.txFrames.get(0)).as("address 0xF8 | read-bit 0x8000 -> 0x80F8, byte-swapped to 0xF880")
				.containsExactly(0xF8, 0x80, 0x00, 0x00);
	}

	@Test
	public void readRegisterRoundTrip() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(0x42, 0xABCD);

		// WHEN
		int value = new Atm90E36(fake).readRegister(0x42);

		// THEN
		then(value).as("register value decoded from the response frame").isEqualTo(0xABCD);
		then(fake.txFrames.get(0)).as("address 0x42 | 0x8000 -> 0x8042, byte-swapped to 0x4280")
				.containsExactly(0x42, 0x80, 0x00, 0x00);
	}

	@Test
	public void writeRegisterFraming() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();

		// WHEN
		new Atm90E36(fake).writeRegister(0x33, 0x01F4); // MMode0 <- 500

		// THEN
		then(fake.txFrames.get(0))
				.as("address 0x33 -> byte-swapped 0x3300; value 0x01F4 -> byte-swapped 0xF401")
				.containsExactly(0x33, 0x00, 0xF4, 0x01);
		then(fake.writes).as("single register write recorded").hasSize(1);
		then(fake.writes.get(0).address).as("write address").isEqualTo(0x33);
		then(fake.writes.get(0).value).as("write value").isEqualTo(0x01F4);
	}

	@Test
	public void readMeasurementsUsesOneBatchedTransfer() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(0xD9, 65427); // UrmsA -> 654.27 V
		fake.registers.put(0xDA, 24000); // UrmsB -> 240.00 V
		fake.registers.put(0xDD, 1500); //  IrmsA -> 1.500 A
		fake.registers.put(0xB1, 3); //     PmeanA -> (3*65536)*0.00032 = 62.91456 W
		fake.registers.put(0xC1, 0); //     PmeanA LSB
		fake.registers.put(0xB0, 0xFFFF); // PmeanT -> -1 -> -20.97152 W
		fake.registers.put(0xC0, 0); //     PmeanT LSB
		fake.registers.put(0xBC, 0xFC18); // PFmeanT -> -1000 -> -1.0
		fake.registers.put(0xF8, 6000); //  Freq -> 60.00 Hz

		// WHEN
		Atm90E36.Measurements m = new Atm90E36(fake).readMeasurements();

		// THEN
		then(fake.batchOpens).as("one batch handle").isEqualTo(1);
		then(fake.batchTransfers).as("one transfer for the whole row").isEqualTo(1);
		then(fake.txFrames).as("16 register reads in the batch").hasSize(16);
		then(fake.lastSettleMicros).as("inter-access settle time").isEqualTo(10);

		then(m.voltageA()).as("voltage A").isCloseTo(654.27, within(TOLERANCE));
		then(m.voltageB()).as("voltage B").isCloseTo(240.00, within(TOLERANCE));
		then(m.voltageC()).as("voltage C, unset register").isCloseTo(0.0, within(TOLERANCE));
		then(m.currentA()).as("current A").isCloseTo(1.5, within(TOLERANCE));
		then(m.powerA()).as("power A").isCloseTo(62.91456, within(TOLERANCE));
		then(m.powerTotal()).as("negative total power").isCloseTo(-20.97152, within(TOLERANCE));
		then(m.powerFactorTotal()).as("negative total power factor").isCloseTo(-1.0, within(TOLERANCE));
		then(m.frequency()).as("frequency").isCloseTo(60.0, within(TOLERANCE));
	}

	@Test
	public void readMeasurementsMatchesIndividualAccessors() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		int[] regs = { 0xD9, 0xDA, 0xDB, 0xDD, 0xDE, 0xDF, 0xB1, 0xC1, 0xB2, 0xC2, 0xB3, 0xC3, 0xB0,
				0xC0, 0xBC, 0xF8 };
		for ( int i = 0; i < regs.length; i++ ) {
			fake.registers.put(regs[i], (i * 7919) & 0xFFFF); // arbitrary distinct values
		}
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN
		Atm90E36.Measurements m = eic.readMeasurements();

		// THEN
		then(m.voltageA()).as("voltage A").isCloseTo(eic.getLineVoltageA(), within(TOLERANCE));
		then(m.voltageC()).as("voltage C").isCloseTo(eic.getLineVoltageC(), within(TOLERANCE));
		then(m.currentB()).as("current B").isCloseTo(eic.getLineCurrentB(), within(TOLERANCE));
		then(m.powerA()).as("power A").isCloseTo(eic.getActivePowerA(), within(TOLERANCE));
		then(m.powerC()).as("power C").isCloseTo(eic.getActivePowerC(), within(TOLERANCE));
		then(m.powerTotal()).as("total power").isCloseTo(eic.getTotalActivePower(), within(TOLERANCE));
		then(m.powerFactorTotal()).as("total power factor").isCloseTo(eic.getTotalPowerFactor(),
				within(TOLERANCE));
		then(m.frequency()).as("frequency").isCloseTo(eic.getFrequency(), within(TOLERANCE));
	}

	@Test
	public void repeatedReadMeasurementsReuseOneBatch() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		Atm90E36 eic = new Atm90E36(fake);

		// WHEN
		eic.readMeasurements();
		eic.readMeasurements();
		eic.readMeasurements();

		// THEN
		then(fake.batchOpens).as("batch created once and reused").isEqualTo(1);
		then(fake.batchTransfers).as("one transfer per read").isEqualTo(3);
		then(fake.batchCloses).as("not closed until close()").isEqualTo(0);

		eic.close();

		then(fake.batchCloses).as("batch released by close()").isEqualTo(1);
		then(fake.open).as("SPI device closed").isFalse();
	}

	@Test
	public void closeIsIdempotentAndTryWithResourcesFriendly() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		Atm90E36 outside;

		// WHEN
		try (Atm90E36 eic = new Atm90E36(fake)) {
			outside = eic;
			eic.readMeasurements();
		} // closed here

		// THEN
		then(fake.open).as("closed on exit from try-with-resources").isFalse();
		then(fake.batchCloses).as("batch released").isEqualTo(1);

		// a second close (e.g. a shutdown hook racing normal exit) is harmless
		outside.close();

		then(fake.batchCloses).as("batch not closed twice").isEqualTo(1);
	}

	@Test
	public void lineVoltageScaling() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(UrmsA, 65427);

		then(new Atm90E36(fake).getLineVoltageA()).isCloseTo(654.27, within(TOLERANCE));
	}

	@Test
	public void lineCurrentScaling() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(IrmsA, 65427);

		then(new Atm90E36(fake).getLineCurrentA()).isCloseTo(65.427, within(TOLERANCE));
	}

	@Test
	public void activePowerPositive() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(PmeanA, 5);
		fake.registers.put(PmeanALSB, 0);

		// (5 * 65536 + 0) * 0.00032
		then(new Atm90E36(fake).getActivePowerA()).isCloseTo(104.8576, within(TOLERANCE));
	}

	@Test
	public void activePowerNegativeTwosComplement() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(PmeanA, 0xFFFF); // -1
		fake.registers.put(PmeanALSB, 0);

		then(new Atm90E36(fake).getActivePowerA()).isCloseTo(-20.97152, within(TOLERANCE));
	}

	@Test
	public void powerFactorNegative() {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(PFmeanT, 0xFC18); // -1000

		then(new Atm90E36(fake).getTotalPowerFactor()).isCloseTo(-1.0, within(TOLERANCE));
	}

	@Test
	public void openConfiguresTheSpiBus() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();

		// WHEN
		new Atm90E36(fake).open();

		// THEN
		then(fake.open).as("SPI device opened").isTrue();
		then(fake.mode).as("SPI mode").isEqualTo(3);
		then(fake.maxSpeedHz).as("SPI clock").isEqualTo(200000);
		then(fake.bitsPerWord).as("SPI word size").isEqualTo(8);
		then(fake.writes).as("the chip's own registers are left alone").isEmpty();
	}

	@Test
	public void configurePushesCalibration() {
		// GIVEN
		FakeSpiDevice fake = new FakeSpiDevice();
		Atm90E36 eic = new Atm90E36(fake); // default Atm90E36Config

		// WHEN
		eic.configure(new Atm90E36Config());

		// THEN
		then(fake.registers).as("calibration registers pushed to the chip").containsEntry(MMode0, 0x0087)
				.containsEntry(MMode1, 0x0000).containsEntry(UgainA, 50000).containsEntry(IgainA, 32498);

		// CSZero holds the XOR of the CONFIG-block values written before it
		int expected = 0x5678 ^ 0x0861 ^ 0xC468 ^ 0x0087 ^ 0x0000 ^ 0x1D4C ^ 0x1D4C ^ 0x1D4C ^ 0x02EE
				^ 0x02EE ^ 0x02EE;
		then(fake.registers).as("CONFIG block checksum").containsEntry(CSZero, expected & 0xFFFF);
	}

	@Test
	public void configurePushesTheComposedMeteringMode() {
		// GIVEN
		Atm90E36Config config = new Atm90E36Config();
		config.setLineFrequency(LineFrequency.HZ_60);
		config.setWiring(Wiring.THREE_PHASE_THREE_WIRE);
		config.setSummedPhases(EnumSet.of(Phase.A, Phase.C));
		config.setPhaseCurrentPgaGain(PgaGain.X2);
		config.setVoltageGain(12345);
		config.setCurrentGain(23456);
		FakeSpiDevice fake = new FakeSpiDevice();

		// WHEN
		new Atm90E36(fake, config).configure(config);

		// THEN
		then(fake.registers).as("the composed configuration reaches the chip")
				.containsEntry(MMode0, 0x1185) // 3P3W 60Hz, phases A + C
				.containsEntry(MMode1, 21) // 2X on I1/I2/I3
				.containsEntry(UgainA, 12345).containsEntry(IgainA, 23456);
	}

	@Test
	public void configReconstructsTheConstructionSettings() {
		// GIVEN
		Atm90E36Config config = new Atm90E36Config();
		config.setLineFrequency(LineFrequency.HZ_60);
		config.setWiring(Wiring.THREE_PHASE_THREE_WIRE);
		config.setSummedPhases(EnumSet.of(Phase.A, Phase.C));
		config.setPhaseCurrentPgaGain(PgaGain.X4);
		config.setVoltageGain(12345);
		config.setCurrentGainA(1);
		config.setCurrentGainB(2);
		config.setCurrentGainC(3);
		Atm90E36 eic = new Atm90E36(new FakeSpiDevice(), config);

		// WHEN
		Atm90E36Config result = eic.config();

		// THEN
		then(result.meteringMode()).as("MMode0").isEqualTo(config.meteringMode());
		then(result.pgaGainMode()).as("MMode1").isEqualTo(config.pgaGainMode());
		then(result.getLineFrequency()).as("line frequency").isEqualTo(LineFrequency.HZ_60);
		then(result.getWiring()).as("wiring").isEqualTo(Wiring.THREE_PHASE_THREE_WIRE);
		then(result.getSummedPhases()).as("summed phases").containsExactly(Phase.A, Phase.C);
		then(result.getCurrentGainI1()).as("I1 PGA gain").isEqualTo(PgaGain.X4);
		then(result.getVoltageGain()).as("voltage gain").isEqualTo(12345);
		then(result.getCurrentGainA()).as("current gain A").isEqualTo(1);
		then(result.getCurrentGainB()).as("current gain B").isEqualTo(2);
		then(result.getCurrentGainC()).as("current gain C").isEqualTo(3);
	}

	@Test
	public void configIsANewObjectEachTimeAndDoesNotWriteBack() {
		// GIVEN
		Atm90E36 eic = new Atm90E36(new FakeSpiDevice());

		// WHEN
		Atm90E36Config first = eic.config();
		first.setLineFrequency(LineFrequency.HZ_60);
		first.setVoltageGain(999);

		// THEN
		then(eic.config()).as("a distinct instance each call").isNotSameAs(first);
		then(eic.config().getLineFrequency()).as("the driver is unaffected")
				.isEqualTo(LineFrequency.HZ_50);
		then(eic.config().getVoltageGain()).as("the driver is unaffected").isEqualTo(50000);
		then(eic.getMeteringMode()).as("the driver register value is unchanged").isEqualTo(0x0087);
	}

	/** Run {@link Atm90E36#configure(Atm90E36Config)} with the given frequency and wiring. */
	private static FakeSpiDevice configuredWith(LineFrequency frequency, Wiring wiring) {
		Atm90E36Config config = new Atm90E36Config();
		config.setLineFrequency(frequency);
		config.setWiring(wiring);
		FakeSpiDevice fake = new FakeSpiDevice();
		new Atm90E36(fake, config).configure(config);
		return fake;
	}

	@Test
	public void sagThresholdFollowsFreq60HzBit() {
		// the sag threshold is selected by MMode0 bit 12 alone, not by matching
		// whole MMode0 words, so it holds across wiring configurations
		then(configuredWith(LineFrequency.HZ_50, Wiring.THREE_PHASE_FOUR_WIRE).registers).as("3P4W 50Hz")
				.containsEntry(SagTh, SAG_TH_50HZ);
		then(configuredWith(LineFrequency.HZ_50, Wiring.THREE_PHASE_THREE_WIRE).registers)
				.as("3P3W 50Hz").containsEntry(SagTh, SAG_TH_50HZ);
		then(configuredWith(LineFrequency.HZ_60, Wiring.THREE_PHASE_FOUR_WIRE).registers).as("3P4W 60Hz")
				.containsEntry(SagTh, SAG_TH_60HZ);
		then(configuredWith(LineFrequency.HZ_60, Wiring.THREE_PHASE_THREE_WIRE).registers)
				.as("3P3W 60Hz").containsEntry(SagTh, SAG_TH_60HZ);
	}

	/** Read {@code SysStatus0} back as {@code sysStatus0} and check for an error. */
	private static boolean calibrationErrorFor(int sysStatus0) {
		FakeSpiDevice fake = new FakeSpiDevice();
		fake.registers.put(SysStatus0, sysStatus0);
		return new Atm90E36(fake).calibrationError();
	}

	@Test
	public void calibrationErrorDetectsChecksumBits() {
		then(calibrationErrorFor(0x0000)).as("no bits set").isFalse();
		then(calibrationErrorFor(0x8000)).as("b15 is reserved, not a checksum error").isFalse();
		then(calibrationErrorFor(1 << 14)).as("CS0Err").isTrue();
		then(calibrationErrorFor(1 << 12)).as("CS1Err").isTrue();
		then(calibrationErrorFor(1 << 10)).as("CS2Err").isTrue();
		then(calibrationErrorFor(1 << 8)).as("CS3Err").isTrue();
	}

}

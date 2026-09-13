/* ==================================================================
 * Atm90E36Register.java - 13/09/2026
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

import org.jspecify.annotations.Nullable;

/**
 * Enumeration of ATM90E36 SPI registers, named as in the datasheet register
 * list (Table-4).
 *
 * @author matt
 * @version 1.0
 */
public enum Atm90E36Register {

	// STATUS AND SPECIAL REGISTERS

	/** Software reset. */
	SoftReset(0x00),

	/** System status 0. */
	SysStatus0(0x01),

	/** System status 1. */
	SysStatus1(0x02),

	/** Function enable 0. */
	FuncEn0(0x03),

	/** Function enable 1. */
	FuncEn1(0x04),

	/** Voltage sag threshold. */
	SagTh(0x08),

	// CONFIGURATION REGISTERS

	/** Configuration registers start command. */
	ConfigStart(0x30),

	/** High word of the PL constant. */
	PLconstH(0x31),

	/** Low word of the PL constant. */
	PLconstL(0x32),

	/** Metering method configuration. */
	MMode0(0x33),

	/** PGA gain configuration. */
	MMode1(0x34),

	/** Active startup power threshold. */
	PStartTh(0x35),

	/** Reactive startup power threshold. */
	QStartTh(0x36),

	/** Apparent startup power threshold. */
	SStartTh(0x37),

	/** Startup power threshold for phase active energy accumulation. */
	PPhaseTh(0x38),

	/** Startup power threshold for phase reactive energy accumulation. */
	QPhaseTh(0x39),

	/** Startup power threshold for phase apparent energy accumulation. */
	SPhaseTh(0x3A),

	/** Checksum 0 ({@code CS0}). */
	CSZero(0x3B),

	// CALIBRATION REGISTERS

	/** Calibration registers start command. */
	CalStart(0x40),

	/** Phase A active power offset. */
	PoffsetA(0x41),

	/** Phase A reactive power offset. */
	QoffsetA(0x42),

	/** Phase B active power offset. */
	PoffsetB(0x43),

	/** Phase B reactive power offset. */
	QoffsetB(0x44),

	/** Phase C active power offset. */
	PoffsetC(0x45),

	/** Phase C reactive power offset. */
	QoffsetC(0x46),

	/** Phase A calibration gain. */
	GainA(0x47),

	/** Phase A calibration phase angle. */
	PhiA(0x48),

	/** Phase B calibration gain. */
	GainB(0x49),

	/** Phase B calibration phase angle. */
	PhiB(0x4A),

	/** Phase C calibration gain. */
	GainC(0x4B),

	/** Phase C calibration phase angle. */
	PhiC(0x4C),

	/** Checksum 1 ({@code CS1}). */
	CSOne(0x4D),

	// FUNDAMENTAL/HARMONIC ENERGY CALIBRATION REGISTERS

	/** Harmonic calibration start command. */
	HarmStart(0x50),

	/** Phase A fundamental active power offset. */
	POffsetAF(0x51),

	/** Phase B fundamental active power offset. */
	POffsetBF(0x52),

	/** Phase C fundamental active power offset. */
	POffsetCF(0x53),

	/** Phase A fundamental active power gain. */
	PGainAF(0x54),

	/** Phase B fundamental active power gain. */
	PGainBF(0x55),

	/** Phase C fundamental active power gain. */
	PGainCF(0x56),

	/** Checksum 2 ({@code CS2}). */
	CSTwo(0x57),

	// MEASUREMENT CALIBRATION REGISTERS

	/** Measurement calibration start command. */
	AdjStart(0x60),

	/** Phase A voltage RMS gain. */
	UgainA(0x61),

	/** Phase A current RMS gain. */
	IgainA(0x62),

	/** Phase A voltage RMS offset. */
	UoffsetA(0x63),

	/** Phase A current RMS offset. */
	IoffsetA(0x64),

	/** Phase B voltage RMS gain. */
	UgainB(0x65),

	/** Phase B current RMS gain. */
	IgainB(0x66),

	/** Phase B voltage RMS offset. */
	UoffsetB(0x67),

	/** Phase B current RMS offset. */
	IoffsetB(0x68),

	/** Phase C voltage RMS gain. */
	UgainC(0x69),

	/** Phase C current RMS gain. */
	IgainC(0x6A),

	/** Phase C voltage RMS offset. */
	UoffsetC(0x6B),

	/** Phase C current RMS offset. */
	IoffsetC(0x6C),

	/** Sampled N line current RMS gain. */
	IgainN(0x6D),

	/** Sampled N line current RMS offset. */
	IoffsetN(0x6E),

	/** Checksum 3 ({@code CS3}). */
	CSThree(0x6F),

	// ENERGY REGISTERS (read-to-clear)

	/** Total forward active energy. */
	APenergyT(0x80),

	/** Phase A forward active energy. */
	APenergyA(0x81),

	/** Phase B forward active energy. */
	APenergyB(0x82),

	/** Phase C forward active energy. */
	APenergyC(0x83),

	/** Total reverse active energy. */
	ANenergyT(0x84),

	/** Phase A reverse active energy. */
	ANenergyA(0x85),

	/** Phase B reverse active energy. */
	ANenergyB(0x86),

	/** Phase C reverse active energy. */
	ANenergyC(0x87),

	/** Total forward reactive energy. */
	RPenergyT(0x88),

	/** Phase A forward reactive energy. */
	RPenergyA(0x89),

	/** Phase B forward reactive energy. */
	RPenergyB(0x8A),

	/** Phase C forward reactive energy. */
	RPenergyC(0x8B),

	/** Total reverse reactive energy. */
	RNenergyT(0x8C),

	/** Phase A reverse reactive energy. */
	RNenergyA(0x8D),

	/** Phase B reverse reactive energy. */
	RNenergyB(0x8E),

	/** Phase C reverse reactive energy. */
	RNenergyC(0x8F),

	/** Total (arithmetic sum) apparent energy. */
	SAenergyT(0x90),

	/** Phase A apparent energy. */
	SenergyA(0x91),

	/** Phase B apparent energy. */
	SenergyB(0x92),

	/** Phase C apparent energy. */
	SenergyC(0x93),

	/** Total (vector sum) apparent energy. */
	SVenergyT(0x94),

	/** Metering status 0. */
	EnStatus0(0x95),

	/** Metering status 1. */
	EnStatus1(0x96),

	// POWER AND POWER FACTOR REGISTERS

	/** Total (all-phase-sum) active power. */
	PmeanT(0xB0),

	/** Phase A active power. */
	PmeanA(0xB1),

	/** Phase B active power. */
	PmeanB(0xB2),

	/** Phase C active power. */
	PmeanC(0xB3),

	/** Total (all-phase-sum) reactive power. */
	QmeanT(0xB4),

	/** Phase A reactive power. */
	QmeanA(0xB5),

	/** Phase B reactive power. */
	QmeanB(0xB6),

	/** Phase C reactive power. */
	QmeanC(0xB7),

	/** Total (arithmetic sum) apparent power ({@code SAmeanT}). */
	SmeanT(0xB8),

	/** Phase A apparent power. */
	SmeanA(0xB9),

	/** Phase B apparent power. */
	SmeanB(0xBA),

	/** Phase C apparent power. */
	SmeanC(0xBB),

	/** Total power factor. */
	PFmeanT(0xBC),

	/** Phase A power factor. */
	PFmeanA(0xBD),

	/** Phase B power factor. */
	PFmeanB(0xBE),

	/** Phase C power factor. */
	PFmeanC(0xBF),

	/** Lower word of total (all-phase-sum) active power. */
	PmeanTLSB(0xC0),

	/** Lower word of phase A active power. */
	PmeanALSB(0xC1),

	/** Lower word of phase B active power. */
	PmeanBLSB(0xC2),

	/** Lower word of phase C active power. */
	PmeanCLSB(0xC3),

	/** Lower word of total (all-phase-sum) reactive power. */
	QmeanTLSB(0xC4),

	/** Lower word of phase A reactive power. */
	QmeanALSB(0xC5),

	/** Lower word of phase B reactive power. */
	QmeanBLSB(0xC6),

	/** Lower word of phase C reactive power. */
	QmeanCLSB(0xC7),

	/** Lower word of total (arithmetic sum) apparent power. */
	SAmeanTLSB(0xC8),

	/** Lower word of phase A apparent power. */
	SmeanALSB(0xC9),

	/** Lower word of phase B apparent power. */
	SmeanBLSB(0xCA),

	/** Lower word of phase C apparent power. */
	SmeanCLSB(0xCB),

	// VOLTAGE AND CURRENT RMS REGISTERS

	/** Phase A voltage RMS. */
	UrmsA(0xD9),

	/** Phase B voltage RMS. */
	UrmsB(0xDA),

	/** Phase C voltage RMS. */
	UrmsC(0xDB),

	/** N line calculated current RMS. */
	IrmsN0(0xDC),

	/** Phase A current RMS. */
	IrmsA(0xDD),

	/** Phase B current RMS. */
	IrmsB(0xDE),

	/** Phase C current RMS. */
	IrmsC(0xDF),

	// FREQUENCY, ANGLE AND TEMPERATURE REGISTERS

	/** Frequency. */
	Freq(0xF8),

	/** Phase A mean phase angle. */
	PAngleA(0xF9),

	/** Phase B mean phase angle. */
	PAngleB(0xFA),

	/** Phase C mean phase angle. */
	PAngleC(0xFB),

	/** Measured temperature. */
	Temp(0xFC);

	private final int address;

	private Atm90E36Register(int address) {
		this.address = address;
	}

	/**
	 * Get the register address.
	 *
	 * @return the address, {@code 0x00}-{@code 0xFF}
	 */
	public int getAddress() {
		return address;
	}

	/**
	 * Get the register with a given address.
	 *
	 * @param address
	 *        the register address
	 * @return the register, or {@code null} if no register has that address
	 */
	public static @Nullable Atm90E36Register forAddress(int address) {
		for ( Atm90E36Register r : values() ) {
			if ( r.address == address ) {
				return r;
			}
		}
		return null;
	}

	/**
	 * Get the register with a given name, ignoring case.
	 *
	 * @param name
	 *        the register name, for example {@code PmeanT}
	 * @return the register, or {@code null} if no register has that name
	 */
	public static @Nullable Atm90E36Register forName(String name) {
		for ( Atm90E36Register r : values() ) {
			if ( r.name().equalsIgnoreCase(name) ) {
				return r;
			}
		}
		return null;
	}

}

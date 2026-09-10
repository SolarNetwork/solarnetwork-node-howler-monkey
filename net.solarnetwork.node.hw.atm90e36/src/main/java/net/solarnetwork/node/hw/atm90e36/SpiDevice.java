/* ==================================================================
 * SpiDevice.java - 10/09/2026
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

/**
 * A minimal abstraction of a Linux SPI character device (for example
 * {@code /dev/spidev0.0}).
 *
 * <p>
 * The contract mirrors the small slice of the {@code spidev} API used by the
 * {@code meter-tool} program: configure the bus, then perform full-duplex
 * transfers where the number of bytes clocked out always equals the number
 * clocked in (the equivalent of {@code spidev}'s {@code xfer2} or
 * {@code spidev2}'s {@code transfer}).
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public interface SpiDevice extends AutoCloseable {

	/**
	 * Open the underlying device and apply the given bus configuration.
	 *
	 * @param mode
	 *        the SPI mode (0-3); mode 3 (CPOL=1, CPHA=1) for the ATM90E36
	 * @param maxSpeedHz
	 *        the maximum bus clock frequency, in Hz
	 * @param bitsPerWord
	 *        the word size, in bits (8 for the ATM90E36)
	 * @throws SpiException
	 *         if the device cannot be opened or configured
	 */
	void open(int mode, int maxSpeedHz, int bitsPerWord);

	/**
	 * Perform a single chip-select-asserted, full-duplex transfer.
	 *
	 * @param tx
	 *        the bytes to clock out
	 * @return the bytes clocked in, the same length as {@code tx}
	 * @throws SpiException
	 *         if the transfer fails
	 */
	byte[] transfer(byte[] tx);

	/**
	 * Close the device, releasing the underlying file descriptor.
	 *
	 * <p>
	 * Never throws; overrides {@link AutoCloseable#close()} to drop the checked
	 * exception.
	 * </p>
	 */
	@Override
	void close();

}

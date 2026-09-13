/* ==================================================================
 * FakeSpiDevice.java - 10/09/2026
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

package net.solarnetwork.node.hw.atm90e36.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.solarnetwork.node.hw.linux.spi.SpiDevice;

/**
 * In-memory {@link SpiDevice} that emulates the ATM90E36 SPI framing, for
 * host-side unit tests (no hardware).
 *
 * <p>
 * It understands the 4-byte frame of datasheet §4.2.1, all MSB first: a 16-bit
 * command of the access type bit (bit 15, set for reads) and a 15-bit register
 * address, of which only the lower 10 bits are decoded, followed by a 16-bit
 * value. Reads are answered from {@link #registers}; writes are recorded in
 * {@link #writes}.
 * </p>
 */
public class FakeSpiDevice implements SpiDevice {

	/** A recorded register write. */
	public static final class Write {

		public final int address;
		public final int value;

		Write(int address, int value) {
			this.address = address;
			this.value = value;
		}

		@Override
		public String toString() {
			return String.format("Write{0x%02X <- 0x%04X}", address, value);
		}
	}

	/** Register address (0x00-0xFF) to 16-bit value returned on read. */
	public final Map<Integer, Integer> registers = new HashMap<>();

	/**
	 * Register addresses this device clears to zero after a read, as the chip
	 * does for its {@code R/C} registers.
	 *
	 * <p>
	 * Pre-populated with the regular energy registers, {@code 80H}-{@code 94H}.
	 * </p>
	 */
	public final Set<Integer> readToClear = new HashSet<>();

	/** All register writes, in order. */
	public final List<Write> writes = new ArrayList<>();

	/** Number of software resets performed. */
	public int softResets = 0;

	/** Raw transmit frames passed to {@link #transfer(byte[])}, in order. */
	public final List<byte[]> txFrames = new ArrayList<>();

	public boolean open = false;
	public int mode = -1;
	public int maxSpeedHz = -1;
	public int bitsPerWord = -1;

	/** Number of {@link #batch(byte[][], int)} handles created. */
	public int batchOpens = 0;

	/** Number of {@code Batch.transfer()} calls across all handles. */
	public int batchTransfers = 0;

	/** Number of {@code Batch.close()} calls. */
	public int batchCloses = 0;

	/**
	 * The {@code settleMicros} argument from the last
	 * {@link #batch(byte[][], int)}.
	 */
	public int lastSettleMicros = -1;

	/**
	 * Constructor.
	 */
	public FakeSpiDevice() {
		super();
		for ( int addr = 0x80; addr <= 0x94; addr++ ) {
			readToClear.add(addr);
		}
	}

	/** The {@code SoftReset} register address. */
	private static final int SOFT_RESET = 0x00;

	/** The value that triggers a software reset. */
	private static final int SOFT_RESET_COMMAND = 0x789A;

	@Override
	public void open(int mode, int maxSpeedHz, int bitsPerWord) {
		this.open = true;
		this.mode = mode;
		this.maxSpeedHz = maxSpeedHz;
		this.bitsPerWord = bitsPerWord;
	}

	@Override
	public byte[] transfer(byte[] tx) {
		txFrames.add(tx.clone());
		if ( tx.length != 4 ) {
			throw new IllegalArgumentException("Expected a 4-byte frame, got " + tx.length);
		}
		int command = ((tx[0] & 0xFF) << 8) | (tx[1] & 0xFF);
		boolean read = (command & 0x8000) != 0;
		int address = command & 0x3FF;

		if ( read ) {
			int v = registers.getOrDefault(address, 0) & 0xFFFF;
			if ( readToClear.contains(address) ) {
				registers.put(address, 0);
			}
			// the chip drives the value on SDO during the last 16 clocks
			return new byte[] { 0, 0, (byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF) };
		}

		int value = ((tx[2] & 0xFF) << 8) | (tx[3] & 0xFF);
		writes.add(new Write(address, value));
		registers.put(address, value);
		if ( address == SOFT_RESET && value == SOFT_RESET_COMMAND ) {
			softReset();
		}
		return new byte[4];
	}

	/**
	 * Reset the registers to their power-on values, as writing
	 * {@code 789AH} to the {@code SoftReset} register does on the chip.
	 */
	private void softReset() {
		softResets++;
		registers.clear();
		for ( int startRegister : new int[] { 0x30, 0x40, 0x50, 0x60 } ) {
			registers.put(startRegister, 0x6886);
		}
	}

	@Override
	public SpiDevice.Batch batch(byte[][] txFrames, int settleMicros) {
		batchOpens++;
		lastSettleMicros = settleMicros;
		return new SpiDevice.Batch() {

			@Override
			public byte[][] transfer() {
				batchTransfers++;
				byte[][] rx = new byte[txFrames.length][];
				for ( int i = 0; i < txFrames.length; i++ ) {
					rx[i] = FakeSpiDevice.this.transfer(txFrames[i]);
				}
				return rx;
			}

			@Override
			public void close() {
				batchCloses++;
			}
		};
	}

	@Override
	public void close() {
		open = false;
	}

}

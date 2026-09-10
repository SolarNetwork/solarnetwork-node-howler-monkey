/* ==================================================================
 * Activator.java - 10/09/2026
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

package net.solarnetwork.node.hw.atm90e36.spi;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Bundle activator that releases the JNA native mappings held by
 * {@link LinuxSpiDevice} when the bundle stops, so the binding class and its
 * per-method native handles do not linger across a reinstall.
 *
 * @author matt
 * @version 1.0
 */
public class Activator implements BundleActivator {

	@Override
	public void start(BundleContext context) {
		// nothing to do: LinuxSpiDevice binds libc lazily, on first use
	}

	@Override
	public void stop(BundleContext context) {
		LinuxSpiDevice.unregisterNativeMethods();
	}

}

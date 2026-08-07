/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * The net bridge's CLIENT half: an ordinary Phonalyser audio backend whose
 * devices live on a headless server reached over the wire format in
 * {@link org.edgo.audio.measure.net.proto} (specified by doc/NET-PROTOCOL.md).
 *
 * <p>In place: the connection (handshake, request/response correlation,
 * keepalive, binary-frame demux), the device manager the runtime SPI discovers,
 * the capture stream that feeds remote PCM into the ordinary pipeline, the
 * playback skeleton spec 7 leaves unimplemented, and the discovery listener.
 * The preferences, the server-list dialog and the generator remoting arrive
 * with the remaining client phases.
 *
 * <p>Nothing here may touch SWT or any GUI package: a backend that needed a
 * toolkit could no longer be discovered through the same runtime SPI every
 * other backend is.
 */
package org.edgo.audio.measure.net.client;

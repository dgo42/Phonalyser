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

package org.edgo.audio.measure.gui.sound;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioDeviceManagerProvider;

/**
 * Registers {@link DyingLaneStub} in this test class path's LOCAL slot.
 *
 * <p>Unlike the net carrier's stub, this claim is CONTESTED: the CLI module puts
 * the real JavaSound backend here too, and the winner is whichever the service
 * loader reaches first (test classes precede the dependency jars).  The tests
 * therefore assert the manager they were handed before they assert anything
 * about it, so a class-path order that ever changed fails as itself instead of
 * as a mystery about a real sound card.
 */
public final class DyingLaneStubProvider implements AudioDeviceManagerProvider {

    /** Public and no-argument for the service loader. */
    public DyingLaneStubProvider() {
    }

    @Override
    public AudioBackendType backendType() {
        return AudioBackendType.JAVASOUND;
    }

    @Override
    public AudioDeviceManager create() {
        return new DyingLaneStub();
    }
}

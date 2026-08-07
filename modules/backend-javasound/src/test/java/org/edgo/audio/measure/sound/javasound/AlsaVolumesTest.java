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

package org.edgo.audio.measure.sound.javasound;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link AlsaVolumes} over the whole chain - a JavaSound mixer name, the
 * card's {@code /proc} stream files, its USB descriptors and an {@code amixer
 * contents} dump - on a host that has none of them, and records what WOULD have
 * been written instead of writing it.
 *
 * <p>The machine is the bench this work was built for ({@link AlsaPortsTest}):
 * card 1 a USB interface whose PCM device 0 is the microphone and headphone
 * pair and whose PCM device 1 is the line pair. The second dump is that same
 * card with its sliders where the bench found them - both line volumes at
 * -29 dB, which is what made 1 V measure as 34 mV through a perfectly
 * calibrated chain.
 */
class AlsaVolumesTest {

    private static final Path PROC = Paths.get("src", "test", "resources", "proc-asound");

    /** The bench card as the operator had left it: the line pair turned down to
     *  -29 dB in both directions, the microphone gain at its top. */
    private static final Path TURNED_DOWN =
            Paths.get("src", "test", "resources", "amixer", "card1-contents-turned-down.txt");

    private static final String LINE_DEVICE = "CB5 [plughw:1,1]";
    private static final String MIC_DEVICE = "CB5 [plughw:1,0]";
    private static final String HDA_DEVICE = "ALC262 Analog [plughw:0,0]";

    private static final int CARD = 1;

    /**
     * An {@link AlsaVolumes} that records what it would have set instead of
     * running {@code amixer} - the one thing in it that changes a machine, and
     * the one thing a test host must never let it do.
     */
    private static final class FixtureVolumes extends AlsaVolumes {

        private final List<String> writes = new ArrayList<>();

        /** True for a driver that refuses the write - a read-only control, a
         *  device pulled between the open and the set. */
        private boolean refuse;

        FixtureVolumes(ProcAsound proc, AlsaPorts ports, AlsaJacks jacks, boolean linux) {
            super(proc, ports, jacks, linux);
        }

        @Override
        boolean set(int card, int numid, String value) {
            writes.add(card + ":" + numid + "=" + value);
            return !refuse;
        }
    }

    /** The device tree this machine's sysfs is built in. */
    @TempDir
    Path sysfs;

    private final ProcAsound proc = new ProcAsound(PROC);

    /** Where the card's directory sits - {@code /sys/class/sound} on the bench. */
    private Path sysRoot;

    /** The sysfs shape the kernel publishes: the card's directory hangs several
     *  levels below the USB device that carries it, and the descriptor blob -
     *  which is what names the socket - sits at the device. */
    @BeforeEach
    void bench() throws IOException {
        Path device = Files.createDirectories(sysfs.resolve("usb1").resolve("1-2"));
        Files.write(device.resolve("descriptors"), new UsbDescriptorFixture().benchBlob());
        sysRoot = device.resolve("interface-0").resolve("sound");
        Files.createDirectories(sysRoot.resolve("card1"));
    }

    // ------------------------------------------------------------ what is set

    @Test
    void anOpenPinsTheOpenedPortsOwnVolume() {
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, TURNED_DOWN));
        volumes.pinToUnity(LINE_DEVICE, true);
        assertEquals(List.of("1:9=31,31"), volumes.writes,
                "the line pair's capture volume, from -29 dB (value 2) to 0 dB (value 31), "
                        + "written per channel so one side cannot be left behind");
    }

    @Test
    void thePlaybackSideIsPinnedTheSameWay() {
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, TURNED_DOWN));
        volumes.pinToUnity(LINE_DEVICE, false);
        assertEquals(List.of("1:24=127,127"), volumes.writes);
    }

    @Test
    void noOtherPortIsTouched() {
        // The harm this rule exists to prevent: the same card's HEADPHONE volume
        // (numid 20) and its MICROPHONE gain (numid 5) are turned down too, and
        // an open of the line pair must leave both exactly where the operator
        // put them - nobody asked about those sockets.
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, TURNED_DOWN));
        volumes.pinToUnity(LINE_DEVICE, true);
        volumes.pinToUnity(LINE_DEVICE, false);
        assertEquals(List.of("1:9=31,31", "1:24=127,127"), volumes.writes);
    }

    @Test
    void unityIsZeroDbAndNotMaximum() {
        // The bench card as it really is: the microphone gain's scale STARTS at
        // 0 dB and runs up to +19.5 dB, so unity is its MINIMUM.  A "100
        // percent" would have added 19.5 dB of gain to a calibrated chain.
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, AlsaJacksTest.CARD1_CONTENTS));
        volumes.pinToUnity(MIC_DEVICE, true);
        assertEquals(List.of("1:5=0"), volumes.writes);
    }

    @Test
    void theStreamsOwnVolumeIsThisDevicesToo() {
        // A card that puts its feature unit on the PCM stream rather than on a
        // socket: every sample of the opened device passes through it whatever
        // socket it came from, however the card spells its name.
        FixtureVolumes volumes = pinner(dump(CARD, List.of(
                "numid=3,iface=MIXER,name='PCM Capture Volume'",
                "  ; type=INTEGER,access=rw---R--,values=2,min=0,max=127,step=0",
                "  : values=69,69",
                "  | dBscale-min=-63.50dB,step=0.50dB,mute=0",
                "numid=7,iface=MIXER,name='Capture Volume'",
                "  ; type=INTEGER,access=rw---R--,values=2,min=0,max=31,step=0",
                "  : values=2,2",
                "  | dBscale-min=-31.00dB,step=1.00dB,mute=0",
                "numid=20,iface=MIXER,name='Headphone Playback Volume'",
                "  ; type=INTEGER,access=rw---R--,values=2,min=0,max=127,step=0",
                "  : values=69,69",
                "  | dBscale-min=-63.50dB,step=0.50dB,mute=0")));
        volumes.pinToUnity(LINE_DEVICE, true);
        assertEquals(List.of("1:3=127,127", "1:7=31,31"), volumes.writes,
                "and the playback control of the same card is not in the capture chain");
    }

    @Test
    void twinStreamControlsAreResolvedByThePcmDeviceNumber() {
        // The bench card that drove this rule: one feature unit per PCM pair,
        // ALL named 'PCM Playback Volume' and told apart only by index= - with
        // no port-named playback control at all, so refusing the twins left the
        // playback chain unpinned entirely.
        FixtureVolumes volumes = pinner(dump(CARD, List.of(
                "numid=3,iface=MIXER,name='PCM Playback Volume'",
                "  ; type=INTEGER,access=rw---R--,values=2,min=0,max=127,step=0",
                "  : values=60,60",
                "  | dBscale-min=-63.50dB,step=0.50dB,mute=0",
                "numid=7,iface=MIXER,name='PCM Playback Volume',index=1",
                "  ; type=INTEGER,access=rw---R--,values=2,min=0,max=127,step=0",
                "  : values=60,60",
                "  | dBscale-min=-63.50dB,step=0.50dB,mute=0")));
        volumes.pinToUnity(LINE_DEVICE, false);
        assertEquals(List.of("1:7=127,127"), volumes.writes,
                "index 1 is plughw:1,1's own feature unit - the kernel assigns stream-"
                        + "control indexes in PCM device order; index 0 is the other pair's");
    }

    // --------------------------------------------------------- what is not set

    @Test
    void aControlAlreadyAtUnityIsNeitherWrittenNorMentioned() {
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, AlsaJacksTest.CARD1_CONTENTS));
        List<String> said = warningsWhile(() -> {
            volumes.pinToUnity(LINE_DEVICE, true);
            volumes.pinToUnity(LINE_DEVICE, false);
        });
        assertEquals(List.of(), volumes.writes, "both line volumes already read 0 dB");
        assertEquals(List.of(), said);
    }

    @Test
    void aNameTheCardPublishesTwiceIsNamedAndLeftAlone() {
        // Two sockets of one word publish the same control name (the second
        // carrying an index=) and nothing in the dump says which of them serves
        // the PCM device being opened.  A guess would move a stranger's socket.
        FixtureVolumes volumes = pinner(dump(CARD, List.of(
                "numid=9,iface=MIXER,name='Line Capture Volume'",
                "  ; type=INTEGER,access=rw---R--,values=2,min=0,max=31,step=0",
                "  : values=2,2",
                "  | dBscale-min=-31.00dB,step=1.00dB,mute=0",
                "numid=17,iface=MIXER,name='Line Capture Volume',index=1",
                "  ; type=INTEGER,access=rw---R--,values=2,min=0,max=31,step=0",
                "  : values=2,2",
                "  | dBscale-min=-31.00dB,step=1.00dB,mute=0")));
        List<String> said = warningsWhile(() -> volumes.pinToUnity(LINE_DEVICE, true));
        assertEquals(List.of(), volumes.writes);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("Line Capture Volume"), said.get(0));
    }

    @Test
    void aDeviceWhoseSocketCannotBeNamedIsLeftAloneAndSaidSo() {
        // An HD-Audio codec publishes no stream file, so nothing says which of
        // the card's volumes is in front of it.  The controls are named in the
        // warning, because an operator whose level reads low needs to know which
        // ones were NOT pinned.
        FixtureVolumes volumes = pinner(new FixtureJacks(0, TURNED_DOWN));
        List<String> said = warningsWhile(() -> volumes.pinToUnity(HDA_DEVICE, true));
        assertEquals(List.of(), volumes.writes);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("Mic Capture Volume")
                && said.get(0).contains("Line Capture Volume"), said.get(0));
    }

    @Test
    void aMachineWithoutAmixerSaysSoOnceAndTheOpenGoesOn() {
        // No alsa-utils, no control device: the dump comes back empty for every
        // card.  It must be said - a volume nobody could read may be attenuating
        // the chain - but not on every open for the life of the process.
        FixtureVolumes volumes = pinner(new FixtureJacks(99, TURNED_DOWN));
        List<String> said = warningsWhile(() -> {
            volumes.pinToUnity(LINE_DEVICE, true);
            volumes.pinToUnity(LINE_DEVICE, false);
        });
        assertEquals(List.of(), volumes.writes);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("alsa-utils"), said.get(0));
    }

    @Test
    void aWriteTheDriverRefusesIsConfessedAndTheOpenGoesOn() {
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, TURNED_DOWN));
        volumes.refuse = true;
        List<String> said = warningsWhile(() -> volumes.pinToUnity(LINE_DEVICE, true));
        assertEquals(List.of("1:9=31,31"), volumes.writes, "it was attempted");
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("Line Capture Volume"), said.get(0));
    }

    @Test
    void aStoppedCaptureRestoresWhatThePinMoved() {
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, TURNED_DOWN));
        volumes.pinToUnity(LINE_DEVICE, true);
        volumes.restore(LINE_DEVICE, true);
        assertEquals(List.of("1:9=31,31", "1:9=2,2"), volumes.writes,
                "the mixer belongs to the operator between opens - what the pin moved "
                        + "comes back as the open found it");
    }

    @Test
    void aRestoreWithoutAPinWritesNothing() {
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, TURNED_DOWN));
        volumes.restore(LINE_DEVICE, true);
        assertEquals(List.of(), volumes.writes);
    }

    @Test
    void aRefusedPinLeavesNothingToRestore() {
        FixtureVolumes volumes = pinner(new FixtureJacks(CARD, TURNED_DOWN));
        volumes.refuse = true;
        volumes.pinToUnity(LINE_DEVICE, true);
        volumes.refuse = false;
        volumes.restore(LINE_DEVICE, true);
        assertEquals(List.of("1:9=31,31"), volumes.writes,
                "a write the driver refused moved nothing, so there is nothing to put back");
    }

    @Test
    void aHostThatIsNotLinuxAsksNothingAndTouchesNothing() {
        FixtureJacks jacks = new FixtureJacks(CARD, TURNED_DOWN);
        FixtureVolumes volumes = new FixtureVolumes(proc, new AlsaPorts(proc, sysRoot, jacks),
                jacks, false);
        volumes.pinToUnity(LINE_DEVICE, true);
        volumes.pinToUnity(LINE_DEVICE, false);
        assertEquals(List.of(), volumes.writes);
        assertEquals(0, jacks.reads(), "there is no amixer to run on a host that is not Linux");
    }

    @Test
    void aMixerWithNoAlsaAddressIsNotAskedAboutAtAll() {
        FixtureJacks jacks = new FixtureJacks(CARD, TURNED_DOWN);
        FixtureVolumes volumes = new FixtureVolumes(proc, new AlsaPorts(proc, sysRoot, jacks),
                jacks, true);
        volumes.pinToUnity("Primary Sound Driver", true);
        volumes.pinToUnity(null, false);
        assertEquals(List.of(), volumes.writes);
        assertEquals(0, jacks.reads());
    }

    // ------------------------------------------------------------- the fixture

    /** The pinner for one card's dump, wired the way the device manager wires
     *  the real one: one jack reader serving both the port names and the
     *  controls. */
    private FixtureVolumes pinner(AlsaJacks jacks) {
        return new FixtureVolumes(proc, new AlsaPorts(proc, sysRoot, jacks), jacks, true);
    }

    /** An {@link AlsaJacks} answering a dump written out in the test itself -
     *  for the control layouts the bench card does not have. */
    private AlsaJacks dump(int card, List<String> lines) {
        return new AlsaJacks() {
            @Override
            List<String> readContents(int forCard) {
                return forCard == card ? lines : List.of();
            }
        };
    }

    /** The WARN (and higher) lines {@link AlsaVolumes} emits while {@code action}
     *  runs - the confessions are half of what this class does, so they are
     *  asserted rather than hoped for. */
    private List<String> warningsWhile(Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(AlsaVolumes.class);
        List<String> messages = new ArrayList<>();
        AbstractAppender appender =
                new AbstractAppender("volume-capture", null, null, true, Property.EMPTY_ARRAY) {
                    @Override
                    public void append(LogEvent event) {
                        if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                            messages.add(event.getMessage().getFormattedMessage());
                        }
                    }
                };
        appender.start();
        logger.addAppender(appender);
        // This module ships no test log configuration, so the default root level
        // is ERROR and every guarded log.warn would be skipped before the
        // appender ever saw it.  Raised on this logger alone, and put back.
        Level was = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            action.run();
        } finally {
            logger.setLevel(was);
            logger.removeAppender(appender);
            appender.stop();
        }
        return messages;
    }
}

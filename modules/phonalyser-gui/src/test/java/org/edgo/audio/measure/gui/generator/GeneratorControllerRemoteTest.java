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

package org.edgo.audio.measure.gui.generator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.sound.RecordingRemoteBackendUi;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.RemoteGenerator;
import org.edgo.audio.measure.wav.PcmFileLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What leaves this machine when the DDS is a network away.
 *
 * <p>Every defect this covers had the same shape: a setting the pane showed as
 * applied, a bench that was never told, and nothing on screen to say so.  None
 * of them is a wrong VALUE - they are commands that were not sent at all - so
 * the assertions are about the recorded command sequence rather than about
 * audio, and they run headless against {@link BenchGeneratorStub}.
 */
class GeneratorControllerRemoteTest {

    /** The bench this test's preferences are keyed to - its own
     *  {@code BackendPrefs} entry, so nothing here touches a real backend's
     *  saved device or rate. */
    private static final String SERVER_ID = "b7e0-bench-generator";
    /** What the client asks the bench's DAC for. */
    private static final int ASKED_RATE = 48000;
    /** What a DAC that only runs at 44.1 kHz answers {@code gen.open} with. */
    private static final int GRANTED_RATE = 44100;
    private static final int BIT_DEPTH = 24;
    private static final double SWEEP_SECONDS = 1.0;
    private static final double TONE_HZ = 1000.0;
    private static final double AMPLITUDE_VRMS = 1.0;
    /** A minimal harmonic {@code .dpd}: the H1 row is the system-delay
     *  reference, the rest are the corrections. */
    private static final String HARMONIC_DPD =
            "harmonic;frequency_hz;level_dbfs;amplitude_pct;phase_deg\n"
                    + "1;1000.0;-3.0;100.0;-90.0\n"
                    + "2;2000.0;-90.0;0.5;30.0\n"
                    + "3;3000.0;-95.0;0.2;-15.0\n";

    @TempDir
    private Path tempDir;

    /** How long the remote watcher thread may take to notice the bench stopped -
     *  generous next to its own poll interval, so a loaded build agent cannot
     *  make this flaky. */
    private static final long AWAIT_STOP_MS = 5_000;
    /** A file size over any plausible limit, and the limit the STUB reports with
     *  it.  Neither is the protocol's own number: what this test proves is that
     *  whatever the seam refuses with reaches the operator, not what the ceiling
     *  is - that belongs to the backend-net test, against the shared constant. */
    private static final long OVERSIZE_BYTES = 64L * 1024 * 1024;
    private static final long STUBBED_LIMIT_BYTES = 50L * 1024 * 1024;
    /** How long the stub stalls inside {@code playFile}, standing in for a slow
     *  link.  Long enough that a start returning promptly proves the transfer is
     *  on its own thread, short enough not to drag the suite. */
    private static final long SLOW_UPLOAD_MS = 1_500;

    /** The client the bench names as holding its DAC - what a refusal has to
     *  quote, because "ask that client to let go" is the operator's next move and
     *  they cannot ask until they are told which one it is. */
    private static final String HOLDER = "Bench tablet";

    private BenchGeneratorStub bench;
    /** The bench UI the device combos read lock state through - staged here so a
     *  refused start can be asked to name the holder. */
    private RecordingRemoteBackendUi remoteUi;
    private GeneratorController controller;
    private BackendKey previousActive;
    private BackendKey previousSelection;
    private GenSignalForm previousForm;
    private double previousFrequencyHz;
    private double previousSweepSeconds;
    private double previousRightFs;
    private boolean previousSnap;
    private String previousDpd;

    @BeforeEach
    void connectTheBench() {
        Preferences prefs = Preferences.instance();
        // The controller writes through the live singleton; transient mode makes
        // sure this test cannot reach the user's preferences file.
        prefs.setTransientMode(true);
        previousSelection    = prefs.getSelectedBackend();
        previousForm         = prefs.getGenSignalForm();
        previousFrequencyHz  = prefs.getGenFrequencyHz();
        previousSweepSeconds = prefs.getGenSweepDurationSec();
        previousRightFs      = prefs.getDacFsVoltageAmplRight();
        previousSnap         = prefs.isGenSnapToFftBin();
        previousDpd          = prefs.getGenDpd();

        AudioBackend audio = AudioBackend.instance();
        previousActive = audio.activeKey();
        bench = assertInstanceOf(BenchGeneratorStub.class, audio.manager(AudioBackendType.NET),
                "META-INF/services/...AudioDeviceManagerProvider must name the stub, or this "
                        + "test would silently exercise the LOCAL generator path");
        bench.reset();
        remoteUi = assertInstanceOf(RecordingRemoteBackendUi.class,
                RemoteBackendRegistry.instance().getUi(),
                "META-INF/services/...RemoteBackendUi must name the recording stub");
        remoteUi.reset();
        // The full remote key: what it IS (QA40X) and where (SERVER_ID); the net
        // carrier is derived, never selected by itself (dual-level rule).
        audio.setActive(BackendKey.of(SERVER_ID, AudioBackendType.QA40X));

        prefs.setSelectedBackend(BackendKey.of(SERVER_ID, AudioBackendType.QA40X));
        BackendPrefs backend = prefs.current();
        backend.setOutputDeviceName(BenchGeneratorStub.DEVICE_NAME);
        backend.setOutputSampleRate(ASKED_RATE);
        backend.setOutputBitDepth(BIT_DEPTH);
        // One grid question at a time: the snap is the far end's job (gen.fftGrid)
        // and asserting it here would only re-test FftBinSnap.
        prefs.setGenSnapToFftBin(false);
        prefs.setGenFrequencyHz(TONE_HZ);
        prefs.setGenAmplitudeVrms(AMPLITUDE_VRMS);
    }

    @AfterEach
    void disconnectTheBench() {
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        if (remoteUi != null) {
            remoteUi.reset();   // one instance serves the JVM: no staged holder may leak
        }
        AudioBackend.instance().setActive(previousActive);
        Preferences prefs = Preferences.instance();
        if (previousSelection != null) {
            prefs.setSelectedBackend(previousSelection);
        }
        prefs.setGenSignalForm(previousForm);
        prefs.setGenFrequencyHz(previousFrequencyHz);
        prefs.setGenSweepDurationSec(previousSweepSeconds);
        prefs.setDacFsVoltageAmplRight(previousRightFs);
        prefs.setGenSnapToFftBin(previousSnap);
        prefs.setGenDpd(GenSignalForm.SINE_COMP, previousDpd == null ? "" : previousDpd);
    }

    @Test
    void everySettingReachesTheBenchAndTheAnalyzersGridGoesLast() {
        Preferences.instance().setGenSignalForm(GenSignalForm.SINE);
        controller = new GeneratorController();

        controller.start();

        assertNull(controller.getLastStartError());
        List<String> sent = bench.names();
        assertEquals("openGenerator", sent.get(0), "the lane is taken before anything is set");
        assertTrue(sent.contains("setForm"));
        assertTrue(sent.contains("setFrequency"));
        assertTrue(sent.contains("setAmplitudeVrms"));
        assertTrue(sent.contains("setDacFsVoltageAmpl"));
        assertTrue(sent.contains("setRightLaneScale"),
                "a card whose two DAC full-scales differ emits an uncalibrated right lane "
                        + "without it, and there is nothing on screen to say so");
        assertEquals("startGenerator", sent.get(sent.size() - 1));
        assertEquals(sent.size() - 2, sent.indexOf("fftGrid"),
                "the grid is snapped against the waveform the far end HOLDS, so it must "
                        + "follow the form and not precede it");
        assertTrue(controller.isRunning());
    }

    /** Commands recorded after {@code mark} - the stub's log is never reset
     *  mid-scenario, because a reset also drops the pushed running state the
     *  bench answers {@code isRunning()} from. */
    private List<BenchGeneratorStub.Command> commandsAfter(int mark) {
        List<BenchGeneratorStub.Command> all = bench.commands();
        return all.subList(Math.min(mark, all.size()), all.size());
    }

    /** The waveform the bench was last told to hold, or null when none was
     *  pushed - what a form switch has to get right. */
    private GenSignalForm formPushedIn(List<BenchGeneratorStub.Command> commands) {
        GenSignalForm pushed = null;
        for (BenchGeneratorStub.Command c : commands) {
            if ("setForm".equals(c.name()) && !c.args().isEmpty()) {
                pushed = (GenSignalForm) c.args().get(0);
            }
        }
        return pushed;
    }

    @Test
    void switchingIntoADualToneWhilePlayingRestartsOnTheNewWaveform() {
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(GenSignalForm.SINE);
        controller = new GeneratorController();
        controller.start();
        assertTrue(controller.isRunning());
        int mark = bench.commands().size();

        prefs.setGenSignalForm(GenSignalForm.DUAL_TONE);

        List<BenchGeneratorStub.Command> tail = commandsAfter(mark);
        List<String> sent = new ArrayList<>();
        for (BenchGeneratorStub.Command c : tail) sent.add(c.name());
        assertTrue(sent.contains("stopGenerator") && sent.contains("startGenerator"),
                "a second tone needs its own DDS accumulator, which only a rebuild "
                        + "stands up - a live form swap cannot: " + sent);
        assertEquals(GenSignalForm.DUAL_TONE, formPushedIn(tail),
                "the rebuild has to carry the JUST-SELECTED waveform; replaying the run "
                        + "the lane was started with re-emits the single tone, and the "
                        + "second tone never appears");
    }

    @Test
    void switchingOutOfADualToneWhilePlayingRestartsOnTheSingleTone() {
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(GenSignalForm.DUAL_TONE);
        controller = new GeneratorController();
        controller.start();
        assertTrue(controller.isRunning());
        int mark = bench.commands().size();

        prefs.setGenSignalForm(GenSignalForm.SINE);

        List<BenchGeneratorStub.Command> tail = commandsAfter(mark);
        List<String> sent = new ArrayList<>();
        for (BenchGeneratorStub.Command c : tail) sent.add(c.name());
        assertTrue(sent.contains("stopGenerator") && sent.contains("startGenerator"),
                "leaving a dual tone tears the second accumulator down, which is a "
                        + "rebuild: " + sent);
        assertEquals(GenSignalForm.SINE, formPushedIn(tail),
                "the rebuild has to carry the JUST-SELECTED waveform; replaying the "
                        + "started run keeps emitting the dual tone and the second tone "
                        + "goes on sounding");
    }

    @Test
    void aCompensatedWaveformCarriesItsSavedCorrectionsToTheBench() throws IOException {
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(GenSignalForm.SINE_COMP);
        prefs.setGenDpd(GenSignalForm.SINE_COMP, writeHarmonicDpd().toString());
        controller = new GeneratorController();

        controller.start();

        assertNull(controller.getLastStartError());
        List<String> sent = bench.names();
        assertTrue(sent.contains("applyCompensation"),
                "compensated sine IS its corrections - a bench that never got them emits a "
                        + "plain sine whose THD is then recorded as if it were predistorted");
        assertTrue(sent.indexOf("applyCompensation") < sent.indexOf("startGenerator"),
                "and they must be in place before the first sample leaves the DAC");
    }

    @Test
    void aCompensatedWaveformWithNoFileIsRefusedRatherThanQuietlyDowngraded() {
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(GenSignalForm.SINE_COMP);
        prefs.setGenDpd(GenSignalForm.SINE_COMP, "");
        controller = new GeneratorController();

        controller.start();

        assertEquals(I18n.t("generator.error.needPredistortion"), controller.getLastStartError(),
                "the same refusal the local path gives - the operator has to be told");
        List<String> sent = bench.names();
        assertFalse(sent.contains("startGenerator"), "nothing may be emitted");
        assertTrue(sent.contains("closeGenerator"),
                "and the lane goes back, or the bench holds a DAC for a client that gave up");
    }

    /**
     * A compensated sine whose {@code .dpd} is gone names the file.
     *
     * <p>The path is a preference: it survives the file being moved, renamed or
     * left on a drive that is not mounted, and the operator then presses Play on a
     * configuration that cannot work.  Opened blind that is an {@code IOException}
     * like any other and the start reported "could not build the signal generator"
     * with a {@code {0}} that only ever holds a DEVICE reason - so what reached the
     * operator was "reason unknown" about a path only they can correct.
     */
    @Test
    void aCompensatedWaveformWhoseFileIsGoneNamesTheMissingFile() {
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(GenSignalForm.SINE_COMP);
        String missing = tempDir.resolve("moved-away.dpd").toString();
        prefs.setGenDpd(GenSignalForm.SINE_COMP, missing);
        controller = new GeneratorController();

        controller.start();

        String reported = controller.getLastStartError();
        assertNotNull(reported, "a start that cannot work must say so");
        assertTrue(reported.contains(missing),
                "the operator can only fix a file they are told the name of: " + reported);
        assertEquals(I18n.t("generator.error.predistortionFileMissing", missing), reported);
        assertFalse(reported.contains(I18n.t("device.error.reason.unknown")),
                "and no device reason is invented for it: " + reported);
        assertFalse(bench.names().contains("startGenerator"), "nothing may be emitted");
    }

    /**
     * A DAC another client is measuring on is refused BY NAME.
     *
     * <p>"The device is in use by another application" is true and useless on a
     * bench that serves several operators - the next move is to ask that client to
     * let go.  The holder is the same fact the device combos already show for a
     * locked bench device, so it is read through the same seam and rendered with
     * the same wording rather than a second phrasing of the same refusal.
     */
    @Test
    void aDacAnotherClientHoldsIsRefusedByThatClientsName() {
        Preferences.instance().setGenSignalForm(GenSignalForm.SINE);
        bench.setRefuseOpen(true);
        remoteUi.setLockedBy(HOLDER);
        controller = new GeneratorController();

        controller.start();

        assertFalse(controller.isRunning(), "a refused lane emits nothing");
        DeviceRef output = AudioBackend.instance().getActiveOutputDevice();
        assertEquals(I18n.t("generator.error.remoteRefused",
                        I18n.t("preferences.device.lockedBy", output.displayName(), HOLDER)),
                controller.getLastStartError());
        assertTrue(controller.getLastStartError().contains(HOLDER),
                "the holder reaches the operator: " + controller.getLastStartError());
    }

    /** The same refusal from a bench that named no holder still says WHY: the
     *  reason the client read off the protocol's own error code, never the
     *  "reason unknown" a code nobody mapped used to leave behind. */
    @Test
    void aRefusedDacWithNoHolderNamedStillSaysItIsInUse() {
        Preferences.instance().setGenSignalForm(GenSignalForm.SINE);
        bench.setRefuseOpen(true);
        controller = new GeneratorController();

        controller.start();

        assertEquals(I18n.t("generator.error.remoteRefused",
                        I18n.t("device.error.reason.inUse")),
                controller.getLastStartError());
    }

    @Test
    void theGrantedRateIsWhatEverySampleCountIsComputedAgainst() {
        bench.setGrantedRate(GRANTED_RATE);
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(GenSignalForm.LINEAR_SWEEP);
        prefs.setGenSweepDurationSec(SWEEP_SECONDS);
        controller = new GeneratorController();

        controller.start();

        assertNull(controller.getLastStartError());
        assertEquals(Integer.valueOf(GRANTED_RATE), bench.firstArg("setSweepDurationSamples"),
                "a duration in seconds turned into samples at the rate that was ASKED for is "
                        + "a sweep of the wrong length - 8.8 % long on a 44.1 kHz DAC");
    }

    @Test
    void aBenchThatStoppedByItselfCanBeStartedAgain() {
        Preferences.instance().setGenSignalForm(GenSignalForm.SINE);
        controller = new GeneratorController();
        controller.start();
        assertTrue(controller.isRunning());

        // The operator disconnected the server in the Preferences dialog and then
        // cancelled it: the tone is gone and nothing was published about it.
        bench.reset();

        assertFalse(controller.isRunning(),
                "the BENCH's answer is the only one a Play button may believe");
        controller.start();
        assertNull(controller.getLastStartError());
        assertTrue(bench.names().contains("openGenerator"),
                "a local flag the bench never confirmed must not refuse every later Play");
    }

    @Test
    void stoppingEndsTheToneBeforeItGivesTheLaneBack() {
        Preferences.instance().setGenSignalForm(GenSignalForm.SINE);
        controller = new GeneratorController();
        controller.start();

        controller.stop();

        List<String> sent = bench.names();
        assertEquals(List.of("stopGenerator", "closeGenerator"),
                sent.subList(sent.size() - 2, sent.size()),
                "the order the local path drains and closes in, and the reason a bench never "
                        + "keeps a DAC for a client that has stopped measuring");
        assertFalse(controller.isRunning());
    }

    // -------------------------------------------------------------------------
    // Playing a file ON the bench (spec §3 upload + 4.5 gen.playFile)
    // -------------------------------------------------------------------------

    @Test
    void aFilePlaysOnAFreshSessionWithNoToneEverStarted() throws IOException {
        // The bench bug: only the TONE's start ever opened a
        // generator session, so playing a file first was refused before a byte
        // left this machine - no upload, no server activity, no dialog.
        controller = new GeneratorController();
        bench.setFileState(new RemoteGenerator.FileState(true, false));
        assertFalse(bench.isGeneratorOpen(), "no tone was started, so no lane is open");

        controller.startFilePlayback(writePlayableFile(), false);

        assertTrue(awaitBenchCommand("openGenerator"),
                "the file path opens the lane itself, exactly as the local play "
                        + "loop opens its own line");
        assertTrue(awaitBenchCommand("playFile"),
                "and the file really reaches the bench");
        assertNull(controller.getFilePlayError(), "with nothing refused");
        assertTrue(bench.isGeneratorOpen(), "the session it opened stays open");
    }

    @Test
    void theLaneIsAlwaysOpenBeforeTheFileIsCommanded() throws IOException {
        // Taking the lane from a running tone closes it (GeneratorLane.stop
        // releases the remote session), so the file path must re-take it - the
        // invariant is not "opened once" but "open at the moment gen.playFile
        // is sent", which is the thing the bench refused on.
        controller = new GeneratorController();
        bench.setFileState(new RemoteGenerator.FileState(true, false));
        controller.start();                       // a tone is playing on the lane

        controller.startFilePlayback(writePlayableFile(), false);

        assertTrue(awaitBenchCommand("playFile"), "the file plays");
        List<String> sent = bench.names();
        assertTrue(sent.lastIndexOf("openGenerator") < sent.lastIndexOf("playFile"),
                "the lane is opened before the file is commanded, not after: " + sent);
        assertTrue(sent.lastIndexOf("closeGenerator") < sent.lastIndexOf("openGenerator"),
                "and the close that took the lane from the tone comes first: " + sent);
    }

    @Test
    void aFileIsUploadedToTheBenchInsteadOfBeingRefused() throws IOException {
        controller = new GeneratorController();
        File file = writePlayableFile();
        bench.setFileState(new RemoteGenerator.FileState(true, false));

        controller.startFilePlayback(file, true);

        assertTrue(awaitBenchCommand("playFile"),
                "the bench was actually told to play - not merely a flag set here");
        assertNull(controller.getFilePlayError(),
                "a bench plays files now - the old refusal is gone");
        assertArrayEquals(Files.readAllBytes(file.toPath()), bench.getPlayedFile(),
                "the bench got the file's bytes, unchanged");
        assertTrue(bench.isPlayedFileLoop(), "and the loop flag the operator ticked");
        assertEquals(PcmFileLoader.MIME_WAV, bench.getPlayedFileMimeType(),
                "the type comes from the decoder authority for the picked file, so "
                        + "the bench decodes by what it IS");
    }

    @Test
    void theDeclaredTypeFollowsThePickedFilesExtension() throws IOException {
        controller = new GeneratorController();
        bench.setFileState(new RemoteGenerator.FileState(true, false));

        controller.startFilePlayback(writePlayableFile("clip.flac"), false);

        assertTrue(awaitBenchCommand("playFile"), "the bench was asked");
        assertEquals(PcmFileLoader.MIME_FLAC, bench.getPlayedFileMimeType(),
                "a .flac is declared audio/flac - the case the bench's content "
                        + "sniff cannot settle when an ID3 tag precedes the marker");
    }

    @Test
    void stoppingAFileOnTheBenchCommandsTheFarEnd() throws IOException {
        controller = new GeneratorController();
        bench.setFileState(new RemoteGenerator.FileState(true, false));
        controller.startFilePlayback(writePlayableFile(), false);
        assertTrue(awaitBenchCommand("playFile"), "playing on the bench first");

        controller.stopFilePlayback();

        assertTrue(awaitTrue(() -> bench.getStopFileCalls() == 1),
                "stop reaches across the wire exactly once, not just the local flag");
    }

    @Test
    void aFileThatRunsOutOnTheBenchClearsThePlayingFlag() throws IOException {
        controller = new GeneratorController();
        bench.setFileState(new RemoteGenerator.FileState(true, false));
        controller.startFilePlayback(writePlayableFile(), false);
        assertTrue(awaitBenchCommand("playFile"), "playing on the bench first");

        // The end-of-file edge a non-looping file reaches on its own.  playing
        // stays TRUE so only the `finished` clause can end the watch - a client
        // that dropped it would hang here rather than pass by accident.
        bench.setFileState(new RemoteGenerator.FileState(true, true));

        assertTrue(awaitStopped(),
                "a file that ran out on the bench stops the player here too, with "
                        + "nobody pressing anything");
        assertEquals(0, bench.getStopFileCalls(),
                "and a file that ended by itself is never commanded to stop");
    }

    @Test
    void aBenchThatRefusesTheFileLeavesTheControllerStoppedAndSaysWhy() throws IOException {
        controller = new GeneratorController();
        bench.setRefuseFile(new IllegalStateException("the bench's store is full"));

        controller.startFilePlayback(writePlayableFile(), false);

        assertTrue(awaitBenchCommand("playFile"),
                "the bench really was asked - this is a refusal, not an early return");
        assertTrue(awaitStopped(), "nothing is playing after a refusal");
        assertNotNull(controller.getFilePlayError(),
                "and the operator is told, in their language - the bench's own words "
                        + "stay in the log");
    }

    @Test
    void anAsyncFailureIsAnnouncedAndTheMessageIsClaimableExactlyOnce() throws IOException {
        controller = new GeneratorController();
        bench.setRefuseFile(new IllegalStateException("the bench's store is full"));
        List<Void> stopped = new ArrayList<>();
        Consumer<Void> listener = stopped::add;
        MessageBus.instance().subscribe(Events.FILE_PLAY_STOPPED, listener);
        try {
            controller.startFilePlayback(writePlayableFile(), false);

            // The failure lands on the play thread, long after the click returned:
            // FILE_PLAY_STOPPED is the ONLY route by which it can reach a dialog.
            assertTrue(awaitTrue(() -> !stopped.isEmpty()),
                    "the remote path announces its end exactly as the local one does");
            String first = controller.takeFilePlayErrorForReport();
            assertNotNull(first, "and the message is there to be claimed");
            assertNull(controller.takeFilePlayErrorForReport(),
                    "claimed ONCE - a later re-sync must not repeat a dialog the "
                            + "operator has already dismissed");
        } finally {
            MessageBus.instance().unsubscribe(Events.FILE_PLAY_STOPPED, listener);
        }
    }

    @Test
    void anOversizeFileIsReportedWithItsSizeAndTheBenchesLimit() throws IOException {
        controller = new GeneratorController();
        bench.setRefuseFile(new RemoteGenerator.FileTooLargeException(
                "too big", OVERSIZE_BYTES, STUBBED_LIMIT_BYTES));

        controller.startFilePlayback(writePlayableFile(), false);

        assertTrue(awaitStopped(), "an over-size file leaves nothing playing");
        String reported = controller.getFilePlayError();
        assertNotNull(reported, "an over-size file is refused with a message");
        // Both numbers, to one decimal, and NOT the heap message's boilerplate:
        // the old key's own "64-bit Java" made a bare "64" match by accident.
        assertTrue(reported.contains("64.0"), "the file's own size in MB: " + reported);
        assertTrue(reported.contains("50.0"), "and the bench's limit in MB: " + reported);
        assertFalse(reported.contains("-Xmx"),
                "a server-side cap is not a Java heap problem: " + reported);
    }

    @Test
    void stoppingDuringAnUploadNeverStartsPlayingAndBlocksNothing() throws IOException {
        controller = new GeneratorController();
        bench.setPlayFileDelayMs(SLOW_UPLOAD_MS);

        long startedAt = System.nanoTime();
        controller.startFilePlayback(writePlayableFile(), false);
        long returnedAfterMs = (System.nanoTime() - startedAt) / 1_000_000L;

        // The whole point of the asynchronous start: the caller is the UI thread
        // and must come back at once, with the transfer still running behind it.
        assertTrue(returnedAfterMs < SLOW_UPLOAD_MS / 2,
                "start returned in " + returnedAfterMs + " ms - the upload must not "
                        + "run on the caller's thread");

        controller.stopFilePlayback();   // must not block on the upload either

        assertTrue(awaitStopped(), "nothing is left playing");
        assertTrue(awaitTrue(() -> bench.getStopFileCalls() >= 1),
                "the bench is told to stop, because by then it may hold the file");
    }

    @Test
    void theUploadNoticeIsRaisedAndAlwaysTakenDownAgain() throws IOException {
        controller = new GeneratorController();
        bench.setFileState(new RemoteGenerator.FileState(true, false));
        List<String> notices = new ArrayList<>();
        Consumer<Void> up = ignored -> notices.add("started");
        Consumer<Void> down = ignored -> notices.add("finished");
        MessageBus.instance().subscribe(Events.FILE_UPLOAD_STARTED, up);
        MessageBus.instance().subscribe(Events.FILE_UPLOAD_FINISHED, down);
        try {
            controller.startFilePlayback(writePlayableFile(), false);
            assertTrue(awaitTrue(() -> notices.size() >= 2), "both ends announced");
            assertEquals(List.of("started", "finished"), notices,
                    "the notice goes up before the transfer and comes down after it");

            // And on the FAILURE path too - a notice that outlives its transfer
            // is worse than none at all.
            notices.clear();
            bench.setRefuseFile(new IllegalStateException("the bench's store is full"));
            controller.stopFilePlayback();
            controller.startFilePlayback(writePlayableFile(), false);

            assertTrue(awaitTrue(() -> notices.size() >= 2),
                    "a refused upload still takes its notice down");
            assertEquals(List.of("started", "finished"), notices);
        } finally {
            MessageBus.instance().unsubscribe(Events.FILE_UPLOAD_STARTED, up);
            MessageBus.instance().unsubscribe(Events.FILE_UPLOAD_FINISHED, down);
        }
    }

    @Test
    void togglingLoopMidPlayReachesTheBench() throws IOException {
        controller = new GeneratorController();
        bench.setFileState(new RemoteGenerator.FileState(true, false));
        controller.startFilePlayback(writePlayableFile(), false);
        assertTrue(awaitBenchCommand("playFile"), "playing on the bench first");
        assertNull(bench.getPushedFileLoop(), "nothing pushed yet");

        Preferences.instance().setGenPlayFromLoop(true);

        assertTrue(awaitTrue(() -> Boolean.TRUE.equals(bench.getPushedFileLoop())),
                "ticking loop mid-play reaches the far end, so the lap that is "
                        + "ending repeats instead of only the next start looping");

        Preferences.instance().setGenPlayFromLoop(false);

        assertTrue(awaitTrue(() -> Boolean.FALSE.equals(bench.getPushedFileLoop())),
                "and unticking reaches it too - the lap FINISHES rather than "
                        + "being cut off");
    }

    @Test
    void theNoticeCoversTheLaneOpenWaitNotJustTheTransfer() throws Exception {
        // The bench case: on a LAN the HTTP transfer is a blink, while opening an
        // exclusive device takes seconds.  A notice that spanned only the put
        // appeared and vanished AFTER the wait it was meant to explain, so the
        // operator saw five seconds of nothing.
        controller = new GeneratorController();
        bench.setFileState(new RemoteGenerator.FileState(true, false));
        List<String> notices = new ArrayList<>();
        Consumer<Void> up = ignored -> notices.add("started");
        Consumer<Void> down = ignored -> notices.add("finished");
        CountDownLatch open = new CountDownLatch(1);
        bench.setOpenGate(open);
        MessageBus.instance().subscribe(Events.FILE_UPLOAD_STARTED, up);
        MessageBus.instance().subscribe(Events.FILE_UPLOAD_FINISHED, down);
        try {
            controller.startFilePlayback(writePlayableFile(), false);
            assertTrue(bench.getOpenEntered().await(AWAIT_STOP_MS, TimeUnit.MILLISECONDS),
                    "the lane open really started");

            // The load-bearing assertion: the notice is ALREADY up while the open
            // is still blocked, so it covers the wait rather than following it.
            assertTrue(awaitTrue(() -> notices.contains("started")),
                    "the notice is on screen during the lane-open wait");
            assertFalse(notices.contains("finished"),
                    "and has not come down yet - the open has not returned");

            open.countDown();

            assertTrue(awaitBenchCommand("playFile"), "the file is commanded");
            assertTrue(awaitTrue(() -> notices.contains("finished")),
                    "and the notice comes down once playback is commanded");
            assertEquals(List.of("started", "finished"), notices,
                    "exactly one notice, spanning prepare-and-upload");
        } finally {
            MessageBus.instance().unsubscribe(Events.FILE_UPLOAD_STARTED, up);
            MessageBus.instance().unsubscribe(Events.FILE_UPLOAD_FINISHED, down);
        }
    }

    @Test
    void aRefusedLaneOpenStillTakesTheNoticeDown() throws Exception {
        // The earliest exit of the new span: the open itself is refused, before
        // any byte is read.  A notice left up here would sit over a dead session.
        controller = new GeneratorController();
        bench.setRefuseOpen(true);
        List<String> notices = new ArrayList<>();
        Consumer<Void> up = ignored -> notices.add("started");
        Consumer<Void> down = ignored -> notices.add("finished");
        MessageBus.instance().subscribe(Events.FILE_UPLOAD_STARTED, up);
        MessageBus.instance().subscribe(Events.FILE_UPLOAD_FINISHED, down);
        try {
            controller.startFilePlayback(writePlayableFile(), false);

            assertTrue(awaitTrue(() -> notices.size() >= 2), "both ends announced");
            assertEquals(List.of("started", "finished"), notices,
                    "a refused open takes the notice down with it");
            assertTrue(bench.names().contains("openGenerator"), "the open was tried");
            assertFalse(bench.names().contains("playFile"),
                    "and nothing was uploaded - there was no lane to play on");
        } finally {
            MessageBus.instance().unsubscribe(Events.FILE_UPLOAD_STARTED, up);
            MessageBus.instance().unsubscribe(Events.FILE_UPLOAD_FINISHED, down);
        }
    }

    @Test
    void aLoopToggledDuringTheUploadStillReachesTheBench() throws Exception {
        // The bench case: a 45 MB file to a server over Wi-Fi is minutes of
        // upload, and the operator ticks "In loop" while the progress notice is
        // up.  Until this was fixed the tick was dropped at BOTH ends - the value
        // captured at the click won here, and the live push reached a bench that
        // had no file session yet, which ignores it by design.
        controller = new GeneratorController();
        Preferences.instance().setGenPlayFromLoop(false);
        bench.setFileState(new RemoteGenerator.FileState(true, false));
        CountDownLatch upload = new CountDownLatch(1);
        bench.setUploadGate(upload);            // a latch, so the ordering is exact

        controller.startFilePlayback(writePlayableFile(), false);
        assertTrue(bench.getUploadEntered().await(AWAIT_STOP_MS, TimeUnit.MILLISECONDS),
                "the upload really started before the toggle");

        Preferences.instance().setGenPlayFromLoop(true);   // ticked mid-transfer
        upload.countDown();                                // the bytes land

        assertTrue(awaitTrue(() -> Boolean.TRUE.equals(bench.getPushedFileLoop())),
                "the bench ends up looping as the operator last chose - the "
                        + "toggle is re-asserted once the far end has a file "
                        + "session to apply it to");
        assertFalse(bench.isPlayedFileLoop(),
                "the gen.playFile command itself carried the flag as it stood "
                        + "when the transfer began - it cannot carry a decision "
                        + "made after it was sent, which is why the re-assert "
                        + "above is the mechanism that has to work");
    }

    /** Waits until {@code name} has reached the stubbed bench. */
    private boolean awaitBenchCommand(String name) {
        return awaitTrue(() -> bench.names().contains(name));
    }

    private boolean awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_STOP_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Waits for the remote watcher thread to notice the bench stopped. */
    private boolean awaitStopped() {
        long deadline = System.nanoTime() + AWAIT_STOP_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (!controller.isFilePlaying()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** A small file on disk for the player to pick up.  Its CONTENT never has to
     *  decode here: the client uploads bytes and the bench decodes them, so what
     *  this test can prove is that the right bytes travelled. */
    private File writePlayableFile() throws IOException {
        return writePlayableFile("phonalyser-remote-play.wav");
    }

    private File writePlayableFile(String name) throws IOException {
        Path file = tempDir.resolve(name);
        byte[] content = new byte[2048];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 13 + 5);
        }
        Files.write(file, content);
        return file.toFile();
    }

    @Test
    void theWizardsApplyReachesTheBench() throws IOException {
        Preferences.instance().setGenSignalForm(GenSignalForm.SINE);
        controller = new GeneratorController();
        controller.start();
        bench.reset();

        controller.loadCorrectionsFromFile(writeHarmonicDpd().toString());

        assertTrue(bench.names().contains("applyCompensation"),
                "the .dpd sits on the operator's machine and the bench cannot open it, so the "
                        + "TABLES have to travel - the wizard's commit is otherwise a no-op");
    }

    @Test
    void recalibratingTheRightLaneReachesTheBenchWhileTheToneIsPlaying() {
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(GenSignalForm.SINE);
        controller = new GeneratorController();
        controller.start();
        bench.reset();

        prefs.setDacFsVoltageAmplRight(prefs.getDacFsVoltageAmpl() / 2.0);

        assertNotNull(bench.firstArg("setRightLaneScale"),
                "the lane the scale belongs to is the far end's - recalibrating a card must "
                        + "take effect on the tone that is playing, as it does locally");
    }

    /** A minimal single-tone predistortion file on disk - what
     *  {@code prefs.genDpd} points at in the ordinary way of using
     *  predistortion. */
    private Path writeHarmonicDpd() throws IOException {
        Path file = tempDir.resolve("bench.dpd");
        Files.writeString(file, HARMONIC_DPD);
        return file;
    }
}

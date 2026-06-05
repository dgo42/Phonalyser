package org.edgo.audio.measure.gui.fft;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.fft.FllAutotuneSession.Phase;
import org.edgo.audio.measure.gui.fft.FllAutotuneSession.Rule;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.Preferences;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Modal wizard that auto-tunes the generator frequency-lock {@link
 * FrequencyLockLoop} by relay feedback (Åström–Hägglund → Ziegler–Nichols).
 *
 * <p>While open it installs a {@link FllAutotuneSession} into the live
 * {@link FftView} (which routes the single-tone correction to it), pumps
 * the parent display loop so FFT results keep flowing, and reflects the
 * run live: the current FFT settings, frame-capture % and average count,
 * the limit-cycle waveform, and — once measured — {@code Tu}, {@code Ku}
 * and the resulting {@code Kp/Ki/Kd}.  Capture re-syncs (overrun /
 * discontinuity) are fed to the session (which discards the tainted cycle)
 * and surfaced as a warning.  <b>Apply &amp; Save</b> writes the gains to
 * {@link Preferences}; closing or stopping restores the PID.
 *
 * <p>UI-thread confined: {@link FllAutotuneSession#process} runs from the
 * FFT-result dispatch and the dialog's timer / resync handler run on the
 * same thread, so the shared session needs no synchronization.
 */
@Log4j2
public final class PidAutotuneDialog {

    private static final int TIMER_MS = 90;

    private final Shell   dialog;
    private final Display display;
    private final FftView view;

    private FllAutotuneSession session;   // installed while a run is in progress / held
    private boolean timerArmed;

    private Combo  ruleCombo;
    private Label  fftSizeLbl, fftRateLbl, fftFrameLbl, fftWindowLbl;
    private Label  measuredLbl, phaseLbl, progressLbl, captureLbl, cyclesLbl, relayLbl;
    private Label  tuLbl, kuLbl, gainsLbl, warnLbl;
    private Button startBtn, stopBtn, applyBtn;
    private Canvas wave;

    private final double[] wErr = new double[512];
    private final double[] wOut = new double[512];

    /** Feeds capture re-syncs to the running session so it drops the
     *  tainted limit cycle (and the warning surfaces on the next refresh). */
    private final Consumer<String> onResync = key -> {
        if (session != null && session.isActive()) session.onResync(key);
    };

    public PidAutotuneDialog(Shell parent, FftView view) {
        this.view    = view;
        this.display = parent.getDisplay();
        dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText(I18n.t("fft.autotune.title"));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14; gl.marginHeight = 14; gl.verticalSpacing = 10;
        dialog.setLayout(gl);

        Label intro = new Label(dialog, SWT.WRAP);
        intro.setText(I18n.t("fft.autotune.intro"));
        GridData introGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        introGd.widthHint = 460;
        intro.setLayoutData(introGd);

        buildFftGroup();
        buildRuleRow();
        buildWaveform();
        buildProgressGroup();
        buildButtons();

        MessageBus.instance().subscribe(Events.FFT_CAPTURE_RESYNC, onResync);
        dialog.addDisposeListener(e -> cleanup());
        refresh();
    }

    // ------------------------------------------------------------------ build

    private void buildFftGroup() {
        Group g = new Group(dialog, SWT.NONE);
        g.setText(I18n.t("fft.autotune.group.fft"));
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        fftSizeLbl   = info(g);
        fftRateLbl   = info(g);
        fftFrameLbl  = info(g);
        fftWindowLbl = info(g);
    }

    private void buildRuleRow() {
        Composite row = new Composite(dialog, SWT.NONE);
        GridLayout rl = new GridLayout(2, false);
        rl.marginWidth = 0; rl.marginHeight = 0;
        row.setLayout(rl);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label l = new Label(row, SWT.NONE);
        l.setText(I18n.t("fft.autotune.rule"));
        ruleCombo = new Combo(row, SWT.READ_ONLY);
        for (Rule r : Rule.values()) ruleCombo.add(I18n.t("fft.autotune.rule." + r.name()));
        ruleCombo.select(0);   // IMC — the deadtime-aware no-overshoot default
        ruleCombo.setToolTipText(I18n.t("fft.autotune.rule.tooltip"));
    }

    private void buildWaveform() {
        wave = new Canvas(dialog, SWT.DOUBLE_BUFFERED | SWT.BORDER);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.heightHint = 120;
        gd.widthHint  = 460;
        wave.setLayoutData(gd);
        wave.addPaintListener(e -> paintWave(e.gc, wave.getClientArea()));
    }

    private void buildProgressGroup() {
        Group g = new Group(dialog, SWT.NONE);
        g.setText(I18n.t("fft.autotune.group.progress"));
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        measuredLbl = info(g);
        phaseLbl    = info(g);
        progressLbl = info(g);
        captureLbl  = info(g);
        cyclesLbl   = info(g);
        relayLbl    = info(g);
        tuLbl       = info(g);
        kuLbl       = info(g);
        gainsLbl    = info(g);
        warnLbl     = info(g);
        warnLbl.setForeground(display.getSystemColor(SWT.COLOR_RED));
    }

    private void buildButtons() {
        Composite buttons = new Composite(dialog, SWT.NONE);
        GridLayout bl = new GridLayout(4, false);
        bl.marginWidth = 0; bl.marginHeight = 0;
        buttons.setLayout(bl);
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        startBtn = new Button(buttons, SWT.PUSH);
        startBtn.setText(I18n.t("fft.autotune.start"));
        startBtn.addListener(SWT.Selection, e -> onStart());

        stopBtn = new Button(buttons, SWT.PUSH);
        stopBtn.setText(I18n.t("fft.autotune.stop"));
        stopBtn.setEnabled(false);
        stopBtn.addListener(SWT.Selection, e -> onStop());

        applyBtn = new Button(buttons, SWT.PUSH);
        applyBtn.setText(I18n.t("fft.autotune.apply"));
        applyBtn.setEnabled(false);
        applyBtn.addListener(SWT.Selection, e -> onApply());

        Button close = new Button(buttons, SWT.PUSH);
        close.setText(I18n.t("common.close"));
        close.addListener(SWT.Selection, e -> dialog.close());
    }

    private Label info(Composite parent) {
        Label l = new Label(parent, SWT.NONE);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    // --------------------------------------------------------------- lifecycle

    /** Opens modally and pumps the parent display loop until closed — the
     *  pump keeps FFT results, trim publishes and the autotune session
     *  running underneath the dialog. */
    public void open() {
        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        armTimer();
        while (!dialog.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    private void armTimer() {
        if (timerArmed) return;
        timerArmed = true;
        tick();
    }

    private void tick() {
        if (dialog.isDisposed()) return;
        refresh();
        display.timerExec(TIMER_MS, this::tick);
    }

    private void cleanup() {
        MessageBus.instance().unsubscribe(Events.FFT_CAPTURE_RESYNC, onResync);
        if (session != null) {
            view.setAutotuneSession(null);   // restore + re-lock the PID
            session = null;
        }
    }

    // --------------------------------------------------------------- actions

    private void onStart() {
        if (!ready()) { warnLbl.setText(I18n.t("fft.autotune.notReady")); return; }
        Rule rule = Rule.values()[Math.max(0, ruleCombo.getSelectionIndex())];
        // bias 0: setAutotuneSession resets the PID, so the relay starts from
        // the snapped bin — a clean operating point regardless of prior gains.
        session = new FllAutotuneSession(0.0, rule);
        view.setAutotuneSession(session);
        ruleCombo.setEnabled(false);
        warnLbl.setText("");
        refresh();
    }

    private void onStop() {
        view.setAutotuneSession(null);
        session = null;
        ruleCombo.setEnabled(true);
        refresh();
    }

    private void onApply() {
        if (session == null || !session.isDone()) return;
        Preferences p = Preferences.instance();
        p.setFftFllKp(session.getKp());
        p.setFftFllKi(session.getKi());
        p.setFftFllKd(session.getKd());
        p.save();
        if (log.isInfoEnabled()) {
            log.info("FLL PID autotune applied: Kp={} Ki={} Kd={} (Ku={}, Tu={}s, rule={})",
                    fmt(session.getKp()), fmt(session.getKi()), fmt(session.getKd()),
                    fmt(session.getUltimateGainKu()), fmt(session.getMeasuredTuSeconds()),
                    session.getRule());
        }
        view.setAutotuneSession(null);       // re-lock with the fresh gains
        session = null;
        dialog.close();
    }

    private boolean ready() {
        return view != null && view.isRunning() && view.isGeneratorActive();
    }

    // --------------------------------------------------------------- refresh

    private void refresh() {
        if (dialog.isDisposed()) return;
        refreshFftSettings();

        // Live measured fundamental + error — the key diagnostic for whether
        // the lock is actually tracking the relay dither.
        double measured = session != null && Double.isFinite(session.getMeasuredFrequencyHz())
                ? session.getMeasuredFrequencyHz()
                : (view.getLastResult() != null ? view.getLastResult().fundamentalHzRefined : Double.NaN);
        String errStr = session != null && Double.isFinite(session.getLastErrHz())
                ? fmtSigned(session.getLastErrHz()) : "—";
        measuredLbl.setText(I18n.t("fft.autotune.measured", fmtHz(measured), errStr));

        // Frame capture % + averages are live regardless of run state.
        captureLbl.setText(I18n.t("fft.autotune.capture",
                fmt0(view.getNextFrameProgress() * 100.0), view.getCompletedAnalyses()));

        // A recording that stopped under us aborts an in-progress run.
        if (session != null && session.isActive() && !ready()) {
            onStop();
            warnLbl.setText(I18n.t("fft.autotune.notReady"));
            return;
        }

        Phase phase = session == null ? null : session.getPhase();
        phaseLbl.setText(I18n.t("fft.autotune.phase", phaseLabel(phase)));
        progressLbl.setText(I18n.t("fft.autotune.progress",
                fmt0((session == null ? 0.0 : session.getProgressFraction()) * 100.0)));
        cyclesLbl.setText(I18n.t("fft.autotune.cycles",
                session == null ? 0 : session.getCyclesObserved(),
                session == null ? 0 : session.getTargetCycles()));
        relayLbl.setText(session != null && session.getRelayAmpHz() > 0
                ? I18n.t("fft.autotune.relay", fmt(session.getRelayAmpHz()), fmt(session.getHysteresisHz()))
                : I18n.t("fft.autotune.relay", "—", "—"));

        boolean done = session != null && session.isDone();
        tuLbl.setText(I18n.t("fft.autotune.result.tu", done ? fmtSeconds(session.getMeasuredTuSeconds()) : "—"));
        kuLbl.setText(I18n.t("fft.autotune.result.ku", done ? fmt(session.getUltimateGainKu()) : "—"));
        gainsLbl.setText(done
                ? I18n.t("fft.autotune.result.gains", fmt(session.getKp()), fmt(session.getKi()), fmt(session.getKd()))
                : I18n.t("fft.autotune.result.gains", "—", "—", "—"));

        if (session != null && session.isFailed()) {
            warnLbl.setText(I18n.t(session.getFailMessage()));
        } else if (session != null && session.getResyncCount() > 0) {
            warnLbl.setText(I18n.t("fft.autotune.warn.count", session.getResyncCount()));
        }

        boolean active = session != null && session.isActive();
        startBtn.setEnabled(!active && ready());
        stopBtn.setEnabled(active);
        applyBtn.setEnabled(done);
        if (session == null || session.isFailed() || done) ruleCombo.setEnabled(!active);

        wave.redraw();
    }

    private void refreshFftSettings() {
        FftResult r = view.getLastResult();
        Preferences p = Preferences.instance();
        int    size = r != null ? r.fftSize    : p.getFftLength();
        int    rate = r != null ? r.sampleRate : 0;
        double hzBin = r != null ? r.freqResolution : (rate > 0 ? rate / (double) size : 0);
        fftSizeLbl.setText(I18n.t("fft.autotune.fft.size", String.valueOf(size), fmt(hzBin)));
        fftRateLbl.setText(I18n.t("fft.autotune.fft.rate", String.valueOf(rate)));

        FftOverlap ov = p.getFftOverlap();
        double frame = rate > 0 ? size / (double) rate : 0;
        double hop   = frame * (1.0 - ov.fraction);
        fftFrameLbl.setText(I18n.t("fft.autotune.fft.frame",
                fmtSeconds(frame), fmtSeconds(hop), ov.label));
        fftWindowLbl.setText(I18n.t("fft.autotune.fft.window", p.getFftWindow()));
    }

    private static String phaseLabel(Phase p) {
        if (p == null) return I18n.t("fft.autotune.phase.idle");
        switch (p) {
            case NOISE:   return I18n.t("fft.autotune.phase.noise");
            case SETTLE:  return I18n.t("fft.autotune.phase.settle");
            case MEASURE: return I18n.t("fft.autotune.phase.measure");
            case DONE:    return I18n.t("fft.autotune.phase.done");
            case FAILED:  return I18n.t("fft.autotune.phase.failed");
            default:      return I18n.t("fft.autotune.phase.idle");
        }
    }

    // --------------------------------------------------------------- waveform

    private void paintWave(GC gc, Rectangle a) {
        gc.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.fillRectangle(a);
        int n = session == null ? 0 : session.snapshotWaveform(wErr, wOut);
        if (n < 2) {
            gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
            Point ext = gc.textExtent("—");
            gc.drawText("—", a.x + (a.width - ext.x) / 2, a.y + (a.height - ext.y) / 2, true);
            return;
        }
        double c   = session.getCenterHz();
        double eps = session.getHysteresisHz();
        double d   = session.getRelayAmpHz();
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) { mn = Math.min(mn, wErr[i]); mx = Math.max(mx, wErr[i]); }
        double half = Math.max(Math.max(mx - c, c - mn), Math.max(eps, d)) * 1.2;
        if (!(half > 0)) half = 1e-6;
        double top = c + half, bot = c - half;

        Color gray   = display.getSystemColor(SWT.COLOR_DARK_GRAY);
        Color green  = display.getSystemColor(SWT.COLOR_GREEN);
        Color yellow = display.getSystemColor(SWT.COLOR_YELLOW);

        // center line + hysteresis band
        gc.setForeground(gray);
        int yc = valY(c, top, bot, a);
        gc.drawLine(a.x, yc, a.x + a.width, yc);
        gc.setLineStyle(SWT.LINE_DOT);
        gc.drawLine(a.x, valY(c + eps, top, bot, a), a.x + a.width, valY(c + eps, top, bot, a));
        gc.drawLine(a.x, valY(c - eps, top, bot, a), a.x + a.width, valY(c - eps, top, bot, a));
        gc.setLineStyle(SWT.LINE_SOLID);

        // relay output (square wave around center) then err on top
        drawTrace(gc, yellow, wOut, n, true, c, top, bot, a);
        drawTrace(gc, green,  wErr, n, false, c, top, bot, a);
    }

    private void drawTrace(GC gc, Color col, double[] v, int n,
                           boolean offsetByCenter, double center,
                           double top, double bot, Rectangle a) {
        gc.setForeground(col);
        int prevX = a.x, prevY = valY((offsetByCenter ? center + v[0] : v[0]), top, bot, a);
        for (int i = 1; i < n; i++) {
            int x = a.x + (int) Math.round((i / (double) (n - 1)) * (a.width - 1));
            int y = valY((offsetByCenter ? center + v[i] : v[i]), top, bot, a);
            gc.drawLine(prevX, prevY, x, y);
            prevX = x; prevY = y;
        }
    }

    private static int valY(double v, double top, double bot, Rectangle a) {
        double f = (top - v) / (top - bot);
        f = Math.max(0.0, Math.min(1.0, f));
        return a.y + (int) Math.round(f * (a.height - 1));
    }

    // --------------------------------------------------------------- formatting

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.4g", v) : "—";
    }
    private static String fmt0(double v) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0, v));
    }
    private static String fmtHz(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : "—";
    }
    private static String fmtSigned(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%+.4f", v) : "—";
    }
    private static String fmtSeconds(double s) {
        if (!Double.isFinite(s) || s <= 0) return "—";
        if (s >= 1.0)  return String.format(Locale.ROOT, "%.2f s", s);
        if (s >= 1e-3) return String.format(Locale.ROOT, "%.1f ms", s * 1e3);
        return String.format(Locale.ROOT, "%.0f µs", s * 1e6);
    }
}

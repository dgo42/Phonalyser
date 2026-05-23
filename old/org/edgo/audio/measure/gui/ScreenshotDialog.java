package org.edgo.audio.measure.gui;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.events.VerifyListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.io.File;

/**
 * Generic "save / copy a screenshot" dialog.  Caller supplies a
 * {@link SnapshotRenderer} that knows how to render itself to an off-screen
 * image at any requested size, plus optional callbacks that persist the
 * last-used resolution and folder back into preferences.
 *
 * <p>Layout:
 * <pre>
 *   Width:  [____]
 *   Height: [____]
 *   [x] Keep aspect ratio
 *   Preset: [1280×720      ▾]   ← selecting fills the W/H fields above
 *
 *   [Copy to clipboard] [Save as…] [Close]
 * </pre>
 *
 * <p>The width/height fields are plain numeric Text widgets that accept
 * digits only.  The preset combo is just a quick-fill helper — it does not
 * carry the dialog's state.
 */
@Log4j2
public final class ScreenshotDialog {

    /** Source-of-image strategy: produces a fresh {@link Image} at the requested size. Dialog disposes it. */
    public interface SnapshotRenderer {
        Image render(Display display, int width, int height);
    }

    /** Called after a successful copy or save so the caller can persist the chosen pixel size. */
    public interface SizeCommit {
        void accept(int width, int height);
    }

    /** Called after a successful save so the caller can persist the chosen folder. */
    public interface FolderCommit {
        void accept(String folder);
    }

    /** Common preset resolutions presented in the dropdown beneath the W/H fields. */
    private static final int[][] PRESETS = {
            {1024,  768},
            {1280,  720},
            {1280, 1024},
            {1366,  768},
            {1600,  900},
            {1920, 1080},
            {1920, 1200},
            {1920, 1280},
    };

    private final Shell parent;
    private final int initialW;
    private final int initialH;
    private final int nativeW;
    private final int nativeH;
    private final String initialFolder;
    private final SnapshotRenderer renderer;
    private final SizeCommit   onSize;
    private final FolderCommit onFolder;

    public ScreenshotDialog(Shell parent,
                            int initialWidth, int initialHeight,
                            int nativeWidth,  int nativeHeight,
                            String initialFolder,
                            SnapshotRenderer renderer,
                            SizeCommit onSize,
                            FolderCommit onFolder) {
        this.parent = parent;
        this.initialW = initialWidth  > 0 ? initialWidth  : nativeWidth;
        this.initialH = initialHeight > 0 ? initialHeight : nativeHeight;
        this.nativeW = nativeWidth;
        this.nativeH = nativeHeight;
        this.initialFolder = initialFolder;
        this.renderer = renderer;
        this.onSize = onSize;
        this.onFolder = onFolder;
    }

    public void open() {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText("Screenshot");
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth  = 12;
        layout.marginHeight = 12;
        layout.horizontalSpacing = 8;
        layout.verticalSpacing   = 8;
        dialog.setLayout(layout);

        new Label(dialog, SWT.NONE).setText("Width, px:");
        Text widthText = new Text(dialog, SWT.BORDER);
        widthText.setLayoutData(textData());
        widthText.setText(String.valueOf(initialW));
        widthText.addVerifyListener(positiveIntegerVerifier());

        new Label(dialog, SWT.NONE).setText("Height, px:");
        Text heightText = new Text(dialog, SWT.BORDER);
        heightText.setLayoutData(textData());
        heightText.setText(String.valueOf(initialH));
        heightText.addVerifyListener(positiveIntegerVerifier());

        Button keepAspect = new Button(dialog, SWT.CHECK);
        keepAspect.setText("Keep aspect ratio");
        keepAspect.setSelection(true);
        GridData kaData = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        kaData.horizontalSpan = 2;
        keepAspect.setLayoutData(kaData);

        // Aspect-ratio coupling between the two fields.  suppressUpdate[]
        // breaks the feedback loop when one field's listener writes into the
        // other.
        boolean[] suppressUpdate = { false };
        widthText.addListener(SWT.Modify, e -> {
            if (suppressUpdate[0] || !keepAspect.getSelection()) return;
            Integer w = parseInt(widthText.getText());
            if (w == null || nativeW <= 0) return;
            int h = (int) Math.round(w * (double) nativeH / nativeW);
            suppressUpdate[0] = true;
            heightText.setText(String.valueOf(h));
            suppressUpdate[0] = false;
        });
        heightText.addListener(SWT.Modify, e -> {
            if (suppressUpdate[0] || !keepAspect.getSelection()) return;
            Integer h = parseInt(heightText.getText());
            if (h == null || nativeH <= 0) return;
            int w = (int) Math.round(h * (double) nativeW / nativeH);
            suppressUpdate[0] = true;
            widthText.setText(String.valueOf(w));
            suppressUpdate[0] = false;
        });

        new Label(dialog, SWT.NONE).setText("Preset:");
        Combo presetCombo = new Combo(dialog, SWT.READ_ONLY);
        presetCombo.setLayoutData(textData());
        for (int[] p : PRESETS) presetCombo.add(p[0] + "×" + p[1]);
        presetCombo.addListener(SWT.Selection, e -> {
            int idx = presetCombo.getSelectionIndex();
            if (idx < 0 || idx >= PRESETS.length) return;
            // Write both fields at once and skip the aspect-ratio coupling
            // so the preset's exact W×H is preserved even when "Keep aspect"
            // is on.
            suppressUpdate[0] = true;
            widthText.setText(String.valueOf(PRESETS[idx][0]));
            heightText.setText(String.valueOf(PRESETS[idx][1]));
            suppressUpdate[0] = false;
        });

        // --- Buttons --------------------------------------------------------
        Composite buttonBar = new Composite(dialog, SWT.NONE);
        GridData bbData = new GridData(SWT.END, SWT.CENTER, true, false);
        bbData.horizontalSpan = 2;
        buttonBar.setLayoutData(bbData);
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8;
        buttonBar.setLayout(rl);

        Button copyBtn = new Button(buttonBar, SWT.PUSH);
        copyBtn.setText("Copy to clipboard");
        copyBtn.addListener(SWT.Selection, e -> {
            int[] wh = readSize(widthText, heightText);
            if (wh == null) return;
            Image img = renderer.render(dialog.getDisplay(), wh[0], wh[1]);
            Clipboard cb = new Clipboard(dialog.getDisplay());
            try {
                cb.setContents(new Object[]{ img.getImageData() },
                               new Transfer[]{ ImageTransfer.getInstance() });
                log.info("Screenshot {}×{} copied to clipboard", wh[0], wh[1]);
                if (onSize != null) onSize.accept(wh[0], wh[1]);
            } finally {
                cb.dispose();
                img.dispose();
            }
            dialog.close();
        });

        String[] folderHolder = { initialFolder };
        Button saveBtn = new Button(buttonBar, SWT.PUSH);
        saveBtn.setText("Save as…");
        saveBtn.addListener(SWT.Selection, e -> {
            FileDialog fd = new FileDialog(dialog, SWT.SAVE);
            fd.setFilterExtensions(new String[]{ "*.png", "*.jpg", "*.bmp" });
            fd.setFilterNames(new String[]{ "PNG image (*.png)", "JPEG image (*.jpg)", "Bitmap image (*.bmp)" });
            fd.setOverwrite(true);
            if (folderHolder[0] != null) fd.setFilterPath(folderHolder[0]);
            String path = fd.open();
            if (path == null) return;
            int[] wh = readSize(widthText, heightText);
            if (wh == null) return;
            Image img = renderer.render(dialog.getDisplay(), wh[0], wh[1]);
            try {
                ImageLoader loader = new ImageLoader();
                loader.data = new ImageData[]{ img.getImageData() };
                String lower = path.toLowerCase();
                int format = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? SWT.IMAGE_JPEG
                           : lower.endsWith(".bmp") ? SWT.IMAGE_BMP
                           : SWT.IMAGE_PNG;
                loader.save(path, format);
                log.info("Screenshot {}×{} saved to {}", wh[0], wh[1], path);
                if (onSize != null) onSize.accept(wh[0], wh[1]);
                String parent = new File(path).getParent();
                if (parent != null) {
                    folderHolder[0] = parent;
                    if (onFolder != null) onFolder.accept(parent);
                }
            } finally {
                img.dispose();
            }
            dialog.close();
        });

        Button closeBtn = new Button(buttonBar, SWT.PUSH);
        closeBtn.setText("Close");
        closeBtn.addListener(SWT.Selection, e -> dialog.close());

        dialog.pack();
        dialog.open();
    }

    /** Reject any non-digit character so the Text only accepts unsigned integers. */
    private VerifyListener positiveIntegerVerifier() {
        return (VerifyEvent e) -> {
            for (int i = 0; i < e.text.length(); i++) {
                if (!Character.isDigit(e.text.charAt(i))) {
                    e.doit = false;
                    return;
                }
            }
        };
    }

    private int[] readSize(Text widthText, Text heightText) {
        Integer w = parseInt(widthText.getText());
        Integer h = parseInt(heightText.getText());
        if (w == null || h == null || w <= 0 || h <= 0) return null;
        return new int[]{ w, h };
    }

    private Integer parseInt(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException ex) { return null; }
    }

    private GridData textData() {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 200;
        return gd;
    }
}

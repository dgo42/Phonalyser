package org.edgo.audio.measure.gui.util;

import org.edgo.audio.measure.sound.javasound.CsjsoundNativePath;
import lombok.experimental.UtilityClass;

/**
 * Startup path setup of the GUI, run by {@code GuiMain}.
 */
@UtilityClass 
public class NativeAppPaths {

    /**
     * Stages the csjsound provider's native library so a FAT-JAR run can find it.
     *
     * <p>Must happen before anything triggers the JavaSound service scan: the
     * provider is discovered through that scan and its DLL has to be on the
     * library path by then, and a fat jar has no installer to have put it there.
     * Naming the JavaSound backend is the reason this module depends on it at
     * COMPILE scope while every other backend is runtime-only.
     */
    public void installCsjsoundLibrary() {
        CsjsoundNativePath.installForFatJar();
    }

}

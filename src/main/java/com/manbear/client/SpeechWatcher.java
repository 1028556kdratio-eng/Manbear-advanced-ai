package com.manbear.client;

import com.manbear.ManbearClientConfig;
import com.manbear.ManbearMod;
import com.manbear.net.ManbearNet;
import com.manbear.net.SaidNamePacket;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Hears you say "man bear" / "bear". Uses the speech recognizer that is BUILT INTO WINDOWS (System.Speech),
 * run through PowerShell: nothing is downloaded, the audio never leaves your PC, and only the fact that you said
 * the word is reported to the server. Windows only; on other systems this does nothing.
 */
public final class SpeechWatcher {
    private static volatile boolean running;
    private static volatile Process process;
    private static boolean hookInstalled;

    private static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'SilentlyContinue'",
            "Add-Type -AssemblyName System.Speech",
            "$engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine",
            "$engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))",
            "$engine.SetInputToDefaultAudioDevice()",
            "$null = Register-ObjectEvent -InputObject $engine -EventName SpeechRecognized -Action {",
            "  $t = $Event.SourceEventArgs.Result.Text",
            "  if ($t -match '(?i)\\bman ?bear\\b|\\bbear\\b') { [Console]::Out.WriteLine('HEARD ' + $t); [Console]::Out.Flush() }",
            "}",
            "$engine.RecognizeAsync([System.Speech.Recognition.RecognizeMode]::Multiple)",
            "while ($true) { Start-Sleep -Milliseconds 300 }");

    private SpeechWatcher() { }

    public static synchronized void start() {
        if (running || !ManbearClientConfig.SPEECH_RECOGNITION.get()) return;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            ManbearMod.LOGGER.info("Manbear: speech recognition only works on Windows, skipping");
            return;
        }
        running = true;
        if (!hookInstalled) {
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(SpeechWatcher::stop));
        }
        Thread t = new Thread(SpeechWatcher::run, "manbear-speech");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        running = false;
        Process p = process;
        if (p != null) p.destroy();
    }

    private static void run() {
        try {
            String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);
            process = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.startsWith("HEARD ")) heard();
                }
            }
        } catch (Throwable t) {
            ManbearMod.LOGGER.info("Manbear: speech recognition unavailable ({})", t.toString());
        } finally {
            running = false;
            Process p = process;
            if (p != null) p.destroy();
        }
    }

    private static void heard() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.getConnection() != null && mc.player != null) ManbearNet.CHANNEL.sendToServer(new SaidNamePacket());
        });
    }
}

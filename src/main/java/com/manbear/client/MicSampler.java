package com.manbear.client;

import com.manbear.ManbearClientConfig;
import com.manbear.ManbearMod;
import com.manbear.voice.VoiceCodec;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens to the local microphone while the player is in a world. Speech is kept in RAM only,
 * segmented into short audio fragments, and shuffled to make the Manbear's voice collage.
 * Nothing is written to disk. Sharing fragments with a multiplayer server is a separate opt-in.
 */
public final class MicSampler {
    public static final int RATE = 16000;
    private static final int CHUNK_BYTES = 3200; // 100 ms of 16 kHz mono, signed 16-bit PCM
    private static final int MAX_FRAGMENTS = 48;
    private static final int FRAME_SAMPLES = RATE / 50; // 20 ms energy frames
    private static final int MIN_FRAGMENT_SAMPLES = RATE * 18 / 100; // 180 ms
    private static final int MAX_FRAGMENT_SAMPLES = RATE * 92 / 100; // 920 ms
    private static final int TARGET_FRAGMENT_SAMPLES = RATE * 48 / 100; // ~480 ms
    private static final int SENTENCE_GAP_MIN_SAMPLES = RATE * 7 / 100; // 70 ms
    private static final int SENTENCE_GAP_RANGE_SAMPLES = RATE * 11 / 100; // up to 180 ms
    private static final int EDGE_FADE_SAMPLES = RATE / 100; // 10 ms click-prevention fade

    private static final List<VoiceFragment> FRAGMENTS = new ArrayList<>();
    private static final AtomicLong PHRASE_IDS = new AtomicLong(1L);
    private static final Random RANDOM = new Random();
    private static final ConcurrentLinkedQueue<byte[]> UPLOADS = new ConcurrentLinkedQueue<>();
    private static volatile boolean running;

    private MicSampler() { }

    public static synchronized void start() {
        if (running || !ManbearClientConfig.MIC_SAMPLING.get()) return;
        running = true;
        Thread t = new Thread(MicSampler::run, "manbear-mic");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        running = false;
        UPLOADS.clear();
        synchronized (FRAGMENTS) {
            FRAGMENTS.clear(); // audio snippets never outlive the current session
        }
    }

    public static boolean isRunning() { return running; }

    public static int fragmentCount() {
        synchronized (FRAGMENTS) { return FRAGMENTS.size(); }
    }

    /** Number of distinct speech moments represented in the current in-memory buffer. */
    public static int phraseCount() {
        synchronized (FRAGMENTS) {
            java.util.HashSet<Long> ids = new java.util.HashSet<>();
            for (VoiceFragment fragment : FRAGMENTS) ids.add(fragment.phraseId);
            return ids.size();
        }
    }

    /** One original phrase for the optional multiplayer-sharing path. */
    public static byte[] pollUpload() { return UPLOADS.poll(); }

    /** Purges queued audio immediately when the player disables multiplayer voice sharing. */
    public static void clearPendingUploads() { UPLOADS.clear(); }

    /**
     * Builds a collage from several separate things the player said. Every collage uses snippets
     * from at least two distinct utterances; if the buffer only contains one utterance, wait for
     * another instead of replaying pieces from the same saying as though they were new sentences.
     */
    public static short[] randomClip() {
        List<VoiceFragment> snapshot;
        synchronized (FRAGMENTS) {
            if (FRAGMENTS.size() < 2) return null;
            snapshot = new ArrayList<>(FRAGMENTS);
        }

        Map<Long, List<VoiceFragment>> byPhrase = new LinkedHashMap<>();
        for (VoiceFragment fragment : snapshot) {
            byPhrase.computeIfAbsent(fragment.phraseId, ignored -> new ArrayList<>()).add(fragment);
        }
        if (byPhrase.size() < 2) return null;

        List<List<VoiceFragment>> sayings = new ArrayList<>(byPhrase.values());
        Collections.shuffle(sayings, RANDOM);
        int maxPieces = Math.min(4, snapshot.size());
        int targetPieces = 2 + RANDOM.nextInt(Math.max(1, maxPieces - 1)); // 2-4 snippets
        List<VoiceFragment> selected = new ArrayList<>(targetPieces);

        // Choose one snippet from each distinct saying first, guaranteeing cross-saying audio.
        for (List<VoiceFragment> saying : sayings) {
            if (selected.size() >= targetPieces) break;
            selected.add(saying.get(RANDOM.nextInt(saying.size())));
        }

        // If there were only two or three sayings in memory, fill the remaining slots with other
        // snippets, still without repeating a fragment in the same collage.
        if (selected.size() < targetPieces) {
            List<VoiceFragment> extras = new ArrayList<>(snapshot);
            Collections.shuffle(extras, RANDOM);
            for (VoiceFragment extra : extras) {
                if (selected.size() >= targetPieces) break;
                if (!selected.contains(extra)) selected.add(extra);
            }
        }

        List<short[]> samples = new ArrayList<>(selected.size());
        for (VoiceFragment fragment : selected) samples.add(fragment.samples);
        return spliceFragments(samples, samples.size(), samples.size(), RANDOM);
    }

    /** Reorders short fragments within one shared phrase for a remote-player voice echo. */
    public static short[] splicePhrase(short[] source) {
        if (source == null || source.length == 0) return null;
        List<short[]> pieces = splitIntoFragments(source);
        if (pieces.size() < 2) return source;
        return spliceFragments(pieces, 2, 4, RANDOM);
    }

    private static void run() {
        TargetDataLine line = null;
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false); // signed 16-bit little-endian mono
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                ManbearMod.LOGGER.info("Manbear: no usable microphone, voice sampling disabled");
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            line.start();

            byte[] buf = new byte[CHUNK_BYTES];
            List<short[]> current = new ArrayList<>();
            boolean talking = false;
            int silent = 0;
            long currentPhraseId = 0L;
            double floor = 150;
            while (running) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 1) continue;
                short[] pcm = new short[n / 2];
                double sum = 0;
                for (int i = 0; i < pcm.length; i++) {
                    short v = (short) ((buf[2 * i + 1] << 8) | (buf[2 * i] & 0xFF));
                    pcm[i] = v;
                    sum += (double) v * v;
                }
                double rms = Math.sqrt(sum / Math.max(1, pcm.length));
                double threshold = Math.max(ManbearClientConfig.MIC_THRESHOLD.get(), floor * 3.5);
                if (rms > threshold) {
                    if (!talking) {
                        talking = true;
                        currentPhraseId = PHRASE_IDS.getAndIncrement();
                    }
                    silent = 0;
                    current.add(pcm);
                } else {
                    floor = floor * 0.98 + rms * 0.02; // gently learn room noise
                    if (talking) {
                        current.add(pcm);
                        if (++silent >= 7) { // 0.7 s of quiet ends the phrase
                            finish(current, currentPhraseId);
                            current = new ArrayList<>();
                            talking = false;
                            currentPhraseId = 0L;
                            silent = 0;
                        }
                    }
                }
                if (current.size() > 60) { // cap each audio chunk at ~6 s without pretending it was a new saying
                    finish(current, currentPhraseId);
                    current = new ArrayList<>();
                    // Keep the same phrase id and talking state until a real quiet gap ends the saying.
                }
            }
        } catch (Throwable t) {
            if (running) ManbearMod.LOGGER.info("Manbear: microphone sampling stopped ({})", t.toString());
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            running = false;
        }
    }

    private static void finish(List<short[]> chunks, long phraseId) {
        if (!running || chunks.size() < 5) return;
        int total = 0;
        for (short[] c : chunks) total += c.length;
        if (total < RATE / 3) return;

        short[] phrase = new short[total];
        int pos = 0;
        int peak = 1;
        for (short[] c : chunks) {
            for (short v : c) peak = Math.max(peak, Math.abs((int) v));
            System.arraycopy(c, 0, phrase, pos, c.length);
            pos += c.length;
        }
        double gain = Math.min(4.0, 24000.0 / peak);
        for (int i = 0; i < phrase.length; i++) {
            phrase[i] = (short) Math.max(-32768, Math.min(32767, Math.round(phrase[i] * gain)));
        }

        // The server receives a complete short phrase only if the user explicitly enabled sharing.
        // Local playback uses smaller fragments kept in this process's RAM.
        if (running && ManbearClientConfig.SHARE_VOICE.get() && UPLOADS.size() < 4) {
            UPLOADS.add(VoiceCodec.encode(phrase));
        }

        List<short[]> fragments = splitIntoFragments(phrase);
        synchronized (FRAGMENTS) {
            if (!running) return;
            for (short[] fragment : fragments) {
                FRAGMENTS.add(new VoiceFragment(phraseId, fragment));
                while (FRAGMENTS.size() > MAX_FRAGMENTS) FRAGMENTS.remove(RANDOM.nextInt(FRAGMENTS.size()));
            }
        }
    }

    /**
     * Splits speech at short low-energy gaps, then divides long continuous sections near energy valleys.
     * This is waveform-based (no transcription or external service), so fragments are word-like rather than
     * guaranteed word-aligned; the resulting collage deliberately sounds uncanny.
     */
    private static List<short[]> splitIntoFragments(short[] pcm) {
        List<short[]> out = new ArrayList<>();
        if (pcm == null || pcm.length < MIN_FRAGMENT_SAMPLES) return out;

        int frameCount = (pcm.length + FRAME_SAMPLES - 1) / FRAME_SAMPLES;
        double[] rms = new double[frameCount];
        double peakFrameRms = 0;
        for (int f = 0; f < frameCount; f++) {
            int from = f * FRAME_SAMPLES;
            int to = Math.min(pcm.length, from + FRAME_SAMPLES);
            double sum = 0;
            for (int i = from; i < to; i++) sum += (double) pcm[i] * pcm[i];
            rms[f] = Math.sqrt(sum / Math.max(1, to - from));
            peakFrameRms = Math.max(peakFrameRms, rms[f]);
        }
        double threshold = Math.max(160.0, peakFrameRms * 0.09);
        boolean[] active = new boolean[frameCount];
        for (int i = 0; i < frameCount; i++) active[i] = rms[i] >= threshold;

        // Fill brief energy dips (e.g. between consonants) but preserve actual pauses between phrases/words.
        int maxGapFrames = 3; // 60 ms
        for (int i = 0; i < active.length; ) {
            if (active[i]) { i++; continue; }
            int start = i;
            while (i < active.length && !active[i]) i++;
            int length = i - start;
            if (start > 0 && i < active.length && length <= maxGapFrames) {
                for (int j = start; j < i; j++) active[j] = true;
            }
        }

        List<int[]> ranges = new ArrayList<>();
        int startFrame = -1;
        for (int i = 0; i <= active.length; i++) {
            boolean on = i < active.length && active[i];
            if (on && startFrame < 0) startFrame = i;
            if (!on && startFrame >= 0) {
                int start = Math.max(0, startFrame * FRAME_SAMPLES - RATE / 40); // ~25 ms consonant padding
                int end = Math.min(pcm.length, i * FRAME_SAMPLES + RATE / 40);
                if (end > start) ranges.add(new int[]{start, end});
                startFrame = -1;
            }
        }

        // Join very short syllables to adjacent fragments instead of throwing them away.
        List<int[]> merged = new ArrayList<>();
        int pendingStart = -1;
        for (int[] range : ranges) {
            int s = pendingStart >= 0 ? pendingStart : range[0];
            int e = range[1];
            if (e - s < MIN_FRAGMENT_SAMPLES) {
                pendingStart = s;
                continue;
            }
            merged.add(new int[]{s, e});
            pendingStart = -1;
        }
        if (pendingStart >= 0) {
            if (!merged.isEmpty()) merged.get(merged.size() - 1)[1] = ranges.get(ranges.size() - 1)[1];
            else if (pcm.length - pendingStart >= MIN_FRAGMENT_SAMPLES) merged.add(new int[]{pendingStart, pcm.length});
        }

        for (int[] range : merged) {
            int start = range[0];
            int end = range[1];
            while (end - start > MAX_FRAGMENT_SAMPLES) {
                int desired = start + TARGET_FRAGMENT_SAMPLES;
                int lowSample = Math.max(start + MIN_FRAGMENT_SAMPLES, desired - RATE / 10);
                int highSample = Math.min(end - MIN_FRAGMENT_SAMPLES, desired + RATE / 10);
                int lowFrame = Math.max(0, lowSample / FRAME_SAMPLES);
                int highFrame = Math.min(frameCount - 1, highSample / FRAME_SAMPLES);
                int valleyFrame = Math.max(lowFrame, Math.min(highFrame, desired / FRAME_SAMPLES));
                double valleyRms = Double.MAX_VALUE;
                for (int f = lowFrame; f <= highFrame; f++) {
                    if (rms[f] < valleyRms) {
                        valleyRms = rms[f];
                        valleyFrame = f;
                    }
                }
                int cut = valleyFrame * FRAME_SAMPLES;
                if (cut - start < MIN_FRAGMENT_SAMPLES || end - cut < MIN_FRAGMENT_SAMPLES) cut = desired;
                cut = Math.max(start + MIN_FRAGMENT_SAMPLES, Math.min(end - MIN_FRAGMENT_SAMPLES, cut));
                addFragment(out, pcm, start, cut);
                start = cut;
            }
            if (end - start >= MIN_FRAGMENT_SAMPLES) addFragment(out, pcm, start, end);
        }
        return out;
    }

    private static void addFragment(List<short[]> out, short[] source, int start, int end) {
        start = Math.max(0, start);
        end = Math.min(source.length, end);
        if (end - start < MIN_FRAGMENT_SAMPLES) return;
        short[] fragment = new short[end - start];
        System.arraycopy(source, start, fragment, 0, fragment.length);

        int peak = 1;
        for (short sample : fragment) peak = Math.max(peak, Math.abs((int) sample));
        double gain = Math.min(2.0, 20000.0 / peak);
        for (int i = 0; i < fragment.length; i++) {
            fragment[i] = (short) Math.max(-32768, Math.min(32767, Math.round(fragment[i] * gain)));
        }
        out.add(fragment);
    }

    private static short[] spliceFragments(List<short[]> pool, int minPieces, int maxPieces, Random random) {
        if (pool == null || pool.isEmpty()) return null;
        if (pool.size() == 1) return copyAndFade(pool.get(0));

        List<short[]> choices = new ArrayList<>(pool);
        Collections.shuffle(choices, random);
        int count = Math.min(choices.size(), minPieces + random.nextInt(Math.max(1, Math.min(maxPieces, choices.size()) - minPieces + 1)));
        if (count < 2) count = 2;
        count = Math.min(count, choices.size());

        int total = 0;
        int[] gaps = new int[Math.max(0, count - 1)];
        for (int i = 0; i < count; i++) total += choices.get(i).length;
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = SENTENCE_GAP_MIN_SAMPLES + random.nextInt(SENTENCE_GAP_RANGE_SAMPLES + 1);
            total += gaps[i];
        }
        short[] result = new short[total];
        int offset = 0;
        for (int i = 0; i < count; i++) {
            short[] fragment = choices.get(i);
            copyWithFade(fragment, result, offset);
            offset += fragment.length;
            if (i < gaps.length) offset += gaps[i]; // leave a natural, eerie gap between snippets
        }
        return result;
    }

    private static short[] copyAndFade(short[] input) {
        short[] result = new short[input.length];
        copyWithFade(input, result, 0);
        return result;
    }

    private static void copyWithFade(short[] input, short[] output, int offset) {
        System.arraycopy(input, 0, output, offset, input.length);
        int fade = Math.min(EDGE_FADE_SAMPLES, input.length / 3);
        for (int i = 0; i < fade; i++) {
            double inGain = (double) i / Math.max(1, fade);
            double outGain = (double) (fade - i - 1) / Math.max(1, fade);
            int inIndex = offset + i;
            int outIndex = offset + input.length - fade + i;
            output[inIndex] = (short) (output[inIndex] * inGain);
            output[outIndex] = (short) (output[outIndex] * outGain);
        }
    }
    /** A waveform snippet tied to the separate utterance it came from. */
    private static final class VoiceFragment {
        private final long phraseId;
        private final short[] samples;

        private VoiceFragment(long phraseId, short[] samples) {
            this.phraseId = phraseId;
            this.samples = samples;
        }
    }

}

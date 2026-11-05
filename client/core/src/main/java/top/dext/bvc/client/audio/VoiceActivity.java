package top.dext.bvc.client.audio;

/**
 * Voice activation: open when the level crosses the threshold, stay open a little after it
 * drops so word endings are not cut off.
 */
public final class VoiceActivity {

    /** 300 ms of hangover at 20 ms per frame. */
    static final int HANGOVER_FRAMES = 15;

    private int hangover;

    public boolean update(float levelDb, float thresholdDb) {
        if (levelDb >= thresholdDb) {
            hangover = HANGOVER_FRAMES;
            return true;
        }
        if (hangover > 0) {
            hangover--;
            return true;
        }
        return false;
    }

    public void reset() {
        hangover = 0;
    }

    /** RMS level of a frame in dBFS (0 = full scale, about -90 = silence). */
    public static float levelDb(short[] frame) {
        double sum = 0;
        for (short sample : frame) {
            sum += (double) sample * sample;
        }
        double rms = Math.sqrt(sum / frame.length);
        return rms < 1 ? -90f : (float) (20 * Math.log10(rms / 32768.0));
    }
}

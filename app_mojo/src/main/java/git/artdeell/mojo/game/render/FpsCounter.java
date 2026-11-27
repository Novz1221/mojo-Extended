package git.artdeell.mojo.game.render;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts frames presented by the game. Fed by the TextureView surface listener. */
public final class FpsCounter {
    private static final AtomicInteger sFrames = new AtomicInteger();

    private FpsCounter() {
    }

    public static void onFrame() {
        sFrames.incrementAndGet();
    }

    public static int totalFrames() {
        return sFrames.get();
    }
}

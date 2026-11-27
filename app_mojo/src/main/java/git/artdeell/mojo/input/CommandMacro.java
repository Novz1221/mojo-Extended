package git.artdeell.mojo.input;

import android.content.Context;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import git.artdeell.mojo.game.GameView;

/**
 * One-tap chat command: opens chat (T), types the stored text and presses Enter.
 * Delays are scaled by the "command speed" setting (percent, 100 = normal).
 */
public final class CommandMacro implements Runnable {
    public static final String PREFS = "cmdmacro";
    public static final String KEY_SPEED = "cmdSpeed";
    public static final int DEFAULT_SPEED = 100;
    public static final int MIN_SPEED = 50, MAX_SPEED = 300;

    private final GameView view;
    private final String command;
    private final int speed;

    private CommandMacro(GameView view, String command, int speed) {
        this.view = view;
        this.command = command;
        this.speed = speed;
    }

    public static void start(GameView view, String command) {
        if (view == null || command == null || command.isEmpty()) return;
        Context ctx = view.getContext();
        int speed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SPEED, DEFAULT_SPEED);
        if (speed < 10) speed = DEFAULT_SPEED;
        new Thread(new CommandMacro(view, command, speed), "CommandMacro").start();
    }

    private void key(int code, int action) {
        view.processKeyEvent(new KeyEvent(action, code));
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms * 100 / speed);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void run() {
        key(KeyEvent.KEYCODE_T, KeyEvent.ACTION_DOWN);
        pause(40);
        key(KeyEvent.KEYCODE_T, KeyEvent.ACTION_UP);
        pause(250);

        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(command.toCharArray());
        if (events != null) {
            for (KeyEvent e : events) {
                view.processKeyEvent(KeyEvent.changeFlags(e, 0));
                pause(12);
            }
        }
        pause(150);

        key(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN);
        pause(40);
        key(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP);
    }
}

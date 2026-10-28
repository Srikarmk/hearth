package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Chess tile: you against Dr. Malvek, with the remote. Arrows move the cursor, OK picks up and puts down,
 * Menu opens New game / Difficulty / Switch sides, Back leaves. The game is saved after every move.
 */
public class ChessActivity extends Activity {

    private static final String PREFS = "chess";
    private static final long MIN_THINK_MS = 700, READ_MS = 1400, IDLE_MS = 75_000, TYPE_MS = 22;
    /** A player move that loses this much (centipawns, by a short search) is a blunder; gaining this much shines. */
    private static final int BLUNDER = 200, SHINE = 150;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Malvek malvek = new Malvek();
    private final Random random = new Random();

    private ChessBoardView board;
    private TextView speech, status, levelText, material;
    private Chess game;
    private ChessAi.Level level = ChessAi.Level.CASUAL;
    private int player = Chess.WHITE;
    private boolean thinking;
    /** Bumped by every new game, so a search still running for an old one is dropped. */
    private int generation;
    private int pendingFrom = -1, pendingTo = -1;
    private String typing = "";
    private int typed;

    private final Runnable typeStep = new Runnable() {
        @Override
        public void run() {
            if (typed >= typing.length()) return;
            typed++;
            speech.setText(typing.substring(0, typed));
            handler.postDelayed(this, TYPE_MS);
        }
    };

    private final Runnable idle = () -> {
        if (!thinking && game.side == player && game.result() == Chess.Result.NONE) say(Malvek.Moment.IDLE);
    };

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        setContentView(buildLayout());

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            level = ChessAi.Level.valueOf(prefs.getString("level", level.name()));
        } catch (IllegalArgumentException ignored) {
        }
        player = prefs.getInt("player", Chess.WHITE);
        String fen = prefs.getString("fen", null);
        try {
            game = fen == null ? null : Chess.fromFen(fen);
        } catch (RuntimeException e) {
            game = null;
        }
        if (game == null || game.result() != Chess.Result.NONE) {
            newGame();
        } else {
            attach();
            say(Malvek.Moment.INTRO);
            if (game.side != player) think(null);
        }
    }

    private View buildLayout() {
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setColors(new int[]{0xFF231A33, 0xFF0B0910});
        bg.setGradientRadius(1400 * d);
        bg.setGradientCenter(0.7f, 0.3f);
        root.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Math.round(40 * d);
        row.setPadding(pad, pad, pad, pad);
        root.addView(row, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        board = new ChessBoardView(this);
        row.addView(board, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        panelLp.setMarginStart(Math.round(48 * d));
        row.addView(panel, panelLp);

        panel.addView(text(R.string.font_light, 46, 0xFFEDE6F7, 0.12f, getString(R.string.malvek_name)));
        panel.addView(text(R.string.font_medium, 13, 0xFF8A80A3, 0.32f, getString(R.string.malvek_title)));

        speech = text(R.string.font_regular, 25, 0xF2FFFFFF, 0f, "");
        speech.setLineSpacing(0, 1.25f);
        speech.setMinLines(4);
        int sp = Math.round(24 * d);
        speech.setPadding(sp, sp, sp, sp);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x1AFFFFFF);
        glass.setStroke(Math.round(1.5f * d), 0x33FFFFFF);
        glass.setCornerRadius(22 * d);
        speech.setBackground(glass);
        LinearLayout.LayoutParams speechLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        speechLp.topMargin = Math.round(36 * d);
        panel.addView(speech, speechLp);

        status = text(R.string.font_medium, 19, 0xFFC7B8E8, 0.04f, "");
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = Math.round(28 * d);
        panel.addView(status, statusLp);
        levelText = text(R.string.font_regular, 15, 0x99FFFFFF, 0.1f, "");
        panel.addView(levelText);
        material = text(R.string.font_regular, 15, 0x99FFFFFF, 0.04f, "");
        panel.addView(material);

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView hints = text(R.string.font_regular, 14, 0x80FFFFFF, 0.06f, getString(R.string.chess_hints));
        hints.setGravity(Gravity.START);
        panel.addView(hints);
        return root;
    }

    private TextView text(int font, float sp, int color, float spacing, String value) {
        TextView t = new TextView(this);
        t.setTypeface(UiFont.get(this, font));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLetterSpacing(spacing);
        t.setText(value);
        return t;
    }

    private void attach() {
        board.game = game;
        board.flipped = player == Chess.BLACK;
        board.selected = -1;
        board.targets.clear();
        board.choosing = false;
        board.lastFrom = board.lastTo = -1;
        board.cursor = Chess.square(player == Chess.WHITE ? "e2" : "e7");
        board.invalidate();
        updatePanel();
    }

    private void newGame() {
        generation++;
        thinking = false;
        game = Chess.fromFen(Chess.START);
        attach();
        save();
        say(Malvek.Moment.INTRO);
        if (game.side != player) think(null);
    }

    private static boolean gameKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!gameKey(event.getKeyCode())) return super.dispatchKeyEvent(event);   // volume and the rest
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        handler.removeCallbacks(idle);
        handler.postDelayed(idle, IDLE_MS);
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return arrow(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return arrow(1, 0);
            case KeyEvent.KEYCODE_DPAD_UP: return arrow(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return arrow(0, 1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                ok();
                return true;
            case KeyEvent.KEYCODE_MENU:
                menu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                back();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private boolean arrow(int dc, int dr) {
        if (board.choosing) {
            if (dc != 0) board.choice = (board.choice + dc + ChessBoardView.PROMOTIONS.length) % ChessBoardView.PROMOTIONS.length;
        } else {
            board.cursor = board.moved(board.cursor, dc, dr);
        }
        board.invalidate();
        return true;
    }

    private void back() {
        if (board.choosing) {
            board.choosing = false;
        } else if (board.selected >= 0) {
            board.selected = -1;
            board.targets.clear();
        } else {
            finish();
            return;
        }
        board.invalidate();
    }

    private void ok() {
        if (game.result() != Chess.Result.NONE) {
            newGame();
            return;
        }
        if (thinking || game.side != player) return;
        if (board.choosing) {
            int promo = ChessBoardView.PROMOTIONS[board.choice];
            board.choosing = false;
            for (int m : game.legalMovesFrom(pendingFrom)) {
                if (Chess.to(m) == pendingTo && Chess.promo(m) == promo) { playerMove(m); return; }
            }
            return;
        }
        int sq = board.cursor;
        if (board.selected >= 0 && board.targets.contains(sq)) {
            List<Integer> moves = game.legalMovesFrom(board.selected);
            int chosen = 0, count = 0;
            for (int m : moves) if (Chess.to(m) == sq) { chosen = m; count++; }
            if (count > 1) {   // a promotion: four moves to one square
                pendingFrom = board.selected;
                pendingTo = sq;
                board.choosing = true;
                board.choice = 0;
                board.invalidate();
            } else if (count == 1) {
                playerMove(chosen);
            }
            return;
        }
        int p = game.board[sq];
        board.targets.clear();
        if (p != Chess.EMPTY && Integer.signum(p) == player) {
            board.selected = sq;
            for (int m : game.legalMovesFrom(sq)) board.targets.add(Chess.to(m));
        } else {
            board.selected = -1;
        }
        board.invalidate();
    }

    private void playerMove(int m) {
        String before = game.fen();
        boolean capture = game.captured(m) != Chess.EMPTY;
        apply(m);
        Chess.Result r = game.result();
        if (r != Chess.Result.NONE) {
            end(r);
            return;
        }
        boolean check = game.inCheck();
        think(new int[]{capture ? 1 : 0, check ? 1 : 0}, before);
    }

    private void think(int[] facts) {
        think(facts, null);
    }

    /** Searches off the main thread; facts and before describe the player's move, if there was one. */
    private void think(int[] facts, String before) {
        thinking = true;
        status.setText(R.string.chess_thinking);
        int gen = generation;
        String now = game.fen();
        ChessAi.Level lvl = level;
        long start = System.currentTimeMillis();
        worker.execute(() -> {
            int swing = 0;
            if (before != null) {
                ChessAi judge = new ChessAi(ChessAi.Level.SHARP, 1);
                int was = judge.evaluate(Chess.fromFen(before));     // for the player, before the move
                int is = -judge.evaluate(Chess.fromFen(now));        // for the player, after it
                swing = is - was;
            }
            int reply = new ChessAi(lvl, System.nanoTime()).best(Chess.fromFen(now));
            int finalSwing = swing;
            long wait = Math.max(0, MIN_THINK_MS - (System.currentTimeMillis() - start));
            handler.postDelayed(() -> {
                if (gen != generation || isFinishing()) return;
                Malvek.Moment reaction = null;
                if (facts != null) {
                    if (facts[1] == 1) reaction = Malvek.Moment.YOU_CHECK;
                    else if (finalSwing <= -BLUNDER) reaction = Malvek.Moment.YOU_BLUNDER;
                    else if (finalSwing >= SHINE) reaction = Malvek.Moment.YOU_SHINE;
                    else if (facts[0] == 1) reaction = Malvek.Moment.YOU_CAPTURE;
                    else if (random.nextInt(5) == 0) reaction = Malvek.Moment.THINKING;
                }
                if (reaction != null) say(reaction);
                handler.postDelayed(() -> {
                    if (gen == generation && !isFinishing()) reply(reply);
                }, reaction != null ? READ_MS : 0);
            }, wait);
        });
    }

    private void reply(int m) {
        thinking = false;
        if (m == 0) return;
        boolean capture = game.captured(m) != Chess.EMPTY;
        apply(m);
        Chess.Result r = game.result();
        if (r != Chess.Result.NONE) {
            end(r);
            return;
        }
        if (game.inCheck()) say(Malvek.Moment.HE_CHECKS);
        else if (capture) say(Malvek.Moment.HE_CAPTURES);
        updatePanel();
    }

    private void apply(int m) {
        int from = Chess.from(m), to = Chess.to(m);
        int moving = Chess.promo(m) != 0 ? game.side * Chess.promo(m) : game.board[from];
        game.play(m);
        board.lastFrom = from;
        board.lastTo = to;
        board.selected = -1;
        board.targets.clear();
        board.slide(from, to, moving);
        save();
        updatePanel();
    }

    private void end(Chess.Result r) {
        thinking = false;
        boolean whiteWon = r == Chess.Result.WHITE_WINS, blackWon = r == Chess.Result.BLACK_WINS;
        if (whiteWon || blackWon) {
            boolean youWon = (whiteWon && player == Chess.WHITE) || (blackWon && player == Chess.BLACK);
            say(youWon ? Malvek.Moment.YOU_WIN : Malvek.Moment.HE_WINS);
            status.setText(youWon ? R.string.chess_you_won : R.string.chess_he_won);
        } else {
            say(Malvek.Moment.DRAW);
            status.setText(R.string.chess_draw);
        }
    }

    private void updatePanel() {
        if (thinking) status.setText(R.string.chess_thinking);
        else if (game.result() == Chess.Result.NONE) status.setText(game.side == player ? R.string.chess_your_move : R.string.chess_thinking);
        levelText.setText(getString(R.string.chess_level, getString(levelName(level)),
                getString(player == Chess.WHITE ? R.string.chess_white : R.string.chess_black)));
        int balance = 0;
        int[] value = {0, 1, 3, 3, 5, 9, 0};
        for (int p : game.board) balance += Integer.signum(p) * value[Math.abs(p)];
        balance *= player;
        material.setText(balance == 0 ? getString(R.string.chess_even)
                : balance > 0 ? getString(R.string.chess_you_up, balance) : getString(R.string.chess_he_up, -balance));
    }

    private static int levelName(ChessAi.Level l) {
        switch (l) {
            case SHARP: return R.string.chess_sharp;
            case RUTHLESS: return R.string.chess_ruthless;
            default: return R.string.chess_casual;
        }
    }

    private void menu() {
        String[] items = {getString(R.string.chess_new_game), getString(R.string.chess_difficulty, getString(levelName(level))),
                getString(player == Chess.WHITE ? R.string.chess_play_black : R.string.chess_play_white)};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(items, (dlg, which) -> {
                    if (which == 0) {
                        newGame();
                    } else if (which == 1) {
                        chooseLevel();
                    } else {
                        player = -player;
                        newGame();
                    }
                })
                .show();
    }

    private void chooseLevel() {
        ChessAi.Level[] levels = ChessAi.Level.values();
        String[] names = new String[levels.length];
        for (int i = 0; i < levels.length; i++) names[i] = getString(levelName(levels[i]));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setSingleChoiceItems(names, level.ordinal(), (dlg, which) -> {
                    level = levels[which];
                    save();
                    updatePanel();
                    dlg.dismiss();
                })
                .show();
    }

    private void say(Malvek.Moment moment) {
        handler.removeCallbacks(typeStep);
        typing = malvek.say(moment);
        typed = 0;
        speech.setText("");
        handler.post(typeStep);
    }

    private void save() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("fen", game.fen()).putString("level", level.name()).putInt("player", player).apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(idle);
        save();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}

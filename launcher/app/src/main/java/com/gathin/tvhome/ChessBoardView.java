package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

/**
 * The board for ChessActivity: squares, pieces (sliding when they move), the remote's cursor, the selected
 * piece's targets, the last move, a check glow, and the promotion picker. It only draws; ChessActivity plays.
 * Cursor and selection are board squares; flipped draws Black at the bottom.
 */
final class ChessBoardView extends View {

    private static final int LIGHT = 0xFFC9C2D6, DARK = 0xFF5E5675;
    private static final int LAST_MOVE = 0x66D9A441, SELECTED = 0x8059C2A8;
    private static final long SLIDE_MS = 240;
    static final int[] PROMOTIONS = {Chess.QUEEN, Chess.ROOK, Chess.BISHOP, Chess.KNIGHT};

    private final Bitmap[] white = new Bitmap[7], black = new Bitmap[7];
    private final Paint square = new Paint();
    private final Paint piece = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    Chess game;
    boolean flipped;
    int cursor = Chess.square("e2");
    int selected = -1;
    final Set<Integer> targets = new HashSet<>();
    int lastFrom = -1, lastTo = -1;
    /** The promotion picker is up over the board, with this choice in PROMOTIONS highlighted. */
    boolean choosing;
    int choice;

    private int slideFrom = -1, slideTo = -1, slidePiece;
    private long slideStart;

    ChessBoardView(Context context) {
        super(context);
        for (int k = Chess.PAWN; k <= Chess.KING; k++) {
            white[k] = load(context, "chess_" + "pnbrqk".charAt(k - 1) + "l");
            black[k] = load(context, "chess_" + "pnbrqk".charAt(k - 1) + "d");
        }
        dot.setColor(0x66101018);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x66101018);
        cursorFill.setColor(0x33FFFFFF);
        cursorEdge.setStyle(Paint.Style.STROKE);
        cursorEdge.setColor(0xF2FFFFFF);
        label.setTypeface(UiFont.get(context, R.string.font_medium));
        veil.setColor(0xB3000000);
        setFocusable(false);
    }

    private static Bitmap load(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        return BitmapFactory.decodeResource(context.getResources(), id);
    }

    void slide(int from, int to, int p) {
        slideFrom = from;
        slideTo = to;
        slidePiece = p;
        slideStart = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Screen column/row of a board square, after flipping. */
    int screenCol(int sq) { return flipped ? 7 - Chess.col(sq) : Chess.col(sq); }
    int screenRow(int sq) { return flipped ? 7 - Chess.row(sq) : Chess.row(sq); }

    /** The square the cursor reaches by moving dc/dr on screen, clamped to the board. */
    int moved(int sq, int dc, int dr) {
        int c = Math.max(0, Math.min(7, screenCol(sq) + dc));
        int r = Math.max(0, Math.min(7, screenRow(sq) + dr));
        return flipped ? (7 - r) * 8 + (7 - c) : r * 8 + c;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = Math.min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (game == null) return;
        float s = getWidth() / 8f;
        label.setTextSize(s * 0.16f);
        ring.setStrokeWidth(s * 0.07f);
        cursorEdge.setStrokeWidth(Math.max(3f, s * 0.045f));

        long now = SystemClock.uptimeMillis();
        float t = slideFrom < 0 ? 1f : Math.min(1f, (now - slideStart) / (float) SLIDE_MS);
        t = 1f - (1f - t) * (1f - t);   // ease out

        int kingInCheck = game.inCheck() ? game.king(game.side) : -1;
        for (int sq = 0; sq < 64; sq++) {
            float x = screenCol(sq) * s, y = screenRow(sq) * s;
            boolean light = (Chess.row(sq) + Chess.col(sq)) % 2 == 0;
            square.setColor(light ? LIGHT : DARK);
            canvas.drawRect(x, y, x + s, y + s, square);
            if (sq == lastFrom || sq == lastTo) { square.setColor(LAST_MOVE); canvas.drawRect(x, y, x + s, y + s, square); }
            if (sq == selected) { square.setColor(SELECTED); canvas.drawRect(x, y, x + s, y + s, square); }
            if (sq == kingInCheck) {
                check.setShader(new RadialGradient(x + s / 2, y + s / 2, s * 0.7f, 0xE6FF3B3B, 0x00FF3B3B, Shader.TileMode.CLAMP));
                canvas.drawRect(x, y, x + s, y + s, check);
            }
            label.setColor(light ? DARK : LIGHT);
            if (screenCol(sq) == 0) canvas.drawText(String.valueOf((char) ('8' - Chess.row(sq))), x + s * 0.05f, y + s * 0.2f, label);
            if (screenRow(sq) == 7) canvas.drawText(String.valueOf((char) ('a' + Chess.col(sq))), x + s * 0.84f, y + s * 0.95f, label);
        }

        for (int sq = 0; sq < 64; sq++) {
            int p = game.board[sq];
            if (p == Chess.EMPTY || (t < 1f && sq == slideTo)) continue;
            drawPiece(canvas, p, screenCol(sq) * s, screenRow(sq) * s, s);
        }
        for (int sq : targets) {
            float cx = (screenCol(sq) + 0.5f) * s, cy = (screenRow(sq) + 0.5f) * s;
            if (game.board[sq] == Chess.EMPTY) canvas.drawCircle(cx, cy, s * 0.14f, dot);
            else canvas.drawCircle(cx, cy, s * 0.44f, ring);
        }
        if (t < 1f) {
            float x = screenCol(slideFrom) * s + (screenCol(slideTo) - screenCol(slideFrom)) * s * t;
            float y = screenRow(slideFrom) * s + (screenRow(slideTo) - screenRow(slideFrom)) * s * t;
            drawPiece(canvas, slidePiece, x, y, s);
            postInvalidateOnAnimation();
        } else {
            slideFrom = -1;
        }

        float cx = screenCol(cursor) * s, cy = screenRow(cursor) * s, inset = s * 0.04f;
        dst.set(cx + inset, cy + inset, cx + s - inset, cy + s - inset);
        canvas.drawRoundRect(dst, s * 0.12f, s * 0.12f, cursorFill);
        canvas.drawRoundRect(dst, s * 0.12f, s * 0.12f, cursorEdge);

        if (choosing) drawPicker(canvas, s);
    }

    private void drawPiece(Canvas canvas, int p, float x, float y, float s) {
        Bitmap b = p > 0 ? white[p] : black[-p];
        if (b == null) return;
        src.set(0, 0, b.getWidth(), b.getHeight());
        float pad = s * 0.06f;
        dst.set(x + pad, y + pad, x + s - pad, y + s - pad);
        canvas.drawBitmap(b, src, dst, piece);
    }

    private void drawPicker(Canvas canvas, float s) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), veil);
        float top = getHeight() / 2f - s * 0.75f, left = getWidth() / 2f - s * 2.4f;
        for (int i = 0; i < PROMOTIONS.length; i++) {
            float x = left + i * s * 1.2f;
            square.setColor(i == choice ? 0xFFEDE6F7 : 0xFF3A3348);
            dst.set(x, top, x + s * 1.1f, top + s * 1.5f);
            canvas.drawRoundRect(dst, s * 0.15f, s * 0.15f, square);
            drawPiece(canvas, game.side * PROMOTIONS[i], x + s * 0.05f, top + s * 0.25f, s);
        }
    }
}

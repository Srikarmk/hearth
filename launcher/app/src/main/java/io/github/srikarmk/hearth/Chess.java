package io.github.srikarmk.hearth;

import java.util.ArrayList;
import java.util.List;

/**
 * Chess rules: the position, legal moves (castling, en passant, promotion), make and undo, and how a game ends.
 * No Android code, so it is unit tested with perft counts.
 *
 * Squares are 0..63 with 0 = a8 and 63 = h1, so row 0 is Black's back rank, as the board is drawn. Pieces are
 * signed: positive White, negative Black, by kind PAWN..KING.
 */
final class Chess {

    static final int EMPTY = 0, PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    static final int WHITE = 1, BLACK = -1;

    static final int FLAG_EP = 1, FLAG_CASTLE = 2, FLAG_DOUBLE = 4;
    private static final int WK = 1, WQ = 2, BK = 4, BQ = 8;

    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    final int[] board = new int[64];
    int side = WHITE;
    int castling;
    int ep = -1;
    int halfmove;
    int fullmove = 1;

    private final int[] undoCaptured = new int[1024];
    private final int[] undoCastling = new int[1024];
    private final int[] undoEp = new int[1024];
    private final int[] undoHalfmove = new int[1024];
    private final int[] undoMove = new int[1024];
    private final List<String> history = new ArrayList<>();
    private int ply;

    static int move(int from, int to, int promo, int flags) {
        return from | to << 6 | promo << 12 | flags << 16;
    }

    static int from(int m) { return m & 63; }
    static int to(int m) { return m >> 6 & 63; }
    static int promo(int m) { return m >> 12 & 7; }
    static int flags(int m) { return m >> 16; }
    static int row(int sq) { return sq >> 3; }
    static int col(int sq) { return sq & 7; }

    static Chess fromFen(String fen) {
        Chess c = new Chess();
        String[] parts = fen.trim().split("\\s+");
        int sq = 0;
        for (char ch : parts[0].toCharArray()) {
            if (ch == '/') continue;
            if (Character.isDigit(ch)) {
                sq += ch - '0';
            } else {
                int kind = "pnbrqk".indexOf(Character.toLowerCase(ch)) + 1;
                c.board[sq++] = Character.isUpperCase(ch) ? kind : -kind;
            }
        }
        c.side = parts.length > 1 && parts[1].equals("b") ? BLACK : WHITE;
        String cr = parts.length > 2 ? parts[2] : "-";
        if (cr.contains("K")) c.castling |= WK;
        if (cr.contains("Q")) c.castling |= WQ;
        if (cr.contains("k")) c.castling |= BK;
        if (cr.contains("q")) c.castling |= BQ;
        c.ep = parts.length > 3 && !parts[3].equals("-") ? square(parts[3]) : -1;
        c.halfmove = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        c.fullmove = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        c.history.add(c.key());
        return c;
    }

    String fen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                int p = board[r * 8 + f];
                if (p == EMPTY) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                char ch = "pnbrqk".charAt(Math.abs(p) - 1);
                sb.append(p > 0 ? Character.toUpperCase(ch) : ch);
            }
            if (empty > 0) sb.append(empty);
            if (r < 7) sb.append('/');
        }
        sb.append(side == WHITE ? " w " : " b ");
        String cr = ((castling & WK) != 0 ? "K" : "") + ((castling & WQ) != 0 ? "Q" : "")
                + ((castling & BK) != 0 ? "k" : "") + ((castling & BQ) != 0 ? "q" : "");
        sb.append(cr.isEmpty() ? "-" : cr).append(' ').append(ep < 0 ? "-" : name(ep));
        return sb.append(' ').append(halfmove).append(' ').append(fullmove).toString();
    }

    static int square(String name) {
        return (8 - (name.charAt(1) - '0')) * 8 + (name.charAt(0) - 'a');
    }

    static String name(int sq) {
        return "" + (char) ('a' + col(sq)) + (char) ('8' - row(sq));
    }

    static String uci(int m) {
        String s = name(from(m)) + name(to(m));
        return promo(m) == 0 ? s : s + "nbrq".charAt(promo(m) - KNIGHT);
    }

    private static final int[] KNIGHT_D = {-17, -15, -10, -6, 6, 10, 15, 17};
    private static final int[] KNIGHT_DC = {-1, 1, -2, 2, -2, 2, -1, 1};
    private static final int[] KING_D = {-9, -8, -7, -1, 1, 7, 8, 9};
    private static final int[] KING_DC = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] ROOK_D = {-8, 8, -1, 1};
    private static final int[] BISHOP_D = {-9, -7, 7, 9};

    /** One step from sq by d, or -1 off the board; dc is the column change d is meant to make. */
    private static int step(int sq, int d, int dc) {
        int t = sq + d;
        if (t < 0 || t > 63) return -1;
        return col(t) - col(sq) == dc ? t : -1;
    }

    private static int colStep(int d) {
        return d == -9 || d == 7 || d == -1 ? -1 : (d == -7 || d == 9 || d == 1 ? 1 : 0);
    }

    boolean attacked(int sq, int by) {
        // Pawns: a White pawn attacks up the board (towards row 0).
        int pr = by == WHITE ? 1 : -1;
        for (int dc = -1; dc <= 1; dc += 2) {
            int r = row(sq) + pr, c = col(sq) + dc;
            if (r >= 0 && r < 8 && c >= 0 && c < 8 && board[r * 8 + c] == by * PAWN) return true;
        }
        for (int i = 0; i < 8; i++) {
            int t = step(sq, KNIGHT_D[i], KNIGHT_DC[i]);
            if (t >= 0 && board[t] == by * KNIGHT) return true;
            t = step(sq, KING_D[i], KING_DC[i]);
            if (t >= 0 && board[t] == by * KING) return true;
        }
        for (int d : ROOK_D) if (slides(sq, d, by, ROOK)) return true;
        for (int d : BISHOP_D) if (slides(sq, d, by, BISHOP)) return true;
        return false;
    }

    private boolean slides(int sq, int d, int by, int kind) {
        int dc = colStep(d);
        for (int t = step(sq, d, dc); t >= 0; t = step(t, d, dc)) {
            int p = board[t];
            if (p == EMPTY) continue;
            return p == by * kind || p == by * QUEEN;
        }
        return false;
    }

    int king(int who) {
        for (int i = 0; i < 64; i++) if (board[i] == who * KING) return i;
        return -1;
    }

    boolean inCheck() {
        int k = king(side);
        return k >= 0 && attacked(k, -side);
    }

    List<Integer> legalMoves() {
        List<Integer> out = new ArrayList<>();
        for (int m : pseudoMoves()) {
            make(m);
            int k = king(-side);
            boolean ok = k >= 0 && !attacked(k, side);
            undo();
            if (ok) out.add(m);
        }
        return out;
    }

    List<Integer> legalMovesFrom(int sq) {
        List<Integer> out = new ArrayList<>();
        for (int m : legalMoves()) if (from(m) == sq) out.add(m);
        return out;
    }

    private List<Integer> pseudoMoves() {
        List<Integer> out = new ArrayList<>(48);
        for (int sq = 0; sq < 64; sq++) {
            int p = board[sq];
            if (p == EMPTY || Integer.signum(p) != side) continue;
            switch (Math.abs(p)) {
                case PAWN: pawnMoves(sq, out); break;
                case KNIGHT: jumps(sq, KNIGHT_D, KNIGHT_DC, out); break;
                case BISHOP: for (int d : BISHOP_D) slideMoves(sq, d, out); break;
                case ROOK: for (int d : ROOK_D) slideMoves(sq, d, out); break;
                case QUEEN:
                    for (int d : BISHOP_D) slideMoves(sq, d, out);
                    for (int d : ROOK_D) slideMoves(sq, d, out);
                    break;
                case KING:
                    jumps(sq, KING_D, KING_DC, out);
                    castleMoves(sq, out);
                    break;
                default: break;
            }
        }
        return out;
    }

    private void pawnMoves(int sq, List<Integer> out) {
        int dir = side == WHITE ? -8 : 8;
        int startRow = side == WHITE ? 6 : 1;
        int lastRow = side == WHITE ? 0 : 7;
        int one = sq + dir;
        if (one >= 0 && one < 64 && board[one] == EMPTY) {
            addPawn(sq, one, 0, lastRow, out);
            int two = one + dir;
            if (row(sq) == startRow && board[two] == EMPTY) out.add(move(sq, two, 0, FLAG_DOUBLE));
        }
        for (int dc = -1; dc <= 1; dc += 2) {
            int t = step(sq, dir + dc, dc);
            if (t < 0) continue;
            if (board[t] != EMPTY && Integer.signum(board[t]) == -side) addPawn(sq, t, 0, lastRow, out);
            else if (t == ep) out.add(move(sq, t, 0, FLAG_EP));
        }
    }

    private static void addPawn(int from, int to, int flags, int lastRow, List<Integer> out) {
        if (row(to) == lastRow) {
            for (int k = QUEEN; k >= KNIGHT; k--) out.add(move(from, to, k, flags));
        } else {
            out.add(move(from, to, 0, flags));
        }
    }

    private void jumps(int sq, int[] d, int[] dc, List<Integer> out) {
        for (int i = 0; i < d.length; i++) {
            int t = step(sq, d[i], dc[i]);
            if (t >= 0 && (board[t] == EMPTY || Integer.signum(board[t]) == -side)) out.add(move(sq, t, 0, 0));
        }
    }

    private void slideMoves(int sq, int d, List<Integer> out) {
        int dc = colStep(d);
        for (int t = step(sq, d, dc); t >= 0; t = step(t, d, dc)) {
            if (board[t] == EMPTY) {
                out.add(move(sq, t, 0, 0));
                continue;
            }
            if (Integer.signum(board[t]) == -side) out.add(move(sq, t, 0, 0));
            break;
        }
    }

    private void castleMoves(int sq, List<Integer> out) {
        int home = side == WHITE ? 60 : 4;
        if (sq != home || attacked(sq, -side)) return;
        int kingSide = side == WHITE ? WK : BK, queenSide = side == WHITE ? WQ : BQ;
        if ((castling & kingSide) != 0 && board[sq + 1] == EMPTY && board[sq + 2] == EMPTY
                && board[sq + 3] == side * ROOK && !attacked(sq + 1, -side) && !attacked(sq + 2, -side)) {
            out.add(move(sq, sq + 2, 0, FLAG_CASTLE));
        }
        if ((castling & queenSide) != 0 && board[sq - 1] == EMPTY && board[sq - 2] == EMPTY && board[sq - 3] == EMPTY
                && board[sq - 4] == side * ROOK && !attacked(sq - 1, -side) && !attacked(sq - 2, -side)) {
            out.add(move(sq, sq - 2, 0, FLAG_CASTLE));
        }
    }

    /** Plays m, which must come from legalMoves() (or pseudoMoves(), for the legality check). */
    void make(int m) {
        int from = from(m), to = to(m), flags = flags(m);
        int piece = board[from];
        undoMove[ply] = m;
        undoCaptured[ply] = board[to];
        undoCastling[ply] = castling;
        undoEp[ply] = ep;
        undoHalfmove[ply] = halfmove;
        ply++;

        board[to] = promo(m) != 0 ? side * promo(m) : piece;
        board[from] = EMPTY;
        if ((flags & FLAG_EP) != 0) {
            int victim = to + (side == WHITE ? 8 : -8);
            undoCaptured[ply - 1] = board[victim];
            board[victim] = EMPTY;
        }
        if ((flags & FLAG_CASTLE) != 0) {
            if (to > from) { board[to - 1] = board[to + 1]; board[to + 1] = EMPTY; }
            else { board[to + 1] = board[to - 2]; board[to - 2] = EMPTY; }
        }
        ep = (flags & FLAG_DOUBLE) != 0 ? (from + to) / 2 : -1;
        if (Math.abs(piece) == KING) castling &= side == WHITE ? ~(WK | WQ) : ~(BK | BQ);
        castling &= ~rookRight(from) & ~rookRight(to);
        halfmove = Math.abs(piece) == PAWN || undoCaptured[ply - 1] != EMPTY ? 0 : halfmove + 1;
        if (side == BLACK) fullmove++;
        side = -side;
    }

    private static int rookRight(int sq) {
        switch (sq) {
            case 63: return WK;
            case 56: return WQ;
            case 7: return BK;
            case 0: return BQ;
            default: return 0;
        }
    }

    void undo() {
        ply--;
        side = -side;
        if (side == BLACK) fullmove--;
        int m = undoMove[ply], from = from(m), to = to(m), flags = flags(m);
        int moved = promo(m) != 0 ? side * PAWN : board[to];
        board[from] = moved;
        board[to] = (flags & FLAG_EP) != 0 ? EMPTY : undoCaptured[ply];
        if ((flags & FLAG_EP) != 0) board[to + (side == WHITE ? 8 : -8)] = undoCaptured[ply];
        if ((flags & FLAG_CASTLE) != 0) {
            if (to > from) { board[to + 1] = board[to - 1]; board[to - 1] = EMPTY; }
            else { board[to - 2] = board[to + 1]; board[to + 1] = EMPTY; }
        }
        castling = undoCastling[ply];
        ep = undoEp[ply];
        halfmove = undoHalfmove[ply];
    }

    /** The piece m captures, or EMPTY. */
    int captured(int m) {
        return (flags(m) & FLAG_EP) != 0 ? -side * PAWN : board[to(m)];
    }

    /** A move played in the game (not in search): kept for threefold repetition. */
    void play(int m) {
        make(m);
        history.add(key());
    }

    private String key() {
        String f = fen();
        return f.substring(0, f.lastIndexOf(' ', f.lastIndexOf(' ') - 1));
    }

    enum Result { NONE, WHITE_WINS, BLACK_WINS, STALEMATE, FIFTY_MOVES, REPETITION, MATERIAL }

    Result result() {
        if (legalMoves().isEmpty()) {
            if (!inCheck()) return Result.STALEMATE;
            return side == WHITE ? Result.BLACK_WINS : Result.WHITE_WINS;
        }
        if (halfmove >= 100) return Result.FIFTY_MOVES;
        String now = history.get(history.size() - 1);
        int seen = 0;
        for (String k : history) if (k.equals(now)) seen++;
        if (seen >= 3) return Result.REPETITION;
        if (insufficientMaterial()) return Result.MATERIAL;
        return Result.NONE;
    }

    private boolean insufficientMaterial() {
        int minors = 0;
        for (int p : board) {
            int k = Math.abs(p);
            if (k == PAWN || k == ROOK || k == QUEEN) return false;
            if (k == KNIGHT || k == BISHOP) minors++;
        }
        return minors <= 1;
    }

    long perft(int depth) {
        if (depth == 0) return 1;
        long n = 0;
        for (int m : legalMoves()) {
            make(m);
            n += perft(depth - 1);
            undo();
        }
        return n;
    }
}

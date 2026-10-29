package io.github.srikarmk.hearth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dr. Malvek's brain: alpha-beta search over material and piece-square tables, captures tried first.
 * CASUAL looks two plies ahead and adds noise, so it misses things a person would; SHARP looks three and plays
 * captures out; RUTHLESS looks four.
 */
final class ChessAi {

    enum Level {
        CASUAL(2, false, 45), SHARP(3, true, 8), RUTHLESS(4, true, 0);

        final int depth;
        final boolean quiesce;
        final int noise;

        Level(int depth, boolean quiesce, int noise) {
            this.depth = depth;
            this.quiesce = quiesce;
            this.noise = noise;
        }
    }

    static final int MATE = 100_000;
    private static final int INF = 1_000_000;
    private static final int[] VALUE = {0, 100, 320, 330, 500, 900, 0};

    // Simplified evaluation tables (Tomasz Michniewski), White's view, a8 first.
    private static final int[][] PST = {
            {},
            {0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10, 5, 5, 10, 25, 25, 10, 5, 5,
                    0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5, 5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0},
            {-50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10, 0, -30,
                    -30, 5, 15, 20, 20, 15, 5, -30, -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30,
                    -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50},
            {-20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 10, 10, 5, 0, -10,
                    -10, 5, 5, 10, 10, 5, 5, -10, -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10, -10,
                    -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20},
            {0, 0, 0, 0, 0, 0, 0, 0, 5, 10, 10, 10, 10, 10, 10, 5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5,
                    -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, 0, 0, 0, 5, 5, 0, 0, 0},
            {-20, -10, -10, -5, -5, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 5, 5, 5, 0, -10,
                    -5, 0, 5, 5, 5, 5, 0, -5, 0, 0, 5, 5, 5, 5, 0, -5, -10, 5, 5, 5, 5, 5, 0, -10,
                    -10, 0, 5, 0, 0, 0, 0, -10, -20, -10, -10, -5, -5, -10, -10, -20},
            {-30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
                    -30, -40, -40, -50, -50, -40, -40, -30, -20, -30, -30, -40, -40, -30, -30, -20,
                    -10, -20, -20, -20, -20, -20, -20, -10, 20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20},
    };

    private final Level level;
    private final Random random;

    ChessAi(Level level, long seed) {
        this.level = level;
        this.random = new Random(seed);
    }

    /** The move to play for the side to move, or 0 when there is none. */
    int best(Chess c) {
        List<Integer> moves = ordered(c, c.legalMoves());
        if (moves.isEmpty()) return 0;
        int[] scores = new int[moves.size()];
        int bestScore = -INF;
        for (int i = 0; i < moves.size(); i++) {
            c.make(moves.get(i));
            // Full window per root move: the scores are compared against each other below.
            scores[i] = -search(c, level.depth - 1, -INF, INF, 1);
            c.undo();
            if (level.noise > 0 && Math.abs(scores[i]) < MATE / 2) {
                scores[i] += random.nextInt(level.noise * 2 + 1) - level.noise;
            }
            bestScore = Math.max(bestScore, scores[i]);
        }
        List<Integer> top = new ArrayList<>();
        for (int i = 0; i < moves.size(); i++) if (scores[i] == bestScore) top.add(moves.get(i));
        return top.get(random.nextInt(top.size()));
    }

    /** How good the position is for the side to move, in centipawns, from a short search. */
    int evaluate(Chess c) {
        return search(c, 1, -INF, INF, 0);
    }

    private int search(Chess c, int depth, int alpha, int beta, int ply) {
        if (depth <= 0) return level.quiesce ? quiesce(c, alpha, beta, 0) : staticEval(c);
        List<Integer> moves = c.legalMoves();
        if (moves.isEmpty()) return c.inCheck() ? -(MATE - ply) : 0;
        if (c.halfmove >= 100) return 0;
        for (int m : ordered(c, moves)) {
            c.make(m);
            int score = -search(c, depth - 1, -beta, -alpha, ply + 1);
            c.undo();
            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private int quiesce(Chess c, int alpha, int beta, int qply) {
        int stand = staticEval(c);
        if (stand >= beta || qply > 6) return stand;
        if (stand > alpha) alpha = stand;
        List<Integer> captures = new ArrayList<>();
        for (int m : c.legalMoves()) if (c.captured(m) != Chess.EMPTY || Chess.promo(m) == Chess.QUEEN) captures.add(m);
        for (int m : ordered(c, captures)) {
            c.make(m);
            int score = -quiesce(c, -beta, -alpha, qply + 1);
            c.undo();
            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    /** Material plus placement, for the side to move. */
    static int staticEval(Chess c) {
        int score = 0;
        for (int sq = 0; sq < 64; sq++) {
            int p = c.board[sq];
            if (p == Chess.EMPTY) continue;
            int kind = Math.abs(p);
            int idx = p > 0 ? sq : (7 - Chess.row(sq)) * 8 + Chess.col(sq);
            int v = VALUE[kind] + PST[kind][idx];
            score += p > 0 ? v : -v;
        }
        return score * c.side;
    }

    /** Captures first, most valuable victim by least valuable attacker, then promotions, then the rest. */
    private static List<Integer> ordered(Chess c, List<Integer> moves) {
        List<Integer> out = new ArrayList<>(moves);
        out.sort((a, b) -> Integer.compare(priority(c, b), priority(c, a)));
        return out;
    }

    private static int priority(Chess c, int m) {
        int victim = Math.abs(c.captured(m));
        int attacker = Math.abs(c.board[Chess.from(m)]);
        int p = victim == 0 ? 0 : 10 * VALUE[victim] - VALUE[attacker] + 10_000;
        if (Chess.promo(m) != 0) p += VALUE[Chess.promo(m)];
        return p;
    }
}

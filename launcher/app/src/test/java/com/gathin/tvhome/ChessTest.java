package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Move generation against the published perft counts, plus the endings and the AI's sanity. */
public class ChessTest {

    private static final String KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
    private static final String POSITION_3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";

    @Test
    public void startPositionPerft() {
        Chess c = Chess.fromFen(Chess.START);
        assertEquals(20, c.perft(1));
        assertEquals(400, c.perft(2));
        assertEquals(8902, c.perft(3));
        assertEquals(197281, c.perft(4));
    }

    @Test
    public void kiwipetePerft() {
        Chess c = Chess.fromFen(KIWIPETE);
        assertEquals(48, c.perft(1));
        assertEquals(2039, c.perft(2));
        assertEquals(97862, c.perft(3));
    }

    @Test
    public void enPassantAndPinsPerft() {
        Chess c = Chess.fromFen(POSITION_3);
        assertEquals(14, c.perft(1));
        assertEquals(191, c.perft(2));
        assertEquals(2812, c.perft(3));
        assertEquals(43238, c.perft(4));
    }

    @Test
    public void makeAndUndoRestoreThePosition() {
        Chess c = Chess.fromFen(KIWIPETE);
        String before = c.fen();
        for (int m : c.legalMoves()) {
            c.make(m);
            c.undo();
            assertEquals(Chess.uci(m), before, c.fen());
        }
    }

    @Test
    public void foolsMateIsCheckmate() {
        Chess c = Chess.fromFen(Chess.START);
        for (String m : new String[]{"f2f3", "e7e5", "g2g4", "d8h4"}) c.play(find(c, m));
        assertEquals(Chess.Result.BLACK_WINS, c.result());
    }

    @Test
    public void stalemateIsADraw() {
        Chess c = Chess.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        assertEquals(Chess.Result.STALEMATE, c.result());
    }

    @Test
    public void kingsAloneIsADraw() {
        Chess c = Chess.fromFen("8/8/4k3/8/8/4K3/8/8 w - - 0 1");
        assertEquals(Chess.Result.MATERIAL, c.result());
    }

    @Test
    public void aiTakesAHangingQueen() {
        Chess c = Chess.fromFen("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1");
        int m = new ChessAi(ChessAi.Level.CASUAL, 1).best(c);
        assertEquals("d1d5", Chess.uci(m));
    }

    @Test
    public void aiFindsMateInOne() {
        Chess c = Chess.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1");
        int m = new ChessAi(ChessAi.Level.RUTHLESS, 1).best(c);
        assertEquals("a1a8", Chess.uci(m));
    }

    @Test
    public void aiAlwaysPlaysALegalMove() {
        Chess c = Chess.fromFen(KIWIPETE);
        int m = new ChessAi(ChessAi.Level.SHARP, 7).best(c);
        assertNotEquals(0, m);
        assertTrue(c.legalMoves().contains(m));
    }

    private static int find(Chess c, String uci) {
        for (int m : c.legalMoves()) if (Chess.uci(m).equals(uci)) return m;
        throw new AssertionError("no move " + uci);
    }
}

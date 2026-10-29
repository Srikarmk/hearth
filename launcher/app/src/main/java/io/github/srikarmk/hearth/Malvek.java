package io.github.srikarmk.hearth;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/** What Dr. Malvek, Sovereign of Vyrnhold, says over the board. Never the same line twice in a row. */
final class Malvek {

    enum Moment { INTRO, THINKING, HE_CAPTURES, YOU_CAPTURE, HE_CHECKS, YOU_CHECK, YOU_BLUNDER, YOU_SHINE, HE_WINS, YOU_WIN, DRAW, IDLE }

    private static final Map<Moment, String[]> LINES = new EnumMap<>(Moment.class);

    static {
        LINES.put(Moment.INTRO, new String[]{
                "You stand before Malvek. Few do so twice.",
                "Sit. Move. Lose. Malvek has a schedule to keep.",
                "Vyrnhold's throne room has no draws, only lessons.",
                "Malvek welcomes you to the last game you'll remember fondly.",
        });
        LINES.put(Moment.THINKING, new String[]{
                "Malvek is not thinking. Malvek is deciding how you lose.",
                "Patience. Genius takes a moment to choose among its options.",
                "Every road ends at Malvek. He is merely picking the scenic one.",
        });
        LINES.put(Moment.HE_CAPTURES, new String[]{
                "Another subject joins the dungeons of Vyrnhold.",
                "Malvek accepts your tribute.",
                "Did that piece mean something to you? How sad.",
                "Removed. As all obstacles to Malvek are removed.",
                "Your army thins. Malvek's patience does not.",
        });
        LINES.put(Moment.YOU_CAPTURE, new String[]{
                "A loan. Malvek will collect with interest.",
                "You took a pawn of Vyrnhold. Vyrnhold has many pawns.",
                "Enjoy it. Malvek allows small joys before great sorrows.",
                "Hm. Malvek permitted that. Remember that he permitted it.",
                "A scratch on the armor. The armor does not care.",
        });
        LINES.put(Moment.HE_CHECKS, new String[]{
                "Check. Malvek suggests you begin composing an apology.",
                "Your king trembles. Wise of him.",
                "Check. Run, little king. Run while running means something.",
        });
        LINES.put(Moment.YOU_CHECK, new String[]{
                "You dare raise a hand to Malvek's king?",
                "A check. Malvek's king steps aside, as a god steps over an ant.",
                "Bold. Pointless, but bold.",
        });
        LINES.put(Moment.YOU_BLUNDER, new String[]{
                "Oh. Oh, that was a mistake. Malvek will savor it.",
                "Malvek saw that coming four moves ago. You did not see it at all.",
                "Did you mean to do that? Do not answer. Malvek knows.",
                "A gift! Malvek had not prepared a thank-you note.",
        });
        LINES.put(Moment.YOU_SHINE, new String[]{
                "Interesting. Malvek will remember that move. And you.",
                "Hmph. A competent move. Do not let it go to your head.",
                "Clever. Malvek grants you the rank of worthy adversary. Temporarily.",
        });
        LINES.put(Moment.HE_WINS, new String[]{
                "Checkmate. Kneel. Then, if you must, press OK to try again.",
                "And so it ends, as it always ends: with Malvek.",
                "Vyrnhold thanks you for your service. Your king will be framed.",
        });
        LINES.put(Moment.YOU_WIN, new String[]{
                "Impossible. Malvek demands a rematch. Malvek DEMANDS it.",
                "A fluke. A cosmic accident. Malvek will be filing a complaint with the universe.",
                "You... have defeated Malvek. Tell no one. Press OK. Now.",
        });
        LINES.put(Moment.DRAW, new String[]{
                "A draw. Malvek calls it a strategic ceasefire.",
                "Neither of us wins. Malvek finds this barely acceptable.",
        });
        LINES.put(Moment.IDLE, new String[]{
                "Malvek grows old waiting. Malvek does not grow old.",
                "The clock of Vyrnhold is ticking. Metaphorically.",
                "Are you thinking, or have you fled the room?",
        });
    }

    private final Random random = new Random();
    private final Map<Moment, Integer> last = new EnumMap<>(Moment.class);

    String say(Moment moment) {
        String[] lines = LINES.get(moment);
        int prev = last.containsKey(moment) ? last.get(moment) : -1;
        int i = random.nextInt(lines.length);
        if (lines.length > 1 && i == prev) i = (i + 1) % lines.length;
        last.put(moment, i);
        return lines[i];
    }
}

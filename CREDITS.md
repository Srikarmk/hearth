# Credits

Hearth is built on [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin, taken at commit
`985bbd1` (2026-10-28). From there:

| Part | Origin |
|---|---|
| `launcher/` | cleanyourtv's launcher, with `FireHomeService` added and the dock set for Fire TV |
| `tools/` | cleanyourtv's ADB client and scripts (`tv.py`, `disable.sh`, `restore.sh`, `recovery.py`, `b64push.py`, `theme.sh`, `adb-closed.py`) |
| `lists/fire-tv-afthy7abba.txt` | cleanyourtv's `examples/amazon-fire-tv-aftdec012e` list, re-tested on an AFTHY7ABBA |
| `FireHomeService` | Ported from bansiman's Home Return ([bansiman/cleanyourtv](https://github.com/bansiman/cleanyourtv/tree/my-tv), MIT) |
| Outfit typeface | The Outfit Project Authors, SIL Open Font License 1.1 |
| Chess pieces | Colin M.L. Burnett's set from [Wikimedia Commons](https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces) (`Chess_*t45.svg`, rendered at 160 px), [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/). The images in `launcher/app/src/main/res/drawable-nodpi/chess_*.png` stay under that licence. |
| Piece-square tables | Tomasz Michniewski's Simplified Evaluation Function |
| `lists/` (other models) | cleanyourtv's Fire TV lists from `examples/` (MIT), each with its source in its first line |
| Ambience: Fireplace | ["Fantastic fireplace fire chimney hearth background"](https://commons.wikimedia.org/wiki/File:Fantastic-fireplace-fire-chimney-hearth-_background_-_texture_-_motion_graphics_-_free_video_library.webm) by free video library, [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| Ambience: Rain on the window | ["Radevormwald - Raindrops on a window 10"](https://commons.wikimedia.org/wiki/File:Radevormwald_-_Raindrops_on_a_window_10_(1)_ies.webm) by Frank Vincentz, [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/) |
| Ambience: Aquarium | ["Fish tank 1 2014-12-27"](https://commons.wikimedia.org/wiki/File:Fish_tank_1_2014-12-27.ogv) by FASTILY, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| Ambience: Snowfall | ["Snow on Myrstigen"](https://commons.wikimedia.org/wiki/File:Snow_on_Myrstigen.webm) by W.carter, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| Ambience: Ocean at dusk | ["Waves crashing on rocks off Beach 4, Kalaloch Beach, Washington 01"](https://commons.wikimedia.org/wiki/File:Waves_crashing_on_rocks_off_Beach_4,_Kalaloch_Beach,_Washington_01.webm) by Joe Mabel, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |

The ambience clips are not stored in this repo: the TV fetches each from Wikimedia Commons (1080p VP9 versions
Commons makes of the originals) the first time it is picked. Hearth plays them looped with a crossfade and darkens
the top behind the clock, and shows each one's author and licence on screen when the scene comes up.

Both upstream projects are MIT licensed; their notice is kept in [LICENSE](LICENSE).

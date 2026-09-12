# Fonote

The app's interface is in French. Labels quoted below — « Terminé », **Mes observations** — are
the ones shown on screen, left as they appear there.

## Navigation and search

Three cards: **Mes matchs annotés** (my annotated matches) on the left, **Accueil** (home) in the
centre and **Matchs enregistrés** (saved matches) on the right; from home, swiping right opens the
annotated matches, swiping left the saved matches. The **Calendrier** (calendar) is a separate
screen, opened by the "Calendrier ›" links on home: it is a destination you ask for, not a card you
come across. Once inside, swiping changes the week — left for the next one, right for the
previous one — like the two arrows in the header, which make exactly the same movement. A week
always runs from Monday to Sunday: the dates in the header are the calendar's, not a seven-day
window around the day it was opened. The
neighbouring week is already drawn before you get there: the three weeks displayed are requested together.
The annotated matches gather the fixtures with at least one non-deleted note, even finished ones,
including demos and synced notes. Search filters teams, competition, date and note text,
ignoring case and accents. A match whose details are missing
stays reachable through its identifier. The calendar has a search within the displayed week,
kept when the week changes. The fields stay visible above the lists.
Home favourites only show matches that are scheduled, in progress or at half-time;
the stars of finished matches are kept but their cards are hidden on home.

## Personalised home

A match's star in the calendar adds it to **Mes matchs favoris** (my favourite matches) on home;
a second tap removes it. Favourites are kept on this device. Adding a favourite
also starts downloading its details if the server answers, to prepare notes offline.
**Prochains matchs de mes clubs** (my clubs' next matches) shows the nearest upcoming fixture for
each club chosen in **Mes suivis** (my follows), without listing twice a fixture between two
followed clubs. Finished, cancelled, postponed or already started matches are left out of this
section; **Aujourd'hui** (today) remains available.

The server exposes `/v1/football/teams/{id}/matches`. In a club's own schedule, ESPN only
publishes fixtures already played; so the server reads the schedule of the league the club
plays in — resolved once for the season — then keeps only its fixtures, adding its European
nights. The client asks for the year ahead (100 fixtures at most per
club), then picks the next match by date. Responses join the saved calendars for offline use. A club with no
known future fixture is flagged, and successful loads are not repeated within five minutes
during a single session. Restart the server after this update to enable the new route.

## Offline use

The app automatically keeps the calendars viewed, the details of opened matches
(lineups, facts and statistics), the competition catalogue viewed and the crests displayed.
The **Matchs enregistrés** card, to the right of home, also exists in demo mode: this list stays reachable after
the app is closed, with no server, no follow filter and no date limit. A match known
only from its calendar already lets you take general notes; its lineup requires
a first download online.

Local copies are displayed immediately. Outside demo mode, calendars are refreshed in the
background if the server answers; opened matches are too, without replacing a note
in progress. Data may stay stale offline. A response without a lineup does not delete
a lineup already saved. Matches from the same period are merged by identifier,
and each detail is replaced when refreshed, with no history of responses. Saved matches
are not purged automatically. Notes stay in their own separate SQLite log; the
migration keeps existing notes and their sync state. Syncing notes
with other devices stays manual and requires the server and its token.

Check on a running emulator: `bash scripts/check-offline-android.sh` (no test dependency to download).
The checks use a separate test database: migration from version 1, keeping
notes, reopening, overlapping calendars, keeping lineups and invalid transactions.

## Football data: ESPN (personal use)

Everything comes from ESPN's public feed, with no key and no account: the competition catalogue,
calendars, a club's schedule and a match sheet. The `/v1/football/…` contract has not
changed shape — it is the one the client saves on the device and reads back offline — but
the identifiers it carries are ESPN's.

There is no longer any matching between two providers. Previously the calendar came from one
provider and the lineup from another, with no shared identifier: the two sheets were
joined by comparing display names and a kick-off time, which failed silently as soon as a
club was spelt differently on one side than on the other. A match sheet is now read
directly by its identifier, on `summary`, which answers whatever league is named —
so a saved match opens without anything to look up first. The 22 starters must
be present and distinct, otherwise the lineup is declared unavailable and the match stays
openable for general notes.

Shirt colours come from ESPN: they are lightened so that the number stays
legible, and the away team switches to its alternate colour if the two look too
alike. An in-memory cache limits calls: five minutes at least for home, calendars and followed clubs, even today's.
Only the details of a match opened live may be read again after thirty seconds. The
catalogue and a league's list of clubs last a day. A calendar queries
the thirteen competitions, four at a time: requested one after another they would keep the
client waiting on a page it has already drawn from its local copy, all at once they would arrive
in a burst on a feed where we are a guest. A league that does not answer stays quiet without
failing the others. ESPN itself declares its feed stale after nine seconds, so this pace
stays well below what its own cache expects.

On home and the calendar, pulling down from the top of the list and releasing
refreshes the data. A loading indicator appears centred above the content, then
folds away gradually when loading ends. During a refresh, the content can still be
pulled slightly: it eases back on release, with no new request.
Home also reads again the favourite matches and the next matches of followed clubs.

The gesture reads the server again without forcing ESPN: the cache durations above still apply,
and simultaneous requests for the same resource share a single ESPN read.
When the provider fails, its last cached response stays available and a new
attempt waits five minutes, even if no response had been obtained yet. With no connection,
the data already saved on the phone can still be viewed.

The server also reports how the match unfolds — substitutions, goals, cards, actual kick-off and
half-time — in `timeline` and `clock`, substitutes in `bench`, team counters
in `team_stats`, the stadium, referee and attendance in `ground`, and per-player counters
in the `stats` of each lineup row. The server passes on the provider's raw keys
(`possessionPct`, `wonCorners`…): naming them in French is the client's business,
as with competition codes. None of this enters the operation log:
the provider's facts are shown next to the notes, never inside them, so that the summary keeps
measuring only what has been recorded.

The club's three-letter code and its crest travel with the lineup, in each team's `abbreviation`
and `logo`, next to the `tla` and `crest` every calendar entry already carries. Since
there has been only one provider the two say the same thing; the client reads both so
that a club named from either source fits in the same badge. The crest kept
is the one ESPN draws for a dark background when it publishes one — the two files are
often identical, but the note is drawn on a night lawn. A crest that is not a
raster image is discarded rather than drawn as an empty square, since `BitmapFactory` does not
read SVG. The client keeps downloaded crests on disk, at display size, to
find them again offline.

A match card carries a small pitch icon when its lineup is already published:
it is an invitation to open the fixture now rather than after the final whistle. The
calendar only asks for it with `lineups=1`, and the server only checks the matches whose
answer is both knowable and useful — kick-off within two hours, in progress, or
finished less than four hours ago. The rest comes back as `lineup_status: "unknown"`, never as a
value that was not checked: the icon is only drawn on what was looked at and found,
and its absence promises nothing. A European night thus costs a few reads instead of one
per fixture of the season — and those reads are not wasted, since opening the match then
finds the sheet already in hand. The device remembers what it has seen published: a calendar that
answers `unknown` does not remove a badge already earned.

Two things are lost with the former provider. ESPN does not publish a matchday number: a
league card shows "Championnat" where it used to show "Championnat · J3". And since the
identifiers changed space, the follows, favourites and matches already saved on
a device refer to numbers this feed does not know: they have to be ticked again
once. Notes written before the switch carry a match identifier in `fd-…`; the log
being immutable, the server still accepts them and the client shows them as matches whose
details are not downloaded, without ever mistaking them for an ESPN match.

Unavailable lineups or provider errors no longer prevent opening
general notes. Positions on the pitch are schematic, not guaranteed tactical
positions, but each player is placed according to the position published by ESPN
(`Center Left Defender`, `Attacking Midfielder Left`…): depth comes from the position's
family, the side from the qualifier. Sides are drawn as they are seen, not as they are
named: the home team attacks downwards, so a player's left is the image's
right — the right back is drawn on the left of the pitch, as `match.json` has always written it
for the 2018 final (Pavard at 0.13, Hernandez at 0.87). The away team is flipped. `formationPlace` is not used for placement — it is the
classic shirt numbering, where 11 is a winger and 9 the centre forward, not an order
of lines; relying on it put a left attacking midfielder up front. The lines drawn are therefore
those the positions describe, and they match the announced formation in the vast
majority of cases; the remaining gap is a bunching of the front line, never a player
on the wrong side. A position with an unknown label makes the team fall back on the
published formation, and failing that on 4-4-2, rather than scattering the eleven. ESPN is a non-contractual feed: its availability may change.
This integration is meant for the requested personal use, with no guarantee of coverage.
After changing the server, restart it — or launch it with `--reload` from the start, which takes care of that.

First Android prototype for taking football notes, with a personal server shared with the future Quest client. No subscription and no quota-limited API: the bundled lineup is that of the France–Croatia final of 15 July 2018, extracted offline from [StatsBomb Open Data](https://github.com/statsbomb/open-data) (match 8658) into `android/app/src/main/assets/match.json`. Personal use; this data is not redistributed.

## What works in the code

- Home, calendar, follows and options follow Android conventions: title and actions in a bar at the top (back on the left, ☆ follows and ⚙ options on the right) instead of big buttons in the content, touch feedback on every clickable surface, system bars in the page's colour. A match reads like a match: local time (no more UTC) or live status on the left, the two teams and their score in the centre, the competition below. The calendar groups its fixtures by day; an empty list offers to go and pick follows. Its header — title, week and search — does not move: only the weeks slide under it, and the week that arrives takes its place back in the middle without anything seeming to move. Provider codes are translated ("REGULAR_SEASON" becomes "Championnat · J3"). The icons (wheel, star, arrows) are the project's own `VectorDrawable`s, in `android/app/src/main/res/drawable/`: the platform's `android.R.drawable.ic_menu_*` are pre-Material raster images that change from one manufacturer to another, and a font glyph like ⚙ falls back on the system emoji.
- Pitch with the 22 starters placed at their actual position, each team in its formation (4-4-2 / 4-3-3).
- **The match fits in five cards side by side, with the pitch in the middle**, and no longer in a
  pitch followed by a row of buttons. To the right is what the provider reports — the
  **faits** (facts), then the **statistiques**; to the left what I wrote — my **observations**,
  then the **bilan** (summary). Each direction thus says what it brings back, the work stays in the
  centre, and the four bottom buttons disappeared along with what they hid: notes and the summary
  became cards, syncing became the gesture every other page already uses
  — pulling the page down —, export moved to the foot of the observations it
  exports, and the server is configured in Accueil → Options, where the rest is configured.
  Nothing is left under the pitch: no row of buttons, no reminder of the neighbouring cards, no
  status line. Swiping is the only way, and it is the same on all five cards; a bar
  naming the neighbours cost a full row to say what a finger discovers in
  a second. All the height freed this way — two rows — goes to the lawn.
  **Undo** (↶) and **redo** (↷) moved to the end of the prompt line of the input
  panel, which has room to spare at rest: correcting the work sits close to where
  the work is written, without costing the pitch a row. Both are always there, greyed out
  when they have nothing to do: a button that appears and disappears shifts its neighbour, and the
  finger aiming for "undo" landed on "redo".
- **Faits du match** (match facts), the card to the left of the pitch: you bring it in by swiping
  right, you go back by swiping left, and the back gesture does the same before
  touching the note. It carries the score, the match thread (goals with assister, cards,
  substitutions, by minute and in the team's colours), then the stadium and the referee.
  The pager is written in the project (`Pager.java`): the client carries no interface
  dependency, and what a pager adds to a horizontal scroll comes down to snapping and the
  notion of a displayed page. A page turns after a fifth of the screen, not half of it.
  Rows that scroll sideways — the action palette, a note's players — take
  the gesture for themselves by intercepting it before their own buttons, otherwise those consume
  the tap and the card would turn instead of scrolling the palette; a row that already fits
  entirely on screen lets the gesture through instead. The back gesture does not jump to the pitch:
  it undoes one card at a time, and it heads back towards the pitch whichever side you
  come from — the work is in the middle of the stack. A separate page, announced as such — « rien ici n'entre
  dans votre journal ni dans votre bilan » (nothing here enters your log or your summary) — because
  the summary promises to measure only what has been recorded, and a goal counted by the provider
  is not an observation.
  Attendance is left out when it is zero, which means "unknown" and not "nobody".
- **Statistiques**, one more card to the left of the facts: one more swipe
  right brings the two teams' counters face to face, under each one's name and in its colour.
  A separate page because a column of figures is scanned at a glance while a match thread is followed
  line by line, and titled "Statistiques" — what the card shows, not who provides it; the
  source sits in the grey line below, with the same reminder that none of it enters
  the log or the summary. Of the 28 published figures, 13 are shown: the derived
  percentages only repeat the pair above them. A match about which the provider reports
  nothing but still counts has no facts card, and its first swipe right
  therefore leads straight to the figures.
- During a followed match, the app asks again for the lineup **once a minute**, and only
  where it is useful: match screen, from an hour before kick-off until the 140th minute,
  never while a note is open — players must not move under the finger. A
  refresh that fails is a missed refresh, not an error message: notes
  are local and the next minute tries again.
- The pitch follows the match: a published substitution brings the player on at the stated minute, in the place
  of the one they replace, and the player going off leaves the lawn. Notes already written keep their players —
  the log is never redrawn, and a player who went off can still be removed from a note in progress with the panel's
  cross. A chain of changes on the same place is passed along; a change that names
  someone absent is ignored, so that a feed contradicting itself can never empty a place.
- **The benches are at the edge of the pitch**: each club's substitutes on its touchline — the
  top team on the left seen from its goal, the bottom one on the right seen from its own, the U-turn
  the pitch already makes the opposing team take —, by shirt number, and the **coach** at the end,
  on the goal side. A substituted player goes back to the bench, greyed out. They are tapped like
  a player on the pitch: they enter the note with their action, and the summary. The lawn gives up
  its outer margin and a little width to make room for them; a lineup published without a bench
  keeps the full width. ESPN does not publish football managers, not even a name: the coach is
  "Coach PSV", identified by their club (`espn-coach-<club id>`), and the server accepts that
  identifier in a note like an ESPN player.
- **Nine themes to choose from in Accueil → Options**, the original theme included. A theme is not just a palette: it also carries its corner radii (card, control, round action), the way an ordinary button is drawn — filled, filled under a hairline, or hairline only —, whether cards have an edge, and the weight, case and letter spacing of titles; two themes differing only in hue would read as the same app in a bad mood. Each thumbnail in the picker is drawn in the theme it offers, with its own colours, corners and button style. The choice is kept from one session to the next; dialogs follow the theme, light or dark, and the system bars switch their icons to ink on a light theme.
  - Six **card** themes, in the tools family: « Terrain » (lawn green, lemon accent, the original), « Minuit » (slate, lavender gradient, wide corners and cards edged with a hairline), « Papier » (light background, white cards, plain blue), « Stade » (TV gallery black, mint neon, sharp corners, outlined buttons and titles in spaced capitals), « Argile » (warm paper, olive, all in round pills) and « Diagonale », a tribute to AS Monaco.
  - « Diagonale » is the only one that draws on its background (`Skin.sash`): the screen is split from one corner to the other like the Monaco shirt, from the right shoulder to the left hip — seen from the front, from the top-left corner to the bottom-right one —, with the upper half veiled in red. A single diagonal, held by the window behind transparent pages: it stays still when a list scrolls or a card slides, and it runs under the system bars. The cards, for their part, stay white; with each one split, a list of matches became a pile of shirts. A veil and not plain red, because titles and lists cross the split: `SkinCheck` requires them to read on both sides. Plain red stays on the main button and links, and the ring of the player being noted fades from red to white. A miss takes burnt orange there, so as not to be mistaken for the accent. And the lawn is a lighter green there (`Skin.lawn`): the night green of the other themes made a dark hole in such a pale page.
  - Three **flat** themes (`Skin.flat`), in the social apps family: « Fil », « Vert » and « Bleu ». What they share is not a colour, it is one surface less — no cards at all: rows sit directly on the page with a hairline between them, the bar icons lose their pill, and the lawn becomes the only coloured block on the screen. « Fil » is monochrome on a neutral grey, « Vert » a midnight blue that only brings out its green on three elements, « Bleu » a single blue with fully pill-shaped controls.
  - A theme may **sign with a gradient**, but in one place only: the ring of the player being noted (`Skin.ring` / `ringEnd`), drawn as a sweep around the badge rather than as a stroke — a stroke only carries one colour. « Fil » puts its orange-magenta there; it is the only thing on screen that is neither a control nor a surface, and so the only one that can afford it.
- **What a theme does not dress**: the lawn stays green — a light theme can at most lighten it a shade, without the white of the lines ceasing to stand out on it — and shirts keep the clubs' colours, because they are match data and not scenery. What crosses that border is adjusted rather than replaced (`Skin.readable`): a club's colour is lightened or darkened just enough to read on the page at hand — a mustard yellow disappears on white paper —, and a light theme's accent, dark by construction since it must carry on a pale page, is lightened before going onto the pitch. `Skin` is Java without Android, so it can be measured off device: `SkinCheck` checks each theme's WCAG 2.1 contrasts (inks at 7:1, everything that carries meaning at 4.5:1), on the page, on cards, on badges and on the pitch's name plates. An added theme that does not hold these ratios fails there rather than on a reader's screen.
- **The name at the top of the home page is a drawn wordmark** (`Logo`, painted by `Wordmark`), not a heading: a heading takes the theme's face, and a name has to keep its shape in every theme. Only its colours follow the theme — the ink, and the accent for one spot. FOOT shows through FONOTE by the letters' cut: F, O, O and T are square, N and E have their outer corners bevelled. Behind the word runs the halfway line, a hairline that stops short of each letter and only shows where there is room; the first O is the centre circle, with the kick-off spot in the accent. The line is drawn on a layer cleared around the letters, so the gaps show the page itself, even across the cut of « Diagonale ». `LogoCheck` keeps the line a hairline — a barred O reads as a Θ —, the spot on it in the middle of the first O, and the spot at the 3:1 a shape must hold on every page. On a real start, the mark makes an entrance (`Kickoff`, 1.5 s): FOOT first, set tight; then the word opens — the second O and the T slide right, the N grows in the gap it pushes open, the E comes in behind the T — and the spot gives the kick-off, the line running out from it. It blocks nothing, the page is usable underneath; it waits for the window's focus, since the first frames are drawn under the system splash screen; its clock is held by the activity, so the home page being rebuilt when the fixtures arrive does not restart it; and it does not play after a rotation, on coming back from a match, or when Android is set to remove animations. `KickoffCheck` holds that it opens on FOOT alone and ends exactly on the mark at rest.
- Two badge styles to choose from in **Accueil → Options**: « Verre » (glass: dark disc, the team's colour as a ring, a halo and on the number) or « Plein » (solid: painted disc). The choice is shown by a preview drawn with the same code as the pitch, and it is kept from one session to the next. Each player's name has its own plate, drawn on a separate layer, above **all** the badges: on a five-line formation, half a lawn cannot fit six rows without overlap, so the question is not avoiding it but choosing who wins — a name always stays legible, a half-covered number keeps its colour and its place. Only the player being noted comes in front of the names, so that reading a neighbour never costs the number of the one being noted. The touch target is the badge itself (44 dp) and not the box that carried the name: two neighbours are harder to confuse with a finger.
- Two gestures to take a note: tap a player, then their action. There is no save button — each action writes the note straight away.
- A note describes a moment, not a player. While a note is open, the panel **collects**: each player tapped joins it with their own action, and "A scores, B gives the assist, C misses the save" follows on in one go in six taps, without leaving the pitch. With no note open, tapping a player starts one. The only gesture left to say out loud is therefore the end of a moment: **« Terminé »** (done), which brings back the list of notes. The bin next to it throws away the note in progress; as long as no action has been chosen there is nothing to throw away, and the button says « Abandonner » (discard).
- **Free note and match note: a single written note, and only the minute tells them apart.** The quick note is tapped — a player, an action, and it is written straight away; the written note takes its time: you type the text you want, and it is only written on « Terminé », since its text is the note and comes last. « Abandonner », next to it, closes without writing anything: a new note leaves nothing behind, a reopened note stays as it was. These are two buttons and not a single one that changes its word — the first text typed switched the only button to « Terminé », and nothing let you give up any more; « Terminé » stays disabled while there is nothing to write, and a note already written keeps its bin, reduced to the icon. The two buttons of the panel at rest open the **same panel**: « + Note libre » (free note) at the clock's minute, « ✎ Note de match » (match note) with no minute. The players, substitutes and coaches tapped there are **named**, never credited — a tick on their badge, nothing in the summary —, and the two crests under the pitch make it a club's note, one or the other, never both. The minute heads the line; tapping it corrects it, « Sans minute » (no minute) turns it into a match note, and a match note given a minute becomes a free note again: going from one to the other rewrites nothing else. Only the written note can do without a minute: an action happens at a minute. Old notes without a player — a minute and a text — reopen in this panel as free notes.
- **Tactical note**, the second note-taking mode, opened by « ▤ Tactique » next to
  « + Note libre ». Quick mode answers "who did what, and at which minute"; this one
  answers "where, and towards whom" — a pass deserves to be drawn when what matters is the line
  it took and the players it took out, and no palette of eighteen symbols says
  that. It is **the same note** underneath: same identifier, same minute, same comment, same
  recap, and actions given here count in the summary exactly like those tapped
  on the pitch. Only the surface changes, and it takes the whole screen — a board that shares its
  space with a panel is a board you cannot draw on.
  - **« Abandonner » and « Terminé », two buttons**, as on the written note. The board has no
    save button — each stroke is written straight away —, so giving up must write the
    way back: a note born on the board is deleted, a reopened note is put back
    as it was when opened — minute, actions, comment and drawing —, and only what has
    changed is written again. « Terminé » stays disabled as long as nothing is drawn; a reopened note keeps
    its bin, reduced to the icon.
  - Two boards: **blank pitch**, where you place the three or four players who matter
    (the « + équipe » buttons place them at their actual position, including a substitute in the place they hold at
    that minute, and the rest of the gesture is a correction rather than a placement from nothing),
    and **full pitch**, the 22 in their formation, which you move around. You can switch from one to the other at
    any time; emptying the pitch can be undone with `↶`. A nameless counter — « Pion — Strasbourg » — stands in for the opponent whose only role is to have been taken out.
  - With no tool selected, dragging moves players; the four
    strokes draw. A sliding finger is ambiguous — "the player was further left" or "the ball
    went over there" — and guessing wrong costs either a lost stroke or a moved player who was
    well placed. Saying it costs one tap before a series of strokes and never costs a mistake.
  - **Moving in batches.** With no tool selected, dragging on **empty** grass draws a frame: all the
    players caught in the rectangle are selected, and dragging one of them takes them all along. A
    schema is very often a block — a defence stepping up, a midfield sliding across — and
    moving them one by one is the surest way to give up on the drawing. The gesture was free: in
    this mode, a drag on the grass did nothing. The move is **rigid**: the travel is
    bounded once for the whole group and not player by player against the touchline, otherwise
    a defence pushed towards the corner would squash against the edge instead of keeping its shape.
    Grabbing someone outside the group releases the group; tapping the grass releases everything. The panel
    says what the tool in hand can do when nothing is selected, and its height never
    changes — choosing a tool or a player does not move the pitch under the finger.
  - **Long press on a player**: they join or leave the selection. A rectangle is a
    coarse tool — it catches the defensive midfielder standing between the lines aimed at — and
    redrawing it to correct one man costs more than correcting that man. So the group is
    only touched once the finger has said what it wanted: replacing the selection as soon as
    the press starts would lose all the others to a long press meant to remove just one. The duration
    is counted in the view (`ViewConfiguration.getLongPressTimeout()`): it handles each
    event itself, so the long press the platform would have scheduled never is.
    The move threshold is not part of the path — the drag starts from where the touch
    stopped being a press, and nothing moves before that: a tremor under a resting finger is not
    a drag, otherwise it would carry off the selection before the long press had time to speak.
    Careful, the gesture does not mean the same as on the quick mode's pitch, where a long
    press **removes** the player from the note; here a counter is removed with the eraser or with the panel's `×`,
    and a long press only touches the selection.
  - The board's edges are reserved for the app (`setSystemGestureExclusionRects`): a
    full-back stands on the touchline and a group is framed from outside, two gestures
    that start where the system reads a back swipe — dragging Maronnier to the left left
    the note instead of moving the player. The platform caps what an app can claim at 200 dp
    per edge, so the drags closest to the frame are protected, not all of them.
  - Four strokes, told apart by the **shape** of the line and never by its colour — colour already names
    a team: pass (solid line), run without the ball (dashed), carry
    (wavy), shot (double line). Each starts from a ball except the run, which is made without it.
    Four of the palette's five marks (pass, run, shot, eraser) are the project's own `VectorDrawable`s,
    drawn the way the board draws: the solid line and its arrowhead, the dashes,
    the two rails. The fifth is the system's ⚽, kept because no ball drawn in a
    single stroke makes a ball — a pentagon in a circle reads as a target, with its
    seams as a wheel. All fit in the same 20 dp square, whatever they are
    made of: the mark is placed there as an image and not in the line of text, otherwise the
    five cells did not line up — a glyph hangs from a baseline and leaves below it the
    descender of a letter nobody wrote, and an emoji fills its em square while an
    arrow leaves air in it. The ⚽ is matched to the ink of its neighbours, not to the box, otherwise
    it crushes the row. The mark + name pair sits two dp below the middle of the cell:
    exactly centred, it looks high, the name reserving below its baseline the room for a
    descender none of the five words has — the ink stops before the bottom, the box does not, and it is
    the ink that gets read.
    The stroke follows the finger: a straight drag gives a straight line, a curved drag keeps its curve.
    The finger's tremor, for its part, does not survive: each point recorded between the two ends is
    moved twice halfway towards the midpoint of its neighbours, which erases exactly what alternates
    from one sample to the next — the definition of a tremor. The displacement is capped at a
    hundredth of the pitch, so a deliberate angle is rounded and not cut, and the two ends never
    move: they are the players the stroke snapped to. Smoothing happens when drawing
    (`Track.eased`) and not when saving, so that the ball and the player follow the very line
    the board draws, and notes taken before read back like those taken after.
  - A stroke's start **snaps** to the player next to it, and so does the end when
    it is the ball that travels: "from Ripart to Yassine" is thus exact without aiming to the pixel,
    and the pass does leave from Yassine's feet at the instant Yassine receives it. **The end of a
    run, however, does not snap**: it lands where the finger lifted. A player running
    alongside a teammate has not run into them, and dropping the runner right on top puts two
    shirts on the same blade of grass — a placement nobody asked for. The line is then
    pulled back from the disc it touches, otherwise the arrowhead disappears under the player it
    points at. A stroke keeps the coordinates it was drawn with: a schema records
    where the ball went at an instant, not a link that would follow a player.
  - The two buttons that add a player carry the club's crest and its three-letter code — "PSG",
    "MON" — where its name never fitted: a quarter of this row is about sixty
    dp, and "Paris Saint-Germain" ends up as "Paris Sain…" there. The full name is still read out to whoever
    listens to the screen. The crest joins the three letters when it arrives: a note opens well
    before an image loads, and the button does not wait for it to be usable.
  - The tactical board's badges are noticeably smaller than the pitch's: 22 dp instead
    of 32. A player measures a metre on a 68-metre lawn, that is five dp — a badge to
    scale would be a dot, and it has a number to carry. Twenty-two dp is still three
    times a man, but four players in a corner stay four players and a pass between
    neighbours stays a pass, which thirty-two — a good six metres of turf — did not allow.
    The ball and its offset follow the same reduction; the match screen's pitch, for its part, does not
    change: there you point at a player with a finger, you do not draw. The grab radius
    (26 dp) and snap radius (34 dp) are independent of the drawing and stay the size of a
    finger.
  - **↶**, in the note's bar, undoes the last gesture: a botched stroke, a player moved
    by mistake, an eraser that took the player instead of the line. The log does record
    each state — that is what the match screen's ↶ walks back — but not while a note is
    still open, and you would have to leave the note to undo a stroke just made in it.
    So the history keeps whole schemas, forty steps at most: a schema is bounded by
    construction (thirty counters, forty strokes, a hundred and twenty keys), a step costs a few kilobytes,
    and nothing can drift out of sync with the board the way a hand-written inverse
    operation would. The step is taken **after** the change, since every change goes through the same
    point: the state before is simply that of the previous pass. A change that leaves the
    schema identical is not a step, otherwise you would have to press twice. Each note opens
    on its own history, and the button is disabled when there is nothing left to take back. Going
    back is written to the log like any change: here too,
    undo writes the compensating operation.
  - Tapping a player on the board opens the full palette for them, or « Aucune action » (no action). What
    is on the board **is** the note: erasing a player takes away the action they had been given.
  - No save button here either: each stroke, each move, each erasure
    writes straight away. Each of a note's three parts — the participants, the text, the schema —
    is only emitted again if it has really changed, otherwise sending the participants again with every stroke
    would bury the log under versions that say the same thing.
- In **« Mes observations »** (my observations), a drawn note is shown drawn: « ↗ Bonne passe — 20 Ripart »
  says almost nothing about a moment whose whole point was the line the ball took. The card
  therefore carries the board itself, in miniature, and tapping it reopens the board.
- Each card in **« Mes observations »** carries its two actions at the top right, two icons
  in the same stroke: the **pencil** reopens the note where it was written (panel, or board for
  a drawn note), like a tap on the card; the **bin** asks for confirmation.
  The long press hid them from anyone who did not know about it. The summary of the latest notes under the pitch no longer deletes anything: everything
  is corrected or erased from the list.
- A note that only carries a schema is summed up by **the last action of the sequence** —
  « 17 Vitinha passe à 29 P. Brunner », « Frappe de 9 Mbappé », « Conduite de 10 Golovin »,
  « Course de 2 Hakimi » — instead of the word « Schéma », which named the thing without saying anything about it: you
  read back a list of moments to find one, and all the drawn moments looked alike.
  The last, because three good passes and a shot are remembered as the shot, and
  a schema is drawn towards its final gesture whatever it tells before. Everything is weighed on
  the clock the sequence already keeps, so a run made after the pass gets the last word
  just as well as the ball; at equal instants, the ball gives the name, since it is what
  the eye follows. Never the whole sequence: a line in a list is not a sequence, and the
  note is a finger away. The carry is read off the ball, exactly as the board reads it
  to choose between a dashed line and a wavy one; old schemas, whose strokes
  belong to nobody, are named by their shape alone (« Passe », « Tir »), and a board
  where players were only placed announces how many.
- A note's summary always follows the same order, whatever the input order: the strongest first, the good before the bad, ties broken by palette order. It is derived from the action weights, with no extra table, and only applies to rendering — the log keeps the actual input order, so changing one's mind about this order rewrites no note.
- The back gesture undoes the current screen instead of leaving: it first brings you back to the pitch, one card at a time and whichever side you come from, then closes the open note while keeping it, and only leaves the app as a last resort.
- A player is removed from the note with a **long press on the pitch**, where they were put — the cross on their badge does the same. A tap on a player already in the note targets their line to correct their action, which ruled out double-tapping. Removing a note's last player erases it.
- A note left open while the match moves on is the only risk of collecting: after two minutes, its minute badge turns amber.
- The lineup's source (« 4-1-2-1-2 / 4-2-3-1 · ESPN, placement schématique · Ligue 1 ») is no longer written on the match screen at all: it was read once and took up a row forever. Formations can be seen on the pitch, and the competition is already in the header strip. Only a missing lineup still deserves a sentence, and an empty pitch has all the room to carry it.
- Lineup in a fixed strip under the pitch, never overlaid: the pitch stays visible and clickable. Its height does not vary, so that opening a note never moves the players under the finger.
- Eighteen actions paired gesture by gesture, success above, failure below: good/bad, goal/own goal, assist/lost ball, pass/missed pass, dribble/failed dribble, shot on target/shot off target, defensive action/lost duel, save/missed save, yellow, red. Each carries a weight; polarity, colour and summary follow from it, instead of being picked by hand.
- On each summary card, a grey line carries what the provider counted for this player —
  goals, shots, saves, fouls, cards — next to the rating and never inside it. Only non-zero
  counters are written: a line of twelve zeros says nothing. Counters that describe the team and
  not the player (goals conceded, shots faced, saves) are only shown for the goalkeeper: ESPN puts them
  on everyone's line, and « 1 encaissé » (1 conceded) on a striker reads as their fault. A
  goalkeeper who came on during the match, whom the provider only calls "Substitute", is recognised by what
  was counted for them.
- Per-player summary derived from the notes: signed balance on the shirt at rest, a dedicated card two swipes from the pitch — after my observations, since a per-player rating is read after the notes that make it — with a rating out of ten (base 6, half a point per point), the detail that justifies it and the reminder that it only measures what has been recorded.
- Automatic clock from the **actual** kick-off when the provider publishes it, and not the announced
  time: a kick-off delayed by ten minutes otherwise shifted every note of the match. The note's minute
  is prefilled. Adjustable to the broadcast's minute, half-time pause, kick-off at 0′, restart at 45′; it survives the app being closed.
- History, optional comments, deletion.
- `↶` undoes **the last gesture**, and not the last line of the log: a deletion is
  undone, a completed note goes back to its previous version, a comment to the text it
  carried. Writing a match note or a free note means writing the note then its text —
  two lines for a single gesture, and undoing the text left behind an empty note that had to be
  undone a second time. So the tail lines of the same note are undone together, up to and
  including the write of the note; a note born of the gesture goes with a single deletion, which
  leaves its text and its schema intact in the log — restoring it brings it back as it was,
  and not emptied of half of it. Never the same kind of operation twice, otherwise they are two gestures:
  correcting the text of a note written earlier undoes the text, not the note, and a board drawn
  stroke by stroke is undone stroke by stroke. Nothing is removed from the log — undo writes the
  compensating operations, so it syncs like everything else. Any new action puts
  the cursor back at the end.
- `↷` redoes the last undone gesture, over as many levels as were undone, in reverse
  order. Redo is not a log operation but the path just walked in
  reverse: it lives in memory, for the session, and closes as soon as anything
  new is written. Redo writes the gesture's lines again as they are; a note born of the gesture, undone
  by a single deletion, is simply restored, and its text comes back with it since it
  had never left the log. Each undone gesture remembers its **position** in the log, and
  not the number of lines that follow it: the log only grows, so a position does not
  move, whereas what follows it grows with every round trip. Once redone, the cursor rests
  on the gesture again, so that undo undoes it again and a second press finally goes back to the gesture
  before. A sync renumbers the log and slips other devices' lines into it:
  it empties the redo path, whose positions would no longer point at what they pointed at.
  Neither one says anything: the note disappears or comes back in plain sight, and a toast
  repeating it would hide the bottom of the panel for as long as it takes to read.
- SQLite on Android; offline saving, kept after the app is closed.
- **Pulling any match card down refreshes the match.** The gesture asks the
  provider again for what it publishes about this fixture, and overrides what holds back the automatic
  tracking — once a minute, and only in the window where the lineup changes: whoever
  pulls the page is asking now, and "not time yet" cannot be told apart from a failure by
  whoever is looking at the screen. Only the real obstacles remain, each stated as it is: no address,
  a match that does not come from the provider, demo mode, or an open note — players must
  not move under the finger.
  The same refresh row as on home and the calendar shows it: it takes exactly
  the height the finger opened, so the page does not move a pixel on release, then
  folds down to its 56 points for the duration of the request and disappears. It lives above the pager and not
  in the cards, otherwise it would only hold the opened room on the one card carrying it; folded
  away it costs the pitch nothing. A success therefore has nothing to announce — the row showed it —, and
  only a failure still deserves a sentence. The refreshed lineup is only taken up once the
  row has closed: redrawing the screen holds the frame for as long as a closing, which would spend
  its whole run there.
- Manual sync with an SQLite server, authenticated by a personal token: it starts
  with that same gesture, silently and without asking for anything. Without a token it has nothing to do and stays
  quiet: complaining about a missing token to someone who asked for a refresh would be answering the wrong question.
- JSON export of the observations through Android sharing, offered at the foot of the « Mes
  observations » card and only when there is something to export.

The client is written in Java with native Android widgets, with no interface dependency. The Kotlin/Compose mentioned during scoping is not used in this prototype. No provider SDK is coupled to the notes.

## Changing the match

`android/app/src/main/assets/match.json` is produced offline, once, by a script with no dependencies:

```bash
python3 scripts/fetch-match.py 8658 --id wc2018-final \
  --competition "Coupe du monde 2018" --stage Finale --date 2018-07-15 \
  > android/app/src/main/assets/match.json
```

The argument is a [StatsBomb Open Data](https://github.com/statsbomb/open-data) identifier; team colours come from TheSportsDB (free, no key). Official colours cannot be used as they are on the dark lawn — the Bleus' navy drops to a 1.02:1 contrast there — so the script keeps the hue and raises the lightness up to a legible threshold, with a different target per team so that they also differ in lightness and not only in hue. `colourOfficial` keeps the original value.

Positions come from a position → point table kept in the script: right for classic formations, to be checked on rarer systems.

## Starting the server

Python 3.12 or later, with no dependencies to install. From the repository root:

```bash
export FONOTE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
python3 backend/server.py --db fonote.sqlite3
```

During development, `--reload` restarts the server every time a file in `backend/` is saved —
with no dependency, one process watches modification times and restarts its
child. The watching process never imports what it watches: a typo only
kills the child, the traceback shows in the terminal, and the next save brings back a server
that works. A restart also empties the ESPN in-memory cache and reads the `.env` again, which is
precisely the point.

```bash
python3 backend/server.py --db fonote.sqlite3 --reload
```

Anyone who prefers a familiar tool gets the same thing without adding anything to the repository, `uvx`
running the watcher in a disposable environment:

```bash
uvx watchfiles 'python3 backend/server.py --db fonote.sqlite3' backend
```

Detection there is immediate rather than polled every second, at the cost of a first launch
that needs the network. `uvicorn --reload`, however, does not apply: it needs an ASGI
application, and this server is built on `http.server`.

Configure the same token in the app. By default the server listens only on `127.0.0.1:8080`. For a phone connected over USB:

```bash
adb reverse tcp:8080 tcp:8080
```

Home and the calendar of real matches have nothing to configure: the ESPN feed is public and
asks for neither key nor account. The server only needs Internet access.

The server automatically loads the `.env` file at the project root, whatever the
launch directory — it now only looks for `FONOTE_TOKEN` there. Variables already exported take
priority. The `.env` file is ignored by Git; its content is never executed as code.

The server acts as a proxy limited to competitions, teams and matches: the app never talks
to the provider directly, and what comes out of the server is the Fonote contract, not ESPN's
raw response.

Then use `http://127.0.0.1:8080` in the **debug** app. For the Android emulator: `http://10.0.2.2:8080`.

The token is a secret: do not commit it. The release version refuses cleartext HTTP. A remote deployment must go through HTTPS: the server remains this `http.server`, but behind the VPS's Nginx, as the next section describes. One personal space per server, no account management.

## Deploying the server on a VPS

The server fits in a Docker image and a `docker compose up -d`. The image only holds
Python, the two modules of `backend/` and the demo lineup they read: since the
server has no dependencies, there is nothing to install and nothing to pin.

- `backend/Dockerfile` — the image, built from the repository root: `server.py` looks for
  `android/app/src/main/assets/match.json` next to its own folder, so the context must
  see both. The `.dockerignore` is written as an allowlist for that reason: only
  these three files get in, neither the build machine's `.env` nor the sixteen gigabytes of `.tooling/`.
- `compose.yml` — the service, the port and the volume.
- `.env.example` — the variables to copy into `.env`.

Copy `.env.example` to `.env` and fill in the token:

```env
FONOTE_TOKEN=the-same-token-as-in-the-app
FONOTE_BACKEND_IMAGE=registry.mmyumu.fr/fonote-backend:1.1.0
```

Without `FONOTE_TOKEN`, `docker compose` refuses to start rather than launching a server that
would answer 401 to everything. Without Internet access, the server starts and the app stays in
offline demo, as it does locally. The `.env` at the root is the one the server launched
by hand already reads: both uses share the same file, ignored by Git.

From the root, on the development machine:

```bash
docker compose build
docker compose push
```

Then on the VPS:

```bash
docker compose pull
docker compose up -d
```

The operation log lives in the Docker volume `fonote_backend_data`, mounted on `/data`:
the container is disposable, the database is not. Backups remain as described below — they
must go through SQLite's backup API, not through a copy of the main file:

```bash
docker compose exec backend python3 -c "import sqlite3; s=sqlite3.connect('/data/fonote.sqlite3'); d=sqlite3.connect('/data/fonote-backup.sqlite3'); s.backup(d); d.close(); s.close()"
```

The container only listens on the VPS's `127.0.0.1:8080`, never on the outside, and runs as an
unprivileged user. It is up to the machine's Nginx, outside this repository, to terminate TLS
and relay to this port: the release version of the app refuses cleartext HTTP, and the
token must not cross a network unencrypted. `/v1/health` is the only route open
without a token — Docker uses it for the `HEALTHCHECK` —, along with the football proxy, which only exposes
the provider's public data and never the key.

## Building Android

On this WSL machine, the tools are installed in `.tooling/` (ignored by Git): JDK 17, Gradle 8.9, SDK 35, Build-Tools 34, adb, Android Studio and the emulator. From the root:

```bash
bash scripts/build-android.sh
bash scripts/android-studio.sh
```

The first script compiles and runs Lint; the second opens Android Studio on the project. The Gradle Wrapper is provided with a SHA-256 check. On another machine with Java 17 and the SDK configured, use `cd android && ./gradlew :app:assembleDebug` (or `gradlew.bat` on Windows).

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. The app currently targets API 34 for this prototype and runs from Android 8 (API 26) onwards; publishing on the Play Store is out of scope.

**Démo hors ligne** (offline demo) mode is enabled by default in the Options. Home then offers two
local matches: PSG–Monaco (finished, 1–2, 4 September 2026) and Strasbourg–Monaco (upcoming, 12 September
2026 at 17:15). No server or network is needed to open them and take notes in them.
Untick this mode in Options to use a configured personal server.

To use the tools in a terminal:

```bash
source scripts/android-env.sh
adb devices -l
```

The `Fonote_API_35` emulator uses the Google APIs Android 35 image. Under WSL, acceleration requires access to `/dev/kvm`. If needed, run `sudo usermod -aG kvm mmyumu`, then open a session with the new groups (`newgrp kvm`) before launching:

```bash
bash scripts/run-emulator.sh
```

Once the emulator has started or an authorised phone is connected:

```bash
source scripts/android-env.sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n fr.fonote/.MainActivity
```

With several devices, add `-s <serial>` to adb. For a USB phone under WSL, the device must first be forwarded by Windows (usbipd), or use the Windows adb. No device showing in `adb devices` does not prevent compiling.

Tooling compatibility: [AGP 8.7 / Gradle 8.9 / JDK 17](https://developer.android.com/build/releases/agp-8-7-0-release-notes). Debug HTTP and release HTTPS rely on the [Android security configuration](https://developer.android.com/privacy-and-security/security-config).

## Checking

```bash
python3 -m unittest discover -s backend -v
source scripts/android-env.sh
javac -d /tmp/fonote-checks android/app/src/main/java/fr/fonote/{Formation,Lineup,MatchClock,PlayerName,Skin,Logo,Kickoff}.java android/checks/fr/fonote/{Formation,Lineup,MatchClock,PlayerName,Skin,Logo,Kickoff}Check.java
for check in Formation Lineup MatchClock PlayerName Skin Logo Kickoff; do java -ea -cp /tmp/fonote-checks fr.fonote.${check}Check; done
```

The logic that does without Android (placement, clock, names, themes) lives in separate classes,
checked by these `assert`s with no emulator and no test dependency.

Android flows to check on a device: write a note with several players without leaving the pitch, a free note through `+` that names a player, then turn it into a match note by removing its minute, a tactical note (fill the pitch, move a player, draw a pass then a run, frame a whole line and move it as one block, give an action to a player on the board, check that it appears in the summary, come back through « Mes observations » and reopen the schema with a tap), take notes offline, close and reopen while writing, add a comment, check the summary, sync twice, undo then sync again, import on a second device. Notes must not be duplicated or reappear after deletion.

## Server contract and persistence

Every route requires `Authorization: Bearer <token>`:

| Route | Response / effect |
|---|---|
| `GET /v1/matches` | The bundled match, its 22 players and its source |
| `GET /v1/football/competitions` | The thirteen competitions the server can serve |
| `GET /v1/football/matches?lineups=1` | The calendar, saying which lineups are already published |
| `GET /v1/football/competitions/{code}/teams` | A competition's teams, to choose one's follows |
| `GET /v1/football/matches?dateFrom=…&dateTo=…` | Matches in a period, for home and the calendar |
| `GET /v1/football/matches/{id}` | Details, published lineup, substitutes, match events and actual times |
| `POST /v1/operations` | Records an operation; returns `{seq, operation}` |
| `GET /v1/operations` | Full ordered log, usable by Android or Quest |

An operation has an `id` (UUID), a `note_id` (UUID) and a `kind`. Extending a note emits one again under the same `note_id` with a new `id`: the log keeps both, the reader keeps the latest and preserves the chronological place of the first.

- `note`: `match_id`, `minute` (an integer, or `null` for a note about the whole match) and `entries`, the list of `{player_id, action}` of the moment — each player at most once, an empty list for a written note. A note that credits nobody, free or match, may **name**: `team` (`home`, `away` or `null`) or `players`, a list of identifiers, one or the other and never both; an action note refuses both. A note without a minute carries no action. Notes written before this shape carried `player_id` and `action` at the root; the log being immutable, the server and the client still accept both.
- `comment`: `text` (2,000 characters at most).
- `delete`: no extra field.
- `restore`: no extra field; makes a deleted note visible again.
- `diagram`: `schema`, the board of a tactical note — `board` (`blank` or `full`), `tokens` and
  `shapes`. Written next to the note under the same `note_id`, like a comment: a drawn note
  is a note that also happens to be drawn, and the log only knows one kind of note.
  A counter is `{player_id, x, y}` for a match player, or `{team, label?, x, y}` with `team`
  among `home`, `away`, `neutral` for a nameless counter; a stroke is `{kind, points}` with `kind`
  among `pass`, `run`, `carry`, `shot` and 2 to 32 coordinate pairs. `carry` is no longer written —
  a run by the ball carrier is a carry, deduced from the ball — but the log being
  immutable, it is still accepted when reading. Coordinates are
  fractions of the pitch (0 to 1), with the home team attacking downwards — the frame in which
  `match.json` already writes its lineup. Bounds: 30 counters, 40 strokes. Deliberately
  geometry and nothing else: a counter names a player and stops there, so nothing here can
  contradict the lineup, the summary or the log about who they are or what they did.

Sending the same identifier and content again has no effect. Different content under the same identifier is refused. Operations are immutable. The client first sends its pending operations, then fetches the shared log. A deletion wins over any concurrent change: rewriting a deleted note does not make it reappear, only an explicit `restore` operation does. For a note as for a comment, the last version received by the server prevails. Before syncing, pending local operations are applied after the server's.

The log keeps previous comments and marks deleted notes as hidden: deleting a note in the interface is **not** a physical erasure. A permanent erasure and purging devices will need a dedicated mechanism. Note UUIDs must not be reused. The prototype fetches the whole log: pagination will be needed if its volume grows.

To back up the server database while it is in use, use SQLite's backup API (do not copy only the main file during WAL writes):

```bash
python3 -c "import sqlite3; source=sqlite3.connect('fonote.sqlite3'); target=sqlite3.connect('fonote-backup.sqlite3'); source.backup(target); target.close(); source.close()"
```

## Next steps

Validation on a phone and on personal matches. Follows and provider statistics are in place; the Quest interface is not yet. No provider data is collected or archived at this stage; the original notes stay independent of any retention terms that may apply to that data.

## Tactical sequences and keyframes

The board's time describes the move (0 to 120 seconds, in tenths), independently of the
match minute. Tap the time to enter a precise instant, or move the cursor.

### Placing and animating

- With no tool, dragging a player changes their position **at this instant**. Between two keyframes,
  the gesture adds one; the previous and next positions stay fixed.
- The initial placement is kept at 0 s on the first later move. Example:
  place at A, go to 1.2 s, move to B, play with **▶**. At the last key,
  playback starts again from the beginning; it stops at the last key or the last marker.
- Playback follows the screen frame by frame: players and the ball glide between two
  tenths instead of jumping, and the bar's cursor moves along with them. The ball leaves the
  passer's feet and reaches the receiver's gradually. A pause goes back to the tenth
  displayed, where editing resumes.
- **‹ ◇ ›** applies to the selected player, the selection of several players or the ball.
  The arrows jump to the previous/next keyframe. The neutral diamond adds a key
  without changing the animation; the filled orange diamond deletes the current key. A partial
  diamond means some players in the group have a key here: tapping completes the group.
- **Éditer… → Keyframes** also lets you change a key's instant.
- **Éditer… → Maintenir ici jusqu'à…** (hold here until…) makes the selected players wait before their run.
- **Éditer… → Déplacer toute la trajectoire** (move the whole path) arms a global move for the next gesture.
- **Course** (run) draws a movement from the player. **↝** offers an automatic duration,
  preset durations and a precise entry. After a stroke, the cursor moves to the arrival
  to chain the rest of the move. For a simultaneous run, go back to the start with **‹**
  or **Trajet… → Aller au départ**.
- **Tacle** (tackle) is drawn from the tackling player to the targeted player. The tackler runs at the pace
  of a run and stops on contact, where the target will be at that instant, without covering them. If
  the target has the ball, the tackler wins it on contact. The line is dashed and ends in
  a cross instead of an arrowhead.

### Correcting and syncing

Tap a path then **Trajet…**, or open **Mouvements**, to choose its start,
its duration, correct its ends with the orange circles or redraw its route.
Replacing a stretch that is already animated is announced before confirmation.

**▾** opens the shared timeline. Dragging a block changes its start; dragging its right
edge changes its duration. The settings stay available in the Mouvements menu.
**Lier le départ…** (link the start…) ties a motion to the start or the end of another. Changing
its start afterwards sets the offset from that reference. **Délier** (unlink) keeps its current time.
Only linked motions follow a change; cycles, crossed positions and
going beyond the 120 seconds are refused. Deleting a reference detaches its dependents while
keeping their times.

The ball follows its carrier. A pass to a player follows their reception position; a shot
ends on a free point. In a pass's menu, **Receveur dans cet espace…** (receiver into this space…) creates a
run towards where the ball arrives, synchronised with the pass; replacing an existing run
requires confirmation. Possession inconsistencies are flagged in the timeline.

**Éditer… → Repères** (markers) adds named bookmarks on the time, with a thumbnail, for example
« Réception » at 2.4 s. They are drawn as pennants above the time bar; tapping a
pennant jumps there. They are only for navigating: they neither create nor change any
keyframe, which remain the rectangles inside the bar.

**↶ / ↷** undo and redo a whole gesture, including its timing consequences and
the annotations of removed players. The history keeps 40 gestures while the edit is open.
Each finished gesture is saved offline in an SQLite transaction; an error
restores the previous state. A selection or playback creates no operation.

### Format and checks

The `version: 3` diagram keeps the tracks of the players and the ball. Each key has a stable
`id`; `after` and `offset` express a timing relation to another key. Motions
are the stretches between successive positions, identified by their arrival: a shared
position is never duplicated. `baked` marks an already smoothed route, split without changing
the animation. `steps` holds the markers `{id, name, t}`. Only the tackle gives a `kind` to a
player's key (`tackle`); a player's other strokes are read off the ball. A stroke may be as long
as the gesture: it is kept whole while being drawn, then saved as 32 points
spread at equal intervals along the line, with its two ends unchanged. The server validates references,
resolved times and the absence of cycles. Limits: 30 counters, 120 keys per track, 120 steps.

Deploy the compatible server before syncing the new diagrams. Older
formats stay readable with the existing reader; no migration of test data is
needed. Do not edit a v3 sequence again with an older app. Any reset
of test data must cover the logs of both devices and of the server together.

```bash
source scripts/android-env.sh
FONOTE_JSON_JAR="$FONOTE_ROOT/.tooling/android-studio/plugins/grazie/lib/org.json-json.jar"
javac -cp "$FONOTE_JSON_JAR" -d /tmp/fonote-checks android/app/src/main/java/fr/fonote/{Track,Diagram,Sequence,TacticalHistory}.java android/checks/fr/fonote/{Track,Diagram,Sequence}Check.java
java -ea -cp "/tmp/fonote-checks:$FONOTE_JSON_JAR" fr.fonote.TrackCheck
java -ea -cp "/tmp/fonote-checks:$FONOTE_JSON_JAR" fr.fonote.DiagramCheck json
java -ea -cp "/tmp/fonote-checks:$FONOTE_JSON_JAR" fr.fonote.SequenceCheck
python3 -m unittest discover -s backend -q
bash scripts/check-offline-android.sh
```

The instrumentation checks real gestures, the diamond's states, navigation, undo,
annotations and SQLite atomicity in a separate test log.

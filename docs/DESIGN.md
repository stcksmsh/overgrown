# Overgrown design spec (v1, 24 Nov 2026)

For the agents building the UI. Reference mockups: https://claude.ai/artifact/2Wk7DPjJfxWoPFpEQUF1SF (Today, Plant, Add plant step 1). Where the mockups and this file disagree, this file wins. All items are decided by the owner (section 9); anything this file does not cover, ask.

## 1. Principles

1. **Colour means state.** A screen is only as loud as the number of things that need the person.
2. **Act where you read.** Anything the app asks for can be answered on the card that asks it.
3. **No model words.** The person never sees "water balance", "estimate", "K", "depletion", "confidence".
4. **Colour is never the only signal.** Every state also has words and an icon or glyph.
5. Light and dark themes both ship. No dynamic (wallpaper) colour.

## 2. Colour

### Roles

| Role | Light | Dark | Use |
|---|---|---|---|
| ground | `#EEF0EC` | `#121512` | screen background |
| surface | `#FFFFFF` | `#1E221E` | neutral cards, inputs, secondary buttons |
| ink | `#1B1B1B` | `#F2F4F0` | text, primary button fill |
| on-ink | `#FFFFFF` | `#121512` | text on primary button |
| muted | `#4D4D4D` | `#B4BAB2` | secondary text |
| outline | `#C9CDC6` | `#3A403A` | borders of secondary buttons on surface |

### State blocks (same in both themes; each block carries its own text colour)

| Block | Fill | Text on it | Means |
|---|---|---|---|
| sky | `#BFD9F2` | `#1B1B1B` | needs water |
| marigold | `#F2B632` | `#1B1B1B` | the app has a question (soil check, "did rain reach it?") |
| brick | `#B8401C` | `#FFFFFF` | weather alert, destructive confirmation |
| moss | `#2F6B3F` | `#FFFFFF` | fine / resting; brand colour; selected state |

Tints for chips on surface: moss-tint `#DDE8D8`, brick-tint `#F3D9CF`. Gauge fill on neutral surfaces: moss in light, `#7FC08F` in dark.

Rules:
- One state block per card. Never nest a state block in another.
- A plant that is fine uses moss (featured) or surface (compact). It never uses sky or marigold.
- "Repot or expect daily watering" is a marigold card (a question/decision), not an alert.
- Dark values above are proposed and have **not** been contrast-checked; verify 4.5:1 for text and 3:1 for large text and gauge outlines before merging.

## 3. Type

Typeface: **Bricolage Grotesque**, bundled in the APK (no network font loading). It is SIL OFL 1.1: the owner allows OFL for bundled fonts only; extend the licence gate (D6) with that exception and list the font in the notices.

| Style | Size / weight | Use |
|---|---|---|
| display | 44 / 800, tracking -3% | screen titles ("Today"), plant name on its screen |
| title | 30 / 800, tracking -2% | plant name on an action card |
| card title | 22 / 800 | plant name on compact tiles, big numerals |
| body | 17 / 400 | action text |
| label | 16 / 600 | buttons, emphasised lines |
| secondary | 15 / 400 | supporting text |

Sizes are sp and must scale with the system font size up to 1.3x without clipping; cards grow, text never truncates plant names to fewer than 12 characters.

## 4. Shape and spacing

- 4 dp grid. Screen padding 20. Gap between cards 10. Card padding 16-18.
- Radius: card 20, button and input 12, glyph chip 16, bars 5-6.
- Touch targets at least 48 dp high. Button height 48.
- No shadows, no gradients, no borders on state blocks.

## 5. Components

**PotGauge.** Trapezoid pot (top wider than base, with a rim line) filled from the bottom to the share of water left. Sizes 40, 44, 84. On a state block: outline and fill in that block's text colour, empty part white. On surface: outline ink, fill moss. Content description: "Water left: N percent". This shape is also the app icon. The percentage shown is water **left**, never "used".

**Glyph.** One per care profile, used on cards, on the picker and as the fallback when a plant has no photo. Rules: 48 unit grid; filled shapes only, one colour; built from at most 6 primitives (ellipses, circles, triangles) plus stems; no outlines, faces, pots or shading; reads at 24 dp. Current set: conifer, succulent, leafy, fern, orchid, herb. One new glyph per new archetype.

**ActionCard** (Today, "Do now"). State block; title = plant name; body = the action in plain words; glyph top right; then either:
- water: PotGauge + short level word + primary button **Watered**;
- soil check: three equal buttons **Wet / OK / Dry**.

**RestingTile** (Today, "All fine"). Half width, two per row, always surface. Glyph in a moss or moss-tint chip, small gauge, name, one line ("Fine. Check Thu."). A full moss card is used only for the plant-name block on the plant screen and for a selected tile. (The Today mockup shows a moss tile for Boris; that is superseded.)

**AlertCard.** Brick. One per weather event per day range, never one per day: "Cold nights Mon-Wed. Move Boris against the wall." Icon + text + **Done** (surface button). Several plants in one event are named in one card.

**DayStrip.** Ten columns: weather mark above (frost, rain, heat), bar showing predicted water left, weekday letter. The next action day is ink and bold. Frost marks are brick.

**LearningDots.** Surface card: "Still learning Boris: 3 of about 8 answers" and eight dots, filled ones marigold. When learned: "Knows Boris well" and the card collapses to one line.

**Buttons.** Primary: ink fill. Secondary: surface fill (on ground or on a state block), outline only when on surface. Destructive: brick fill, always behind a confirmation or followed by Undo.

**WizardProgress.** Four bars, done = ink, rest = surface, plus "1 of 4".

## 6. Screens

Mocked: Today, Plant, Add plant step 1 (name search + pick by look).

Not mocked yet; build to the rules above and submit screenshots for review:
- **Add plant 2: where.** Indoors / outdoors as two large tiles; existing places as tiles; new place asks only a name and a city (search, or "Use my location": approximate-location permission only, requested on tap, coordinates rounded to about 1 km; nearest city name from a bundled offline list). No coordinates unless "Advanced" is opened.
- **Add plant 3: pot.** Width across the top in cm, shape (shallow / standard / tall) as drawn tiles, material, drainage holes. Volume is computed; litres only under "Advanced".
- **Add plant 4: exposure.** Outdoors: "When it rains, does the soil get wet?" Yes / A little / No / Not sure; "Is it windy where the pot stands?" Indoors: "Does sun fall on the leaves?" and the light check. "Not sure" stores rain = partly, wind = normal, and is recorded so a later version can ask after rain.
- **Empty Today.** One sentence on what the app does, one button "Add your first plant". No weather controls before a place exists.
- **Navigation:** two bottom tabs, Today and Plants; Places and Settings open from an icon in the top bar.
- **Plants list, Places list** (edit, delete with Undo).
- **Plant photo:** shown only on the plant screen in V1; cards and tiles always use the glyph.
- **History** on the plant screen: grouped by week, each entry editable and deletable.
- **Settings:** notification times, theme, units, export/import (import behind a brick confirmation), weighing (calibration), about.
- **Notifications:** water = "Pothos needs water, about 700 ml" + Watered / Snooze; soil check = Wet / OK / Dry; alert title starts with the event ("Frost Mon-Wed: Boris").

## 7. Words

| Never | Say |
|---|---|
| Soil water used 30 % | 70 % water left |
| Dry by the water balance | Soil is about dry |
| Uses water at 38 % of the first estimate | (omit) |
| Check the soil (after "Bone dry") | Water now |
| Nothing to do (next to an alert) | Nothing to water. Frost Mon-Wed. |
| No care data: general defaults | Using general care rules. Pick a plant type for better advice. |

Sentences are short, sentence case, no exclamation marks, no emoji.

## 8. Engineering rules

- All tokens in one Compose theme file; components take roles, never hex values.
- Roborazzi (or Paparazzi) screenshot test for every component state and every screen, light and dark, at 1.0x and 1.3x font scale. These images are what the owner reviews.
- TalkBack: every button labelled; gauge and DayStrip have text descriptions; state is announced in words.
- Bundle fonts and glyphs as resources; no runtime downloads.

## 9. Owner decisions (24 Nov 2026)

1. OFL allowed for bundled fonts.
2. "Use my location" added (reverses D26: approximate, on tap, optional; city search stays).
3. Two tabs: Today, Plants. Places and Settings behind a top-bar icon.
4. Soil answers: Wet / OK / Dry on Today cards and notifications; "Bone dry" also offered on the plant screen. Before building, confirm the learning rule (D18) handles the three-answer path: "Dry" from a quick answer covers the Dry and Bone dry bands together.
5. Pot size asked as width across the top + shape; litres computed, editable under "Advanced".
6. "Not sure" on rain and wind = partly / normal for now; learning exposure from answers after rain is post-V1.
7. Glyphs on cards; photo only on the plant screen in V1.
8. Resting tiles are surface with a moss chip.
9. Indoor plants are in V1.
10. Launch coverage: about 12 owner-reviewed archetypes plus a name table of about 300 common plant names mapped to them. Archetype review comes before species entries.
11. Weighing (calibration) moves from developer options into Settings; the engine starts using it after V1.
12. Trademark check and lawyer review start now, in parallel.
13. Repositories go public at the first beta.

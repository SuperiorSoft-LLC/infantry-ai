# CHART-STYLE: the standard for every chart and stats list

Every chart, trend, meter and ranked list in Stats (and any plugin that draws numbers) follows this page. PACE-TREND-1/2 must read it first. Source: CHART-STYLE-1.

## Examples

- **Good: "Over the session"** (Stats). A card with a title and caption, a readable time axis, a proportional line, scale buttons with Fit as the default, labels in the muted tone, values in the text tone. It is the target look.
- **Bad: the old "Estimated cost by"**. Row labels set no colour, so they took the Material default (near-black) on the dark card and were unreadable. The list was bare text with a huge gap between label and value, no scale, raw 8-character ids, "1 turns", no total, no share. If a number has no visual scale and no readable name, it is not finished.

## Colour

1. Colours come from `Palette` tokens only (`Text`, `Muted`, `Accent`, `Border`, `Raised`, `Ok`, `Warn`, `Danger`, `Info`). No `Color(0x...)`, no `Color.Black/White/Gray`.
2. **Every `Text` sets `color =` explicitly.** An unset colour inherits the Material content colour, which does not suit the card. Never dark text on a dark surface.
3. WCAG AA in dark and light: text 4.5:1 (`Contrast.TEXT_AA`), meaningful graphics 3:1 (`Contrast.GRAPHIC_AA`) against the card (`Palette.Raised`). Decorative tracks (`Palette.Border`) are exempt; never put information only there.
4. Colour is never the only carrier: pair it with a label, value or position.
5. Test it: `ThemeContrastAssert.assertReadable(text = ..., graphics = ...)` (plugins/official/Infantry_Stats/test) checks the tokens a chart draws with in both themes. Every new chart adds one call.

## Cards

`Palette.Raised`, 6 dp corners, 10 dp padding: a muted upper-case title (10 sp, SemiBold, letter-spaced) and under it a muted one-line caption saying what is shown and how it is computed. Estimates are labelled once, in the caption, not on every row.

## Time charts

- Scale buttons (e.g. 5m / 1h / 1d / all) with **Fit** as the default.
- The time axis carries labels at readable intervals (clock time for short ranges, date for long); the value axis shows its max and 0.
- Hover or tap shows the exact value and time. Touch must not need hover.

## Ranked lists (replace bare text lists)

One row per item, biggest first, unknown last:

1. Readable human label first (ticket title, role, model, harness); the id only as secondary muted text after it.
2. A proportional horizontal bar under the label (track `Border`, fill `Accent`, minimum visible width), scaled to the largest row.
3. Value right-aligned (SemiBold, text tone), then the share of the total in muted type.
4. A total row under a rule: count and sum. Rows that cannot be priced or measured say so ("n/a", "N without a price are not in the total"), never 0.
5. Tap or hover on a row opens a detail line (counts, per-unit value).
6. Cap the list (12) and say how many are not shown.

## States

- Empty: one muted line, "No data yet."
- Loading: a muted line naming what is read ("Reading the usage meter..."), never a blank card.
- Error or unsupported: say what is missing and what fixes it.

## Numbers

- Units always shown. Thousands compact (`12.3k`, `4.1M`) via the shared formatters; the full value in the detail.
- Pluralise counts: "1 turn", "2 turns". Percentages whole, "<1%" for a sliver.
- Estimated money is `~$1.23`, and the caption says it is an estimate and from what.
- Unknown is "n/a", never an invented number.

## Accessibility

Each row or point has a merged semantic description with label, value and share ("Fix the chart, tick-111: 2 turns, ~$30, 67% of total"). Clickable rows have a click label. Targets are at least a text line tall; keyboard focus follows `clickable`.

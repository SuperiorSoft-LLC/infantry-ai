<!-- SPDX-License-Identifier: Apache-2.0 -->
# UI style

The app's UI kit is `app/packages/ui`. Reuse it; never build a one-off dialog, card or form.

- **Components:** `app/packages/ui/src/components` (avatar, badge, button, card, dialog, dropdown-menu, input, label,
  progress, scroll-area, select, separator, switch, tabs, textarea, toggle, toggle-group, tooltip). One modal: `dialog`.
- **Theme tokens:** `app/packages/ui/src/theme.css` (`--color-background`, `--color-card`, `--color-primary`, ...). Dark
  is the default (the `.dark` class on `<html>`). A web UI plugin builds with the same tokens
  (`@infantry/ui-plugin-api/theme.css`), so its classes match. Never write a raw colour.
- **Toasts:** `app/packages/client/src/toasts.ts` and `app/shell/src/components/layout/toast-host.tsx`.
- **Markdown:** `app/packages/client/src/markdown.ts`.

## Charts and stats lists

1. Colours come from theme tokens only. Text meets WCAG AA (4.5:1) and meaningful graphics 3:1 against the card, in dark
   and light. Colour is never the only carrier of meaning.
2. Every chart sits in a card with a title and a one-line caption saying what is shown and how it is computed.
3. Time charts have scale buttons with **Fit** as the default, a labelled time axis, and the exact value on hover or tap.
4. Ranked lists: a readable label first (an id only as muted text), a proportional bar, the value right-aligned with its
   share, a total row, and "n/a" (never 0) for what cannot be measured. Cap the list and say how many are hidden.

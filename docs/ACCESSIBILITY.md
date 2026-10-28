# Accessibility

Dynamic Island 1.9 follows Apple's Human Interface Guidelines for layout and accessibility, adapted to Android. This page lists what the app does and what is still known to be missing. Please [report problems](https://github.com/ajitpal/dynamic-island-android/issues) with your phone model and the setting you use.

## In the app
- **TalkBack:** every row, switch, button and tab has a spoken label. Section titles are marked as headings, so you can jump between them. Tab badges are read out ("Island, needs attention", "Island, 3 new").
- **Text size:** all text is in scalable units and follows *Settings → Display → Font size*. Rows grow instead of cutting text off.
- **Touch targets:** at least 48 dp for every control.
- **Colour:** light and dark themes follow the system. Body text, secondary text and the blue accent meet WCAG AA contrast (at least 4.5:1) on their backgrounds in both themes; status is never shown by colour alone (each state also has text or an icon).
- **Onboarding** works with TalkBack, and each permission step says what it is for before opening Android's settings.

## In the island
- **Time to take action:** if you set *Accessibility → Time to take action* in Android, pop-ups and expanded cards stay up at least that long.
- **Remove animations:** with Android's *Remove animations* on, the island changes size without the spring bounce.
- Buttons, media controls, tools and the reply field in island cards have spoken labels. Whole-card summaries for TalkBack are not done yet (see below).
- Every island action is also available from the app (Inbox, tools, module shortcuts), so you never need a gesture on the island.

## Known gaps
- Expanded island cards don't yet announce a one-line summary when they appear.
- The island's gestures (swipe down for Inbox, long-press for tools) have no TalkBack custom actions yet; use the app's Island tab instead.
- Nothing has been tested with Switch Access or with very large display sizes on small phones.
- The app is in English only. Translations are welcome (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

## For module developers
Modules never draw island UI themselves: the island draws your cards, so they inherit all of the above. Give every tool and button a short, clear label, and give your own screens content descriptions.

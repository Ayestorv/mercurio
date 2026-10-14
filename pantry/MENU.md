# Menu: Mercurio

Queue: 2026-10-12-pantry-queue.md
Counts: open 7, in flight 1, shipped 4, parked 2, dropped 0, needs fixing 0

## Steer

- up-next: prompt-above-keys

## Up next

**board-one-tap-choices**: Agents board ask with one-tap option chips (`board-one-tap-choices`) (queue #4, medium, repo, since 2026-10-12)

- Done when: On the Agents board, a Robolectric test renders a needs-you card from a fixture whose `ChatAsk.Blocked` has three parsed options and reads, by test tag: the question in the heading style with the gold accent, one `OrmusKey` per option in order, the first gold-filled and the others ghost, the agent name and pane labels in the mono micro-label style, and no Approve or Deny keys; a card whose ask has no parsed options still shows Approve and Deny; tapping an option goes through the guarded answer path, and a fake exec channel sees exactly one call carrying that option's number; no new color literal appears under `app/src/main` (tokens from `OrmusTokens` only); the gate passes
- Verify on: repo
- Evidence: Linear ORM-344. Usage: `board:approve` and `board:deny` never used, `board:chat` 3 times, `board:open` never used; `key:down` 105 and `key:enter` 254 taps, inferred to be mostly picking options in agent menus. Map: "Approve or deny from the phone" (Us Y through notifications and Chat View; Moshi Y). DNA P4 (act on a blocked agent without opening its session), G4 (gold is the one filled action, keys from `OrmusKey`), E1, E3
- Issue: #145
- Order: herdr-drag-scroll, board-one-tap-choices, drag-arrows, needs-you-poll, title-pull-drawer, auto-reconnect, tailscale-oauth
- Tie: none

## Atoms

| Key | Title | State | Confidence | Class | Since | Queue # | Issue | Because |
|-----|-------|-------|------------|-------|-------|---------|-------|---------|
| auto-reconnect | A dropped link reconnects once on its own (`auto-reconnect`) | open | medium | host | 2026-10-12 | 2 | - | - |
| board-one-tap-choices | Agents board ask with one-tap option chips (`board-one-tap-choices`) | open | medium | repo | 2026-10-12 | 4 | #145 | - |
| drag-arrows | Long-press and drag sends arrow keys (`drag-arrows`) | open | medium | repo | 2026-10-12 | 5 | - | - |
| herd-approve | Approve and deny guarded by Herdr's seq (`herd-approve`) | in-flight | medium | repo | 2026-10-03 | 3 | #130 | PR #148 (build/herd-approve) |
| herdr-drag-scroll | Drag to scroll a Herdr pane (`herdr-drag-scroll`) | open | high | host | 2026-10-12 | 1 | - | - |
| needs-you-poll | Needs you count stays fresh for other sessions (`needs-you-poll`) | open | medium | repo | 2026-10-12 | 7 | - | - |
| tailscale-oauth | Tailscale list works with no host connected (`tailscale-oauth`) | open | low | repo | 2026-10-03 | 8 | - | - |
| title-pull-drawer | Pull down on the title opens the session drawer (`title-pull-drawer`) | open | medium | repo | 2026-10-12 | 6 | - | - |

## Retired

| Key | Title | State | Since | Issue | Because |
|-----|-------|-------|-------|-------|---------|
| herd-home | Herd home lists agents by who needs you (`herd-home`) | shipped | 2026-10-03 | #41 | closed completed #41 |
| herd-notify | Notifications come from Herdr status (`herd-notify`) | shipped | 2026-10-03 | - | merged PR #57 |
| ormus-reskin | Ormus brand re-skin from tokens (`ormus-reskin`) | shipped | 2026-10-03 | #58 | bullet: Ormus brand re-skin from tokens (`ormus-reskin`): shipped, PR #55 |
| prompt-above-keys | Prompts sit above the key rows (`prompt-above-keys`) | shipped | 2026-10-03 | #37 | closed completed #37 |
| reader-cleanup | Reader shows clean agent output (`reader-cleanup`) | parked | 2026-10-03 | - | bullet: Reader shows clean agent output (`reader-cleanup`): parked, Chat View (PR #111, #141) shows Claude and Codex panes clean; the reader was opened 7 times in 4,247 actions and its scroll keys were never used; Linear ORM-168 says keep for non-agent panes or close |
| vibe-fold | Vibe fleet roster folded into Logos (`vibe-fold`) | parked | 2026-10-03 | - | bullet: Vibe fleet roster folded into Logos (`vibe-fold`): parked, DNA G3 keeps machine names out of the tree and PR #100 removed the built-in machines; Linear ORM-167 |

## Notes

- steer up-next prompt-above-keys ignored: not eligible

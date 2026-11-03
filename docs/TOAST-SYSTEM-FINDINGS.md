# Toast system vs. the owner design (read-only verification, 2026-10-27)

Base: feature-agenthq-mvp @ 9efb09a. Paths are under superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/.

## 1. Where it lives
- `ToastHost.kt:69` `fun ToastHost(state: AppState, onOpen, modifier, reducedMotion)`: a concrete top-level composable. Card is `private fun Toast` (`ToastHost.kt:89`).
- `shell/blocks/ShellToast.kt:13` `object ShellToast : Block("infantry.shell.toast", ...)`, listed in the overlays of `shell/blocks/CoreShell.kt:63` (child of the dock stack, sibling of ShellBoard etc.). Not the root of the tree.
- Data: `model/Toast.kt` (`ToastEvent`, `AppState.withToast`, `visibleToasts`); `AppState.toasts` (`model/Model.kt:609`).
- No interface, abstract class, ToastService or LocalToast exists anywhere (infantryUi, infantryUiCore, infantryPlugins, infantry daemon). `UpdatePrompt.kt` has its own unrelated `UpdateCard`. `plugin/api/Services.kt:27` `Notifications.notify(title, body)` is an unwired plugin interface (no toast implementation found).
- Verdict: a concrete shell composable inside a leaf overlay block. NOT a root plugin with an abstract helper.

## 2. How a block three levels deep toasts today
It cannot. The only producer is the reducer: `model/Model.kt:1466` (`upserted.withToast(...)` on ticket start), plus cross-project notices derived in `ToastHost.kt:70`. A block reaches no `post`/`show` function: `LocalShell` (`AppShell.kt:96`) gives `s.state`/`s.controller`, and neither exposes a toast poster. Rendering is only `ShellToast.Render` -> `ToastHost(state, ...)` (`ShellToast.kt:18`), which reads `state.toasts`.

## 3. Capabilities
- Replace the helper with another implementation: NO. `ShellToast` hard-codes `ToastHost`; no abstraction to implement. Only a whole-block override of `infantry.shell.toast` by id might swap the host (resolver not checked), and it would still read `AppState.toasts`.
- Wrap a subtree in a different toast system (nearest provider wins): NO. No CompositionLocal for toasts. Existing locals: LocalShell (`AppShell.kt:96`), LocalBlockSize/LocalHostSurface (`block/layout/Contract.kt:62,69`), none toast-related. Nothing to resolve "nearest".
- Register a toast as a child of the system toast and call it from anywhere: NO. `ShellToast` extends `Block` and does not override `withChildren`, so the default `refuse("... takes no children yet")` fires (`block/layout/Contract.kt` Block). It also has no call API.

## 4. Gaps and smallest change
1. No abstract helper. Add `interface ToastService { fun post(ToastEvent); fun dismiss(id) }` (or `abstract class ToastSystem`) in infantryUiCore; default impl wraps `controller` -> `AppState.withToast`.
2. No ambient access. Add `val LocalToast = staticCompositionLocalOf<ToastService> { error(...) }`; provide it in the toast block around its children AND at the root (so descendants anywhere resolve it). Nearest provider wins for free.
3. Not a root plugin. Move `infantry.shell.toast` from the overlay list to wrap the dock (root, children = rest of the tree): `ToastBlock(id, children, place)` overriding `withChildren`, rendering `CompositionLocalProvider(LocalToast provides impl) { RenderPlain(children); host }` (pattern: `ImageViewerHostBlock`, CoreShell.kt ~75).
4. Replace/wrap: after 1-3, a user block of the same shape with its own provider and an inner `ToastService` covers both; no further code.
5. Child-of-toast: the `withChildren` override from 3 covers it; calling from anywhere is `LocalToast.current.post(...)`.
6. Reducer coupling: the default impl needs a controller entry point (e.g. `controller.postToast(ToastEvent)`); today only Model.kt:1466 posts.
7. Per-item template node: the card is private (`ToastHost.kt:89-126`). Restyling needs a template child block `infantry.shell.toast.item` (props: kind colour, title, body, close) rendered per `ToastEvent` via a `ToastItemScope`/slot in the host; defaults = the present Row (3dp kind bar, title 11sp Muted, body 12sp kind colour, "×" close). Keep the TOAST_* test tags on it. The host keeps the lifecycle (3 visible, 6 s, hover pause, slide) so a template only draws.

Not tested/changed: docs only; no build or run.

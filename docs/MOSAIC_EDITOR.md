# Mosaic node editor

In-game editor for `shaders/bedray/mosaic.json` node graphs (see the "Mosaic node graphs" section of
[CUSTOM_RT_SHADERS.md](CUSTOM_RT_SHADERS.md)). Open it with **J** (Controls > Bedray, rebindable like any key).
It edits the `mosaic.json` of the active folder pack, or starts a new graph that **Apply** saves as a new pack.

## Layout

Fixed regions that never overlap (`MosaicLayout`, covered by `MosaicEditorModelTest`): tool bar on top, node
palette on the left, canvas in the middle, inspector on the right, status line at the bottom. On narrow screens the
side panels shrink, then the palette hides (quick-add replaces it), then the inspector. Optional tool-bar buttons
(Fit, Preview, ?) give way before the essential ones; their shortcuts keep working.

| Region | What it does |
|---|---|
| Tool bar | Nodes (palette), Settings, ? (shortcuts), Code, Undo, Redo, Fit, Preview, Live, Apply, Done |
| Palette | Search box, Favorites, Recent, then the node categories (collapsible). Click adds in view, drag places, right-click pins to Favorites |
| Canvas | Nodes and wires; drag empty space to pan, wheel zooms around the cursor |
| Inspector | Selected node: help, live parameter editing, inputs (with disconnect), output kinds, Duplicate/Delete. Several nodes: counts, Arrange, Align, Duplicate, Copy, Delete. Nothing selected: graph state, key summary, socket color legend |
| Status line | Validation result (click an error to jump to the node) and a hint for whatever is under the mouse |

Floating windows: **Shortcuts** (F1) and **Settings** (Ctrl+,) are movable by their title bar and stay open next to the
canvas; the node context menu opens on right-click. A click or a scroll always goes to the topmost element under the
mouse only.

## Working with nodes

- Add: palette click/drag; double-click, right-click or Space on the canvas opens a search popup at the cursor.
- Wire: drag from an output to an input or the other way. Drop on a node body to use its first free fitting input;
  drop on empty canvas to pick a new node that is wired in immediately. Right-click an input to cut its wire; drag a
  wired input to re-route it.
- Socket colors show kinds: float grey, vec2 green, vec3 amber, vec4 violet, white = accepts several. A wire that the
  port cannot take is refused with the reason; empty required ports never block wiring a graph under construction.
- Parameters (constant values, swizzle masks, push/buffer slots) apply as you type; an edit is one undo step.
- Select: click a node; Shift/Ctrl+click adds or removes one; drag on empty canvas draws a selection box (Shift
  adds to the selection); Ctrl+A selects everything, Esc clears. Right- or middle-drag pans (with the
  "left-drag selects" setting off, left-drag pans too). A plain right-click on empty canvas opens the add popup.
- Groups: dragging any selected node moves the whole selection; arrow keys nudge it (Shift: one grid step).
  Delete, Duplicate, Copy and the context menu work on the whole selection. Duplicate keeps wires coming from
  outside the group; wires inside the group always follow the copies.
- Clipboard: Copy puts the selection on the system clipboard as JSON (a `mosaic.json` document with
  `"mosaicFragment": 1`); Paste reads it back with its top-left corner at the mouse, so fragments move between
  packs, worlds or people. Pasted nodes get fresh ids; wires to nodes that were not copied are dropped.
- Arrange (Ctrl+L) lays the selection out (or the whole graph when fewer than two nodes are selected) in columns by
  dependency depth, every wire running left to right; Align left / Align top are in the group inspector and the
  context menu.
- Code (F2) shows the GLSL the game compiles, or the Slang mirror, for the current graph, with Copy.
- Live (default on): every valid change is written to the pack's `mosaic.json` about 0.4 s after the last edit and
  the running stage hot-reloads it. Preview makes the canvas see-through so the world behind shows the result. The
  editor does not pause the game. Saves that only move nodes produce the same shader, and the host keeps the running
  stage instead of recompiling it.

## Shortcuts (defaults, all rebindable)

| Action | Keys |
|---|---|
| Add node at cursor | Space, Shift+A |
| Search the palette | Ctrl+F |
| Undo / Redo | Ctrl+Z / Ctrl+Y, Ctrl+Shift+Z |
| Copy / Paste node | Ctrl+C / Ctrl+V |
| Duplicate / Delete | Ctrl+D / Delete, Backspace |
| Select all / Arrange | Ctrl+A / Ctrl+L |
| Generated code window | F2 |
| Nudge selection | Arrow keys (Shift: grid step; fixed, not rebindable) |
| Save (Apply) | Ctrl+S |
| Fit / Center on selection | F, Home / C |
| Zoom in / out / 100% | = Num+ / - Num- / Ctrl+0 |
| Palette / Inspector | Tab / I |
| Live / Preview / Snap to grid | L / P / G |
| Shortcuts window / Settings | F1 / Ctrl+, |

Rebinding: open Shortcuts, click a key chip (or + to add one), press the new combination. Delete/Backspace clears the
slot, Esc cancels. A combination already used by another action moves to the new one (the status line says which).

## Settings file

`config/bedray/mosaic-editor.json` (editor-wide, not part of any pack). Every field is optional; unreadable fields
fall back to their defaults one by one.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `snap` | bool | false | Snap dragged nodes to the grid (hold Alt to place freely) |
| `grid` | int 8..64 | 16 | Grid step in canvas units |
| `portLabels` | bool | true | Port names on nodes |
| `leftDragSelects` | bool | true | Left-drag on empty canvas draws a selection box; false makes it pan |
| `wireThickness` | int 1..3 | 2 | Wire thickness |
| `liveByDefault`, `previewByDefault` | bool | true, false | Live and Preview state when the editor opens |
| `showPalette`, `showInspector` | bool | true | Panel visibility, remembered |
| `collapsed` | string[] | [] | Collapsed palette groups (`FAVORITES`, `RECENT`, category names) |
| `favorites`, `recent` | string[] | [] | Node types; unknown types are dropped, recent keeps 8 |
| `keys` | {action: string[]} | defaults | Shortcuts as text, e.g. `"undo": ["Ctrl+Z"]`; actions not listed keep their defaults |

## Verification level

Pure parts (catalog, layout, history, keymap, settings file, validator, group copies, clipboard fragments, arrange)
are unit-tested: `MosaicGraphTest`, `MosaicEditorModelTest`, `MosaicPrefsTest`, `MosaicGroupTest`. The screen compiles against Minecraft 26.3; it has not yet been
exercised in a running game session.

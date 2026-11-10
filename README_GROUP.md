# v170 – Group = ONE layer (Alight Motion style) + Edit Group

## What a Group is now
- A Group is **one layer** to the outside. In the timeline it is a single green bar; the layers inside are hidden there.
  Everything a layer can do works on the whole group: Transform, Animations, Opacity, **Blend mode, Effects, Masks**, eye,
  keyframes, Duplicate, Delete.
- **Edit Group**: select the group -> tool "Edit group" (first tool). Now the timeline and the canvas show only the layers of
  that group; edit them one by one (Transform, Effects, trim, move, reorder lanes ...). A green bar on the timeline
  (top right) leads back out; the group is then selected again.
- A layer added while you are inside a group (Add, text, shape, media ...) goes **into** the group and follows it.
  Audio / Camera / Chain cannot be in a group: adding one leaves the group first.
- Selecting a layer that lives in another group (or the top level) switches to that level automatically.
- Group inside a Group: grouping inside Edit Group makes the new group a layer of the outer one.
- Trim: drag an edge of the group bar. From then on the group keeps its own length and the layers inside are cut at its ends
  (`TimelineClip.groupFreeLength`). A group you never trimmed still covers exactly its layers.
- Ungroup gives the layers the group's opacity again (blend / effects / masks of the group are dropped).

## How it is drawn (preview AND export, same code path)
Renderer (C++): `Renderer::beginGroup / endGroup` (`renderer.cpp`): the layers of a group are drawn into their own transparent
canvas-sized picture (up to 4 levels deep, `grpFbo_`), then that picture is handled like a photo layer: Effects / Masks
(`applyFx`), opacity, blend mode. Blend modes of layers *inside* a group blend with the group's own picture only
(`uGroup` in the blend shader, W3C compositing with a transparent backdrop).
`NativePreviewEngine.drawFrame -> drawScope / drawGroupLayer`, `NativeProjectExporter -> drawScope / drawGroupLayer`.
The group's move / scale / turn still reaches the layers through their parent link (`ParentRig`), so the group's picture is
placed at 0 / 0 / 1. ParentRig no longer hands the group's opacity down (`inheritedGroupOpacity` is for Ungroup only).
Stacking: a group sits in the stacking order like any layer (Z, then lane); inside it the same rule applies to its layers.

Model: `GroupOps.scopeTracks(tracks, groupId)` = the lanes of one level (used by timeline, canvas, both engines),
`GroupOps.validScope`; ViewModel: `editingGroupId`, `enterGroup`, `exitGroup`, `reorderShownLane`;
UI: `GroupEditBar`, "Edit group" tool in `ClipEditToolsPanel`, `CanvasArea(tracks = shown level, nativeTracks = everything)`.

## Known limits
- Canvas: a group shows one marker frame (130 dp box, like a Null), not the bounding box of its contents. Inside Edit Group the
  frame of a layer sits at the layer's own position (not moved by the group), same as with a Null parent.
- A text / shape / sticker that the C++ painters cannot draw (Compose fallback) is drawn by Compose above the picture, not
  inside the group's picture.
- Group split is not available; Group lanes cannot be reordered across levels.
- Blend mode "matte" on a layer inside a group cuts the group's own picture only.
- Not compiled in the sandbox for Android (only the pure model code + ParentRig + C++ syntax were checked): please build with
  GitHub Actions and test on the phone.

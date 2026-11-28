// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #9: the bar a multi-select shows above the lanes: how many are picked, an epic picker from `epic_list`
// ("No epic" clears it), Move and Clear. Hidden while nothing is selected.
import { useState } from "react";
import { Button } from "@infantry/ui";
import { useProjects } from "@/components/git/use-projects";
import { INHERIT, PickerField } from "@/components/dialogs/picker-field";
import { epicsStore, useQuery, useQueryOnOpen } from "@/components/panels/use-panels";
import { boardStore } from "./use-board";
import { moveToEpic, selectionStore, useSelection } from "./selection";

export function EpicMoveBar() {
  const ids = useSelection((s) => s.ids);
  const project = useProjects((s) => s.currentId);
  const store = epicsStore();
  useQueryOnOpen(store, project ?? undefined, !project || ids.length === 0);
  const epics = useQuery(store, (s) => s.data).filter((e) => !project || e.projectId === project);
  const [epic, setEpic] = useState(INHERIT);
  if (!ids.length) return null;
  const move = () => {
    const b = boardStore().getState();
    moveToEpic(b.tickets, ids, epic === INHERIT ? "" : epic, b.save);
    selectionStore.getState().clear();
  };
  return (
    <div role="toolbar" aria-label="Selected tickets" className="mb-3 flex flex-wrap items-end gap-2 rounded-lg border border-info/40 bg-info/5 p-2 text-xs">
      <span className="self-center font-medium">{ids.length} selected</span>
      <div className="w-56">
        <PickerField id="board-move-epic" label="Move to epic" options={epics.map((e) => e.id)} labels={Object.fromEntries(epics.map((e) => [e.id, e.name]))} inheritLabel="No epic" value={epic} onChange={setEpic} />
      </div>
      <Button size="sm" onClick={move}>Move</Button>
      <Button size="sm" variant="ghost" onClick={() => selectionStore.getState().clear()}>Clear</Button>
    </div>
  );
}

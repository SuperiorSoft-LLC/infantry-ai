// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Projects switcher: the daemon's projects (`project_list`), the current one drives the Git panel. Adding a
// project asks for a name and a directory (`project_save`); the daemon refuses a directory that does not exist.
// Each row of the open list shows the project icon (letters on a stored color, or a small uploaded image) and an Edit
// and a Delete button (on hover; always on touch). The closed box shows no icon and keeps its size.
import { useState, type PointerEvent, type MouseEvent } from "react";
import { ChevronDown, Check, FolderPlus, Pencil, Trash2 } from "lucide-react";
import { AppDialog, Button, Input, Label, Switch } from "@infantry/ui";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@infantry/ui";
import {
  ICON_IMAGE_MAX_CHARS,
  ICON_KEYS,
  ICON_PALETTE,
  deleteConfirmed,
  projectIcon,
  projectInitials,
  type ProjectInfo,
} from "@infantry/client";
import { projectsStore, useProjects, useProjectsLoaded } from "@/components/git/use-projects";
import { BaseBranchPicker } from "./base-branch-picker";

export function ProjectIconBadge({ icon, size = 28 }: { icon: { text: string; color: string; image: string | null }; size?: number }) {
  const style = { width: size, height: size, fontSize: Math.round(size * 0.42) };
  if (icon.image) return <img src={icon.image} alt="" style={style} className="shrink-0 rounded-md object-cover" />;
  return (
    <span style={{ ...style, background: icon.color }} className="inline-flex shrink-0 items-center justify-center rounded-md font-semibold leading-none text-white">
      {icon.text}
    </span>
  );
}

/** Keeps a press on a row button from selecting the row (Radix selects on click and on pointer up). */
const own = (e: PointerEvent | MouseEvent) => {
  e.stopPropagation();
  e.preventDefault();
};

export function ProjectPicker() {
  useProjectsLoaded();
  const projects = useProjects((s) => s.projects);
  const current = useProjects((s) => s.currentId);
  const [menuOpen, setMenuOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<ProjectInfo | null>(null);
  const [deleting, setDeleting] = useState<ProjectInfo | null>(null);
  const [name, setName] = useState("");
  const [dir, setDir] = useState("");
  const label = projects.find((p) => p.id === current)?.name ?? (projects.length === 0 ? "No projects" : "Project");
  const save = () => {
    projectsStore().getState().saveProject({ name: name.trim(), directory: dir.trim() });
    setAdding(false);
    setName("");
    setDir("");
  };
  const rowAction = (open: () => void) => (e: MouseEvent) => {
    own(e);
    setMenuOpen(false);
    open();
  };
  return (
    <>
      <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen}>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm" className="min-w-0 max-w-48 shrink font-semibold">
            <span className="truncate">{label}</span>
            <ChevronDown className="text-muted-foreground" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="w-64">
          <DropdownMenuLabel>Projects</DropdownMenuLabel>
          {projects.length === 0 && <DropdownMenuItem disabled>The daemon has no projects yet</DropdownMenuItem>}
          {projects.map((p) => (
            <DropdownMenuItem key={p.id} onSelect={() => projectsStore().getState().select(p.id)} title={p.directory} className="group gap-2 py-1.5" data-testid="project-row">
              <Check className={p.id === current ? "opacity-100" : "opacity-0"} />
              <ProjectIconBadge icon={projectIcon(p)} />
              <span className="min-w-0 flex-1 truncate">{p.name}</span>
              <span className="flex shrink-0 gap-0.5 opacity-0 group-hover:opacity-100 group-focus:opacity-100 [@media(hover:none)]:opacity-100">
                <button
                  type="button"
                  aria-label={`Edit ${p.name}`}
                  title="Edit"
                  className="rounded p-1 text-muted-foreground hover:bg-background hover:text-foreground"
                  onPointerDown={own}
                  onPointerUp={own}
                  onClick={rowAction(() => setEditing(p))}
                >
                  <Pencil className="size-3.5" />
                </button>
                <button
                  type="button"
                  aria-label={`Delete ${p.name}`}
                  title="Delete"
                  className="rounded p-1 text-muted-foreground hover:bg-background hover:text-destructive"
                  onPointerDown={own}
                  onPointerUp={own}
                  onClick={rowAction(() => setDeleting(p))}
                >
                  <Trash2 className="size-3.5" />
                </button>
              </span>
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => setAdding(true)}>
            <FolderPlus /> Add project...
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <AppDialog
        open={adding}
        onOpenChange={setAdding}
        title="Add project"
        description="A name and the folder of its git repository."
        footer={
          <Button size="sm" disabled={!name.trim()} onClick={save}>
            Add
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="project-name">Name</Label>
            <Input id="project-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="project-dir">Directory</Label>
            <Input id="project-dir" value={dir} onChange={(e) => setDir(e.target.value)} placeholder="C:\code\my-repo" className="font-mono" />
          </div>
        </div>
      </AppDialog>
      {editing && <EditProjectDialog key={editing.id} project={projects.find((p) => p.id === editing.id) ?? editing} onClose={() => setEditing(null)} />}
      {deleting && <DeleteProjectDialog key={deleting.id} project={deleting} onClose={() => setDeleting(null)} />}
    </>
  );
}

/** Scales an image file down to a small square data URL that fits the daemon's per-value limit, or null. */
async function shrinkImage(file: File): Promise<string | null> {
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((ok, fail) => {
      const i = new Image();
      i.onload = () => ok(i);
      i.onerror = fail;
      i.src = url;
    });
    for (const [px, type, q] of [[48, "image/webp", 0.85], [40, "image/jpeg", 0.7], [32, "image/jpeg", 0.5]] as const) {
      const c = document.createElement("canvas");
      c.width = c.height = px;
      const side = Math.min(img.width, img.height);
      c.getContext("2d")?.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, px, px);
      const data = c.toDataURL(type, q);
      if (data.length <= ICON_IMAGE_MAX_CHARS) return data;
    }
    return null;
  } catch {
    return null;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function EditProjectDialog({ project, onClose }: { project: ProjectInfo; onClose: () => void }) {
  const start = projectIcon(project);
  const [name, setName] = useState(project.name);
  const [iconText, setIconText] = useState(project.data?.[ICON_KEYS.text] ?? "");
  const [iconColor, setIconColor] = useState(start.color);
  const [iconImage, setIconImage] = useState<string | null>(start.image);
  const [autoRemove, setAutoRemove] = useState(!!project.autoRemoveDoneWorkers);
  const [caps, setCaps] = useState<Record<string, number>>({ ...(project.laneCaps ?? {}) });
  const [imageError, setImageError] = useState<string | null>(null);
  const preview = { text: iconText.trim() || projectInitials(name), color: iconColor, image: iconImage };
  const otherData = Object.entries(project.data ?? {}).filter(([k]) => !k.startsWith("icon."));
  const experiments = Object.entries(project.experiments ?? {});
  const capsValid = Object.values(caps).every((n) => Number.isInteger(n) && n >= 1);
  const save = () => {
    projectsStore().getState().editProject(project.id, { name, iconText, iconColor, iconImage, autoRemoveDoneWorkers: autoRemove, laneCaps: caps });
    onClose();
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Edit project"
      footer={
        <Button size="sm" disabled={!name.trim() || !capsValid} onClick={save}>
          Save
        </Button>
      }
    >
      <div className="min-w-0 space-y-4">
        <p className="break-all font-mono text-xs text-muted-foreground">{project.directory || project.id}</p>
        <div className="space-y-1.5">
          <Label htmlFor="edit-project-name">Name</Label>
          <Input id="edit-project-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="space-y-2">
          <Label>Icon</Label>
          <div className="flex items-center gap-3">
            <ProjectIconBadge icon={preview} size={44} />
            <div className="min-w-0 flex-1 space-y-2">
              <Input aria-label="Icon letters" value={iconText} maxLength={3} placeholder={projectInitials(name)} onChange={(e) => setIconText(e.target.value)} disabled={!!iconImage} className="w-24" />
              <div className="flex flex-wrap gap-1.5">
                {ICON_PALETTE.map((c) => (
                  <button
                    key={c}
                    type="button"
                    aria-label={`Color ${c}`}
                    onClick={() => setIconColor(c)}
                    style={{ background: c }}
                    className={`size-6 rounded-md border-2 ${c === iconColor ? "border-foreground" : "border-transparent"}`}
                  />
                ))}
              </div>
              <div className="flex items-center gap-2">
                <Button size="sm" variant="outline" asChild>
                  <label className="cursor-pointer">
                    Upload image
                    <input
                      type="file"
                      accept="image/*"
                      className="hidden"
                      onChange={async (e) => {
                        const f = e.target.files?.[0];
                        e.target.value = "";
                        if (!f) return;
                        const data = await shrinkImage(f);
                        setImageError(data ? null : "That image could not be made small enough; try a simpler one.");
                        if (data) setIconImage(data);
                      }}
                    />
                  </label>
                </Button>
                {iconImage && (
                  <Button size="sm" variant="ghost" onClick={() => setIconImage(null)}>
                    Use letters
                  </Button>
                )}
              </div>
              {imageError && <p className="text-xs text-destructive">{imageError}</p>}
            </div>
          </div>
        </div>
        <div className="space-y-2 rounded-lg border p-3">
          <div className="text-sm font-semibold">Project settings</div>
          <div className="flex items-center justify-between gap-3">
            <Label htmlFor="edit-project-autoremove" className="font-normal">
              Remove workers when their ticket is done
            </Label>
            <Switch id="edit-project-autoremove" checked={autoRemove} onCheckedChange={setAutoRemove} />
          </div>
          {Object.keys(caps).length > 0 && (
            <div className="space-y-1.5">
              <div className="text-xs text-muted-foreground">Workers at once, per lane</div>
              {Object.entries(caps).map(([lane, n]) => (
                <div key={lane} className="flex items-center justify-between gap-3">
                  <span className="truncate text-sm">{lane}</span>
                  <Input type="number" min={1} value={n} onChange={(e) => setCaps({ ...caps, [lane]: Number(e.target.value) })} className="h-8 w-20" />
                </div>
              ))}
            </div>
          )}
          <BaseBranchPicker project={project} />
          {[...experiments, ...otherData].length > 0 && (
            <div className="space-y-0.5">
              <div className="text-xs text-muted-foreground">Other values (set with infantry project config)</div>
              {[...experiments, ...otherData].map(([k, v]) => (
                <div key={k} className="flex justify-between gap-3 text-xs">
                  <span className="truncate font-mono">{k}</span>
                  <span className="truncate font-mono text-muted-foreground">{v}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </AppDialog>
  );
}

function DeleteProjectDialog({ project, onClose }: { project: ProjectInfo; onClose: () => void }) {
  const [typed, setTyped] = useState("");
  const ok = deleteConfirmed(typed);
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Delete ${project.name}?`}
      footer={
        <Button
          size="sm"
          variant="destructive"
          disabled={!ok}
          onClick={() => {
            projectsStore().getState().deleteProject(project.id);
            onClose();
          }}
        >
          Delete
        </Button>
      }
    >
      <div className="space-y-3 text-sm">
        <p>
          This removes the project from Infantry AI: its name, settings, icon and workspace list. The daemon refuses while one of its agents is running.
        </p>
        <p className="rounded-md border p-2">
          <span className="font-semibold">Not deleted:</span> the folder and the files on disk
          {project.directory ? <span className="break-all font-mono"> ({project.directory})</span> : null}. They stay exactly as they are.
        </p>
        <div className="space-y-1.5">
          <Label htmlFor="delete-project-confirm">Type DELETE to confirm</Label>
          <Input id="delete-project-confirm" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
        </div>
      </div>
    </AppDialog>
  );
}

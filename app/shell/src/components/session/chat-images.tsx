// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Chat image thumbnails (Kotlin IMG-2): a message's `[image: <path>]` references fetched with `image_fetch` (once per
// window) and drawn as small framed thumbnails; a click opens the full image in the one dialog. `readImageFile` turns
// a picked or pasted file into the base64 `image_upload` takes.
import { useEffect, useState } from "react";
import { ImageOff } from "lucide-react";
import { fetchImage, type ImageRef } from "@infantry/client";
import { AppDialog } from "@infantry/ui";
import { daemonStore } from "@/daemon/daemon";

/** A picked or pasted file as base64 (no `data:` prefix). */
export function readImageFile(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).replace(/^data:[^,]*,/, ""));
    r.onerror = () => reject(r.error);
    r.readAsDataURL(file);
  });
}

function Thumb({ image }: { image: ImageRef }) {
  const [url, setUrl] = useState<string | null | undefined>(undefined);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let live = true;
    void fetchImage(daemonStore().getState(), image.id).then((u) => live && setUrl(u));
    return () => {
      live = false;
    };
  }, [image.id]);
  if (url === null)
    return (
      <span className="inline-flex h-16 items-center gap-1 rounded-md border px-2 text-[11px] text-muted-foreground" title={image.path}>
        <ImageOff className="size-3.5" /> image not found
      </span>
    );
  return (
    <>
      <button type="button" onClick={() => url && setOpen(true)} title={image.path} className="size-16 overflow-hidden rounded-md border bg-muted">
        {url && <img src={url} alt="Attached image" className="size-full object-cover" />}
      </button>
      {url && (
        <AppDialog open={open} onOpenChange={setOpen} title="Image" description={image.path}>
          <img src={url} alt="Attached image" className="max-h-[70vh] w-full object-contain" />
        </AppDialog>
      )}
    </>
  );
}

export function ImageThumbs({ refs }: { refs: ImageRef[] }) {
  if (!refs.length) return null;
  return (
    <div className="flex flex-wrap gap-2">
      {refs.map((r, i) => (
        <Thumb key={`${r.id}-${i}`} image={r} />
      ))}
    </div>
  );
}

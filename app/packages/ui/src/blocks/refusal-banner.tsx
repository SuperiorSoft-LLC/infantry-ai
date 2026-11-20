// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The banner a tab shows when the daemon refused what it asked, with a Dismiss (moved into the kit by WEBUI-1).
import { Button } from "../components/button";

export function RefusalBanner({ text, onDismiss }: { text: string | null; onDismiss: () => void }) {
  if (!text) return null;
  return (
    <div role="alert" className="mb-3 flex items-center justify-between rounded-lg border border-danger/60 bg-danger/10 px-3 py-2 text-xs text-danger">
      <span>{text}</span>
      <Button variant="ghost" size="xs" onClick={onDismiss}>
        Dismiss
      </Button>
    </div>
  );
}

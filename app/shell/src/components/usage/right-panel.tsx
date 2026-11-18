// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Right panel. Its tabs come from the plugin registry (plugins/catalog.tsx `tabsFor("right")`): Attention, Usage,
// Prompts, Skills, plus any right-container tab an enabled plugin declares.
import { attentionCount } from "@infantry/client";
import { useAttention } from "@/components/mail/use-mail";
import { useUi } from "@/state/ui-state";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@infantry/ui";
import { ScrollArea } from "@infantry/ui";
import { KeyHint } from "@infantry/ui";
import { useTabs } from "@/plugins/catalog";

export function RightPanel() {
  const ui = useUi();
  const pending = useAttention(attentionCount);
  const tabs = useTabs("right");
  const value = tabs.some((t) => t.id === ui.rightTab) ? ui.rightTab : (tabs[0]?.id ?? "");
  return (
    <aside aria-label="Side panel" className="flex h-full w-[360px] shrink-0 flex-col border-l bg-background">
      <Tabs value={value} onValueChange={(v) => ui.set("rightTab", v)} className="flex min-h-0 flex-1 flex-col gap-0">
        <TabsList variant="line" className="no-scrollbar h-10! w-full shrink-0 justify-start gap-1 overflow-x-auto border-b px-2">
          {tabs.map((t) => (
            <TabsTrigger key={t.id} value={t.id} className="flex-none gap-1.5 px-2 text-xs data-[state=active]:text-danger after:bg-danger!">
              <t.icon className="size-3.5" />
              {t.id === "attention" && pending > 0 ? `${t.label} (${pending})` : t.label}
              {t.hint && <KeyHint>{t.hint}</KeyHint>}
            </TabsTrigger>
          ))}
        </TabsList>
        <ScrollArea className="min-h-0 flex-1">
          <div className="p-3">
            {tabs.map((t) => (
              <TabsContent key={t.id} value={t.id}>
                {t.id === value && t.render()}
              </TabsContent>
            ))}
          </div>
        </ScrollArea>
      </Tabs>
    </aside>
  );
}

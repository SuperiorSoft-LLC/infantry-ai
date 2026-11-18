// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Right panel: Attention / Usage / Prompts / Skills.
import { Bell, Gauge, MessageSquare, Star, Sparkles, FileText } from "lucide-react";
import { prompts, skills } from "@/mock/data";
import { attentionCount } from "@infantry/client";
import { useAttention } from "@/components/mail/use-mail";
import { useUi, type RightTab } from "@/state/ui-state";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@infantry/ui";
import { ScrollArea } from "@infantry/ui";
import { ListRow } from "@infantry/ui";
import { KeyHint } from "@infantry/ui";
import { UsagePanel } from "./usage-panel";
import { AttentionPanel } from "./attention-panel";

const tabs: { id: RightTab; label: string; icon: typeof Bell; hint?: string }[] = [
  { id: "attention", label: "Attention", icon: Bell, hint: "^6" },
  { id: "usage", label: "Usage", icon: Gauge, hint: "^7" },
  { id: "prompts", label: "Prompts", icon: MessageSquare },
  { id: "skills", label: "Skills", icon: Star },
];

export function RightPanel() {
  const ui = useUi();
  const pending = useAttention(attentionCount);
  return (
    <aside aria-label="Side panel" className="flex h-full w-[360px] shrink-0 flex-col border-l bg-background">
      <Tabs value={ui.rightTab} onValueChange={(v) => ui.set("rightTab", v as RightTab)} className="flex min-h-0 flex-1 flex-col gap-0">
        <TabsList variant="line" className="no-scrollbar h-10! w-full shrink-0 justify-start gap-1 overflow-x-auto border-b px-2">
          {tabs.map((t) => (
            <TabsTrigger
              key={t.id}
              value={t.id}
              className="flex-none gap-1.5 px-2 text-xs data-[state=active]:text-danger after:bg-danger!"
            >
              <t.icon className="size-3.5" />
              {t.id === "attention" && pending > 0 ? `${t.label} (${pending})` : t.label}
              {t.hint && <KeyHint>{t.hint}</KeyHint>}
            </TabsTrigger>
          ))}
        </TabsList>
        <ScrollArea className="min-h-0 flex-1">
          <div className="p-3">
            <TabsContent value="attention">
              <AttentionPanel />
            </TabsContent>
            <TabsContent value="usage">
              <UsagePanel />
            </TabsContent>
            <TabsContent value="prompts" className="space-y-2">
              {prompts.map((p) => (
                <ListRow key={p.id} row={p} icon={<FileText className="size-4" />} />
              ))}
            </TabsContent>
            <TabsContent value="skills" className="space-y-2">
              {skills.map((s) => (
                <ListRow key={s.id} row={s} icon={<Sparkles className="size-4" />} />
              ))}
            </TabsContent>
          </div>
        </ScrollArea>
      </Tabs>
    </aside>
  );
}

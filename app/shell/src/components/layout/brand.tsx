// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Infantry AI emblem and wordmark (BrandEmblem.kt).
export function BrandEmblem({ className = "size-6" }: { className?: string }) {
  return <img src="/infantry-ai-emblem.png" alt="" className={className} draggable={false} />;
}

export function Brand() {
  return (
    <div className="flex items-center gap-2 pr-1">
      <BrandEmblem />
      <span className="text-[15px] font-bold whitespace-nowrap text-primary">Infantry AI</span>
    </div>
  );
}

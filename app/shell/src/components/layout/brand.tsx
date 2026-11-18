// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Infantry AI emblem and wordmark (BrandEmblem.kt).
export function BrandEmblem({ className = "size-6" }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <path d="M16 3 30 28H2Z" fill="none" stroke="var(--primary)" strokeWidth="2.4" strokeLinejoin="round" />
      <path d="M16 11 23.5 24h-15Z" fill="var(--primary)" opacity=".85" />
      <path d="M12 21h8" stroke="var(--background)" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

export function Brand() {
  return (
    <div className="flex items-center gap-2 pr-1">
      <BrandEmblem />
      <span className="text-[15px] font-bold whitespace-nowrap text-primary">Infantry AI</span>
    </div>
  );
}

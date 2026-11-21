// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A row that scrolls sideways with < > arrows. The arrows show only while it overflows and each hides at its end; the
// active item scrolls into view; the wheel scrolls it sideways. Used by the role filter and both tab bars.
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ComponentPropsWithoutRef, type ReactNode } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { scrollToReveal, stripEdges, wheelDelta, type StripEdges } from "@infantry/client";
import { cn, IconButton } from "@infantry/ui";

const STEP = 120;
const FITS: StripEdges = { overflow: false, atStart: true, atEnd: true };

type RowProps = Omit<ComponentPropsWithoutRef<"div">, "children" | "className">;

export function ScrollStrip({
  label,
  activeKey,
  children,
  className,
  rowClassName,
  rowProps,
  as: Row = "div",
}: {
  /** Names the arrows: "Scroll {label} left". */
  label: string;
  /** Changes when the active item changes; that item (`[aria-selected="true"]`) is scrolled into view. */
  activeKey?: string;
  children: ReactNode;
  className?: string;
  rowClassName?: string;
  rowProps?: RowProps;
  as?: "div" | "nav";
}) {
  const row = useRef<HTMLElement>(null);
  const [edges, setEdges] = useState<StripEdges>(FITS);

  const measure = useCallback(() => {
    const el = row.current;
    if (!el) return;
    const next = stripEdges(el.scrollLeft, el.clientWidth, el.scrollWidth);
    setEdges((p) => (p.overflow === next.overflow && p.atStart === next.atStart && p.atEnd === next.atEnd ? p : next));
  }, []);

  useLayoutEffect(measure);
  useEffect(() => {
    const el = row.current;
    if (!el) return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    for (const c of Array.from(el.children)) ro.observe(c);
    return () => ro.disconnect();
  }, [measure, children]);

  useEffect(() => {
    const el = row.current;
    const item = el?.querySelector<HTMLElement>('[aria-selected="true"]');
    if (!el || !item) return;
    const to = scrollToReveal(el.scrollLeft, el.clientWidth, item.offsetLeft, item.offsetWidth);
    if (to !== el.scrollLeft) el.scrollTo({ left: to, behavior: "smooth" });
  }, [activeKey]);

  useEffect(() => {
    const el = row.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      const dx = wheelDelta(e.deltaX, e.deltaY);
      if (!dx || el.scrollWidth <= el.clientWidth) return;
      e.preventDefault();
      el.scrollLeft += dx;
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, []);

  const scroll = (dx: number) => row.current?.scrollBy({ left: dx, behavior: "smooth" });
  const RowTag = Row as "div";

  return (
    <div className={cn("flex min-w-0 items-center gap-1", className)}>
      {edges.overflow && (
        <IconButton label={`Scroll ${label} left`} size="icon-xs" disabled={edges.atStart} className={cn(edges.atStart && "invisible")} onClick={() => scroll(-STEP)}>
          <ChevronLeft />
        </IconButton>
      )}
      <RowTag
        ref={row as React.RefObject<HTMLDivElement>}
        {...rowProps}
        onScroll={measure}
        className={cn("no-scrollbar relative flex min-w-0 flex-1 overflow-x-auto", rowClassName)}
      >
        {children}
      </RowTag>
      {edges.overflow && (
        <IconButton label={`Scroll ${label} right`} size="icon-xs" disabled={edges.atEnd} className={cn(edges.atEnd && "invisible")} onClick={() => scroll(STEP)}>
          <ChevronRight />
        </IconButton>
      )}
    </div>
  );
}

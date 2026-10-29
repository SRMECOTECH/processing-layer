"use client";

import { Empty, PageHeader } from "@/components/ops/kit";
import { IngestionApiCard, RedisCard, ValhallaCard, WhereThingsRun } from "@/components/ops/connections";
import { formatTime } from "@/lib/format";
import type { ConnectionsOverview } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function ConnectionsPage() {
  const { data, error, refresh } = usePoll<ConnectionsOverview>("/api/connections", 10_000);
  return (
    <div className="stack">
      <PageHeader
        description="Where the service finds Redis and the routing engine: this laptop or the HP home server. A change applies at once, without a restart, and is kept for the next start."
        meta={[
          ["Saved in", "PostgreSQL · connection_setting"],
          ["API", "/api/connections"],
          ["Checked", data ? formatTime(data.checkedAt) : "…"],
        ]}
      />

      {!data ? (
        <Empty>{error ?? "Checking every connection (a few seconds when the HP does not answer)."}</Empty>
      ) : (
        <>
          <RedisCard view={data.redis} onChanged={refresh} />
          <div className="grid-2">
            <ValhallaCard view={data.valhalla} onChanged={refresh} />
            <IngestionApiCard data={data.ingestionApi} onCheck={refresh} />
          </div>
          <WhereThingsRun data={data} />
        </>
      )}
    </div>
  );
}

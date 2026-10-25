import { OpsFrame } from "@/components/ops/ops-frame";

export default function SectionLayout({ children }: { children: React.ReactNode }) {
  return <OpsFrame>{children}</OpsFrame>;
}

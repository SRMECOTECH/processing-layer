import { redirect } from "next/navigation";

// The console opens on the first section.
export default function Home() {
  redirect("/stream");
}

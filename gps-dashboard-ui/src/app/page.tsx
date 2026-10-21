import { redirect } from "next/navigation";

// The console opens on the live fleet.
export default function Home() {
  redirect("/live");
}

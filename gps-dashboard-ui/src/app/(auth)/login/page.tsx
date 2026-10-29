import { SignIn } from "@/components/auth/sign-in";

/** /login?next=/live/map — after signing in, the visitor lands on `next` (the page they first asked for). */
export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string | string[] }> }) {
  const { next } = await searchParams;
  return <SignIn next={typeof next === "string" ? next : "/"} />;
}

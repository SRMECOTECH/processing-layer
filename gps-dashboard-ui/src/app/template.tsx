// A template re-mounts on every navigation, so each page fades in gently.
export default function Template({ children }: { children: React.ReactNode }) {
  return <div className="page">{children}</div>;
}

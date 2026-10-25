/** A heading that blurs in word by word (50 ms apart); screen readers get the plain text. */
export function Words({ text }: { text: string }) {
  const words = text.split(" ");
  return (
    <span aria-label={text}>
      {words.map((word, n) => (
        <span key={n} aria-hidden className="word" style={{ "--w": n } as React.CSSProperties}>
          {word}
          {n < words.length - 1 ? " " : ""}
        </span>
      ))}
    </span>
  );
}

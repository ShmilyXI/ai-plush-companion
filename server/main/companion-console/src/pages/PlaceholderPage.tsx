interface PlaceholderPageProps {
  title: string
  description: string
}

export function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  return (
    <section className="bento-card page-placeholder">
      <p className="eyebrow">COMPANION CONSOLE</p>
      <h1>{title}</h1>
      <p>{description}</p>
    </section>
  )
}

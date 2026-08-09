import { PlaceholderPage } from './PlaceholderPage'

interface AdminPlaceholderPageProps {
  title: string
  description: string
}

export function AdminPlaceholderPage(props: AdminPlaceholderPageProps) {
  return <PlaceholderPage {...props} />
}

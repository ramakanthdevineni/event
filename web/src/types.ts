export type NavItem = { id: number; label: string; href: string }

export type Me = {
  username: string
  firstName: string
  lastName: string
  email: string
  role: string
  roles: string[]
  isAdmin: boolean
  mustChangePassword: boolean
  homePath: string
  nav: NavItem[]
}

export type UserRow = {
  username: string
  firstName: string
  lastName: string
  email: string
  role: string
  roles: string[]
  enabled: boolean
  isAdmin: boolean
}

export type Venue = { id: number; label: string }
export type WorkItemDef = { id: number; name: string; sortOrder: number }
export type StatusDef = { id: number; label: string; percent: number; sortOrder: number }
export type WorkItem = { name: string; status: string }
export type VenueProgress = {
  id: number
  label: string
  percent: number
  color: string
  workItems: WorkItem[]
}

export type MapMarker = {
  id: number
  label: string
  x: number
  y: number
  percent: number
  color: string
}

export type MapData = {
  viewBox: string
  landPath: string
  markers: MapMarker[]
  venues: VenueProgress[]
}

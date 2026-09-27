import React, { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { MapContainer, TileLayer, Polyline, CircleMarker, Popup } from 'react-leaflet'
import { location as locApi } from '../api/client'
import type { LocationPoint } from '../api/types'
import 'leaflet/dist/leaflet.css'

export default function LocationMap() {
  const { id } = useParams<{ id: string }>()
  const [points, setPoints] = useState<LocationPoint[]>([])

  useEffect(() => {
    if (id) locApi.history(id).then(r => setPoints(r.data.points ?? []))
  }, [id])

  const center: [number, number] = points.length
    ? [points[0].latitude, points[0].longitude]
    : [0, 0]

  const line: [number, number][] = points.map(p => [p.latitude, p.longitude])

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-4">Location History — {points.length} points</h1>
      {points.length > 0 && (
        <div className="h-96 rounded overflow-hidden border border-border">
          <MapContainer center={center} zoom={13} style={{ height: '100%', width: '100%' }}>
            <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
            <Polyline positions={line} color="#7c3aed" />
            {points.map((p, i) => (
              <CircleMarker key={i} center={[p.latitude, p.longitude]}
                radius={i === 0 ? 8 : 4}
                color={i === 0 ? '#22c55e' : '#7c3aed'}>
                <Popup>{new Date(p.timestamp).toLocaleString()}<br />±{p.accuracy}m</Popup>
              </CircleMarker>
            ))}
          </MapContainer>
        </div>
      )}
    </div>
  )
}

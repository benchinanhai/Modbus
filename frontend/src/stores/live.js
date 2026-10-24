import { defineStore } from 'pinia'
import { get, connect } from '../api'

export const useLiveStore = defineStore('live', {
  state: () => ({
    overview: null,
    gateways: [],
    devices: [],
    logs: [],
    commands: [],
    lanes: [],
    connected: false,
    paused: false,
    tick: 0,
    conn: null
  }),

  getters: {
    deviceMap(state) {
      const map = {}
      for (const device of state.devices) map[device.id] = device
      return map
    },
    gatewayMap(state) {
      const map = {}
      for (const gateway of state.gateways) map[gateway.id] = gateway
      return map
    },
    stats(state) {
      return (
        state.overview?.stats || {
          online: 0,
          points: 0,
          successRate: 0,
          p95: 0,
          throughput: 0
        }
      )
    }
  },

  actions: {
    async refresh() {
      try {
        const [overview, gateways, devices, logs, commands, threads] = await Promise.all([
          get('/overview'),
          get('/gateways'),
          get('/devices'),
          get('/logs'),
          get('/commands'),
          get('/threads')
        ])
        this.overview = overview
        this.gateways = gateways
        this.devices = devices
        this.logs = logs
        this.commands = commands
        this.lanes = threads.lanes
      } catch (error) {
        console.warn('refresh failed', error)
      }
    },

    async init() {
      await this.refresh()
      this.conn = connect((message) => this.onMessage(message))
    },

    onMessage(message) {
      if (message.type === 'status') {
        this.connected = message.connected
        return
      }
      if (this.paused && message.type === 'telemetry') return

      if (message.type === 'telemetry') {
        const incoming = message.device
        const index = this.devices.findIndex((device) => device.id === incoming.id)
        if (index >= 0) {
          this.devices[index] = incoming
        } else {
          this.devices.push(incoming)
        }
        if (this.overview) {
          this.overview.stats = message.stats
        }
        this.tick += 1
        return
      }

      if (message.type === 'log') {
        this.logs.unshift(message.data)
        if (this.logs.length > 250) this.logs.pop()
        return
      }

      if (message.type === 'command') {
        if (!this.commands.some((command) => command.id === message.data.id)) {
          this.commands.unshift(message.data)
          if (this.commands.length > 50) this.commands.pop()
        }
      }
    },

    togglePause() {
      this.paused = !this.paused
    },

    disconnect() {
      if (this.conn) {
        this.conn.close()
        this.conn = null
      }
    }
  }
})

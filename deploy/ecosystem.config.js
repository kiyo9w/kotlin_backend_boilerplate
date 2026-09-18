// the service server — PM2 process manager.
//
// PM2 runs the installed distribution's launcher script directly. It is a
// single-process JVM: PM2 restarts it, it does not cluster it (the server is
// stateless and the durable scheduler is single-flight across instances, so
// scale out with another host and nginx, not with `instances: 'max'`).
//
//   pm2 start deploy/ecosystem.config.js
//   pm2 save
//
// Secrets come from the environment PM2 is started with (or the host's secret
// store). They are referenced here, never written here.

module.exports = {
  apps: [
    {
      name: 'myproduct-server',
      cwd: __dirname + '/../server/build/install/server',
      script: './bin/server',
      interpreter: 'none',
      instances: 1,
      exec_mode: 'fork',
      autorestart: true,
      max_restarts: 10,
      min_uptime: '30s',
      restart_delay: 2000,
      max_memory_restart: '768M',
      kill_timeout: 10000,
      env: {
        ENVIRONMENT: 'production',
        PORT: '8080',
        // DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD,
        // MODEL_API_KEY, APPLE_ROOT_CA_{PEM,PATH} and the rest are
        // supplied by the host environment / secret store.
      },
      out_file: '/var/log/myproduct/server.out.log',
      error_file: '/var/log/myproduct/server.err.log',
      merge_logs: true,
      time: true,
    },
  ],
};

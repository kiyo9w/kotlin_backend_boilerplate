# Config

Every key is bound once in `core/ServerConfig.kt`. Defaults live in code
(`ConfigDefaults`), never at a call site. The `.env.example` and
`.env.production.example` pair documents each key, and `ConfigExampleTest`
fails when a bound key is missing from either file.

| Key | Default | Absent means |
| --- | --- | --- |
| `DATABASE_URL` | blank | **Local-only memory mode.** No durability; counts die with the process. |
| `MODEL_API_KEY` | blank | Model routes are off; `/v1/example/jobs` still queues an authored result. |
| `MODEL_BASE` | `https://api.openai.com/v1` | OpenAI-compatible base URL. |
| `MODEL_NAME` | `gpt-4o-mini` | Model name. |
| `APP_DAILY_SEED_CAP` | `24` | Server default. `<= 0` means uncapped. |
| `APP_DAILY_TALK_CAP` | `120` | Server default. `<= 0` means uncapped. |
| `APP_FACTORY_KILL` | blank | Model routes stay on. `1` / `true` / `yes` turns them off. |
| `APP_STORE_EVENTS_TRUST_UNVERIFIED` | `false` | Webhooks stay fail-closed. `true` is a local-testing affordance only. |
| `APPLE_ROOT_CA_PEM` | blank | JWS verification is unavailable and the route records-without-granting. |
| `APPLE_ROOT_CA_PATH` | blank | Path fallback for the PEM. |

The `APP_` prefix namespaces the keys this service owns, so a shared host can
run more than one. A stamped product renames the prefix to its own (the
generator does this).

## Secrets

- A secret never enters the repository and never enters a client build.
- The production secret lives in the host's secret store and reaches the
  process as an environment variable.
- `deploy/.env` is gitignored; only the `.example` files are committed, with the
  secret keys left blank.

## Where to add a key

1. Add a constant to `ConfigKey` and a default to `ConfigDefaults` if needed.
2. Bind it in the appropriate config object's `from(env)`.
3. Document it in both `.env.example` files.
4. Run `./gradlew :server:test`; `ConfigExampleTest` fails until the examples
   match.

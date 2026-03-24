# External Runtime Config Examples

These files are minimal external YAML overlays for NeoPsyke's bundled runtime config.

Use them by pointing the matching env var at the file you want:

- `NEOPSYKE_AGENT_CONFIG_FILE`
- `NEOPSYKE_ID_CONFIG_FILE`
- `NEOPSYKE_LLM_CONFIG_FILE`
- `NEOPSYKE_MCP_CONFIG_FILE`
- `NEOPSYKE_MEMORY_CONFIG_FILE`

Example:

```bash
export NEOPSYKE_LLM_CONFIG_FILE="$PWD/examples/runtime-config/llm-runtime.external.example.yaml"
export GOOGLE_API_KEY="..."
./run-neopsyke.sh
```

These files are intentionally small. They rely on the bundled runtime YAML shipped with the app and override only the high-leverage operator decisions.
